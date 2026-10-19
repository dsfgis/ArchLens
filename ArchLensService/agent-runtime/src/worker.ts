import { Annotation, StateGraph, START, END } from "@langchain/langgraph";
import { PostgresSaver } from "@langchain/langgraph-checkpoint-postgres";
import type { RunnableConfig } from "@langchain/core/runnables";
import pg from "pg";
import { randomUUID } from "node:crypto";
import { canonicalHash } from "./contracts.js";
import { BRIDGE_VERSION, invokeToolBridge, resumeToolInvocation, BridgeError } from "./tool-bridge.js";
import type { BridgeProcess, BridgeRequest } from "./tool-bridge.js";
import { runtimeCall, RuntimeError } from "./runtime-bridge.js";
import type { Ticket, RuntimeState, RuntimeContext, ApprovedCheckpoint } from "./runtime-bridge.js";

export const GRAPH_VERSION = "archlens.p0-rules.v1", STATE_VERSION = "archlens.p0-state.v1";
export type Boundary = (name: string, detail: Record<string, unknown>) => Promise<void>;
export interface WorkerConfig {
  runtime: BridgeProcess; tool: BridgeProcess; checkpointPool: pg.Pool; workerId: string;
  heartbeatMillis?: number; onBoundary?: Boundary;
}
const GraphState = Annotation.Root({ app: Annotation<RuntimeState>() });

/** Pin ALL saver operations to an epoch; LangGraph's root namespace is virtualized, not passed as a subgraph. */
export class EpochSaver extends PostgresSaver {
  approved: ApprovedCheckpoint | null;
  private tail: Promise<unknown> = Promise.resolve();
  constructor(pool: pg.Pool, readonly ticket: Ticket, approved: ApprovedCheckpoint | null,
    readonly acknowledge: (id: string, previous: ApprovedCheckpoint | null, state: RuntimeState) => Promise<ApprovedCheckpoint>,
    readonly boundary: Boundary = async () => {}) {
    super(pool, undefined, { schema: "archlens_checkpoint" }); this.approved = approved;
  }
  private scoped(config: RunnableConfig): RunnableConfig {
    if (config.configurable?.thread_id !== this.ticket.runId) throw new RuntimeError("MIG_CHECKPOINT_SCOPE");
    return { ...config, configurable: { ...config.configurable, checkpoint_ns: `${GRAPH_VERSION}/epoch-${this.ticket.epoch}` } };
  }
  private virtual(config: RunnableConfig): RunnableConfig { return { ...config, configurable: { ...config.configurable, checkpoint_ns: "" } }; }
  override async getTuple(config: RunnableConfig) {
    const tuple = await super.getTuple(this.scoped(config));
    if (!tuple) return undefined;
    return { ...tuple, config: this.virtual(tuple.config), parentConfig: tuple.parentConfig ? this.virtual(tuple.parentConfig) : undefined };
  }
  override async *list(config: RunnableConfig, options?: Parameters<PostgresSaver["list"]>[1]) {
    for await (const tuple of super.list(this.scoped(config), options)) {
      yield { ...tuple, config: this.virtual(tuple.config), parentConfig: tuple.parentConfig ? this.virtual(tuple.parentConfig) : undefined };
    }
  }
  override async put(...args: Parameters<PostgresSaver["put"]>): ReturnType<PostgresSaver["put"]> {
    const work = this.tail.then(async () => {
      const [config, checkpoint, metadata, versions] = args;
      const app = checkpoint.channel_values.app as RuntimeState | undefined;
      const previous = this.approved;
      const exported = app ? { ...app, epoch: this.ticket.epoch, sequence: (previous?.sequence ?? 0) + 1 } : undefined;
      // A bounded JSON export lives in the native checkpoint row, in the same PostgresSaver transaction.
      const saved = await super.put(this.scoped(config), exported ? { ...checkpoint, archlens_export: exported } as typeof checkpoint : checkpoint, metadata, versions);
      if (exported) {
        await this.boundary("checkpoint_saved", { checkpointId: checkpoint.id, state: exported });
        this.approved = await this.acknowledge(checkpoint.id, previous, exported);
        await this.boundary("checkpoint_approved", { checkpoint: this.approved });
      }
      return this.virtual(saved);
    });
    this.tail = work; return work;
  }
  override async putWrites(...args: Parameters<PostgresSaver["putWrites"]>) {
    await super.putWrites(this.scoped(args[0]), args[1], args[2], args[3]);
  }
  override async deleteThread(): Promise<void> { throw new RuntimeError("MIG_CHECKPOINT_DELETE_FORBIDDEN"); }
}

/** Import only the Java-approved export. Never select the framework's latest unacknowledged state. */
export async function runTicket(config: WorkerConfig, ticket: Ticket): Promise<void> {
  for (const key of ["LANGSMITH_TRACING", "LANGSMITH_TRACING_V2", "LANGCHAIN_TRACING", "LANGCHAIN_TRACING_V2"])
    process.env[key] = "false";
  const boundary = config.onBoundary ?? (async () => {});
  const heartbeat = config.heartbeatMillis ?? 20_000;
  if (!Number.isSafeInteger(heartbeat) || heartbeat < 50 || heartbeat > 30_000) throw new RuntimeError("MIG_HEARTBEAT_CONFIG");
  const abort = new AbortController(); let heartbeatFailure: unknown; let renewing: Promise<void> | null = null;
  const timer = setInterval(() => {
    if (renewing) return;
    renewing = runtimeCall<Ticket>(config.runtime, "RENEW", { ticket }).then(() => undefined)
      .catch(error => { heartbeatFailure = error; abort.abort(); }).finally(() => { renewing = null; });
  }, heartbeat);
  let saver: EpochSaver | undefined;
  try {
    const context = await runtimeCall<RuntimeContext>(config.runtime, "CONTEXT", { ticket });
    if (context.budget.elapsedMillis >= context.budget.maxElapsedMillis) throw new RuntimeError("MIG_ELAPSED_BUDGET");
    const approved = context.checkpoint;
    if (approved && (approved.state.graphVersion !== GRAPH_VERSION || approved.state.schemaVersion !== STATE_VERSION
      || canonicalHash(approved.state) !== approved.stateHash)) throw new RuntimeError("MIG_CHECKPOINT_VERSION");
    const initial: RuntimeState = approved?.state ?? {
      schemaVersion: STATE_VERSION, graphVersion: GRAPH_VERSION, runId: ticket.runId, revision: ticket.revision,
      requestHash: ticket.requestHash, snapshotHash: context.snapshotHash, epoch: ticket.epoch, sequence: 0,
      phase: "ENTRY", taskId: context.taskId, invocationId: null, artifactId: null, resultHash: null,
    };
    saver = new EpochSaver(config.checkpointPool, ticket, approved, async (id, previous, state) =>
      runtimeCall<ApprovedCheckpoint>(config.runtime, "ACK", { ticket, checkpointId: id,
        expectedSequence: previous?.sequence ?? 0, expectedStateHash: previous?.stateHash ?? null, stateHash: canonicalHash(state) }), boundary);
    const request = (app: RuntimeState, operation: "REGISTER" | "EXECUTE"): BridgeRequest => ({
      protocolVersion: BRIDGE_VERSION, requestId: randomUUID(), operation, ...ticket,
      taskId: operation === "REGISTER" ? app.taskId : null, actionKey: operation === "REGISTER" ? "p0.list-rules" : null,
      invocationId: operation === "EXECUTE" ? app.invocationId : null, snapshotHash: operation === "EXECUTE" ? app.snapshotHash : null,
      toolId: "list_rules", toolVersion: context.toolVersion, arguments: {},
    });
    const route = (state: typeof GraphState.State) => state.app.phase === "ENTRY" ? "register" : state.app.phase === "REGISTERED" ? "execute" : END;
    const graph = new StateGraph(GraphState)
      .addNode("bootstrap", state => ({ app: state.app }))
      .addNode("register", async state => {
        const result = await invokeToolBridge(request(state.app, "REGISTER"), config.tool);
        await boundary("tool_registered", { invocationId: result.invocationId });
        return { app: { ...state.app, phase: "REGISTERED" as const, invocationId: result.invocationId } };
      })
      .addNode("execute", async state => {
        const result = await resumeToolInvocation(request(state.app, "EXECUTE"), config.tool);
        await boundary("tool_result", { invocationId: result.invocationId, artifact: result.artifactRefs[0] });
        const artifact = result.artifactRefs[0]; if (!artifact) throw new RuntimeError("MIG_RESULT_MISSING");
        return { app: { ...state.app, phase: "SUCCEEDED" as const, artifactId: artifact.artifactId, resultHash: artifact.contentHash } };
      })
      .addEdge(START, "bootstrap").addConditionalEdges("bootstrap", route, ["register", "execute", END])
      .addEdge("register", "execute").addEdge("execute", END).compile({ checkpointer: saver });
    const graphConfig: RunnableConfig = { configurable: { thread_id: ticket.runId }, recursionLimit: 8, callbacks: [], signal: abort.signal };
    if (approved) {
      // updateState(asNode) creates a NEW native checkpoint and control edges in the new epoch.
      // The saved phase chooses the next operation; its result is reconciled against Java's ledger.
      const imported = await graph.updateState(graphConfig, { app: { ...initial, epoch: ticket.epoch } }, "bootstrap");
      await boundary("state_imported", { previousEpoch: initial.epoch, epoch: ticket.epoch, phase: initial.phase });
      await graph.invoke(null, { ...graphConfig, ...imported, durability: "sync" });
    } else await graph.invoke({ app: initial }, { ...graphConfig, durability: "sync" });
    if (heartbeatFailure) throw heartbeatFailure;
    await boundary("before_seal", { checkpoint: saver.approved });
    await runtimeCall(config.runtime, "SEAL", { ticket, checkpointHash: saver.approved?.stateHash ?? null, reason: "P0_SCOPE_ONLY" });
    await boundary("sealed", { runId: ticket.runId });
  } catch (error) {
    const code = error instanceof RuntimeError || error instanceof BridgeError ? error.code : "MIG_WORKER_FAILURE";
    // Lost ownership/storage/transport must not fabricate a terminal result; next epoch reconciles durable state.
    if (["STALE_RUN", "MIG_CHECKPOINT_CAS"].includes(code) || /STORAGE|TIMEOUT|SPAWN/.test(code)) throw error;
    const reason = ["MIG_ELAPSED_BUDGET", "MIG_TOOL_BUDGET", "MIG_TOOL_RETRY_BUDGET", "MIG_MODEL_BUDGET", "MIG_CHECKPOINT_VERSION", "MIG_CHECKPOINT_HASH",
      "MIG_CATALOG_CHANGED", "MIG_RESULT_DRIFT", "MIG_CHECKPOINT_INVALID", "MIG_CHECKPOINT_MISSING"].includes(code) ? code : "MIG_WORKER_FAILURE";
    await runtimeCall(config.runtime, "SEAL", { ticket, checkpointHash: null, reason });
    throw error;
  } finally { clearInterval(timer); if (renewing) await renewing; }
}

/** One owner per run. Queued/expired work is discovered anew after every process restart. */
export async function runNext(config: WorkerConfig): Promise<boolean> {
  const ticket = await runtimeCall<Ticket | null>(config.runtime, "CLAIM_NEXT", { workerId: config.workerId });
  if (!ticket) return false;
  await config.onBoundary?.("claimed", { ticket });
  await runTicket(config, ticket); return true;
}

export function checkpointPool(env: NodeJS.ProcessEnv = process.env): pg.Pool {
  const url = new URL(env.ARCHLENS_CHECKPOINT_PG_URL ?? "invalid:");
  if (url.protocol !== "postgresql:" || !url.hostname || url.username || url.password || url.search || url.hash || url.pathname.length < 2
    || !env.ARCHLENS_CHECKPOINT_PG_USER || !env.ARCHLENS_CHECKPOINT_PG_PASSWORD) throw new RuntimeError("MIG_CHECKPOINT_CONFIG");
  return new pg.Pool({ host: url.hostname, port: Number(url.port || 5432), database: decodeURIComponent(url.pathname.slice(1)),
    user: env.ARCHLENS_CHECKPOINT_PG_USER, password: env.ARCHLENS_CHECKPOINT_PG_PASSWORD, max: 3,
    connectionTimeoutMillis: 5_000, query_timeout: 15_000, statement_timeout: 15_000, application_name: "archlens-checkpoint-worker" });
}

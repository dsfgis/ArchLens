import { spawn } from "node:child_process";
import { randomUUID } from "node:crypto";
import { parseJsonText } from "./contracts.js";
import type { BridgeProcess } from "./tool-bridge.js";

export const RUNTIME_PROTOCOL = "archlens.runtime-bridge.v1";
export interface Ticket { caseId: string; runId: string; revision: number; requestHash: string; workerId: string; epoch: number }
export interface RuntimeState {
  schemaVersion: string; graphVersion: string; runId: string; revision: number; requestHash: string;
  snapshotHash: string; epoch: number; sequence: number; phase: "ENTRY" | "REGISTERED" | "SUCCEEDED";
  taskId: string; invocationId: string | null; artifactId: string | null; resultHash: string | null;
}
export interface ApprovedCheckpoint {
  namespace: string; checkpointId: string; sequence: number; eventCursor: number; stateHash: string; state: RuntimeState;
}
export interface RuntimeContext {
  taskId: string; toolVersion: string; snapshotHash: string; checkpoint: ApprovedCheckpoint | null;
  budget: { toolsReserved: number; toolsUsed: number; modelsReserved: number; modelsUsed: number; elapsedMillis: number; maxElapsedMillis: number };
}
export class RuntimeError extends Error { constructor(readonly code: string) { super(code); } }

/** The host protocol carries identity/control only. Raw stderr and transport errors are never exported. */
export async function runtimeCall<T>(config: BridgeProcess, operation: string, args: Record<string, unknown>): Promise<T> {
  const requestId = randomUUID();
  const input = Buffer.from(JSON.stringify({ protocolVersion: RUNTIME_PROTOCOL, requestId, operation, arguments: args }) + "\n");
  const timeout = config.timeoutMillis ?? 30_000;
  if (input.length > 16_384 || !Number.isSafeInteger(timeout) || timeout < 1 || timeout > 30_000)
    throw new RuntimeError("MIG_RUNTIME_CONFIG");
  return new Promise((resolve, reject) => {
    const child = spawn(config.command, [...config.args], { env: config.env ?? process.env, shell: false, windowsHide: true, stdio: ["pipe", "pipe", "ignore"] });
    const chunks: Buffer[] = []; let size = 0; let failure: string | null = null;
    const timer = setTimeout(() => { failure = "MIG_RUNTIME_TIMEOUT"; child.kill("SIGKILL"); }, timeout);
    child.stdout.on("data", (chunk: Buffer) => {
      size += chunk.length;
      if (size > 16_384) { failure = "MIG_RUNTIME_RESPONSE_LIMIT"; child.kill("SIGKILL"); } else chunks.push(chunk);
    });
    child.stdin.on("error", () => {});
    child.on("error", () => { failure = "MIG_RUNTIME_SPAWN"; });
    child.on("close", code => {
      clearTimeout(timer);
      if (failure) { reject(new RuntimeError(failure)); return; }
      try {
        const raw = new TextDecoder("utf-8", { fatal: true }).decode(Buffer.concat(chunks));
        if (!raw.endsWith("\n") || /[\r\n]/.test(raw.slice(0, -1))) throw new Error();
        const r = parseJsonText(raw) as Record<string, unknown>;
        if (!r || Object.keys(r).sort().join(",") !== "errorCode,protocolVersion,requestId,result,status"
          || r.protocolVersion !== RUNTIME_PROTOCOL || r.requestId !== requestId) throw new Error();
        if (r.status === "ERROR" && code === 2 && r.result === null && typeof r.errorCode === "string" && /^[A-Z][A-Z0-9_]{1,79}$/.test(r.errorCode))
          throw new RuntimeError(r.errorCode);
        if (r.status !== "OK" || code !== 0 || r.errorCode !== null) throw new Error();
        resolve(r.result as T);
      } catch (error) { reject(error instanceof RuntimeError ? error : new RuntimeError("MIG_RUNTIME_RESPONSE_INVALID")); }
    });
    child.stdin.end(input);
  });
}

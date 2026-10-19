import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { randomUUID } from "node:crypto";
import { fork } from "node:child_process";
import { once } from "node:events";
import pg from "pg";
import { checkpointPool, runNext, runTicket } from "../src/worker.js";
import { canonicalHash } from "../src/contracts.js";
import { runtimeCall } from "../src/runtime-bridge.js";
import type { Ticket, ApprovedCheckpoint } from "../src/runtime-bridge.js";

const enabled = process.env.ARCHLENS_P0_IT === "true";
const runtime = { command: "java", args: ["-jar", process.env.ARCHLENS_CLI_JAR ?? "", "migration-runtime"] };
const tool = { command: "java", args: ["-jar", process.env.ARCHLENS_CLI_JAR ?? "", "migration-tool-bridge"] };
const call = <T>(op: string, args: Record<string, unknown>) => runtimeCall<T>(runtime, op, args);

test("P0 real PG worker, checkpoint import, crash windows, fences and immutable receipt", { skip: !enabled, timeout: 300_000 }, async t => {
  assert.match(process.env.ARCHLENS_PG_URL!, /\/archlens_migration_it$/);
  const url = new URL(process.env.ARCHLENS_PG_URL!.slice(5));
  const admin = new pg.Pool({ host: url.hostname, port: Number(url.port), database: "archlens_migration_it", user: process.env.ARCHLENS_PG_USER, password: process.env.ARCHLENS_PG_PASSWORD });
  const pool = checkpointPool();
  const config = { runtime, tool, checkpointPool: pool, workerId: "recovery-worker", heartbeatMillis: 20_000 };
  const fixture = JSON.parse(await readFile(new URL("../../../examples/migration/request-code-only.json", import.meta.url), "utf8"));
  fixture.policyRefs.allowedTools = ["list_rules"];
  const enqueue = async (maxToolCalls = 12) => call<{ runId: string }>("ENQUEUE", { submissionKey: randomUUID(), request: { ...fixture, budget: { ...fixture.budget, maxToolCalls } } });
  const get = (runId: string) => call<{ state: string; epoch: number; receipt: { receiptHash: string; receipt: { artifactId: string | null; reason: string } } | null }>("GET", { runId });
  const expire = async (runId: string) => admin.query("UPDATE archlens.migration_run SET lease_until=clock_timestamp()-interval '1 second' WHERE run_id=$1 AND execution_state='RUNNING'", [runId]);
  const crash = async (gate: string, phase?: string, whileAlive?: () => Promise<void>) => {
    const child = fork(new URL("./support/worker-child.js", import.meta.url), [gate, phase ?? "", whileAlive ? "100" : "20000"], { silent: true });
    let errors = ""; child.stderr!.on("data", data => { errors += data.toString(); });
    const result = await new Promise<{ name: string; detail: Record<string, unknown> }>((resolve, reject) => {
      const timer = setTimeout(() => { child.kill("SIGKILL"); reject(new Error("Crash gate timeout " + errors)); }, 25_000);
      child.once("message", msg => { clearTimeout(timer); resolve(msg as { name: string; detail: Record<string, unknown> }); });
      child.once("exit", code => { clearTimeout(timer); reject(new Error("Worker exited before gate: " + code + " " + errors)); });
    });
    try { if (whileAlive) await whileAlive(); } finally { const ended = once(child, "exit"); child.kill("SIGKILL"); await ended; }
    return result;
  };
  try {
    await t.test("checkpoint role cannot read or write business state", async () => {
      await assert.rejects(pool.query("SELECT * FROM archlens.migration_run"), /permission denied/);
      await assert.rejects(pool.query("UPDATE archlens.checkpoint_link SET epoch=epoch"), /permission denied/);
      await assert.rejects(pool.query("CREATE TABLE archlens_checkpoint.forbidden(id int)"), /permission denied/);
    });
    await t.test("normal graph persists native checkpoints and seals PARTIAL once", async () => {
      const { runId } = await enqueue(); let ticket: Ticket | undefined; let cp: ApprovedCheckpoint | undefined;
      await runNext({ ...config, onBoundary: async (name, detail) => { if(name==="claimed")ticket=detail.ticket as Ticket; if(name==="before_seal")cp=detail.checkpoint as ApprovedCheckpoint; } });
      const view = await get(runId); assert.equal(view.state,"PARTIAL"); assert.equal(view.receipt?.receipt.reason,"P0_SCOPE_ONLY");
      assert.ok(view.receipt?.receipt.artifactId);
      const repeated = await call<{ receiptHash: string }>("SEAL", { ticket, checkpointHash: cp!.stateHash, reason: "P0_SCOPE_ONLY" });
      assert.equal(repeated.receiptHash,view.receipt!.receiptHash);
      await assert.rejects(call("SEAL",{ticket,checkpointHash:null,reason:"MIG_TOOL_BUDGET"}),/MIG_SEAL_CONFLICT/);
      const counts = await admin.query("SELECT (SELECT count(*) FROM archlens.tool_invocation WHERE run_id=$1) AS calls,(SELECT count(*) FROM archlens.migration_event WHERE run_id=$1 AND event_type='RUN_SEALED') AS seals,(SELECT count(*) FROM archlens_checkpoint.checkpoints WHERE thread_id=$2) AS checkpoints",[runId,runId]);
      assert.equal(counts.rows[0].calls,"1");assert.equal(counts.rows[0].seals,"1");assert.ok(Number(counts.rows[0].checkpoints)>=3);
    });
    for (const [gate, phase] of [["tool_registered",undefined],["tool_result",undefined],["checkpoint_saved","REGISTERED"],["before_seal",undefined]] as const) {
      await t.test(`SIGKILL at ${gate} ${phase ?? ""} recovers approved state and same invocation`, async () => {
        const { runId } = await enqueue();
        await crash(gate,phase);
        const before = await admin.query("SELECT invocation_id,result_artifact_id FROM archlens.tool_invocation WHERE run_id=$1",[runId]);
        const approved = await admin.query("SELECT epoch,checkpoint_sequence,state_hash FROM archlens.checkpoint_link WHERE run_id=$1",[runId]);
        await expire(runId);
        let imported = false;
        await runNext({ ...config, onBoundary: async name => { if(name==="state_imported")imported=true; } });
        assert.ok(imported);assert.equal((await get(runId)).state,"PARTIAL");
        const after = await admin.query("SELECT invocation_id,result_artifact_id FROM archlens.tool_invocation WHERE run_id=$1",[runId]);
        assert.equal(after.rows.length,1);assert.equal(after.rows[0].invocation_id,before.rows[0].invocation_id);
        if(before.rows[0].result_artifact_id)assert.equal(after.rows[0].result_artifact_id,before.rows[0].result_artifact_id);
        const cp=await admin.query("SELECT epoch,checkpoint_sequence FROM archlens.checkpoint_link WHERE run_id=$1",[runId]);
        assert.ok(Number(cp.rows[0].epoch)>Number(approved.rows[0].epoch));assert.ok(Number(cp.rows[0].checkpoint_sequence)>Number(approved.rows[0].checkpoint_sequence));
        const used=await admin.query("SELECT count(*) FROM archlens.migration_budget_reservation WHERE run_id=$1",[runId]);assert.equal(used.rows[0].count,"1");
      });
    }
    await t.test("no approved checkpoint ignores orphan and rebuilds from durable entry",async()=>{
      const {runId}=await enqueue();await crash("checkpoint_saved","ENTRY");
      assert.equal((await admin.query("SELECT count(*) FROM archlens.checkpoint_link WHERE run_id=$1",[runId])).rows[0].count,"0");
      await expire(runId);await runNext(config);assert.equal((await get(runId)).state,"PARTIAL");
    });
    await t.test("elapsed budget remains exhausted after restart",async()=>{
      const {runId}=await enqueue();await crash("tool_registered");
      await admin.query("UPDATE archlens.migration_run SET started_at=clock_timestamp()-interval '1 hour' WHERE run_id=$1",[runId]);
      await expire(runId);await assert.rejects(runNext(config),/MIG_ELAPSED_BUDGET/);
      const view=await get(runId);assert.equal(view.state,"PARTIAL");assert.equal(view.receipt?.receipt.reason,"MIG_ELAPSED_BUDGET");
      assert.equal((await admin.query("SELECT count(*) FROM archlens.migration_budget_reservation WHERE run_id=$1 AND status='RESERVED'",[runId])).rows[0].count,"1");
    });
    await t.test("incompatible approved version fails closed without replay",async()=>{
      const {runId}=await enqueue();await crash("tool_registered");
      assert.equal((await admin.query("UPDATE archlens.checkpoint_link SET graph_version='future' WHERE run_id=$1",[runId])).rowCount,1);
      await expire(runId);await assert.rejects(runNext(config),/MIG_CHECKPOINT_VERSION/);assert.equal((await get(runId)).state,"FAILED");
      assert.equal((await admin.query("SELECT status FROM archlens.tool_invocation WHERE run_id=$1",[runId])).rows[0].status,"REGISTERED");
    });
    await t.test("heartbeat keeps a live lease and concurrent schedulers cannot take it",async()=>{
      const {runId}=await enqueue();
      await crash("tool_registered",undefined,async()=>{
        await admin.query("UPDATE archlens.migration_run SET lease_until=clock_timestamp()+interval '2 seconds' WHERE run_id=$1",[runId]);
        let renewed=false;
        for(let i=0;i<20;i++){
          const row=(await admin.query("SELECT lease_until>clock_timestamp()+interval '100 seconds' AS renewed FROM archlens.migration_run WHERE run_id=$1",[runId])).rows[0];
          if(row.renewed){renewed=true;break;}await new Promise(resolve=>setTimeout(resolve,100));
        }
        assert.ok(renewed);assert.equal(await call("CLAIM_NEXT",{workerId:"competing-worker"}),null);
      });
      await expire(runId);await runNext(config);assert.equal((await get(runId)).state,"PARTIAL");
    });
    await t.test("parallel and out-of-order ACK cannot move the pointer backward; stale epoch is fenced",async()=>{
      const {runId}=await enqueue();await crash("before_seal");
      const row=(await admin.query("SELECT case_id,worker_id,epoch,revision,request_hash FROM archlens.migration_run WHERE run_id=$1",[runId])).rows[0];
      const ticket:Ticket={caseId:row.case_id,runId,workerId:row.worker_id,epoch:Number(row.epoch),revision:row.revision,requestHash:row.request_hash};
      const context=await call<import("../src/runtime-bridge.js").RuntimeContext>("CONTEXT",{ticket});const previous=context.checkpoint!;
      const ids=[randomUUID(),randomUUID()];const state={...previous.state,sequence:previous.sequence+1};
      // Authority-only CAS fixture: fork a real native checkpoint row without changing its graph channels.
      for(const id of ids)await admin.query("INSERT INTO archlens_checkpoint.checkpoints(thread_id,checkpoint_ns,checkpoint_id,checkpoint,metadata) SELECT thread_id,checkpoint_ns,$3,jsonb_set(jsonb_set(checkpoint,'{archlens_export}',$4::jsonb),'{id}',to_jsonb($3::text)),metadata FROM archlens_checkpoint.checkpoints WHERE thread_id=$1 AND checkpoint_ns=$2 AND checkpoint_id=$5",[runId,previous.namespace,id,JSON.stringify(state),previous.checkpointId]);
      const ack=(id:string)=>call<ApprovedCheckpoint>("ACK",{ticket,checkpointId:id,expectedSequence:previous.sequence,expectedStateHash:previous.stateHash,stateHash:canonicalHash(state)});
      const results=await Promise.allSettled(ids.map(id=>ack(id)));
      assert.equal(results.filter(r=>r.status==="fulfilled").length,1);assert.equal(results.filter(r=>r.status==="rejected").length,1);
      const winner=results.find(r=>r.status==="fulfilled") as PromiseFulfilledResult<ApprovedCheckpoint>;
      assert.deepEqual(await ack(winner.value.checkpointId),winner.value);
      await assert.rejects(call("ACK",{ticket,checkpointId:previous.checkpointId,expectedSequence:previous.sequence-1,expectedStateHash:null,stateHash:previous.stateHash}),/MIG_CHECKPOINT_CAS/);
      const before=(await call<import("../src/runtime-bridge.js").RuntimeContext>("CONTEXT",{ticket})).checkpoint!;assert.equal(before.sequence,state.sequence);assert.ok(before.eventCursor>previous.eventCursor);
      await expire(runId);const next=await call<Ticket>("CLAIM_NEXT",{workerId:"new-epoch-worker"});assert.ok(next.epoch>ticket.epoch);
      await assert.rejects(ack(winner.value.checkpointId),/STALE_RUN/);await runTicket(config,next);assert.equal((await get(runId)).state,"PARTIAL");
    });
    await t.test("tampered checkpoint and result bytes fail closed",async()=>{
      for(const kind of ["checkpoint","result"]){
        const {runId}=await enqueue();await crash("before_seal");
        if(kind==="checkpoint")await admin.query("UPDATE archlens_checkpoint.checkpoints SET checkpoint=jsonb_set(checkpoint,'{archlens_export,resultHash}',to_jsonb(repeat('0',64))) WHERE thread_id=$1 AND checkpoint_id=(SELECT checkpoint_id FROM archlens.checkpoint_link WHERE run_id=$2)",[runId,runId]);
        else await admin.query("UPDATE archlens.tool_result_payload SET content_utf8='{}' WHERE artifact_id=(SELECT result_artifact_id FROM archlens.tool_invocation WHERE run_id=$1)",[runId]);
        await expire(runId);await assert.rejects(runNext(config),kind==="checkpoint"?/MIG_CHECKPOINT_HASH/:/MIG_RESULT_DRIFT/);assert.equal((await get(runId)).state,"FAILED");
      }
    });
    await t.test("cancel rejects late approval and leaves no terminal success",async()=>{
      const {runId}=await enqueue();const stopped=await crash("checkpoint_saved","REGISTERED");
      const state=stopped.detail.state as import("../src/runtime-bridge.js").RuntimeState;
      const row=(await admin.query("SELECT case_id,worker_id,epoch,revision,request_hash FROM archlens.migration_run WHERE run_id=$1",[runId])).rows[0];
      const ticket={caseId:row.case_id,runId,workerId:row.worker_id,epoch:Number(row.epoch),revision:row.revision,requestHash:row.request_hash};
      await call("CANCEL",{runId});
      await assert.rejects(call("ACK",{ticket,checkpointId:stopped.detail.checkpointId,expectedSequence:state.sequence-1,expectedStateHash:null,stateHash:"0".repeat(64)}),/STALE_RUN/);
      assert.equal((await get(runId)).state,"CANCELLED");assert.equal(await runNext(config),false);
    });
  } finally { await pool.end();await admin.end(); }
});

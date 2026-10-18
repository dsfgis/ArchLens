import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import test from "node:test";
import { BridgeError, BRIDGE_VERSION, invokeToolBridge, resumeToolInvocation } from "../src/tool-bridge.js";
import type { BridgeRequest } from "../src/tool-bridge.js";

const mockProcess = `
  let input = "";
  process.stdin.on("data", chunk => input += chunk);
  process.stdin.on("end", () => {
    const mode = process.env.BRIDGE_MODE;
    if (mode === "timeout") { setInterval(() => {}, 1000); return; }
    if (mode === "oversize") { process.stdout.write("x".repeat(17000)); return; }
    const request = JSON.parse(input);
    const response = { protocolVersion: "archlens.tool-bridge.v1", requestId: request.requestId,
      status: "REGISTERED", invocationId: "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
      inputHash: "a".repeat(64), snapshotHash: "b".repeat(64), artifactRefs: [],
      diagnostics: [], coverageDelta: 0, retryability: "REPLAY", elapsedMillis: 1, errorCode: null };
    if (mode.startsWith("reconcile")) {
      if (mode === "reconcile-done" && request.operation === "EXECUTE") process.exit(7);
      response.invocationId = request.invocationId;
      response.snapshotHash = request.snapshotHash;
      response.status = request.operation === "INSPECT" && mode === "reconcile-pending" ? "DISPATCHED" : "SUCCEEDED";
      response.inputHash = request.operation === "INSPECT" ? "a".repeat(64) : null;
      response.artifactRefs = response.status === "SUCCEEDED" ?
        [{ artifactId: "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", contentHash: request.snapshotHash }] : [];
      process.stdout.write(JSON.stringify(response) + "\\n"); return;
    }
    if (mode === "error") { const errorResponse = { ...response, status: "ERROR", invocationId: null,
      inputHash: null, snapshotHash: null, errorCode: "MIG_BRIDGE_STORAGE_ERROR" };
      process.stdout.write(JSON.stringify(errorResponse) + "\\n"); process.exitCode = 2; return; }
    if (mode === "duplicate") { process.stdout.write(JSON.stringify(response).replace('"status":"REGISTERED"', '"status":"REGISTERED","status":"SUCCEEDED"') + "\\n"); return; }
    process.stdout.write(JSON.stringify(response) + "\\n");
    if (mode === "extra-line") process.stdout.write(JSON.stringify(response) + "\\n");
    process.stderr.write("private diagnostic\\n");
  });`;

function request(): BridgeRequest {
  return { protocolVersion: BRIDGE_VERSION, requestId: randomUUID(), operation: "REGISTER",
    caseId: randomUUID(), runId: randomUUID(), revision: 1, requestHash: "a".repeat(64),
    workerId: "testWorker", epoch: 1, taskId: randomUUID(), actionKey: "catalog-first",
    invocationId: null, snapshotHash: null, toolId: "list_rules", toolVersion: "archlens-rules-1.1",
    arguments: {} };
}

function run(mode: string, input = request(), timeoutMillis = 3_000) {
  return invokeToolBridge(input, { command: process.execPath, args: ["-e", mockProcess],
    env: { ...process.env, BRIDGE_MODE: mode }, timeoutMillis });
}

test("bridge uses one bounded process response and does not expose stderr", async () => {
  const result = await run("ok");
  assert.equal(result.status, "REGISTERED");
  assert.equal(result.artifactRefs.length, 0);
  assert.equal(result.errorCode, null);
});

test("bridge rejects duplicate JSON keys and multiple response lines", async () => {
  await assert.rejects(run("duplicate"), (error: unknown) => error instanceof BridgeError
    && error.code === "MIG_BRIDGE_RESPONSE_DUPLICATE_KEY");
  await assert.rejects(run("extra-line"), (error: unknown) => error instanceof BridgeError
    && error.code === "MIG_BRIDGE_RESPONSE_FRAMING");
});

test("bridge stops oversized or timed out child and rejects model-selected arguments", async () => {
  await assert.rejects(run("oversize"), (error: unknown) => error instanceof BridgeError
    && error.code === "MIG_BRIDGE_RESPONSE_TOO_LARGE");
  await assert.rejects(run("timeout", request(), 300), (error: unknown) => error instanceof BridgeError
    && error.code === "MIG_BRIDGE_TIMEOUT" && error.retryability === "REPLAY");
  await assert.rejects(run("error"), (error: unknown) => error instanceof BridgeError
    && error.code === "MIG_BRIDGE_STORAGE_ERROR" && error.retryability === "REPLAY");
  const invalid = { ...request(), arguments: { sql: "SELECT * FROM secrets" } } as unknown as BridgeRequest;
  await assert.rejects(run("ok", invalid), (error: unknown) => error instanceof BridgeError
    && error.code === "MIG_BRIDGE_REQUEST_INVALID");
});

test("recovery inspects persisted state before executing or reusing a result", async () => {
  const base = request();
  const execute: BridgeRequest = { ...base, operation: "EXECUTE", taskId: null, actionKey: null,
    invocationId: randomUUID(), snapshotHash: "b".repeat(64) };
  const pending = await resumeToolInvocation(execute, { command: process.execPath,
    args: ["-e", mockProcess], env: { ...process.env, BRIDGE_MODE: "reconcile-pending" }, timeoutMillis: 3_000 });
  assert.equal(pending.status, "SUCCEEDED");
  const done = await resumeToolInvocation(execute, { command: process.execPath,
    args: ["-e", mockProcess], env: { ...process.env, BRIDGE_MODE: "reconcile-done" }, timeoutMillis: 3_000 });
  assert.equal(done.status, "SUCCEEDED");
  assert.equal(done.artifactRefs[0]?.artifactId, pending.artifactRefs[0]?.artifactId);
});

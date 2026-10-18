import { spawn } from "node:child_process";
import { randomUUID } from "node:crypto";
import { ContractError, parseJsonText } from "./contracts.js";

export const BRIDGE_VERSION = "archlens.tool-bridge.v1";
export const MAX_LINE_BYTES = 16_384;
export type BridgeOperation = "REGISTER" | "INSPECT" | "EXECUTE";

export interface BridgeRequest {
  protocolVersion: typeof BRIDGE_VERSION;
  requestId: string;
  operation: BridgeOperation;
  caseId: string;
  runId: string;
  revision: number;
  requestHash: string;
  workerId: string;
  epoch: number;
  taskId: string | null;
  actionKey: string | null;
  invocationId: string | null;
  snapshotHash: string | null;
  toolId: "list_rules";
  toolVersion: string;
  arguments: Record<string, never>;
}

export interface BridgeResponse {
  protocolVersion: typeof BRIDGE_VERSION;
  requestId: string | null;
  status: "REGISTERED" | "DISPATCHED" | "SUCCEEDED" | "ERROR";
  invocationId: string | null;
  inputHash: string | null;
  snapshotHash: string | null;
  artifactRefs: { artifactId: string; contentHash: string }[];
  diagnostics: string[];
  coverageDelta: number;
  retryability: "NEVER" | "REPLAY";
  elapsedMillis: number;
  errorCode: string | null;
}

export interface BridgeProcess {
  command: string;
  args: readonly string[];
  env?: NodeJS.ProcessEnv;
  timeoutMillis?: number;
}

export class BridgeError extends Error {
  constructor(readonly code: string, readonly retryability: "NEVER" | "REPLAY" = "NEVER") { super(code); }
}

const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const sha256 = /^[0-9a-f]{64}$/;
const object = (value: unknown): value is Record<string, unknown> =>
  value !== null && typeof value === "object" && !Array.isArray(value);

/** A trusted host supplies ticket fields; model arguments remain the empty object. */
function validateRequest(request: BridgeRequest): void {
  if (request.protocolVersion !== BRIDGE_VERSION || !uuid.test(request.requestId)
    || !uuid.test(request.caseId) || !uuid.test(request.runId)
    || !Number.isSafeInteger(request.revision) || request.revision < 1
    || !sha256.test(request.requestHash) || !Number.isSafeInteger(request.epoch) || request.epoch < 1
    || !/^[A-Za-z][A-Za-z0-9._:-]{0,99}$/.test(request.workerId)
    || request.toolId !== "list_rules" || !/^[A-Za-z0-9._:-]{1,120}$/.test(request.toolVersion)
    || !object(request.arguments) || Object.keys(request.arguments).length !== 0) {
    throw new BridgeError("MIG_BRIDGE_REQUEST_INVALID");
  }
  if (request.operation === "REGISTER") {
    if (!request.taskId || !uuid.test(request.taskId) || !request.actionKey
      || !/^[A-Za-z][A-Za-z0-9._:-]{0,199}$/.test(request.actionKey)
      || request.invocationId !== null || request.snapshotHash !== null) {
      throw new BridgeError("MIG_BRIDGE_REQUEST_INVALID");
    }
  } else if (request.operation === "EXECUTE" || request.operation === "INSPECT") {
    if (request.taskId !== null || request.actionKey !== null || !request.invocationId
      || !uuid.test(request.invocationId) || !request.snapshotHash || !sha256.test(request.snapshotHash)) {
      throw new BridgeError("MIG_BRIDGE_REQUEST_INVALID");
    }
  } else throw new BridgeError("MIG_BRIDGE_REQUEST_INVALID");
}

function decodeResponse(bytes: Uint8Array, request: BridgeRequest, exitCode: number | null): BridgeResponse {
  if (bytes.length < 2 || bytes.length > MAX_LINE_BYTES || bytes.at(-1) !== 10) {
    throw new BridgeError("MIG_BRIDGE_RESPONSE_FRAMING");
  }
  const text = new TextDecoder("utf-8", { fatal: true }).decode(bytes);
  if (text.slice(0, -1).includes("\n") || text.slice(0, -1).includes("\r")) {
    throw new BridgeError("MIG_BRIDGE_RESPONSE_FRAMING");
  }
  let parsed: unknown;
  try { parsed = parseJsonText(text); }
  catch (error) {
    throw new BridgeError(error instanceof ContractError && error.code === "MIG_JSON_DUPLICATE_KEY"
      ? "MIG_BRIDGE_RESPONSE_DUPLICATE_KEY" : "MIG_BRIDGE_RESPONSE_INVALID");
  }
  if (!object(parsed) || Object.keys(parsed).sort().join(",") !== [
    "artifactRefs", "coverageDelta", "diagnostics", "elapsedMillis", "errorCode", "inputHash",
    "invocationId", "protocolVersion", "requestId", "retryability", "snapshotHash", "status",
  ].sort().join(",") || parsed.protocolVersion !== BRIDGE_VERSION || parsed.requestId !== request.requestId
    || !["REGISTERED", "DISPATCHED", "SUCCEEDED", "ERROR"].includes(String(parsed.status))
    || !Array.isArray(parsed.artifactRefs) || !Array.isArray(parsed.diagnostics)
    || parsed.diagnostics.length !== 0
    || parsed.coverageDelta !== 0 || typeof parsed.elapsedMillis !== "number"
    || !Number.isSafeInteger(parsed.elapsedMillis) || parsed.elapsedMillis < 0
    || !["NEVER", "REPLAY"].includes(String(parsed.retryability))) {
    throw new BridgeError("MIG_BRIDGE_RESPONSE_INVALID");
  }
  if (parsed.status === "ERROR") {
    if (exitCode !== 2 || typeof parsed.errorCode !== "string" || !/^[A-Z][A-Z0-9_]{1,79}$/.test(parsed.errorCode)
      || parsed.artifactRefs.length !== 0 || parsed.invocationId !== null
      || parsed.inputHash !== null || parsed.snapshotHash !== null) throw new BridgeError("MIG_BRIDGE_RESPONSE_INVALID");
    throw new BridgeError(parsed.errorCode, parsed.retryability as "NEVER" | "REPLAY");
  }
  if (exitCode !== 0 || parsed.errorCode !== null || typeof parsed.invocationId !== "string"
    || !uuid.test(parsed.invocationId) || typeof parsed.snapshotHash !== "string"
    || !sha256.test(parsed.snapshotHash)) throw new BridgeError("MIG_BRIDGE_RESPONSE_INVALID");
  if (parsed.status === "REGISTERED" || parsed.status === "DISPATCHED") {
    if (parsed.artifactRefs.length !== 0 || typeof parsed.inputHash !== "string" || !sha256.test(parsed.inputHash)
      || (request.operation === "REGISTER" ? parsed.status !== "REGISTERED" : request.operation !== "INSPECT")) {
      throw new BridgeError("MIG_BRIDGE_RESPONSE_INVALID");
    }
  } else if (parsed.artifactRefs.length !== 1 || !object(parsed.artifactRefs[0])
    || Object.keys(parsed.artifactRefs[0]).sort().join(",") !== "artifactId,contentHash"
    || typeof parsed.artifactRefs[0].artifactId !== "string" || !uuid.test(parsed.artifactRefs[0].artifactId)
    || typeof parsed.artifactRefs[0].contentHash !== "string" || !sha256.test(parsed.artifactRefs[0].contentHash)
    || parsed.artifactRefs[0].contentHash !== parsed.snapshotHash
    || (request.operation === "INSPECT" ? typeof parsed.inputHash !== "string" || !sha256.test(parsed.inputHash)
      : parsed.inputHash !== null || request.operation !== "EXECUTE")) {
    throw new BridgeError("MIG_BRIDGE_RESPONSE_INVALID");
  }
  if (request.operation === "REGISTER" && parsed.status !== "REGISTERED")
    throw new BridgeError("MIG_BRIDGE_RESPONSE_INVALID");
  return parsed as unknown as BridgeResponse;
}

/** Spawn with an argument array, never a shell; discard stderr so it cannot enter model context. */
export async function invokeToolBridge(request: BridgeRequest, processConfig: BridgeProcess): Promise<BridgeResponse> {
  validateRequest(request);
  const input = Buffer.from(JSON.stringify(request) + "\n", "utf8");
  if (input.length > MAX_LINE_BYTES) throw new BridgeError("MIG_BRIDGE_REQUEST_TOO_LARGE");
  const timeoutMillis = processConfig.timeoutMillis ?? 30_000;
  if (!Number.isSafeInteger(timeoutMillis) || timeoutMillis < 1 || timeoutMillis > 30_000
    || !processConfig.command || processConfig.args.length === 0) throw new BridgeError("MIG_BRIDGE_CONFIG_INVALID");
  return await new Promise<BridgeResponse>((resolve, reject) => {
    const child = spawn(processConfig.command, [...processConfig.args], {
      env: processConfig.env ?? process.env, shell: false, windowsHide: true, stdio: ["pipe", "pipe", "ignore"],
    });
    const chunks: Buffer[] = [];
    let size = 0;
    let fault: BridgeError | null = null;
    const timer = setTimeout(() => { fault = new BridgeError("MIG_BRIDGE_TIMEOUT", "REPLAY"); child.kill(); }, timeoutMillis);
    child.stdout.on("data", (chunk: Buffer) => {
      size += chunk.length;
      if (size > MAX_LINE_BYTES) { fault = new BridgeError("MIG_BRIDGE_RESPONSE_TOO_LARGE"); child.kill(); }
      else chunks.push(chunk);
    });
    child.stdin.on("error", () => { /* The close event reports the process failure without leaking input. */ });
    child.on("error", () => { fault = new BridgeError("MIG_BRIDGE_SPAWN_FAILED"); });
    child.on("close", code => {
      clearTimeout(timer);
      if (fault) { reject(fault); return; }
      try { resolve(decodeResponse(Buffer.concat(chunks), request, code)); }
      catch (error) { reject(error instanceof BridgeError ? error : new BridgeError("MIG_BRIDGE_RESPONSE_INVALID")); }
    });
    child.stdin.end(input);
  });
}

/** Reconcile after process loss; a stored success is returned without rerunning the tool. */
export async function resumeToolInvocation(execute: BridgeRequest, processConfig: BridgeProcess): Promise<BridgeResponse> {
  if (execute.operation !== "EXECUTE") throw new BridgeError("MIG_BRIDGE_REQUEST_INVALID");
  const inspected = await invokeToolBridge({ ...execute, operation: "INSPECT", requestId: randomUUID() }, processConfig);
  if (inspected.status === "SUCCEEDED") return inspected;
  if (inspected.status !== "REGISTERED" && inspected.status !== "DISPATCHED")
    throw new BridgeError("MIG_BRIDGE_RESPONSE_INVALID");
  return invokeToolBridge({ ...execute, requestId: randomUUID() }, processConfig);
}

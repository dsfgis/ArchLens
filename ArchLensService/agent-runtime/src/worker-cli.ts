import { PostgresSaver } from "@langchain/langgraph-checkpoint-postgres";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { setTimeout as delay } from "node:timers/promises";
import { randomUUID } from "node:crypto";
import { parseJsonText } from "./contracts.js";
import { runtimeCall, RuntimeError } from "./runtime-bridge.js";
import { checkpointPool, runNext } from "./worker.js";
import { BridgeError } from "./tool-bridge.js";

const jar = process.env.ARCHLENS_CLI_JAR ?? fileURLToPath(new URL("../../../target/archlens-0.1.0-SNAPSHOT-cli.jar", import.meta.url));
const command = process.env.ARCHLENS_JAVA_COMMAND ?? "java";
const runtime = { command, args: ["-jar", jar, "migration-runtime"] };
const tool = { command, args: ["-jar", jar, "migration-tool-bridge"] };
const [operation, ...args] = process.argv.slice(2);
let interrupted = false;
process.on("SIGINT", () => { interrupted = true; }); process.on("SIGTERM", () => { interrupted = true; });
try {
  if (operation === "initialize" && !args.length) {
    // Explicit administrative command. Regular workers never create schemas or grant privileges.
    await runtimeCall(runtime, "INITIALIZE", {});
    const pool = checkpointPool();
    try { await new PostgresSaver(pool, undefined, { schema: "archlens_checkpoint" }).setup(); }
    finally { await pool.end(); }
    console.log(JSON.stringify({ initialized: true }));
  } else if (operation === "enqueue" && args.length === 2) {
    const request = parseJsonText(await readFile(args[0]!, "utf8"));
    console.log(JSON.stringify(await runtimeCall(runtime, "ENQUEUE", { request, submissionKey: args[1] })));
  } else if ((operation === "get" || operation === "cancel") && args.length === 1) {
    console.log(JSON.stringify(await runtimeCall(runtime, operation.toUpperCase(), { runId: args[0] })));
  } else if ((operation === "once" || operation === "serve") && !args.length) {
    const pool = checkpointPool();
    const config = { runtime, tool, checkpointPool: pool, workerId: "worker-" + randomUUID() };
    try {
      do {
        try { const worked = await runNext(config); if (operation === "once") console.log(JSON.stringify({ worked })); }
        catch (error) {
          const code = error instanceof RuntimeError || error instanceof BridgeError ? error.code : "MIG_WORKER_FAILURE";
          console.error(code); if (operation === "once") throw error;
        }
        if (operation === "once") break;
        // Persisted runs are independent of this timer; another process can claim after lease expiry.
        if (!interrupted) await delay(1000);
      } while (!interrupted);
    } finally { await pool.end(); }
  } else throw new RuntimeError("MIG_WORKER_USAGE");
} catch (error) {
  console.error(error instanceof RuntimeError || error instanceof BridgeError ? error.code : "MIG_WORKER_FAILURE");
  process.exitCode = 2;
}

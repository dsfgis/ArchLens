// Test-only crash gates. Production worker CLI has no fault-injection environment or input.
import { checkpointPool, runNext } from "../../src/worker.js";
const [gate, phase, heartbeat] = process.argv.slice(2);
const pool = checkpointPool();
try {
  await runNext({ runtime: { command: "java", args: ["-jar", process.env.ARCHLENS_CLI_JAR!, "migration-runtime"] },
    tool: { command: "java", args: ["-jar", process.env.ARCHLENS_CLI_JAR!, "migration-tool-bridge"] },
    checkpointPool: pool, workerId: "crash-worker", heartbeatMillis: Number(heartbeat ?? 20_000),
    onBoundary: async (name, detail) => {
      if (name === gate && (!phase || (detail.state as { phase?: string } | undefined)?.phase === phase)) {
        process.send?.({ name, detail }); await new Promise<void>(() => {});
      }
    } });
} catch (e) { console.error(e instanceof Error ? e.message : "WORKER_FAILED"); process.exitCode = 2; }
finally { await pool.end(); }

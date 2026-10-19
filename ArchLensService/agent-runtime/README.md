# P0 persistent TypeScript runtime

This package contains the v1 artifact validators, bounded Java tool bridge, and an opt-in LangGraph worker for the fixed read-only `list_rules` tool. It does not implement a migration planner, use a model, run analyzed projects, or replace the existing Java Agent/web investigations. Successful prototype runs seal a **PARTIAL / P0_SCOPE_ONLY** receipt, never a completed migration plan.

## Build and checks

Node **24+**, TypeScript **7.0.2**, JDK **21**, and a built Java CLI are required. From this directory:

```bash
npm ci --ignore-scripts
npm test
```

Default tests do not require a database; the real worker test explicitly skips unless `ARCHLENS_P0_IT=true`. From the project root, the Linux verification script creates its own disposable PostgreSQL 16 cluster, builds/tests Java and Node, initializes the real saver, grants a restricted checkpoint role, injects process failures, and stops/removes only its own cluster data. Logs remain under `ArchLensService/target/verification/p0-*`:

```bash
source .local/toolchains/activate.sh  # if using the repository-local JDK/Maven
export PATH="$PWD/.local/toolchains/dotnet:$PATH"  # if using the local Roslyn host
export DOTNET_ROOT="$PWD/.local/toolchains/dotnet"
python3 ArchLensService/scripts/verify-p0.py
```

`ARCHLENS_TEST_PG_BIN` can select installed PostgreSQL binaries. This harness never uses an existing application database. The Roslyn host must already be built to run its optional integration cases; skipped tests remain visible in Maven results.

## Configuration and lifecycle

Java uses the existing backend-only `ARCHLENS_PG_*` configuration; the legacy configuration validator still requires the `ARCHLENS_NEO4J_*` fields, although P0 does not connect to Neo4j. `ARCHLENS_JAVA_COMMAND` selects the Java executable; `ARCHLENS_CLI_JAR` selects the packaged CLI (default: `../target/archlens-0.1.0-SNAPSHOT-cli.jar`). Keep secrets in process configuration, not requests or command arguments.

The worker saver has its own settings:

| Variable | Meaning |
| --- | --- |
| `ARCHLENS_CHECKPOINT_PG_URL` | PostgreSQL URL containing host/port/database, without credentials or query parameters |
| `ARCHLENS_CHECKPOINT_PG_USER` | Dedicated checkpoint database role |
| `ARCHLENS_CHECKPOINT_PG_PASSWORD` | Backend-only role password |

Use a dedicated ArchLens database on the same local installation. Explicit initialization requires an administrative checkpoint connection; normal workers never run migrations:

```bash
node dist/src/worker-cli.js initialize
```

After initialization, provision a LOGIN role using your administration process (password omitted below), then grant only these rights and configure the worker with that role:

```sql
GRANT USAGE ON SCHEMA archlens_checkpoint TO archlens_checkpoint_worker;
GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA archlens_checkpoint TO archlens_checkpoint_worker;
```

Do not grant this role access to `archlens`, DDL, or checkpoint deletion. Java's backend role owns the business state and needs read access to native checkpoints. Roles isolate database connections; the local OS user and Java launcher remain trusted and can access backend environment configuration.

The P0 sample declares only `list_rules`, `READ_ONLY`, and `SUMMARY_ONLY`; its source refs are unregistered placeholders, not evidence of source access:

```bash
node dist/src/worker-cli.js enqueue ../examples/migration/request-p0-rules.json my-local-submission-key
node dist/src/worker-cli.js serve
# or: node dist/src/worker-cli.js once
node dist/src/worker-cli.js get RUN_UUID
node dist/src/worker-cli.js cancel RUN_UUID
```

A submission key identifies the same immutable request; changing content under that key is rejected. Only runs explicitly enrolled in `P0_RULES_V1` are scheduled. A single process handles one run at a time; multiple processes compete through Java/PG claims. Restarting discovers queued runs and expired leases. The lease is 180 seconds, heartbeat 20 seconds; restart may wait for lease expiry. The graph is independent of browser lifetime. SIGINT/SIGTERM stops further polling after the current bounded run.

## Recovery and budgets

`thread_id=runId`; `EpochSaver` scopes every native saver operation to `archlens.p0-rules.v1/epoch-N`. It commits a bounded `archlens_export` in the native checkpoint row and asks Java to approve it. Java validates versions, scope, catalogue snapshot, invocation, artifact and raw result hash, then advances the pointer by sequence/hash CAS and a monotonic event cursor. `durability: sync` prevents the next node from running before that approval finishes.

A new owner imports only the Java-approved export via `graph.updateState(..., "bootstrap")` in the new epoch. The fixed phase (`ENTRY`, `REGISTERED`, `SUCCEEDED`) reconstructs the next control edge. It never follows the latest unapproved native checkpoint or silently resumes an incompatible state version. Tool registration uses a stable run-derived task ID and action key; execution inspects the durable ledger and reuses a completed artifact.

Logical tool calls reserve one unit at registration and settle before first dispatch. Replays use the same reservation; actual dispatch attempts are separately persisted and capped at three per invocation. Model reservations use the same transaction discipline, but this graph makes no model calls. Elapsed time starts at the first claim and includes downtime; no budget resets on takeover and uncertain work is not refunded. Token/file/object/validation budgets are not implemented by this fixed tool prototype.

Normal sealing requires the current approved successful checkpoint and produces an immutable PARTIAL receipt. Budget exhaustion is PARTIAL; incompatible versions or detected data drift are FAILED. Repeat sealing returns the original receipt hash; conflicting content and stale worker mutations are rejected. Cancellation does not claim to instantly terminate an already-started process. Lost storage/transport leaves the run for lease-based reconciliation rather than reporting success.

The host runtime protocol (`migration-runtime`) and tool protocol (`migration-tool-bridge`) both use one UTF-8 JSON line, at most 16 KiB in each direction, a 30-second process timeout, argument-array spawn, and controlled error codes. They are trusted local host interfaces, not model tools or public APIs. Neither persists credentials, source contents, or raw process errors in graph state. Cloud tracing is disabled.

## Contracts and compatibility

The five schemas use Ajv 2020-12; Java remains authoritative for cross-artifact references and source truth. Six shared golden fixtures check canonical hashes; duplicate decoded JSON keys, unknown fields and unsupported versions are rejected. One archived Agent report has byte/canonical hash coverage; this is not a complete historical deployment matrix.

The fixed tool bridge preserves `REGISTER`/`INSPECT`/`EXECUTE`; its successful payload contains only invocation/hash/artifact references. Java retains the immutable catalogue bytes. V005 adds runtime enrollment, started-at and budget reservations while retaining V001–V004 checksums. Existing queued generic runs are not automatically enrolled. Existing completed/registered invocations are conservatively counted during upgrade.

## Dependencies and boundaries

Direct runtime additions: `@langchain/langgraph` **1.5.1**, `@langchain/langgraph-checkpoint-postgres` **1.1.0**, `@langchain/core` **1.2.18**, `pg` **8.23.1** (MIT). Existing Ajv **8.20.0**, ajv-formats **3.0.1** are MIT. TypeScript **7.0.2** is Apache-2.0; Node/pg types are MIT. The lockfile records all transitive versions; observed licenses are MIT, Apache-2.0, BSD-3-Clause (`fast-uri`) and ISC (`pg-int8`, `split2`). No install scripts are needed. LangSmith is a transitive dependency; tracing is explicitly disabled.

Implementation references: [LangGraph persistence](https://docs.langchain.com/oss/javascript/langgraph/persistence), [PostgresSaver API](https://reference.langchain.com/javascript/langchain-langgraph-checkpoint-postgres/index). Runtime adoption is based on the repository's real PG/process recovery tests, not documentation alone.

Full source registration, authorization tickets beyond the trusted host, new migration web APIs, arbitrary task DAGs, model planning, clarification/supersession, formal MigrationPlan sealing, external validation jobs, full old-version compatibility and production deployment remain outside this P0 prototype. See [tasks](../specs/implementation/tasks.md).

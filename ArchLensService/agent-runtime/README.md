# P0 TypeScript contract runtime

This package is an isolated contract and bridge prototype for the planned LangGraph worker. It does not schedule runs, call a model, connect directly to a database, or replace the existing Java Agent. Its fixed `list_rules` bridge can start the Java CLI, which accesses ArchLens's own PostgreSQL through backend-only configuration. Node.js 24+ is required for this package; the existing `ArchLensClient` keeps its own runtime baseline.

From this directory:

```bash
npm ci --ignore-scripts
npm test
node dist/src/cli.js request ../examples/migration/request-code-only.json
```

`src/contracts.ts` loads the five versioned JSON Schemas in `../src/main/resources/schema/`, compiles them with Ajv draft 2020-12, and computes canonical SHA-256. Object keys use Unicode code point order; arrays retain input order; nullable fields must be present as explicit `null`; numbers must be safe integers; lone UTF-16 surrogates are rejected. Six fixtures in `../examples/migration/golden-hashes.json` are checked by both Java and Node tests. A changed canonical result requires a new contract version and reviewed fixture migration.

Schema validation is local shape/version validation. Java remains authoritative for cross-file request/snapshot/evidence references, work-item DAGs, source authorization, and later sealing. Before accepting a file, the Node boundary validates JSON syntax and scans its raw text for duplicate object keys, including keys that become equal after JSON escape decoding. `MIG_JSON_DUPLICATE_KEY` returns exit code 2 without exposing input content. The same `parseJsonText` guard now checks bridge responses. The CLI prints only a status and hash, never input contents. One archived Agent report has a separate byte/canonical hash baseline in `../examples/migration/legacy-agent-golden.json`; it is not a complete legacy compatibility suite.

`src/tool-bridge.ts` starts a configured Java command with a fixed argument array such as `java`, `-jar`, the built CLI JAR, `migration-tool-bridge`; it does not use a shell. A trusted host supplies the worker identity, task ID, action key, and registered invocation identity outside model arguments. The only v1 tool is no-argument `list_rules`. The host first sends `REGISTER`, then `EXECUTE` with the returned invocationId and catalog snapshot hash. Java stores the result and returns only an artifact reference. Request/response lines are at most 16 KiB each; the Node timeout is at most 30 seconds. A timed-out call is marked replayable but is never retried automatically. The bridge requires a run already created and claimed in Java/PG; there is no end-user or model-facing CLI for claiming one. See [2026-10-03 bridge verification](../../docs/verification/migration-tool-bridge-2026-10-03.md).

On worker takeover, `resumeToolInvocation` first sends `INSPECT` with the registered invocationId and snapshot hash. `REGISTERED` or `DISPATCHED` resumes the pure read-only tool; `SUCCEEDED` returns the persisted artifact reference without rerunning it. An old epoch is rejected by Java. Actual child-process termination after registration and after dispatch was verified against disposable PostgreSQL; see [recovery verification](../../docs/verification/migration-tool-recovery-2026-10-03.md). No LangGraph checkpoint or scheduler is involved yet.

Pinned direct dependencies: Ajv 8.20.0 (MIT), ajv-formats 3.0.1 (MIT), TypeScript 7.0.2 (Apache-2.0), `@types/node` 24.19.1 (MIT). Transitive packages observed in `package-lock.json` use MIT except `fast-uri` 3.1.8 (BSD-3-Clause). `node_modules/` and `dist/` are generated and ignored.

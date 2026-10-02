# P0 TypeScript contract runtime

This package is an isolated contract prototype for the planned LangGraph worker. It does not schedule runs, call a model, access a database, or replace the existing Java Agent. Node.js 24+ is required for this package; the existing `ArchLensClient` keeps its own runtime baseline.

From this directory:

```bash
npm ci --ignore-scripts
npm test
node dist/src/cli.js request ../examples/migration/request-code-only.json
```

`src/contracts.ts` loads the five versioned JSON Schemas in `../src/main/resources/schema/`, compiles them with Ajv draft 2020-12, and computes canonical SHA-256. Object keys use Unicode code point order; arrays retain input order; nullable fields must be present as explicit `null`; numbers must be safe integers; lone UTF-16 surrogates are rejected. Six fixtures in `../examples/migration/golden-hashes.json` are checked by both Java and Node tests. A changed canonical result requires a new contract version and reviewed fixture migration.

Schema validation is local shape/version validation. Java remains authoritative for cross-file request/snapshot/evidence references, work-item DAGs, source authorization, and later sealing. Before accepting a file, the Node boundary validates JSON syntax and scans its raw text for duplicate object keys, including keys that become equal after JSON escape decoding. `MIG_JSON_DUPLICATE_KEY` returns exit code 2 without exposing input content. This guard applies to `parseJsonFile`; future API/bridge entry points must use the same boundary and bind actual source hashes and authenticated tool provenance. The CLI prints only a status and hash, never input contents. One archived Agent report has a separate byte/canonical hash baseline in `../examples/migration/legacy-agent-golden.json`; it is not a complete legacy compatibility suite.

Pinned direct dependencies: Ajv 8.20.0 (MIT), ajv-formats 3.0.1 (MIT), TypeScript 7.0.2 (Apache-2.0), `@types/node` 24.19.1 (MIT). Transitive packages observed in `package-lock.json` use MIT except `fast-uri` 3.1.8 (BSD-3-Clause). `node_modules/` and `dist/` are generated and ignored.

import { canonicalHash, ContractError, loadValidators, parseJsonFile, validateArtifact } from "./contracts.js";
import type { ArtifactKind } from "./contracts.js";

const kinds = new Set<ArtifactKind>(["request", "plan", "evidence", "validation", "event"]);
const kind = process.argv[2] as ArtifactKind | undefined;
const filename = process.argv[3];
if (!kind || !kinds.has(kind) || !filename || process.argv.length !== 4) {
  process.stderr.write("USAGE: node dist/src/cli.js <request|plan|evidence|validation|event> <artifact.json>\n");
  process.exitCode = 2;
} else {
  try {
    const value = parseJsonFile(filename);
    validateArtifact(kind, value, loadValidators());
    process.stdout.write(`SCHEMA_VALID kind=${kind} hash=${canonicalHash(value)}\n`);
  } catch (error) {
    const code = error instanceof ContractError ? error.code : "MIG_INPUT_INVALID";
    // Never print the raw JSON, path, Ajv error data, or untrusted exception message.
    process.stderr.write(`${code}: artifact rejected\n`);
    process.exitCode = 2;
  }
}

import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import test from "node:test";
import { canonical, canonicalHash, ContractError, loadValidators, parseJsonFile, validateArtifact } from "../src/contracts.js";
import type { ArtifactKind } from "../src/contracts.js";

const fixtures = resolve(import.meta.dirname, "../../../examples/migration");
const validators = loadValidators();
function fixture(name: string): unknown { return parseJsonFile(resolve(fixtures, name)); }

test("five artifact schemas accept their synthetic fixtures", () => {
  const cases: [ArtifactKind, string][] = [
    ["request", "request-code-only.json"], ["plan", "plan-code-only.json"],
    ["evidence", "evidence-code-only.json"], ["validation", "validation-not-run.json"],
    ["event", "event-plan-drafted.json"],
  ];
  for (const [kind, name] of cases) {
    const value = fixture(name);
    if (Array.isArray(value)) for (const item of value) validateArtifact(kind, item, validators);
    else validateArtifact(kind, value, validators);
  }
});

test("all hashes match the shared Java/Node v1 golden file", () => {
  const goldens = fixture("golden-hashes.json") as Record<string, {file: string; sha256: string}>;
  for (const [kind, golden] of Object.entries(goldens)) {
    assert.equal(canonicalHash(fixture(golden.file)), golden.sha256, kind);
  }
});

test("one archived Agent report retains its byte and canonical identities", () => {
  // The legacy fixture is a readback regression baseline, not a new migration Schema sample.
  const golden = fixture("legacy-agent-golden.json") as {file: string; byteSha256: string; canonicalSha256: string};
  const path = resolve(fixtures, golden.file);
  const bytes = readFileSync(path);
  assert.equal(createHash("sha256").update(bytes).digest("hex"), golden.byteSha256);
  assert.equal(canonicalHash(parseJsonFile(path)), golden.canonicalSha256);
});

test("canonical sorting uses code points and arrays retain order", () => {
  assert.equal(canonical({ "😀": 2, "\uE000": 1 }), '{"":1,"😀":2}');
  assert.notEqual(canonicalHash(["a", "b"]), canonicalHash(["b", "a"]));
  assert.throws(() => canonical({ value: Number.MAX_SAFE_INTEGER + 1 }),
    (error: unknown) => error instanceof ContractError && error.code === "MIG_CANONICAL_NUMBER_INVALID");
  assert.throws(() => canonical("\ud800"),
    (error: unknown) => error instanceof ContractError && error.code === "MIG_CANONICAL_UNICODE_INVALID");
});

test("schema rejects unknown properties and unsupported versions", () => {
  const source = fixture("request-code-only.json") as Record<string, unknown>;
  const extra = structuredClone(source);
  (extra.sourceRefs as Record<string, unknown>[])[0]!.password = "synthetic-only";
  assert.throws(() => validateArtifact("request", extra, validators), ContractError);
  const future = structuredClone(source);
  future.schemaVersion = "archlens.migration-request.v99";
  assert.throws(() => validateArtifact("request", future, validators), ContractError);
  const missingNull = structuredClone(source);
  delete (missingNull.sourceRefs as Record<string, unknown>[])[0]!.credentialRef;
  assert.throws(() => validateArtifact("request", missingNull, validators), ContractError);
  const event = fixture("event-plan-drafted.json") as Record<string, unknown>;
  event.sequence = Number.MAX_SAFE_INTEGER + 1;
  assert.throws(() => validateArtifact("event", event, validators), ContractError);
});

test("raw JSON input rejects duplicate decoded keys at every object level", () => {
  const directory = mkdtempSync(join(tmpdir(), "archlens-contract-"));
  const file = join(directory, "input.json");
  const parse = (raw: string): unknown => {
    writeFileSync(file, raw, "utf8");
    return parseJsonFile(file);
  };
  const duplicate = (raw: string): void => {
    assert.throws(() => parse(raw),
      (error: unknown) => error instanceof ContractError && error.code === "MIG_JSON_DUPLICATE_KEY");
  };
  try {
    duplicate('{"scope":1,"scope":2}');
    duplicate('{"scope":1,"\\u0073cope":2}');
    duplicate('{"a\\\"b":1,"a\\u0022b":2}');
    duplicate('{"items":[{"value":1,"value":2}]}');
    assert.deepEqual(parse('{"items":[{"value":1},{"value":2}]}'), { items: [{ value: 1 }, { value: 2 }] });
    assert.deepEqual(parse('{"text":"comma, brace } and escaped \\\"quote\\\""}'),
      { text: 'comma, brace } and escaped "quote"' });
    assert.throws(() => parse('{"items":}'),
      (error: unknown) => error instanceof ContractError && error.code === "MIG_JSON_INVALID");
    assert.throws(() => parse("[".repeat(101) + "0" + "]".repeat(101)),
      (error: unknown) => error instanceof ContractError && error.code === "MIG_JSON_DEPTH_INVALID");
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});

import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import { resolve } from "node:path";
import { Ajv2020 } from "ajv/dist/2020.js";
import type { AnySchema } from "ajv/dist/2020.js";
import type { ValidateFunction } from "ajv";

export type ArtifactKind = "request" | "plan" | "evidence" | "validation" | "event";
const kinds: readonly ArtifactKind[] = ["request", "plan", "evidence", "validation", "event"];
const schemaRoot = resolve(import.meta.dirname, "../../../src/main/resources/schema");
const addFormats = createRequire(import.meta.url)("ajv-formats") as (ajv: Ajv2020) => void;

export class ContractError extends Error {
  constructor(readonly code: string) { super(code); }
}

// The Java authority still performs cross-file checks; this boundary rejects malformed shapes and versions.
export function loadValidators(root = schemaRoot): Record<ArtifactKind, ValidateFunction> {
  const ajv = new Ajv2020({
    strict: true,
    strictTypes: false, // Existing if/then schemas use properties inside typed parent objects.
    allowUnionTypes: true,
    allErrors: true,
    removeAdditional: false,
    useDefaults: false,
    coerceTypes: false,
  });
  addFormats(ajv);
  const validators = {} as Record<ArtifactKind, ValidateFunction>;
  for (const kind of kinds) {
    const path = resolve(root, `archlens.migration-${kind}.v1.schema.json`);
    const schema = JSON.parse(readFileSync(path, "utf8")) as AnySchema;
    validators[kind] = ajv.compile(schema);
  }
  return validators;
}

export function validateArtifact(kind: ArtifactKind, value: unknown, validators: Record<ArtifactKind, ValidateFunction>): void {
  if (!validators[kind](value)) throw new ContractError("MIG_SCHEMA_INVALID");
}

export function parseJsonFile(path: string): unknown {
  const bytes = readFileSync(path);
  if (bytes.length > 5_000_000) throw new ContractError("MIG_INPUT_TOO_LARGE");
  let text: string;
  try { text = new TextDecoder("utf-8", { fatal: true }).decode(bytes); }
  catch { throw new ContractError("MIG_INPUT_ENCODING_INVALID"); }
  let value: unknown;
  try { value = JSON.parse(text) as unknown; }
  catch { throw new ContractError("MIG_JSON_INVALID"); }
  rejectDuplicateKeys(text);
  return value;
}

// JSON.parse validates syntax but silently keeps the final occurrence of an object key.
// Scan the validated text before accepting it, decoding escaped keys so "a" and "\\u0061" collide.
function rejectDuplicateKeys(text: string): void {
  const space = (char: string | undefined): boolean => char === " " || char === "\t" || char === "\n" || char === "\r";
  const skipSpace = (start: number): number => {
    let index = start;
    while (space(text[index])) index++;
    return index;
  };
  const quotedEnd = (start: number): number => {
    let index = start + 1;
    while (index < text.length) {
      if (text[index] === "\\") { index += 2; continue; }
      if (text[index] === '"') return index + 1;
      index++;
    }
    throw new ContractError("MIG_JSON_INVALID"); // Defensive: JSON.parse has already validated syntax.
  };
  const scan = (start: number, depth: number): number => {
    if (depth > 100) throw new ContractError("MIG_JSON_DEPTH_INVALID");
    let index = skipSpace(start);
    if (text[index] === "{") {
      const keys = new Set<string>();
      index = skipSpace(index + 1);
      while (text[index] !== "}") {
        const end = quotedEnd(index);
        const key = JSON.parse(text.slice(index, end)) as string;
        if (keys.has(key)) throw new ContractError("MIG_JSON_DUPLICATE_KEY");
        keys.add(key);
        index = scan(skipSpace(end) + 1, depth + 1); // Skip the validated colon.
        index = skipSpace(index);
        if (text[index] === ",") index = skipSpace(index + 1);
      }
      return index + 1;
    }
    if (text[index] === "[") {
      index = skipSpace(index + 1);
      while (text[index] !== "]") {
        index = skipSpace(scan(index, depth + 1));
        if (text[index] === ",") index = skipSpace(index + 1);
      }
      return index + 1;
    }
    if (text[index] === '"') return quotedEnd(index);
    while (index < text.length && !space(text[index]) && text[index] !== "," && text[index] !== "}" && text[index] !== "]") index++;
    return index;
  };
  scan(0, 0);
}

// Java Json.canonical sorts object keys by Unicode code point and preserves array order.
function compareCodePoints(left: string, right: string): number {
  const a = Array.from(left, c => c.codePointAt(0)!);
  const b = Array.from(right, c => c.codePointAt(0)!);
  for (let i = 0; i < Math.min(a.length, b.length); i++) {
    if (a[i] !== b[i]) return a[i]! - b[i]!;
  }
  return a.length - b.length;
}

function quoteLikeJava(value: string): string {
  let result = '"';
  for (let i = 0; i < value.length; i++) {
    const unit = value.charCodeAt(i);
    if (unit >= 0xd800 && unit <= 0xdbff) {
      const next = value.charCodeAt(i + 1);
      if (!(next >= 0xdc00 && next <= 0xdfff)) throw new ContractError("MIG_CANONICAL_UNICODE_INVALID");
      result += value[i]! + value[++i]!;
      continue;
    }
    if (unit >= 0xdc00 && unit <= 0xdfff) throw new ContractError("MIG_CANONICAL_UNICODE_INVALID");
    const special: Record<number, string> = { 8: "\\b", 9: "\\t", 10: "\\n", 12: "\\f", 13: "\\r", 34: '\\"', 92: "\\\\" };
    if (special[unit]) result += special[unit];
    else if (unit < 32) result += `\\u${unit.toString(16).padStart(4, "0").toUpperCase()}`;
    else result += value[i]!;
  }
  return result + '"';
}

export function canonical(value: unknown, depth = 0): string {
  if (depth > 100) throw new ContractError("MIG_CANONICAL_DEPTH_INVALID");
  if (value === null) return "null";
  if (typeof value === "string") return quoteLikeJava(value);
  if (typeof value === "boolean") return value ? "true" : "false";
  if (typeof value === "number") {
    if (!Number.isSafeInteger(value)) throw new ContractError("MIG_CANONICAL_NUMBER_INVALID");
    return Object.is(value, -0) ? "0" : String(value);
  }
  if (Array.isArray(value)) return `[${value.map(item => canonical(item, depth + 1)).join(",")}]`;
  if (typeof value === "object") {
    const prototype = Object.getPrototypeOf(value);
    if (prototype !== Object.prototype && prototype !== null) throw new ContractError("MIG_CANONICAL_OBJECT_INVALID");
    const object = value as Record<string, unknown>;
    return `{${Object.keys(object).sort(compareCodePoints).map(key =>
      `${quoteLikeJava(key)}:${canonical(object[key], depth + 1)}`).join(",")}}`;
  }
  throw new ContractError("MIG_CANONICAL_VALUE_INVALID");
}

export function canonicalHash(value: unknown): string {
  return createHash("sha256").update(canonical(value), "utf8").digest("hex");
}

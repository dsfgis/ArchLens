import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const service = fileURLToPath(new URL('../ArchLensService/', import.meta.url));
const sourceDirectory = path.join(service, '.local', 'migration-sources');
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const sourceId = /^[A-Za-z][A-Za-z0-9._:-]{0,99}$/;
const submissionKey = /^[A-Za-z0-9._:-]{1,200}$/;

/** Host-side Java protocol. Source roots and credentials are never placed in `request`. */
export function migrationBridge(input) {
  return new Promise((resolve, reject) => {
    const home = process.env.ARCHLENS_JAVA_HOME || process.env.JAVA_HOME;
    const java = home ? path.join(home, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java';
    const jar = path.join(service, 'target', 'archlens-0.1.0-SNAPSHOT-cli.jar');
    const child = spawn(java, [
      '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-cp', jar,
      'io.archlens.cli.MigrationWebCli',
    ], {
      windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'],
      env: { ...process.env, ARCHLENS_MIGRATION_SOURCE_DIR: sourceDirectory },
    });
    let output = '';
    const timer = setTimeout(() => { child.kill(); reject(new Error('BACKEND_TIMEOUT')); }, 30000);
    child.stdout.setEncoding('utf8');
    child.stdout.on('data', chunk => {
      output += chunk;
      if (output.length > 2_000_000) { child.kill(); reject(new Error('REPORT_TOO_LARGE')); }
    });
    child.stderr.resume();
    child.stdin.on('error', () => {});
    child.on('error', () => reject(new Error('BACKEND_UNAVAILABLE')));
    child.on('close', code => {
      clearTimeout(timer);
      const line = output.split(/\r?\n/).find(Boolean);
      if (!line) { reject(new Error('BACKEND_UNAVAILABLE')); return; }
      try {
        const message = JSON.parse(line);
        if (message.event === 'error') throw new Error(message.error || 'BACKEND_FAILED');
        if (message.event !== 'result') throw new Error('BACKEND_PROTOCOL_ERROR');
        if (code !== 0) throw new Error('BACKEND_PROTOCOL_ERROR');
        resolve(message.value);
      } catch (error) {
        reject(error instanceof Error ? error : new Error('BACKEND_PROTOCOL_ERROR'));
      }
    });
    child.stdin.end(JSON.stringify(input));
  });
}

function exact(body, keys) {
  if (!body || typeof body !== 'object' || Array.isArray(body)
      || Object.keys(body).some(key => !keys.includes(key))) throw new Error('INVALID_REQUEST');
}

function validRunId(value) {
  if (!uuid.test(value || '')) throw new Error('INVALID_REQUEST');
  return value;
}

async function readBody(req) {
  const chunks = [];
  let size = 0;
  for await (const chunk of req) {
    size += chunk.length;
    if (size > 240000) throw new Error('INPUT_LIMIT');
    chunks.push(chunk);
  }
  try {
    return JSON.parse(Buffer.concat(chunks).toString('utf8'));
  } catch {
    throw new Error('INVALID_REQUEST');
  }
}

function statusFor(error) {
  if (error === 'MIG_AUTHORIZATION_MISMATCH' || error === 'MIG_CREDENTIAL_REF_MISMATCH') return 403;
  if (error === 'MIG_SOURCE_UNKNOWN' || error === 'RUN_NOT_FOUND') return 404;
  if (['MIG_SOURCE_DRIFT', 'MIG_SOURCE_CONFLICT', 'MIG_SUBMISSION_CONFLICT'].includes(error)) return 409;
  if (/STORAGE|BACKEND|CONTEXT/.test(error)) return 503;
  return 400;
}

/**
 * Injectable handler for HTTP contract tests. The production handler invokes the
 * Java `MigrationWebCli`; tests can replace only the transport.
 */
export function createMigrationHandler({ backend = migrationBridge } = {}) {
  return async function handleMigrations(req, res, json) {
    const url = new URL(req.url, 'http://localhost');
    if (!url.pathname.startsWith('/api/migrations')) return false;
    const modifying = req.method === 'POST';
    if (modifying && (req.headers.origin !== `http://${req.headers.host}`
        || req.headers['content-type']?.split(';')[0] !== 'application/json')
      || !modifying && (req.headers.origin && req.headers.origin !== `http://${req.headers.host}`
        || req.headers['sec-fetch-site'] === 'cross-site')) {
      json(res, 403, { error: 'REQUEST_REJECTED' });
      return true;
    }
    try {
      if (url.pathname === '/api/migrations/sources') {
        if (req.method !== 'POST') throw new Error('METHOD_NOT_ALLOWED');
        const body = await readBody(req);
        exact(body, ['sourceRef', 'kind', 'sourceRoot', 'locator', 'files', 'declaredProduct',
          'declaredVersion', 'authorizationRef', 'credentialRef']);
        if (!sourceId.test(body.sourceRef || '') || typeof body.kind !== 'string'
            || typeof body.authorizationRef !== 'string' || !body.authorizationRef) {
          throw new Error('INVALID_REQUEST');
        }
        if (body.sourceRoot != null && (typeof body.sourceRoot !== 'string' || !path.isAbsolute(body.sourceRoot))) {
          throw new Error('SOURCE_ROOT_INVALID');
        }
        if (body.files != null && (!Array.isArray(body.files) || body.files.some(file =>
          typeof file !== 'string' || !file || path.isAbsolute(file) || file.includes('..') || file.includes(':')))) {
          throw new Error('PATH_OUTSIDE_ROOT');
        }
        if (body.credentialRef != null && typeof body.credentialRef !== 'string') throw new Error('INVALID_REQUEST');
        const value = await backend({
          action: 'register', sourceRef: body.sourceRef, kind: body.kind,
          sourceRoot: body.sourceRoot ?? null, locator: body.locator ?? null,
          files: body.files ?? null, declaredProduct: body.declaredProduct ?? null,
          declaredVersion: body.declaredVersion ?? null, authorizationRef: body.authorizationRef,
          credentialRef: body.credentialRef ?? null, submissionKey: null, request: null, runId: null,
        });
        json(res, 201, value);
        return true;
      }
      if (url.pathname === '/api/migrations') {
        if (req.method !== 'POST') throw new Error('METHOD_NOT_ALLOWED');
        const body = await readBody(req);
        exact(body, ['submissionKey', 'request']);
        if (!submissionKey.test(body.submissionKey || '') || !body.request || typeof body.request !== 'object'
            || Array.isArray(body.request)) throw new Error('INVALID_REQUEST');
        const value = await backend({
          action: 'start', sourceRef: null, kind: null, sourceRoot: null, locator: null,
          files: null, declaredProduct: null, declaredVersion: null, authorizationRef: null,
          credentialRef: null, submissionKey: body.submissionKey, request: body.request, runId: null,
        });
        json(res, 202, value);
        return true;
      }
      const match = /^\/api\/migrations\/([^/]+)(?:\/(cancel))?$/.exec(url.pathname);
      if (!match) throw new Error('INVALID_REQUEST');
      const runId = validRunId(match[1]);
      if (match[2]) {
        if (req.method !== 'POST') throw new Error('METHOD_NOT_ALLOWED');
        exact(await readBody(req), []);
        const value = await backend({
          action: 'cancel', sourceRef: null, kind: null, sourceRoot: null, locator: null,
          files: null, declaredProduct: null, declaredVersion: null, authorizationRef: null,
          credentialRef: null, submissionKey: null, request: null, runId,
        });
        json(res, 200, value);
        return true;
      }
      if (req.method !== 'GET') throw new Error('METHOD_NOT_ALLOWED');
      const value = await backend({
        action: 'get', sourceRef: null, kind: null, sourceRoot: null, locator: null,
        files: null, declaredProduct: null, declaredVersion: null, authorizationRef: null,
        credentialRef: null, submissionKey: null, request: null, runId,
      });
      json(res, 200, value);
      return true;
    } catch (error) {
      const code = /^[A-Z][A-Z0-9_]{1,80}$/.test(error.message) ? error.message : 'INVALID_REQUEST';
      json(res, statusFor(code), { error: code });
      return true;
    }
  };
}

export const handleMigrations = createMigrationHandler();

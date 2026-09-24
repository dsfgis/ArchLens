import http from 'node:http';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { spawn } from 'node:child_process';
import path from 'node:path';
import { handleInvestigations } from './investigations.mjs';

const port = Number(process.env.ARCHLENS_CLIENT_PORT || 4173);
const allowedHosts = new Set([`127.0.0.1:${port}`, `localhost:${port}`]);
let parsing = false;
function json(res, status, body) {
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' });
  res.end(JSON.stringify(body));
}
async function parseTarget(req, res) {
  if (req.method !== 'POST') return json(res, 405, { error: 'METHOD_NOT_ALLOWED' });
  if (!allowedHosts.has(req.headers.host) || req.headers.origin !== `http://${req.headers.host}`
      || req.headers['content-type']?.split(';')[0] !== 'application/json') return json(res, 403, { error: 'REQUEST_REJECTED' });
  if (!process.env.DEEPSEEK_API_KEY) return json(res, 503, { error: 'MODEL_NOT_CONFIGURED' });
  if (parsing) return json(res, 429, { error: 'MODEL_BUSY' });
  parsing = true;
  let timer;
  try {
    const chunks = [];
    let size = 0;
    for await (const chunk of req) {
      size += chunk.length;
      if (size > 24000) return json(res, 413, { error: 'INVALID_REQUEST' });
      chunks.push(chunk);
    }
    const body = JSON.parse(Buffer.concat(chunks).toString('utf8'));
    if (!body || Object.keys(body).length !== 1 || typeof body.description !== 'string'
        || body.description.trim().length < 10 || body.description.length > 4000) return json(res, 400, { error: 'INVALID_REQUEST' });
    const javaHome = process.env.ARCHLENS_JAVA_HOME || process.env.JAVA_HOME;
    const java = javaHome ? path.join(javaHome, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java';
    const jar = fileURLToPath(new URL('../ArchLensService/target/archlens-0.1.0-SNAPSHOT-cli.jar', import.meta.url));
    const child = spawn(java, ['-cp', jar, 'io.archlens.cli.ParseTargetCli'], { stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true });
    child.stdout.setEncoding('utf8');
    const result = await new Promise((resolve, reject) => {
      let output = '';
      timer = setTimeout(() => { child.kill(); reject(new Error('MODEL_TIMEOUT')); }, 70000);
      child.stdout.on('data', chunk => {
        output += chunk.toString('utf8');
        if (output.length > 128000) { child.kill(); reject(new Error('INVALID_MODEL_OUTPUT')); }
      });
      child.stderr.resume(); // JVM diagnostics are not returned to the browser or logged with credentials.
      child.stdin.on('error', () => {});
      child.on('error', () => reject(new Error('MODEL_BACKEND_UNAVAILABLE')));
      child.on('close', () => {
        try { resolve(JSON.parse(output)); } catch { reject(new Error('MODEL_BACKEND_UNAVAILABLE')); }
      });
      child.stdin.end(JSON.stringify({ description: body.description.trim() }));
    });
    json(res, result.error ? 502 : 200, result);
  } catch (error) {
    json(res, error instanceof SyntaxError ? 400 : 502, { error: ['MODEL_TIMEOUT', 'INVALID_MODEL_OUTPUT', 'MODEL_BACKEND_UNAVAILABLE'].includes(error.message) ? error.message : 'INVALID_REQUEST' });
  } finally { clearTimeout(timer); parsing = false; }
}
const files = new Map([
  ['/', ['index.html', 'text/html; charset=utf-8']],
  ['/index.html', ['index.html', 'text/html; charset=utf-8']],
  ['/styles.css', ['styles.css', 'text/css; charset=utf-8']],
  ['/app.js', ['app.js', 'text/javascript; charset=utf-8']],
  ['/draft.html', ['draft.html', 'text/html; charset=utf-8']],
  ['/workbench.js', ['workbench.js', 'text/javascript; charset=utf-8']],
  ['/workbench.css', ['workbench.css', 'text/css; charset=utf-8']],
]);
http.createServer(async (req, res) => {
  if (!allowedHosts.has(req.headers.host)) { res.writeHead(403); res.end('Forbidden'); return; }
  if (await handleInvestigations(req, res, json)) return;
  if (new URL(req.url, 'http://localhost').pathname === '/api/parse-target') return parseTarget(req, res);
  const file = files.get(new URL(req.url, 'http://localhost').pathname);
  if (!file || !['GET', 'HEAD'].includes(req.method)) {
    res.writeHead(404); res.end('Not found'); return;
  }
  try {
    const data = await readFile(fileURLToPath(new URL(file[0], import.meta.url)));
    res.writeHead(200, {
      'Content-Type': file[1], 'Cache-Control': 'no-store',
      'X-Content-Type-Options': 'nosniff',
      'Content-Security-Policy': "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'",
    });
    res.end(req.method === 'HEAD' ? undefined : data);
  } catch {
    res.writeHead(500); res.end('Unable to load page');
  }
}).listen(port, '127.0.0.1', () => console.log(`ArchLens: http://127.0.0.1:${port}`));

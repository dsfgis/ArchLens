// 空根目录只用于无代码文件的数据库调查；恢复请求由 Java 按封存请求再次校验。
async function authorizedRoot(value, request, resume=false) {
  if(!resume && (!request || (!request.files?.length && !request.businessContext?.source))) throw new Error('INVALID_REQUEST');
  if(value==null||value==='') {
    if(resume || (Array.isArray(request?.files)&&request.files.length===0&&request.columnRequest==null&&request.businessContext?.source)) return null;
    throw new Error('SOURCE_ROOT_INVALID');
  }
  if(typeof value!=='string'||!path.isAbsolute(value))throw new Error('SOURCE_ROOT_INVALID');
  const root=await realpath(value).catch(()=>{throw new Error('SOURCE_ROOT_INVALID');});
  if(!(await stat(root)).isDirectory())throw new Error('SOURCE_ROOT_INVALID');return root;
}
import { discoverCSharpProject } from './csharp-project.mjs';
import { discoverDotnetProject } from './dotnet-project.mjs';
import { spawn } from 'node:child_process';
import { mkdir, readFile, writeFile, realpath, stat } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const service = fileURLToPath(new URL('../ArchLensService/', import.meta.url));
const contexts = path.join(service, '.local', 'web-contexts');
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export function bridge(input, onStarted) {
  return new Promise((resolve, reject) => {
    const home = process.env.ARCHLENS_JAVA_HOME || process.env.JAVA_HOME;
    const java = home ? path.join(home, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java';
    const extra=(process.env.ARCHLENS_BUSINESS_JDBC_JARS||'').split(path.delimiter).filter(Boolean);
    if(extra.some(file=>!path.isAbsolute(file)||!file.toLowerCase().endsWith('.jar'))) {reject(new Error('DB_DRIVER_CONFIG_INVALID'));return;}
    const classpath=[path.join(service,'target/archlens-0.1.0-SNAPSHOT-cli.jar'),...extra].join(path.delimiter);
    let child;
    try { child = spawn(java, ['-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-cp', classpath, 'io.archlens.cli.WebAgentCli'], { windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'] }); }
    catch { reject(new Error('BACKEND_UNAVAILABLE')); return; }
    let buffer = '', bytes = 0, value, failure, started;
    const timer = setTimeout(() => { failure = 'BACKEND_TIMEOUT'; child.kill(); }, onStarted ? 155000 : 30000);
    child.stdout.setEncoding('utf8');
    child.stdout.on('data', chunk => {
      bytes += Buffer.byteLength(chunk);
      if (bytes > 20000000) { failure = 'REPORT_TOO_LARGE'; child.kill(); return; }
      buffer += chunk;
      let end;
      while ((end = buffer.indexOf('\n')) >= 0) {
        const line = buffer.slice(0, end); buffer = buffer.slice(end + 1);
        try {
          const message = JSON.parse(line);
          if (message.event === 'error') failure = message.error;
          else if (message.event === 'started') started = Promise.resolve(onStarted?.(message.value)).catch(() => { failure = 'CONTEXT_SAVE_FAILED'; });
          else value = message.value;
        } catch { failure = 'BACKEND_PROTOCOL_ERROR'; child.kill(); }
      }
    });
    // 子进程原始错误可能包含环境细节，不返回浏览器、不写日志。
    child.stderr.resume(); child.stdin.on('error', () => {});
    child.on('error', () => { failure = 'BACKEND_UNAVAILABLE'; });
    child.on('close', async code => {
      clearTimeout(timer); await started;
      if (failure || code !== 0 || value === undefined) reject(new Error(failure || 'BACKEND_UNAVAILABLE'));
      else resolve(value);
    });
    child.stdin.end(JSON.stringify(input));
  });
}
function exact(body, keys) {
  if (!body || typeof body !== 'object' || Array.isArray(body) || Object.keys(body).some(k => !keys.includes(k))) throw new Error('INVALID_REQUEST');
}
function validId(value) { if (!uuid.test(value || '')) throw new Error('INVALID_REQUEST'); return value; }
async function readBody(req) {
  const parts = []; let size = 0;
  for await (const chunk of req) { size += chunk.length; if (size > 240000) throw new Error('INPUT_LIMIT'); parts.push(chunk); }
  try { return JSON.parse(Buffer.concat(parts).toString('utf8')); } catch { throw new Error('INVALID_REQUEST'); }
}
// 可注入传输用于离线 HTTP 契约测试；生产入口始终使用真实 Java bridge。
export function createInvestigationHandler({ backend = bridge, contextDirectory = contexts } = {}) {
let active = 0;
let queries = 0;
return async function handleInvestigations(req, res, json) {
  const url = new URL(req.url, 'http://localhost');
  if (!url.pathname.startsWith('/api/investigations')) return false;
  const modifying = req.method === 'POST';
  if (modifying && (req.headers.origin !== `http://${req.headers.host}` || req.headers['content-type']?.split(';')[0] !== 'application/json')
      || !modifying && (req.headers.origin && req.headers.origin !== `http://${req.headers.host}` || req.headers['sec-fetch-site'] === 'cross-site')) {
    json(res, 403, { error: 'REQUEST_REJECTED' }); return true;
  }
  try {
    if (url.pathname === '/api/investigations/example' && req.method === 'GET') {
      // 只发布仓库自带的合成样例路径，不枚举用户工作区或读取业务文件。
      const scenario = url.searchParams.get('scenario') || 'mysql-postgresql';
      if (!['mysql-postgresql', 'csharp-java', 'dotnet-platform'].includes(scenario)) throw new Error('INVALID_REQUEST');
      const sourceRoot = path.join(service, 'examples', 'scenarios', scenario);
      const request = JSON.parse(await readFile(path.join(sourceRoot, 'agent-clarify.json'), 'utf8'));
      json(res, 200, { sourceRoot, request });
      return true;
    }
    if (['/api/investigations/discover-csharp', '/api/investigations/discover-dotnet'].includes(url.pathname) && req.method === 'POST') {
      if (queries >= 6) throw new Error('SERVER_BUSY');
      queries++;
      try { const body = await readBody(req); exact(body, ['sourceRoot']); json(res, 200, await (url.pathname.endsWith('discover-dotnet') ? discoverDotnetProject : discoverCSharpProject)(body.sourceRoot)); }
      finally { queries--; }
      return true;
    }
    if (['/api/investigations/preview-joint','/api/investigations/test-database'].includes(url.pathname) && req.method === 'POST') {
      if (active >= 2) throw new Error('SERVER_BUSY');
      active++;
      try {
        const body=await readBody(req);exact(body,['sourceRoot','request','connection']);
        if (!body.request || !body.request.businessContext || body.request.columnRequest != null) throw new Error('INVALID_REQUEST');
        const testing=url.pathname.endsWith('test-database');
        let sourceRoot=null;
        if (!testing) {
          sourceRoot=await authorizedRoot(body.sourceRoot,body.request);
        }
        // 凭据只传本次 Java 子进程；不写磁盘、历史上下文或日志。
        json(res,200,await backend({action:testing?'test-database':'preview-joint',sourceRoot,request:body.request,connection:body.connection||null}));
      } finally {active--;}
      return true;
    }
    if (url.pathname === '/api/investigations/preview-dotnet' && req.method === 'POST') {
      // 预览与正式调查共用重任务槽位；不写 web-contexts，不分配假 Run ID。
      if (active >= 2) throw new Error('SERVER_BUSY');
      active++;
      try {
        const body = await readBody(req); exact(body, ['sourceRoot', 'files']);
        if (typeof body.sourceRoot !== 'string' || !path.isAbsolute(body.sourceRoot)) throw new Error('SOURCE_ROOT_INVALID');
        const sourceRoot = await realpath(body.sourceRoot).catch(() => { throw new Error('SOURCE_ROOT_INVALID'); });
        if (!(await stat(sourceRoot)).isDirectory()) throw new Error('SOURCE_ROOT_INVALID');
        if (!Array.isArray(body.files) || !body.files.length || body.files.length > 1000 || body.files.some(f => typeof f !== 'string' || !f.trim())) throw new Error('INVALID_REQUEST');
        const request = {schemaVersion:'archlens.agent.v1', objective:'本地 .NET 平台现状预览',
          target:{scenario:'CURRENT_STATE',sourceProfile:{product:'.NET',version:null},targetProfile:null},
          constraints:[],invariants:[],files:body.files,columnRequest:null,
          collectionBudget:{maxFiles:1000,maxBytes:50000000,timeoutMillis:25000},
          agentBudget:{maxModelCalls:1,maxToolCalls:1,timeoutMillis:30000}};
        json(res, 200, await backend({action:'preview-dotnet',sourceRoot,request}));
      } finally { active--; }
      return true;
    }
    const match = /^\/api\/investigations(?:\/([0-9a-f-]+)(?:\/(resume|cancel|report))?)?$/.exec(url.pathname);
    if (!match || !['GET', 'POST'].includes(req.method)) { json(res, 404, { error: 'NOT_FOUND' }); return true; }
    const [, id, operation] = match;
    if (id) validId(id);
    if (req.method === 'GET') {
      if (operation && operation !== 'report') throw new Error('INVALID_REQUEST');
      if (queries >= 6) throw new Error('SERVER_BUSY');
      queries++;
      try {
        let result;
        if (id) {
          result = await backend({ action: 'get', runId: id });
          if (operation === 'report') {
            if (!result.report && !result.checkpoint) throw new Error('NO_REPORT');
            res.setHeader('Content-Disposition', `attachment; filename="archlens-${id}.json"`);
            json(res, 200, result.report || { quality: 'INCOMPLETE_CHECKPOINT', runId: id, sources: result.checkpoint });
            return true;
          }
          try { result.sourceRoot = JSON.parse(await readFile(path.join(contextDirectory, `${id}.json`), 'utf8')).sourceRoot; } catch { result.sourceRoot = null; }
        } else {
          const offset = Number(url.searchParams.get('offset') || 0);
          if (!Number.isSafeInteger(offset) || offset < 0 || offset > 100000) throw new Error('INVALID_REQUEST');
          result = await backend({ action: 'list', caseId: url.searchParams.has('caseId') ? validId(url.searchParams.get('caseId')) : null, offset });
        }
        json(res, 200, result);
      } finally { queries--; }
    } else if (id && operation === 'cancel') {
      exact(await readBody(req), []);
      json(res, 200, await backend({ action: 'cancel', runId: id }));
    } else if (!id || operation === 'resume') {
      if (active >= 2) throw new Error('SERVER_BUSY');
      // 先占用槽位，再读取请求，避免多个并发请求绕过上限。
      active++;
      try {
        const body = await readBody(req);
        exact(body, id ? ['sourceRoot', 'answers', 'connection'] : ['sourceRoot', 'request', 'connection']);
        const sourceRoot = await authorizedRoot(body.sourceRoot,body.request,Boolean(id));
        if (!id && (!body.request || body.request.columnRequest != null || body.request.target?.scenario === 'COLUMN_CHANGE')) throw new Error('COLUMN_WEB_UNSUPPORTED');
        if (id) {
          let context;
          try { context = JSON.parse(await readFile(path.join(contextDirectory, `${id}.json`), 'utf8')); } catch (e) { if (e.code !== 'ENOENT') throw e; }
          if (context && context.sourceRoot !== sourceRoot) throw new Error('SOURCE_ROOT_CHANGED');
        }
        const input = { action: id ? 'resume' : 'start', sourceRoot, ...(body.connection ? {connection:body.connection} : {}), ...(id ? { runId: id, answers: body.answers } : { request: body.request }) };
        await backend(input, async ticket => {
          validId(ticket.runId);
          await mkdir(contextDirectory, { recursive: true });
          await writeFile(path.join(contextDirectory, `${ticket.runId}.json`), JSON.stringify({ sourceRoot }), { flag: 'wx', encoding: 'utf8' });
          json(res, 202, ticket);
        });
      } finally { active--; }
    } else throw new Error('INVALID_REQUEST');
  } catch (error) {
    if (!res.headersSent) {
      const code = /^[A-Z][A-Z0-9_]{1,80}$/.test(error.message) ? error.message : 'WEB_BACKEND_FAILED';
      json(res, code === 'SERVER_BUSY' ? 429 : code === 'RUN_NOT_FOUND' ? 404 : ['STALE_ANSWERS','AGENT_NOT_RESUMABLE','SOURCE_ROOT_CHANGED'].includes(code) ? 409 : /STORAGE|BACKEND|CONTEXT|REPORT_TOO/.test(code) ? 503 : 400, { error: code });
    }
    // 已返回 202 的错误从 PG 的 FAILED/CANCELLED 或租约过期状态读取。
  }
  return true;
};
}
export const handleInvestigations = createInvestigationHandler();

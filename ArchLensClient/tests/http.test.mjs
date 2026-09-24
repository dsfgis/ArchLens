import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { mkdtemp, readFile, mkdir, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { createInvestigationHandler } from '../investigations.mjs';

const root=fileURLToPath(new URL('../../ArchLensService/examples/scenarios/mysql-postgresql/',import.meta.url));
const request=JSON.parse(await readFile(path.join(root,'agent-clarify.json'),'utf8'));
const id='10000000-0000-4000-8000-000000000001';
async function setup(t,backend) {
  const contextDirectory=await mkdtemp(fileURLToPath(new URL('../../ArchLensService/target/web-http-',import.meta.url)));
  const handler=createInvestigationHandler({backend,contextDirectory});
  const server=http.createServer((req,res)=>handler(req,res,(r,status,body)=>{r.writeHead(status,{'Content-Type':'application/json'});r.end(JSON.stringify(body));}));
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));t.after(()=>{server.closeAllConnections();server.close();});
  const base=`http://127.0.0.1:${server.address().port}`;
  const post=(route,body,headers={})=>fetch(base+route,{method:'POST',headers:{Origin:base,'Content-Type':'application/json',...headers},body:JSON.stringify(body)});
  return {base,post,contextDirectory};
}
test('cross-origin writes and malformed identifiers are rejected before backend invocation',async t=>{
  let calls=0;const {base,post}=await setup(t,async()=>{calls++;});
  assert.equal((await post('/api/investigations',{}, {Origin:'https://untrusted.example'})).status,403);
  assert.equal((await fetch(base+'/api/investigations?caseId=invalid')).status,400);
  assert.equal((await fetch(base+'/api/investigations?offset=-1')).status,400);
  assert.equal((await fetch(base+'/api/investigations',{headers:{Origin:'https://untrusted.example'}})).status,403);
  assert.equal(calls,0);
});
test('asynchronous ticket persists root and changed resume root is rejected',async t=>{
  let complete;const done=new Promise(resolve=>{complete=resolve;});t.after(()=>complete());
  const {base,post,contextDirectory}=await setup(t,async(input,onStarted)=>{
    if(input.action==='get')return {runId:id,caseId:id,state:'RUNNING'};
    await onStarted({runId:id,caseId:id,revision:1});await done;return {status:'PARTIAL'};
  });
  const response=await post('/api/investigations',{sourceRoot:root,request});assert.equal(response.status,202);
  assert.equal((await response.json()).runId,id);
  assert.ok(JSON.parse(await readFile(path.join(contextDirectory,id+'.json'),'utf8')).sourceRoot);
  assert.ok((await (await fetch(base+'/api/investigations/'+id)).json()).sourceRoot);
  assert.equal((await post(`/api/investigations/${id}/resume`,{sourceRoot:path.dirname(root.replace(/[\\/]$/,'')),answers:{}})).status,409);
});
test('extra credentials, unsupported column adapter and invalid roots never reach Java',async t=>{
  let calls=0;const {post}=await setup(t,async()=>{calls++;});
  assert.equal((await post('/api/investigations',{sourceRoot:root,request,password:'synthetic'})).status,400);
  assert.equal((await post('/api/investigations',{sourceRoot:'relative',request})).status,400);
  assert.equal((await post('/api/investigations',{sourceRoot:root,request:{...request,columnRequest:'request.json'}})).status,400);
  assert.equal(calls,0);
});
test('storage errors and stale answers preserve explicit failure status',async t=>{
  const {base,post}=await setup(t,async input=>{throw new Error(input.action==='resume'?'STALE_ANSWERS':'STORAGE_UNAVAILABLE');});
  assert.equal((await fetch(base+'/api/investigations')).status,503);
  const stale=await post(`/api/investigations/${id}/resume`,{sourceRoot:root,answers:{}});assert.equal(stale.status,409);assert.equal((await stale.json()).error,'STALE_ANSWERS');
});
test('two active analyses bound concurrency before reading another request',async t=>{
  let n=0,finish;const done=new Promise(resolve=>{finish=resolve;});t.after(()=>finish());
  const {post}=await setup(t,async(input,onStarted)=>{const runId=`10000000-0000-4000-8000-00000000000${++n}`;await onStarted({runId,caseId:runId,revision:1});await done;return {};});
  assert.equal((await post('/api/investigations',{sourceRoot:root,request})).status,202);
  assert.equal((await post('/api/investigations',{sourceRoot:root,request})).status,202);
  assert.equal((await post('/api/investigations',{sourceRoot:root,request})).status,429);
});
test('report download returns immutable report body as attachment without source-root context',async t=>{
  const report={schemaVersion:'archlens.agent.v1',status:'PARTIAL',questions:[]};
  const {base}=await setup(t,async()=>({report,sourceRoot:'not-for-export',state:'PARTIAL'}));
  const response=await fetch(`${base}/api/investigations/${id}/report`);
  assert.equal(response.status,200);assert.match(response.headers.get('content-disposition'),/^attachment;/);
  assert.deepEqual(await response.json(),report);
});
test('example is an actual local synthetic source without storage or model dependency',async t=>{
  const {base}=await setup(t,async()=>{throw new Error('must not invoke backend');});
  const response=await fetch(base+'/api/investigations/example');assert.equal(response.status,200);
  const example=await response.json();assert.equal(example.request.target.sourceProfile.version,null);
  assert.match(await readFile(path.join(example.sourceRoot,example.request.files[0]),'utf8'),/CREATE TABLE/);
  assert.equal(Object.keys(example).sort().join(','),'request,sourceRoot');
});

// 项目发现用实际目录和内容，后端调用计数保证它不执行 Java 或项目构建。
test('C# discovery excludes build outputs and exposes a bounded explicit manifest',async t=>{
  let calls=0;const {base,post,contextDirectory}=await setup(t,async()=>{calls++;});
  await mkdir(path.join(contextDirectory,'bin'));await mkdir(path.join(contextDirectory,'src'));
  await writeFile(path.join(contextDirectory,'app.csproj'),'<Project/>');
  await writeFile(path.join(contextDirectory,'src','支付.cs'),'class Payment {}');
  await writeFile(path.join(contextDirectory,'bin','generated.cs'),'ignored');
  await writeFile(path.join(contextDirectory,'secret.json'),'not collected');
  const response=await post('/api/investigations/discover-csharp',{sourceRoot:contextDirectory});assert.equal(response.status,200);
  const result=await response.json();assert.deepEqual(result.files,['app.csproj','src/支付.cs']);assert.ok(result.excluded.includes('bin'));assert.equal(calls,0);
  assert.equal((await post('/api/investigations/discover-csharp',{sourceRoot:contextDirectory},{Origin:'http://elsewhere'})).status,403);
  const example=await (await fetch(base+'/api/investigations/example?scenario=csharp-java')).json();
  assert.equal(example.request.target.sourceProfile.product,'C#');for(const f of example.request.files)assert.ok((await readFile(path.join(example.sourceRoot,f))).length);
  assert.equal((await fetch(base+'/api/investigations/example?scenario=../../')).status,400);
});
test('C# discovery rejects absent projects and oversized trees without returning a truncated manifest',async t=>{
  const {post,contextDirectory}=await setup(t,async()=>{throw Error('must not run');});
  assert.equal((await (await post('/api/investigations/discover-csharp',{sourceRoot:contextDirectory})).json()).error,'CSHARP_PROJECT_REQUIRED');
  assert.equal((await post('/api/investigations/discover-csharp',{sourceRoot:'relative'})).status,400);
  await writeFile(path.join(contextDirectory,'app.csproj'),'<Project/>');
  for(let n=0;n<1000;n++)await writeFile(path.join(contextDirectory,`${n}.cs`),'');
  const r=await post('/api/investigations/discover-csharp',{sourceRoot:contextDirectory});assert.equal(r.status,400);assert.equal((await r.json()).error,'PROJECT_DISCOVERY_LIMIT');
});

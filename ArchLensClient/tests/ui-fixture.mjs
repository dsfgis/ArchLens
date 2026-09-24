// 仅供浏览器回归：使用已归档报告模拟存储传输，不能作为真实 PG/模型验收。
import http from 'node:http';
import {readFile,mkdtemp} from 'node:fs/promises';
import {randomUUID} from 'node:crypto';
import {fileURLToPath} from 'node:url';
import {createInvestigationHandler} from '../investigations.mjs';
const archived=new URL('../../ArchLensService/docs/verification/agent-20260918/',import.meta.url);
const first=JSON.parse(await readFile(new URL('pg-clarification.json',archived),'utf8'));
const second=JSON.parse(await readFile(new URL('pg-resumed.json',archived),'utf8'));
const runs=new Map();
const contextDirectory=await mkdtemp(fileURLToPath(new URL('../../ArchLensService/target/web-ui-fixture-',import.meta.url)));
const handler=createInvestigationHandler({contextDirectory,backend:async(input,onStarted)=>{
  if(input.action==='list')return {runs:[...runs.values()].filter(r=>!input.caseId||r.caseId===input.caseId).reverse().slice(input.offset,input.offset+25).map(r=>({...r,report:undefined,reportStatus:r.report?.status,objective:r.report?.request.objective||'网页回归合成调查',createdAt:'UI fixture',leaseExpired:false}))};
  if(input.action==='get'){const r=runs.get(input.runId);if(!r)throw new Error('RUN_NOT_FOUND');return structuredClone(r);}
  if(input.action==='cancel'){const r=runs.get(input.runId);r.state='CANCELLED';return {cancelled:true};}
  const previous=input.action==='resume'?runs.get(input.runId):null;
  if(previous&&!previous.latestRevision)throw new Error('STALE_ANSWERS');
  if(previous&&input.answers.parentReportHash!==previous.reportHash)throw new Error('STALE_ANSWERS');
  const runId=randomUUID(),caseId=previous?.caseId||randomUUID();
  const run={runId,caseId,revision:previous?2:1,latestRevision:true,state:'RUNNING',report:null,reportHash:null,checkpoint:null,projectionState:'NOT_READY'};
  if(previous)previous.latestRevision=false;runs.set(runId,run);await onStarted({runId,caseId,revision:run.revision});
  await new Promise(resolve=>setTimeout(resolve,1800));
  if(run.state==='CANCELLED')return {};
  run.report=structuredClone(previous?second:first);run.reportHash=previous?'b'.repeat(64):second.parentReportHash;run.state='PARTIAL';run.projectionState='NOT_APPLICABLE';return {};
}});
const files=new Set(['index.html','styles.css','workbench.css','workbench.js','draft.html','app.js']);
http.createServer(async(req,res)=>{
  const json=(r,status,body)=>{r.writeHead(status,{'Content-Type':'application/json; charset=utf-8'});r.end(JSON.stringify(body));};
  if(await handler(req,res,json))return;
  const name=req.url==='/'?'index.html':req.url.slice(1);if(!files.has(name)){res.writeHead(404);res.end();return;}
  let content=await readFile(new URL('../'+name,import.meta.url),'utf8');
  if(name==='index.html')content=content.replace('调查工作台</span>','UI 回归夹具 · 非真实存储</span>');
  res.writeHead(200,{'Content-Type':name.endsWith('.js')?'text/javascript; charset=utf-8':name.endsWith('.css')?'text/css; charset=utf-8':'text/html; charset=utf-8'});res.end(content);
}).listen(4184,'127.0.0.1',()=>console.log('UI fixture only: http://127.0.0.1:4184'));

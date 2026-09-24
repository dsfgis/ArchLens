// 只验证指定的真实网页调查：报告附件、PG 历史、旧回答拒绝与封存内容一致性。
// 不创建调查；输入必须是已经通过网页完成的合成示例 Run ID。
import assert from 'node:assert/strict';
import {mkdir,readFile,writeFile} from 'node:fs/promises';
import path from 'node:path';
const [base,firstId,secondId,output]=process.argv.slice(2);
assert.ok(/^http:\/\/(127\.0\.0\.1|localhost):\d+$/.test(base));
assert.ok(firstId&&secondId&&output,'Usage: node tests/verify-live-csharp.mjs <base> <firstId> <secondId> <new-output-dir>');
async function get(url){const r=await fetch(base+url);assert.equal(r.status,200);return r.json();}
const first=await get(`/api/investigations/${firstId}`),second=await get(`/api/investigations/${secondId}`);
assert.equal(first.caseId,second.caseId);assert.equal(first.revision,1);assert.equal(second.revision,2);
assert.equal(first.latestRevision,false);assert.equal(second.latestRevision,true);
assert.equal(first.report.status,'NEEDS_CLARIFICATION');assert.equal(second.report.status,'PARTIAL');
assert.equal(second.report.parentReportHash,first.reportHash);
assert.equal(second.report.orchestration,'MODEL_TOOL_LOOP');assert.ok(second.report.explanations.length>0);
assert.ok(second.report.investigation.sources.length>0);assert.ok(second.report.investigation.findings.length>0);
const download=await fetch(`${base}/api/investigations/${secondId}/report`);
assert.equal(download.status,200);assert.match(download.headers.get('content-disposition'),/^attachment;/);
const downloaded=await download.json();assert.deepEqual(downloaded,second.report);
assert.equal(first.report.questions.length,1);assert.equal(first.report.questions[0].field,'sourceProfile.version');
const answers={schemaVersion:'archlens.agent.v1',parentReportHash:first.reportHash,answers:{[first.report.questions[0].questionId]:'12'}};
const stale=await fetch(`${base}/api/investigations/${firstId}/resume`,{method:'POST',headers:{Origin:base,'Content-Type':'application/json'},body:JSON.stringify({sourceRoot:first.sourceRoot,answers})});
assert.equal(stale.status,409);assert.equal((await stale.json()).error,'STALE_ANSWERS');
const history=await get(`/api/investigations?caseId=${first.caseId}`);
assert.equal(history.runs.length,2);assert.deepEqual(history.runs.map(r=>r.revision),[2,1]);
assert.equal((await get(`/api/investigations/${firstId}`)).reportHash,first.reportHash);
// 此脚本只接受合成示例，核对报告证据确实指向实际文件内容。
assert.equal(second.report.interpretedTarget.sourceProfile.product,'C#');
assert.equal(second.report.interpretedTarget.sourceProfile.version,'12');
const {fileURLToPath}=await import('node:url');
assert.equal(path.resolve(second.sourceRoot),fileURLToPath(new URL('../../ArchLensService/examples/scenarios/csharp-java',import.meta.url)));
const {createHash}=await import('node:crypto');
const sourceHashes={};
for(const source of second.report.investigation.sources){
  assert.ok(second.report.request.files.includes(source.path));
  sourceHashes[source.path]=createHash('sha256').update(await readFile(path.join(second.sourceRoot,source.path))).digest('hex');
  assert.equal(source.sha256,sourceHashes[source.path]);
}
assert.equal(Object.keys(sourceHashes).length,4);
const ruleIds=new Set(second.report.investigation.findings.map(f=>f.rule?.ruleId));
for(const rule of ['CS_DECIMAL','CS_UNSIGNED','CS_AWAIT','CS_SERIALIZATION','CS_PROJECT_PROFILE','CS_PROJECT_DEPENDENCY','CS_PROJECT_REFERENCE'])assert.ok(ruleIds.has(rule),rule);
await mkdir(output,{recursive:false});
for(const [name,value] of Object.entries({first,second,history,downloaded}))await writeFile(path.join(output,`${name}.json`),JSON.stringify(value,null,2)+'\n',{flag:'wx'});
const evidence={verifiedAt:new Date().toISOString(),caseId:first.caseId,firstRunId:firstId,secondRunId:secondId,firstReportHash:first.reportHash,secondReportHash:second.reportHash,sourceHashes,orchestration:second.report.orchestration,modelCalls:second.report.modelCalls,findings:second.report.investigation.findings.length,explanations:second.report.explanations.length,downloadMatches:true,staleAnswerRejected:true,historyRevisions:[2,1]};
await writeFile(path.join(output,'evidence.json'),JSON.stringify(evidence,null,2)+'\n',{flag:'wx'});console.log(JSON.stringify(evidence,null,2));

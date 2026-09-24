// 合成样例的真实取消烟测；只创建 ArchLens 自身运行，不执行 SQL 或修改样例。
import assert from 'node:assert/strict';
import {writeFile} from 'node:fs/promises';
const [base,output]=process.argv.slice(2);
assert.ok(/^http:\/\/(127\.0\.0\.1|localhost):\d+$/.test(base));assert.ok(output);
const example=await (await fetch(base+'/api/investigations/example')).json();
const post=(url,body)=>fetch(base+url,{method:'POST',headers:{Origin:base,'Content-Type':'application/json'},body:JSON.stringify(body)});
const created=await post('/api/investigations',example);assert.equal(created.status,202);const ticket=await created.json();
const response=await post(`/api/investigations/${ticket.runId}/cancel`,{});assert.equal(response.status,200);assert.equal((await response.json()).cancelled,true);
const read=async()=>{const r=await fetch(`${base}/api/investigations/${ticket.runId}`);assert.equal(r.status,200);return r.json();};
assert.equal((await read()).state,'CANCELLED');
// 给当前模型调用时间返回，验证迟到结果不会重新封存为成功。
await new Promise(resolve=>setTimeout(resolve,10000));
const final=await read();assert.equal(final.state,'CANCELLED');assert.equal(final.report,null);
const evidence={verifiedAt:new Date().toISOString(),caseId:ticket.caseId,runId:ticket.runId,state:final.state,lateReportRejected:true};
await writeFile(output,JSON.stringify(evidence,null,2)+'\n',{flag:'wx'});console.log(JSON.stringify(evidence,null,2));

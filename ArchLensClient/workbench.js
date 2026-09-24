const $ = id => document.getElementById(id);
const lines = id => $(id).value.split(/\r?\n/).map(s => s.trim()).filter(Boolean);
const labels = { RUNNING:'运行中', NEEDS_CLARIFICATION:'待补充信息', PARTIAL:'已生成部分结果', FAILED:'运行失败', CANCELLED:'已取消', COMPLETED:'已完成' };
const errors = { PROJECT_DISCOVERY_LIMIT:'项目候选清单超过 1000 文件、50 MB、10000 目录项或扫描时间/深度上限。请选择更小的模块目录，未返回截断清单。', CSHARP_PROJECT_REQUIRED:'目录内未发现 .csproj，请选择 C# 项目目录。', PROJECT_SOURCE_CHANGED:'发现过程中来源发生变化，请重新发现。', STORAGE_CONFIG:'后端尚未配置调查存储，请使用 start.ps1 启动。', STORAGE_UNAVAILABLE:'调查存储暂不可用，请检查服务后刷新。', SOURCE_ROOT_INVALID:'授权根目录不存在或不可读。', SOURCE_ROOT_CHANGED:'恢复调查必须沿用原授权根目录；更换目录请新建调查。', STALE_ANSWERS:'这份回答已过期。请刷新并打开最新修订。', AGENT_NOT_RESUMABLE:'该修订已不能继续回答，请查看最新修订。', INVALID_ANSWERS:'请回答本轮全部问题，且不要改写原先已声明的技术字段。', SERVER_BUSY:'服务正在处理其他请求，请稍后重试。', BACKEND_UNAVAILABLE:'无法启动后端，请检查 JDK 21 并重新构建。', COLUMN_WEB_UNSUPPORTED:'网页暂不支持旧列分析适配，请使用原 CLI。', PATH_OUTSIDE_ROOT:'文件必须是授权目录内的相对路径。', INVALID_REQUEST:'输入格式不正确，请检查必填项。', REQUEST_TIMEOUT:'请求超时，提交结果尚不确定。请先刷新历史记录确认，避免重复提交。' };
let selectedId = null, selected = null, filterCase = null, offset = 0, generation = 0, listGeneration = 0, detailSequence = 0, polling = false, submitting = false, timer;
function el(tag, text, className) { const n = document.createElement(tag); if (text != null) n.textContent = text; if(className)n.className=className; return n; }
function notice(message, error=false) { $('notice').textContent=message; $('notice').className=error?'notice error':'notice'; $('notice').hidden=false; }
function fail(e) { notice(errors[e.message] || `操作失败：${e.message}。输入已保留，可刷新后重试。`, true); }
async function api(url, body) {
  const controller=new AbortController(), timeout=setTimeout(()=>controller.abort(),35000);
  try { const response=await fetch(url,{...(body===undefined?{}:{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)}),signal:controller.signal});
    const result=await response.json();if(!response.ok)throw new Error(result.error||'REQUEST_FAILED');return result;
  } catch(e) { if(e.name==='AbortError')throw new Error('REQUEST_TIMEOUT');throw e; } finally {clearTimeout(timeout);}
}
function status(run) { return run.leaseExpired?'运行中断（租约已过期）':labels[run.reportStatus||run.report?.status||run.state]||run.state; }
async function loadHistory() {
  const seq=++listGeneration;
  const result=await api(`/api/investigations?offset=${offset}${filterCase?`&caseId=${filterCase}`:''}`);
  if(seq!==listGeneration)return;
  $('history').replaceChildren();$('history-label').textContent=filterCase?'当前调查的历史修订':'最近的 Agent 调查';$('all-runs').hidden=!filterCase;
  $('history-status').textContent=result.runs.length?`第 ${offset+1}–${offset+result.runs.length} 条`:'暂无记录';
  for(const run of result.runs) {
    const button=el('button',null,`run-row${selectedId===run.runId?' selected':''}`);button.type='button';
    button.append(el('strong',run.objective),el('small',`修订 ${run.revision} · ${run.latestRevision?'最新':'历史'} · ${status(run)} · ${run.createdAt}`));
    if(run.errorCode)button.append(el('small',`错误：${run.errorCode}`));
    button.addEventListener('click',()=>openRun(run.runId).catch(fail));$('history').append(button);
  }
  $('prev-page').disabled=offset===0;$('next-page').disabled=result.runs.length<25;
  const current=result.runs.find(r=>r.runId===selectedId);
  if(current?.leaseExpired) {polling=false;$('run-status').textContent=status(current);}
}
async function openRun(id, background=false) {
  if(!background){selectedId=id;generation++;$('answers-form').hidden=true;$('answer-submit').disabled=true;$('export-report').hidden=true;$('cancel').hidden=true;$('case-history').hidden=true;$('run-status').textContent='正在读取调查…';}
  const seq=generation, detail=++detailSequence;
  const data=await api(`/api/investigations/${id}`);
  if(seq!==generation||detail!==detailSequence||id!==selectedId)return;
  // 报告未变化时保留正在编辑的回答；过期修订仍及时撤销回答入口。
  const preserve=background&&selected?.runId===id&&selected?.reportHash===data.reportHash&&selected?.latestRevision===data.latestRevision&&selected?.state===data.state&&selected?.projectionState===data.projectionState&&JSON.stringify(selected?.checkpoint)===JSON.stringify(data.checkpoint);
  selected=data;polling=data.state==='RUNNING';
  if(!preserve)renderRun(data);else $('run-status').textContent=status(data);
  if(!background){window.history.replaceState(null,'',`#run=${id}`);await loadHistory();}
  schedule();
}
function listBlock(parent,title,items) { if(!items?.length)return; const block=el('section',null,'report-block');block.append(el('h3',title));const list=el('ul');for(const item of items)list.append(el('li',typeof item==='string'?item:JSON.stringify(item)));block.append(list);parent.append(block); }
function raw(parent,title,data) {const d=el('details');d.append(el('summary',title),el('pre',JSON.stringify(data,null,2)));parent.append(d);}
function renderRun(run) {
  $('run-heading').textContent=`调查详情 · 修订 ${run.revision}`;$('run-status').textContent=status(run);
  $('run-meta').replaceChildren(el('p',`Case ${run.caseId}\nRun ${run.runId}`,'meta'),el('p',`${run.latestRevision?'当前最新修订':'历史修订：不作为当前结论'} · 图投影 ${run.projectionState}`,'meta'));
  $('case-history').hidden=false;$('cancel').hidden=run.state!=='RUNNING';$('export-report').hidden=!run.report&&!run.checkpoint;
  $('answers-form').hidden=true;$('questions').replaceChildren();$('report').replaceChildren();
  if(!run.report) { $('report').append(el('p',run.state==='RUNNING'?'后端正在采集或调查，页面将自动刷新。':'当前运行没有封存报告。','empty'));if(run.checkpoint)raw($('report'),'已保留的来源检查点',run.checkpoint);return; }
  const r=run.report;
  if (/^(调查已提交|回答已提交)/.test($('notice').textContent)) notice('报告已更新，可查看证据或回答澄清问题。');
  $('report').append(el('p',r.request?.objective),el('p',`${r.orchestration} · ${r.modelCalls} 次模型调用`,'meta'));
  if(r.orchestration==='DETERMINISTIC_FALLBACK')$('report').append(el('p','模型未完成闭环，当前显示确定性分析或澄清结果。','report-warning'));
  if(r.status==='NEEDS_CLARIFICATION'&&run.latestRevision&&r.revision<8) {
    $('answers-form').hidden=false;$('answer-submit').disabled=false;$('resume-root').value=run.sourceRoot||'';$('resume-root').readOnly=Boolean(run.sourceRoot);
    for(const q of r.questions) {const wrap=el('div',null,'question'),label=el('label',q.prompt),input=el('input');input.id=`answer-${q.questionId}`;input.dataset.question=q.questionId;input.required=true;input.maxLength=500;label.htmlFor=input.id;wrap.append(label,input,el('p',q.field,'meta'));if(q.field==='sourceProfile.version'&&['c#','csharp'].includes((r.interpretedTarget?.sourceProfile?.product||r.request?.target?.sourceProfile?.product||'').toLowerCase()))wrap.append(el('p','此处填写 C# 语言版本（例如 12），不是 .NET 版本（例如 net8.0）。当前规则矩阵为 C# 12 → Java 21。'));$('questions').append(wrap);}
  } else if(r.status==='NEEDS_CLARIFICATION')$('report').append(el('p','该修订已过期或达到澄清上限，请查看最新修订或新建调查。','report-warning'));
  listBlock($('report'),'诊断',r.diagnostics);
  const inv=r.investigation;
  if(inv) {
    $('report').append(el('p',`${inv.sources.length} 个来源 · ${inv.findings.length} 条发现 · ${inv.coverageGaps.length} 项覆盖缺口`,'meta'));
    listBlock($('report'),'未覆盖与待核实范围',inv.coverageGaps.map(g=>`${g.code}${g.source?` · ${g.source}`:''}`));
    const sourceMap=new Map(inv.sources.map(s=>[s.sourceId,s]));
    if(inv.findings.length)$('report').append(el('h3','证据与发现'));
    for(const f of inv.findings) {
      const card=el('article',null,'finding');card.append(el('h4',f.summary||f.ruleRef||f.findingId),el('p',f.outcome,`outcome ${f.outcome}`));
      if(f.rule) {card.append(el('p',`${f.rule.ruleId} · ${f.rule.version} · ${f.rule.sourceRange} → ${f.rule.targetRange}`,'meta'));
        for(const reference of f.rule.officialSources||[]) {try {const url=new URL(reference);if(url.protocol!=='https:')continue;const a=el('a',url.hostname,'reference');a.href=url.href;a.target='_blank';a.rel='noopener noreferrer';card.append(a,document.createTextNode(' '));}catch{}}
      }
      listBlock(card,'条件',f.conditions);listBlock(card,'未知项',f.unknownReasons);listBlock(card,'建议',f.recommendations);
      for(const evidence of f.evidence||[]) {const src=sourceMap.get(evidence.sourceId),loc=evidence.location;raw(card,`${evidence.kind==='CS_PROJECT_XML_DECLARATION'?'依据文件（整个 XML 声明）':'原文位置'}：${src?.path||evidence.sourceId}${loc?.startLine?` · 第 ${loc.startLine} 行，第 ${loc.startColumn} 列`:''}`,{...evidence,sourcePath:src?.path});}
      if(f.impacts?.length)raw(card,'影响项与确定性',f.impacts);$('report').append(card);
    }
    if(inv.columnAnalysis)raw($('report'),'列分析与影响路径',inv.columnAnalysis);
    raw($('report'),'来源清单与 SHA-256',inv.sources);listBlock($('report'),'验证建议',inv.verificationSuggestions);
  }
  if(r.explanations?.length){$('report').append(el('h3','模型解释（尚未独立核实）'));for(const x of r.explanations){const c=el('article',null,'finding');c.append(el('p',x.explanation),el('p',`${x.findingId} · ${x.outcome}`,'meta'));raw(c,'引用证据',x.evidenceIds);listBlock(c,'建议验证',x.verificationSuggestions);$('report').append(c);}}
  raw($('report'),'工具调用审计',r.trace);raw($('report'),'完整报告',r);
}
function schedule(){clearTimeout(timer);timer=setTimeout(async()=>{if(!selectedId||submitting){schedule();return;}try{await openRun(selectedId,true);await loadHistory();}catch(e){fail(e);}schedule();},polling?3000:15000);}
function profile(prefix){const product=$(`${prefix}-product`).value.trim();return product?{product,version:$(`${prefix}-version`).value.trim()||null}:null;}
$('investigation-form').addEventListener('submit',async event=>{
  event.preventDefault();if(submitting)return;submitting=true;$('submit').disabled=true;
  try {const scenario=$('scenario').value;const target=scenario?{scenario,sourceProfile:profile('source'),targetProfile:profile('target')}:null;
    const request={schemaVersion:'archlens.agent.v1',objective:$('objective').value.trim(),target,constraints:lines('constraints'),invariants:lines('invariants'),files:lines('source-files'),columnRequest:null,collectionBudget:{maxFiles:1000,maxBytes:50000000,timeoutMillis:30000},agentBudget:{maxModelCalls:16,maxToolCalls:24,timeoutMillis:120000}};
    const ticket=await api('/api/investigations',{sourceRoot:$('source-root').value.trim(),request});filterCase=ticket.caseId;offset=0;notice('调查已提交。运行状态和结果会自动更新。');await openRun(ticket.runId);$('run-panel').scrollIntoView({block:'start'});
  }catch(e){fail(e);}finally{submitting=false;$('submit').disabled=false;}
});
$('answers-form').addEventListener('submit',async event=>{
  event.preventDefault();if(submitting||!selected?.reportHash)return;submitting=true;$('answer-submit').disabled=true;const parent=selected;
  try{const answers={schemaVersion:'archlens.agent.v1',parentReportHash:parent.reportHash,answers:Object.fromEntries([...$('questions').querySelectorAll('input')].map(n=>[n.dataset.question,n.value.trim()]))};
    const ticket=await api(`/api/investigations/${parent.runId}/resume`,{sourceRoot:$('resume-root').value.trim(),answers});notice('回答已提交，新修订正在调查。');await openRun(ticket.runId);
  }catch(e){fail(e);if(e.message==='STALE_ANSWERS')await openRun(parent.runId).catch(fail);}finally{submitting=false;$('answer-submit').disabled=false;}
});
$('cancel').addEventListener('click',async()=>{if(!selectedId)return;$('cancel').disabled=true;try{await api(`/api/investigations/${selectedId}/cancel`,{});await openRun(selectedId);}catch(e){fail(e);}finally{$('cancel').disabled=false;}});
$('refresh').addEventListener('click',()=>Promise.all([loadHistory(),selectedId?openRun(selectedId,true):Promise.resolve()]).catch(fail));
$('case-history').addEventListener('click',()=>{filterCase=selected.caseId;offset=0;loadHistory().catch(fail);});
$('all-runs').addEventListener('click',()=>{filterCase=null;offset=0;loadHistory().catch(fail);});
$('prev-page').addEventListener('click',()=>{offset=Math.max(0,offset-25);loadHistory().catch(fail);});
$('next-page').addEventListener('click',()=>{offset+=25;loadHistory().catch(fail);});
$('export-report').addEventListener('click',()=>{if(!selected)return;const a=el('a');a.href=`/api/investigations/${selected.runId}/report`;a.download=`archlens-${selected.runId}.json`;a.click();});
function updateVersionHints(){
  const csharp=['c#','csharp'].includes($('source-product').value.trim().toLowerCase());
  $('source-version').placeholder=csharp?'12（C# 语言版本）':'例如 8.0.36';
  $('target-version').placeholder=$('target-product').value.trim().toLowerCase()==='java'?'21':'例如 16';
}
$('source-product').addEventListener('input',updateVersionHints);$('target-product').addEventListener('input',updateVersionHints);
async function fillExample(scenario, button) {
  button.disabled=true;
  try {
    const example=await api(`/api/investigations/example?scenario=${scenario}`),r=example.request;
    $('source-root').value=example.sourceRoot;$('source-files').value=r.files.join('\n');$('objective').value=r.objective;$('scenario').value=r.target.scenario;
    $('source-product').value=r.target.sourceProfile.product;$('source-version').value=r.target.sourceProfile.version||'';$('target-product').value=r.target.targetProfile.product;$('target-version').value=r.target.targetProfile.version||'';
    $('constraints').value=r.constraints.join('\n');$('invariants').value=r.invariants.join('\n');$('discovery-summary').replaceChildren();
    notice(scenario==='csharp-java'?'已填入 C# 12 / .NET 8 合成项目。可先发现项目文件；提交后对 C# 语言版本问题回答 12。':'已填入 MySQL 合成样例。提交后对源版本问题回答 8.0.36。');updateVersionHints();$('submit').focus();
  } catch(e) {fail(e);} finally {button.disabled=false;}
}
$('example').addEventListener('click',()=>fillExample('mysql-postgresql',$('example')));
$('csharp-example').addEventListener('click',()=>fillExample('csharp-java',$('csharp-example')));
$('discover-csharp').addEventListener('click',async()=>{
  $('discover-csharp').disabled=true;
  try {
    const root=$('source-root').value.trim(), d=await api('/api/investigations/discover-csharp',{sourceRoot:root});
    if($('source-root').value.trim()!==root)return;
    $('source-root').value=d.sourceRoot;$('source-files').value=d.files.join('\n');
    $('scenario').value='LANGUAGE_MIGRATION';$('source-product').value='C#';$('source-version').value='';$('target-product').value='Java';$('target-version').value='21';
    if(!$('objective').value.trim())$('objective').value='调查 C# 项目迁移到 Java 21 的源码特征、框架及依赖，列出证据和待验证行为。';
    $('discovery-summary').replaceChildren(el('p',`发现 ${d.files.length} 个候选文件，${d.bytes} 字节。检查清单后提交；C# 语言版本未知时留空。`));
    listBlock($('discovery-summary'),'发现范围说明',d.warnings);raw($('discovery-summary'),'已排除目录和生成文件',d.excluded);
    updateVersionHints();notice('文件清单已更新；C# 语言版本与 .NET 版本不同，请按项目实际情况填写或等待澄清。');
  } catch(e) {fail(e);} finally {$('discover-csharp').disabled=false;}
});
loadHistory().catch(fail);
const initial=/^#run=([0-9a-f-]{36})$/.exec(location.hash);if(initial)openRun(initial[1]).catch(fail);else schedule();

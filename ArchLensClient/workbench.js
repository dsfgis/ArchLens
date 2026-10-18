import { assessMigration } from './migration-assessment.mjs';

const $ = id => document.getElementById(id);
const lines = id => $(id).value.split(/\r?\n/).map(s => s.trim()).filter(Boolean);
const labels = { RUNNING:'运行中', NEEDS_CLARIFICATION:'待补充信息', PARTIAL:'已生成部分结果', FAILED:'运行失败', CANCELLED:'已取消', COMPLETED:'已完成' };
const errors = { DATASOURCE_CREDENTIALS_REQUIRED:"请填写本次业务数据库连接参数。", DATASOURCE_CONFIG_INVALID:"数据库连接或目标环境填写不完整，请检查。", DATASOURCE_IDENTITY_CHANGED:"连接与原调查的主机、端口、数据库、账号或加密设置不一致，请使用原连接或新建调查。", DATASOURCE_SCOPE_INVALID:"请填写单个数据库名称，不支持通配符或连接 URL。", TARGET_CONTEXT_CONFLICT:"数据库场景中的源/目标技术与业务数据库或目标环境不一致。", DOTNET_PROJECT_REQUIRED:'目录内未发现 .NET 项目或解决方案，请选择包含 .sln/.slnx 或 .csproj/.vbproj/.fsproj 的目录。', PROJECT_DISCOVERY_LIMIT:'项目候选清单超过 1000 文件、50 MB、10000 目录项或扫描时间/深度上限。请选择更小的模块目录，未返回截断清单。', CSHARP_PROJECT_REQUIRED:'目录内未发现 .csproj，请选择 C# 项目目录。', PROJECT_SOURCE_CHANGED:'发现过程中来源发生变化，请重新发现。', STORAGE_CONFIG:'无法开始分析：后端尚未完整配置 ArchLens 自身的 PostgreSQL / Neo4j 存储。请完成后端存储配置并重启服务；当前可使用本地现状预览。', STORAGE_UNAVAILABLE:'调查存储暂不可用，请检查服务后刷新。', SOURCE_ROOT_INVALID:'代码库路径必须是本机存在且可读取的项目文件夹，不能填写单个源码、.sln 或 .csproj 文件。', SOURCE_ROOT_CHANGED:'恢复调查必须沿用原授权根目录；更换目录请新建调查。', STALE_ANSWERS:'这份回答已过期。请刷新并打开最新修订。', AGENT_NOT_RESUMABLE:'该修订已不能继续回答，请查看最新修订。', INVALID_ANSWERS:'请回答本轮全部问题，且不要改写原先已声明的技术字段。', SERVER_BUSY:'服务正在处理其他请求，请稍后重试。', BACKEND_UNAVAILABLE:'无法启动后端，请检查 JDK 21 并重新构建。', COLUMN_WEB_UNSUPPORTED:'网页暂不支持旧列分析适配，请使用原 CLI。', PATH_OUTSIDE_ROOT:'文件必须是授权目录内的相对路径。', INVALID_REQUEST:'输入格式不正确，请检查必填项。', SOURCE_FILES_REQUIRED:'目录内没有可采集的源码或项目声明；仅分析数据库时请选择对应来源。', REQUEST_TIMEOUT:'请求超时，提交结果尚不确定。请先刷新历史记录确认，避免重复提交。' };
let selectedId = null, selected = null, filterCase = null, offset = 0, generation = 0, listGeneration = 0, detailSequence = 0, polling = false, submitting = false, timer;
function el(tag, text, className) { const n = document.createElement(tag); if (text != null) n.textContent = text; if(className)n.className=className; return n; }
function notice(message, error=false) { $('notice').textContent=message; $('notice').className=error?'notice error':'notice'; $('notice').hidden=false; }
function fail(e) { notice(errors[e.message] || `操作失败：${e.message}。输入已保留，可刷新后重试。`, true); }
function fallbackMessage(diagnostics=[]) {
  const reasons=[];
  if(diagnostics.includes('TARGET_CONFLICT'))reasons.push('模型提出的目标与本次已保存的回答冲突');
  if(diagnostics.includes('AGENT_INVALID_ACTION_LIMIT'))reasons.push('连续无效动作达到上限');
  return `模型调查未完成${reasons.length?`：${reasons.join('，')}`:''}。页面保留静态盘点或澄清问题，未形成迁移结论。`;
}
function hasLegacyJavaAssessment(report) {
  const findings=report?.investigation?.findings||[];
  return !/\b(?:java|jdk)\b/i.test(report?.request?.objective||'')
    &&findings.some(f=>f.ruleRef?.startsWith('dotnet-current-state.')
      &&[f.summary,...(f.unknownReasons||[]),...(f.recommendations||[])].some(s=>/\bJava\b/.test(s)));
}
function submissionStatus(message, error=false) {
  const status=$('submit-status');status.textContent=message;status.className=error?'notice error':'notice';status.hidden=false;
}
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
  if(!background){selectedId=id;generation++;$('answers-form').hidden=true;$('answer-submit').disabled=true;$('export-report').hidden=true;$('export-markdown').hidden=true;$('cancel').hidden=true;$('case-history').hidden=true;$('run-status').textContent='正在读取调查…';}
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
function raw(parent,title,data) {const d=el('details'),pre=el('pre');d.append(el('summary',title),pre);
  d.addEventListener('toggle',()=>{if(d.open&&!pre.dataset.loaded){pre.textContent=JSON.stringify(data,null,2);pre.dataset.loaded='true';}});parent.append(d);}
function scopeLabel(report){const code=report.sources?.length>0,db=Boolean(report.databaseInventory);return code&&db?'代码＋数据库':db?'仅数据库':'仅代码';}
function renderRun(run) {
  $('run-heading').textContent=`调查详情 · 修订 ${run.revision}`;$('run-status').textContent=status(run);
  $('run-meta').replaceChildren(el('p',`Case ${run.caseId}\nRun ${run.runId}`,'meta'),el('p',`${run.latestRevision?'当前最新修订':'历史修订：不作为当前结论'} · 图投影 ${run.projectionState}`,'meta'));
  $('case-history').hidden=false;$('cancel').hidden=run.state!=='RUNNING';$('export-report').hidden=!run.report&&!run.checkpoint;$('export-markdown').hidden=!run.report;
  $('answers-form').hidden=true;$('questions').replaceChildren();$('report').replaceChildren();
  if(!run.report) { $('report').append(el('p',run.state==='RUNNING'?'后端正在采集或调查，页面将自动刷新。':'当前运行没有封存报告。','empty'));if(run.checkpoint)raw($('report'),'已保留的来源检查点',run.checkpoint);return; }
  const r=run.report;
  if (/^(调查已提交|回答已提交)/.test($('notice').textContent)) notice('报告已更新，可查看证据或回答澄清问题。');
  renderRepositoryInventory($('report'),r.repositoryInventory);
  $('report').append(el('p',r.request?.objective),el('p',`${r.orchestration} · ${r.modelCalls} 次模型调用`,'meta'));
  if(r.orchestration==='DETERMINISTIC_FALLBACK')$('report').append(el('p',fallbackMessage(r.diagnostics),'report-warning'));
  if(hasLegacyJavaAssessment(r))$('report').append(el('p','这份历史报告的 .NET 现状盘点含有旧版固定的 Java 文案；它不代表本次迁移目标。报告原文保留，新分析将使用修正文案。','report-warning'));
  if(r.status==='NEEDS_CLARIFICATION'&&run.latestRevision&&r.revision<8) {
    $('answers-form').hidden=false;$('answer-submit').disabled=false;$('resume-root').value=run.sourceRoot||'';$('resume-root').readOnly=Boolean(run.sourceRoot);$('resume-root').required=!(run.report.request.businessContext?.source&&run.report.request.files.length===0);
    if(run.report.request.businessContext?.source)setAnalysisMode(run.report.request.files.length?'joint':'database');
    for(const q of r.questions) {const wrap=el('div',null,'question'),label=el('label',q.prompt),input=el('input');input.id=`answer-${q.questionId}`;input.dataset.question=q.questionId;input.required=true;input.maxLength=500;label.htmlFor=input.id;wrap.append(label,input,el('p',q.field,'meta'));$('questions').append(wrap);}
  } else if(r.status==='NEEDS_CLARIFICATION')$('report').append(el('p','该修订已过期或达到澄清上限，请查看最新修订或新建调查。','report-warning'));
  listBlock($('report'),'诊断',r.diagnostics);
  if(r.dotnetSemantics){
    const s=r.dotnetSemantics, block=el('section',null,'report-block');
    const authorized=r.request?.files||[],csharp=authorized.filter(f=>/\.cs$/i.test(f)).length,sourceCount=new Set(s.facts.map(f=>f.path)).size;
    const count=binding=>s.facts.filter(f=>f.binding===binding).length;
    block.append(el('h3','源码分析覆盖情况'),el('p',`本次查看 ${authorized.length} 个文件，其中 ${csharp} 个 C# 文件、${sourceCount} 个产生了语义事实。`),
      el('p',`已定位到源码中的调用或对象创建 ${count('SOURCE_BOUND')} 处；另有 ${count('UNRESOLVED')} 处尚无法可靠定位。未定位不等于代码有错。`),
      el('p','类型和方法“已声明”只说明源码里有定义；单条记录用于核查证据，不是迁移建议。','meta'));
    const gapNames={PROJECT_REFERENCES_NOT_RESOLVED:'工程间引用尚未绑定',PROJECT_COMPILE_ITEMS_NOT_EVALUATED:'实际编译文件清单待核对',CONDITIONAL_SYMBOLS_NOT_EVALUATED:'条件编译分支待核对',PROJECT_LANGUAGE_VERSION_NOT_EVALUATED:'C# 语言版本待核对',HOST_REFERENCES_NOT_TARGET_FRAMEWORK:'部分外部引用不是目标框架的验证结果',GENERATED_SOURCES_NOT_INCLUDED:'构建生成的源码未纳入',RUNTIME_BEHAVIOR_NOT_VERIFIED:'程序实际行为未验证',COMPILATION_ERRORS_OR_MISSING_REFERENCES:'部分编译引用或诊断待排查'};
    listBlock(block,'仍需核实的范围',s.gaps.map(g=>gapNames[g]||g));
    const byProject=new Map();for(const fact of s.facts){const key=fact.project||'未归属工程';if(!byProject.has(key))byProject.set(key,[]);byProject.get(key).push(fact);}
    const advanced=el('details');advanced.append(el('summary',`原始语义证据（供技术核查，${s.facts.length} 条）`));
    advanced.append(el('p','DECLARED＝源码声明；SOURCE_BOUND＝绑定到已采集源码；HOST_REFERENCE_BOUND＝只绑定到取证宿主引用；UNRESOLVED＝尚未解析。','meta'));
    listBlock(advanced,'按工程分布',[...byProject].map(([project,facts])=>`${project}：${facts.length} 条`));
    const samples=[];for(let row=0;samples.length<24;row++){let found=false;for(const facts of byProject.values())if(row<facts.length){samples.push(facts[row]);found=true;if(samples.length===24)break;}if(!found)break;}
    for(const fact of samples){
      const card=el('article',null,'finding');card.append(el('p',`${fact.kind} · ${fact.binding}`),el('p',`${fact.path}:${fact.line}:${fact.column} · ${fact.symbol}${fact.target?' → '+fact.target:''}`,'meta'));
      raw(card,'证据编号及来源哈希',{id:fact.id,sha256:fact.sha256});advanced.append(card);
    }
    if(s.facts.length>24)advanced.append(el('p',`此处只展示 24 条样本；完整 ${s.facts.length} 条可下载原始证据 JSON。`,'meta'));
    block.append(advanced);
    $('report').append(block);
  }
  if(r.migrationPlan){
    const p=r.migrationPlan,block=el('section',null,'report-block');
    block.append(el('h3','迁移方案草案（未经验证）'),el('p',p.targetArchitecture),el('p',p.status,'report-warning'));
    for(const step of p.steps){const card=el('article',null,'finding');card.append(el('h4',step.phase),el('p',step.action),el('p',`验证：${step.validation}`));raw(card,'引用的本地语义事实',step.factIds);block.append(card);}
    listBlock(block,'待确认问题',p.openQuestions);$('report').append(block);
  }
  const assessment=assessMigration(r);
  if(assessment){
    const block=el('section',null,'report-block');
    block.append(el('h3','风险分析'),el('p',assessment.evidenceNote,'meta'));
    for(const risk of assessment.risks){
      const card=el('article',null,'finding');
      card.append(el('h4',`${risk.title} · ${risk.level}优先级`),
        el('p',`依据：${risk.evidence}`),el('p',`可能影响：${risk.impact}`),el('p',`处理与验证：${risk.response}`));
      block.append(card);
    }
    listBlock(block,'主要难点',assessment.difficulties);
    block.append(el('h3','成功率评估'),el('p',assessment.success,'report-warning'),el('p',assessment.method));
    listBlock(block,'可量化前需完成',assessment.gates);
    $('report').append(block);
  }
  const inv=r.investigation;
  if(inv) {
    if(inv.dotnetInventory)renderDotnetInventory($('report'),inv.dotnetInventory);
    renderBusinessInventory($('report'),inv);
    $('report').append(el('p',`分析范围：${scopeLabel(inv)} · ${inv.sources.length} 个代码来源 · ${inv.findings.length} 条发现 · ${inv.coverageGaps.length} 项覆盖缺口`,'meta'));
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
  if(r.subtasks?.length){
    $('report').append(el('h3','后台安排的子任务'));
    for(const [index,task] of r.subtasks.entries()){
      const card=el('article',null,'finding'),t=task.interpretedTarget||task.request.target;
      card.append(el('h4',`子任务 ${index+1} · ${t?.sourceProfile?.product||'现状分析'}${t?.targetProfile?' → '+t.targetProfile.product+' '+(t.targetProfile.version||''):''}`),el('p',`${labels[task.status]||task.status} · ${task.modelCalls} 次模型调用`,'meta'));
      for(const explanation of task.explanations||[])card.append(el('p',explanation.explanation));
      listBlock(card,'需要补充',task.questions?.map(q=>q.prompt));listBlock(card,'诊断',task.diagnostics);
      listBlock(card,'覆盖缺口',task.investigation?.coverageGaps?.map(g=>g.code));
      raw(card,'子任务证据、来源与工具审计',task);$('report').append(card);
    }
  }
  raw($('report'),'工具调用审计',r.trace);raw($('report'),'完整报告',r);
}
function schedule(){clearTimeout(timer);timer=setTimeout(async()=>{if(!selectedId||submitting){schedule();return;}try{await openRun(selectedId,true);await loadHistory();}catch(e){fail(e);}schedule();},polling?3000:15000);}
$('investigation-form').addEventListener('invalid',event=>{
  const label=event.target.labels?.[0]?.textContent||'必填项';
  submissionStatus(`请检查“${label}”：${event.target.validationMessage}`,true);
},true);
$('investigation-form').addEventListener('submit',async event=>{
  event.preventDefault();if(submitting)return;submitting=true;$('submit').disabled=true;$('submit').textContent='正在提交…';
  submissionStatus('正在检查输入并提交分析，请稍候…');
  try {const target=null;
    const request={schemaVersion:'archlens.agent.v1',objective:$('objective').value.trim(),target,constraints:lines('constraints'),invariants:lines('invariants'),files:scopedFiles(),columnRequest:null,collectionBudget:{maxFiles:1000,maxBytes:50000000,timeoutMillis:30000},agentBudget:{maxModelCalls:16,maxToolCalls:24,timeoutMillis:120000}};
    const business=readBusiness();if(business.businessContext){request.schemaVersion='archlens.agent.v2';request.businessContext=business.businessContext;}
    const ticket=await api('/api/investigations',{sourceRoot:scopedRoot(),request,connection:business.connection});filterCase=ticket.caseId;offset=0;notice('调查已提交。运行状态和结果会自动更新。');submissionStatus('分析已提交，正在读取运行状态…');await openRun(ticket.runId);submissionStatus('分析已提交，运行状态和结果会自动更新。');$('run-panel').scrollIntoView({block:'start'});
  }catch(e){fail(e);submissionStatus($('notice').textContent,true);}finally{submitting=false;$('submit').disabled=false;$('submit').textContent='开始分析';}
});
function optional(id){return $(id).value.trim()||null;}
function envProfile(product,version){if(!optional(product)&&optional(version))throw Error('DATASOURCE_CONFIG_INVALID');return optional(product)?{product:optional(product),version:optional(version)}:null;}
function analysisMode(){return $('analysis-mode').value;}
function scopedFiles(){return analysisMode()==='database'?[]:lines('source-files');}
function scopedRoot(){return analysisMode()==='database'?null:$('source-root').value.trim();}
function updateSourceRequirements(){const code=analysisMode()!=='database';$('source-root').required=code;$('source-files').required=false;
  $('source-root').disabled=!code;$('source-files').disabled=!code;
  $('discover-repository').hidden=!code;
  $('preview-repository').hidden=analysisMode()!=='code';$('preview-joint').hidden=analysisMode()==='code';
  $('mode-hint').textContent=code?(analysisMode()==='joint'?'自动发现代码文件，并采集指定数据库的可见结构。':'后台自动识别代码库的技术栈；无需选择迁移或升级场景。'):'仅采集指定业务数据库的可见结构，代码目录和文件清单无需填写。';}
function setAnalysisMode(mode){$('analysis-mode').value=mode;$('db-enabled').checked=mode!=='code';$('db-fields').disabled=mode==='code';if(mode!=='code')$('business-config').open=true;updateSourceRequirements();invalidatePreview();}
$('analysis-mode').addEventListener('change',()=>setAnalysisMode(analysisMode()));
$('source-files').addEventListener('input',()=>{updateSourceRequirements();invalidatePreview();});
function readConnection(force=false){return force||$('db-enabled').checked?{host:optional('db-host'),port:Number($('db-port').value),database:optional('db-name'),username:optional('db-user'),password:$('db-password').value,tlsMode:$('db-tls').value,product:$('db-product').value,service:['Oracle','KingbaseES'].includes($('db-product').value)?optional('db-service'):null}:null;}
function readBusiness(){
  const environment={database:envProfile('env-database','env-db-version'),compatibilityMode:optional('env-mode'),operatingSystem:envProfile('env-os','env-os-version'),architecture:optional('env-arch'),runtime:envProfile('env-runtime','env-runtime-version')};
  if(environment.compatibilityMode&&!environment.database)throw Error('DATASOURCE_CONFIG_INVALID');
  const targetEnvironment=Object.values(environment).some(v=>v!==null)?environment:null;
  const source=$('db-enabled').checked?{product:$('db-product').value,database:optional('db-name'),declaredVersion:optional('db-version'),connectionFingerprint:null}:null;
  const connection=readConnection();
  return {businessContext:source||targetEnvironment?{schemaVersion:'archlens.business-context.v1',source,targetEnvironment}:null,connection};
}
function jointInput(testOnly=false){
  const business=readBusiness();if(!business.businessContext?.source)throw Error('DATASOURCE_CONFIG_INVALID');
  return {sourceRoot:testOnly?null:scopedRoot(),connection:business.connection,request:{schemaVersion:'archlens.agent.v2',objective:optional('objective')||'代码与业务数据库结构现状调查',target:{scenario:'CURRENT_STATE',sourceProfile:{product:'UNSPECIFIED',version:null},targetProfile:null},constraints:lines('constraints'),invariants:lines('invariants'),files:testOnly?[]:scopedFiles(),columnRequest:null,collectionBudget:{maxFiles:1000,maxBytes:50000000,timeoutMillis:25000},agentBudget:{maxModelCalls:1,maxToolCalls:1,timeoutMillis:30000},businessContext:business.businessContext}};
}
function renderBusinessInventory(parent,report){
  if(report.targetEnvironment){raw(parent,'目标环境（用户声明，兼容性未评估）',report.targetEnvironment);}
  const db=report.databaseInventory;if(!db)return;
  const section=el('section',null,'report-block');section.append(el('h3','业务数据库结构'),el('p',`${db.product} ${db.observedVersion||'版本未读取'} · ${db.database} · ${db.status}`));
  section.append(el('p',`采集到 ${db.tables.length} 个表/视图，仅代表当前账号可见范围。未读取业务数据行；代码关联仅是静态候选线索。`,'report-warning'));
  if(db.tables.length===0)section.append(el('p','未采集到可展示的表；请检查连接状态、权限及覆盖缺口。'));
  for(const table of db.tables){const card=el('article',null,'finding');card.append(el('h4',table.name),el('p',`${table.kind} · ${table.columns.length} 个字段 · ${table.indexes.length} 个索引列 · ${table.keys.length} 个约束列`));
    listBlock(card,'字段',table.columns.map(c=>`${c.name} · ${c.columnType} · ${c.nullable?'允许空值':'非空'}${c.extra?' · '+c.extra:''}`));
    const details=db.extendedMetadata;
    if(details){listBlock(card,'默认值、注释与生成表达式标记',details.columns.filter(c=>c.table===table.name).map(c=>`${c.column} · ${c.defaultPresent?'有默认值':'无已知默认值'}${c.defaultExpression?' · 表达式默认值':''}${c.commentPresent?' · 有注释':''}${c.generatedExpressionPresent?' · 生成列表达式':''}`));
      const checks=details.checks.filter(c=>c.table===table.name),view=details.views.find(v=>v.name===table.name);
      if(checks.length)raw(card,'检查约束（不保存表达式）',checks);if(view)raw(card,'视图定义可见性（不保存正文）',view);}
    raw(card,'索引与主外键',{indexes:table.indexes,keys:table.keys});raw(card,'结构证据',{evidenceId:table.evidenceId,sourceFingerprint:db.sourceFingerprint,metadataHash:db.metadataHash});section.append(card);}
  if(report.codeDatabaseAssociation){
    const a=report.codeDatabaseAssociation;
    // 路径中的箭头只展示静态同容器线索；状态和未知项必须同时可见，避免误读成运行时调用图。
    listBlock(section,`代码与对象候选关联（${a.links.length} 条）`,a.links.map(x=>`${x.sourcePath}:${x.line} · ${x.operation} ${x.token} · ${x.state}${x.databaseObject?" → "+x.databaseObject:""}`));
    if(a.accessPaths?.length)listBlock(section,`静态访问路径（${a.accessPaths.length} 条）`,a.accessPaths.map(p=>
      `${p.sourcePath} · ${p.steps.map(s=>`${s.kind}:${s.name}${s.line?`:${s.line}`:''}`).join(' → ')} · ${p.state} · 未确认：${p.unknownReasons.join('、')}`));
    listBlock(section,"关联覆盖缺口",a.coverageGaps);raw(section,"关联证据",{links:a.links,accessPaths:a.accessPaths});
  }
  if(db.extendedMetadata?.programs.length)raw(section,'存储程序与触发器摘要（不保存正文）',db.extendedMetadata.programs);
  if(db.extendedMetadata?.parameters.length)raw(section,'存储程序参数类型',db.extendedMetadata.parameters);
  listBlock(section,'数据库覆盖缺口',db.coverageGaps);raw(section,'采集时间、配置与结构哈希',{startedAt:db.startedAt,finishedAt:db.finishedAt,settings:db.settings,metadataHash:db.metadataHash});parent.append(section);
}
let databaseTestGeneration=0;

function updateDatabaseProduct(){const product=$('db-product').value,mysql=product==='MySQL',service=product==='Oracle'||product==='KingbaseES';
  if(!service)$('db-service').value='';
  $('db-service').hidden=!service;$('db-service-label').hidden=!service;$('db-service').required=service;
  $('db-service-label').textContent=product==='Oracle'?'Oracle 服务名':'KingbaseES 数据库名';
  $('db-name-label').textContent=mysql?'数据库名（仅采集该库）':'对象模式名（仅采集该模式）';
  $('db-port').value=({MySQL:3306,Oracle:1521,KingbaseES:54321,DM:5236})[product];
  const tls=$('db-tls');for(const option of tls.options)option.hidden=mysql?option.value==='DRIVER_DEFAULT':option.value!=='DRIVER_DEFAULT';tls.value=mysql?'VERIFY_IDENTITY':'DRIVER_DEFAULT';
  databaseTestGeneration++;invalidatePreview();
}
$('db-product').addEventListener('change',updateDatabaseProduct);
$('db-enabled').addEventListener('change',()=>setAnalysisMode($('db-enabled').checked?($('source-root').value.trim()?'joint':'database'):'code'));
for(const id of ['business-config','environment-config'])$(id).addEventListener('input',()=>{databaseTestGeneration++;$('database-status').textContent='配置已改变，需重新测试连接或预览。';invalidatePreview();});
$('test-database').addEventListener('click',async()=>{
  const button=$('test-database'),seq=++databaseTestGeneration;button.disabled=true;$('database-status').textContent='正在验证连接、实际版本和所选范围…';
  try {const result=await api('/api/investigations/test-database',jointInput(true));if(seq!==databaseTestGeneration)return;const db=result.connectionTest;
    $('database-status').textContent=db.status==='CONNECTED'?`连接成功：${db.product} ${db.observedVersion}，${db.product==='MySQL'?'只读会话已确认':'客户端只读标志已设置，服务端只读与 TLS 状态未验证'}。仅验证所选范围可见性，未完成结构采集。${db.coverageGaps.includes('DB_DECLARED_VERSION_CONFLICT')?' 声明版本与实际版本不一致。':''}`:`连接未通过：${db.coverageGaps.join('、')}`;
  }catch(e){if(seq===databaseTestGeneration)$('database-status').textContent=errors[e.message]||`连接测试失败：${e.message}`;}finally{button.disabled=false;}
});
$('preview-joint').addEventListener('click',async()=>{
  const button=$('preview-joint'),seq=++previewGeneration;button.disabled=true;previewResult=null;$('export-preview').hidden=true;$('preview-panel').hidden=false;$('preview-report').replaceChildren();$('preview-status').textContent='正在盘点代码与只读采集业务库结构…';
  try {const input=jointInput(),key=JSON.stringify(input);const result=await api('/api/investigations/preview-joint',input);if(seq!==previewGeneration||key!==JSON.stringify(jointInput()))return;
    previewResult=result;$('preview-status').textContent=`${result.report.status} · 未保存 · ${scopeLabel(result.report)}现状报告`;
    renderRepositoryInventory($('preview-report'),result.repositoryInventory);
    renderCurrentStateAssessment($('preview-report'),result.report);
    if(result.report.dotnetInventory)renderDotnetInventory($('preview-report'),result.report.dotnetInventory);renderBusinessInventory($('preview-report'),result.report);
    listBlock($('preview-report'),'覆盖缺口',result.report.coverageGaps.map(g=>`${g.code} · ${g.source||''}`));raw($('preview-report'),'代码来源与 SHA-256',result.report.sources);$('export-preview').hidden=false;$('preview-panel').scrollIntoView({block:'start'});
  }catch(e){if(seq===previewGeneration)$('preview-status').textContent=errors[e.message]||`联合预览失败：${e.message}`;}finally{button.disabled=false;}
});
let previewResult=null,previewGeneration=0;
for(const id of ['objective','constraints','invariants'])$(id).addEventListener('input',invalidatePreview);
function previewKey(){return JSON.stringify({sourceRoot:$('source-root').value.trim(),files:lines('source-files')});}
function invalidatePreview(){
  previewGeneration++;previewResult=null;$('export-preview').hidden=true;
  if(!$('preview-panel').hidden)$('preview-status').textContent='输入已改变，请重新预览。上次展示结果不代表当前输入。';
}
$('source-root').addEventListener('input',()=>{$('source-files').value='';$('discovery-summary').replaceChildren();invalidatePreview();});$('source-files').addEventListener('input',invalidatePreview);
$('preview-repository').addEventListener('click',async()=>{
  const button=$('preview-repository'),key=previewKey(),seq=++previewGeneration;
  button.disabled=true;previewResult=null;$('export-preview').hidden=true;$('preview-panel').hidden=false;
  $('preview-report').replaceChildren();$('preview-status').textContent='正在读取授权清单并生成本地现状报告…';
  try {
    const result=await api('/api/investigations/preview-repository',JSON.parse(key));
    if(seq!==previewGeneration||key!==previewKey())return;
    previewResult=result;$('preview-status').textContent=`${result.report.status} · 未保存 · 仅分析清单中的静态声明`;
    renderRepositoryInventory($('preview-report'),result.repositoryInventory);
    renderCurrentStateAssessment($('preview-report'),result.report);
    if(result.report.dotnetInventory)renderDotnetInventory($('preview-report'),result.report.dotnetInventory);
    listBlock($('preview-report'),'覆盖缺口',result.report.coverageGaps.map(g=>`${g.code} · ${g.source||''}`));
    raw($('preview-report'),'来源清单与 SHA-256',result.report.sources);
    $('export-preview').hidden=false;$('preview-panel').scrollIntoView({block:'start'});
  }catch(e){if(seq===previewGeneration){$('preview-status').textContent=errors[e.message]||`预览失败：${e.message}`;}}
  finally{button.disabled=false;}
});
$('export-preview').addEventListener('click',()=>{
  if(!previewResult)return;
  const url=URL.createObjectURL(new Blob([JSON.stringify(previewResult,null,2)],{type:'application/json;charset=utf-8'}));
  const a=el('a');a.href=url;a.download=previewResult.mode==='LOCAL_JOINT_PREVIEW'?'archlens-joint-preview.json':previewResult.mode==='LOCAL_DATABASE_PREVIEW'?'archlens-database-preview.json':'archlens-repository-preview.json';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);
});
$('answers-form').addEventListener('submit',async event=>{
  event.preventDefault();if(submitting||!selected?.reportHash)return;submitting=true;$('answer-submit').disabled=true;const parent=selected;
  try{const answers={schemaVersion:'archlens.agent.v1',parentReportHash:parent.reportHash,answers:Object.fromEntries([...$('questions').querySelectorAll('input')].map(n=>[n.dataset.question,n.value.trim()]))};
    const ticket=await api(`/api/investigations/${parent.runId}/resume`,{sourceRoot:$('resume-root').value.trim(),answers,connection:parent.report.request.businessContext?.source?readConnection(true):null});notice('回答已提交，新修订正在调查。');await openRun(ticket.runId);
  }catch(e){fail(e);if(e.message==='STALE_ANSWERS')await openRun(parent.runId).catch(fail);}finally{submitting=false;$('answer-submit').disabled=false;}
});
$('cancel').addEventListener('click',async()=>{if(!selectedId)return;$('cancel').disabled=true;try{await api(`/api/investigations/${selectedId}/cancel`,{});await openRun(selectedId);}catch(e){fail(e);}finally{$('cancel').disabled=false;}});
$('refresh').addEventListener('click',()=>Promise.all([loadHistory(),selectedId?openRun(selectedId,true):Promise.resolve()]).catch(fail));
$('case-history').addEventListener('click',()=>{filterCase=selected.caseId;offset=0;loadHistory().catch(fail);});
$('discover-repository').addEventListener('click',async()=>{
  const button=$('discover-repository');button.disabled=true;
  const root=$('source-root').value.trim(), previousFiles=$('source-files').value;
  try {
    const d=await api('/api/investigations/discover-repository',{sourceRoot:root});
    if($('source-root').value.trim()!==root||$('source-files').value!==previousFiles)return;
    $('source-files').value=d.files.join('\n');invalidatePreview();
    $('discovery-summary').replaceChildren(el('p',`发现 ${d.files.length} 个文件，可按需要调整范围。`));
    listBlock($('discovery-summary'),'发现范围说明',d.warnings);raw($('discovery-summary'),'已排除目录和文件',d.excluded);
  }catch(e){fail(e);}finally{button.disabled=false;}
});
function renderRepositoryInventory(parent,inventory){
  if(!inventory)return;
  const kind={FILE_EXTENSION:'文件类型',MAVEN_DECLARATION:'Maven 声明',PROJECT_DECLARATION:'项目声明',LANGUAGE_DECLARATION:'语言版本声明',TARGET_FRAMEWORK_DECLARATION:'目标框架声明',PACKAGE_DECLARATION:'包声明',MODULE_DECLARATION:'模块声明',BUILD_DECLARATION:'构建声明'};
  const gaps={DECLARATIONS_NOT_RUNTIME_PROOF:'项目声明仅用于识别技术栈，不代表实际运行环境已验证。',SEMANTIC_COVERAGE_DEPENDS_ON_ANALYZER:'具体分析能力取决于已接入的解析器，未覆盖内容保留为未知。'};
  const summary=[...new Set(inventory.signals.filter(s=>s.kind!=='FILE_EXTENSION').map(s=>`${s.product}${s.version?' '+s.version:''}`))];
  if(summary.length)parent.append(el('p',`工程声明识别：${summary.slice(0,12).join('、')}。声明不代表实际运行环境已验证。`,'meta'));
  const detail=el('details');detail.append(el('summary',`技术声明原始清单（${inventory.signals.length} 条，供核查）`));
  listBlock(detail,'按文件列出的声明',inventory.signals.map(s=>`${s.product}${s.version?' '+s.version:'（版本未确定）'} · ${kind[s.kind]||s.kind} · ${s.path}`));
  listBlock(detail,'识别范围',inventory.gaps.map(g=>gaps[g]||g));parent.append(detail);
}
function renderDotnetInventory(parent,inventory){
  const block=el('details',null,'report-block'),c=inventory.coverage;
  block.append(el('summary',`.NET 工程配置明细（${c.parsedProjects}/${c.projectFiles} 个工程，供技术核查）`),el('p','以下是静态项目声明；条件、SDK 默认值、实际编译配置和业务行为尚未验证。','report-warning'));
  block.append(el('p',`已提交 ${c.submittedFiles} 个文件，已采集 ${c.collectedFiles} 个；项目声明已解析 ${c.parsedProjects}/${c.projectFiles}；解决方案已解析 ${c.parsedSolutions}/${c.solutionFiles}；源码 ${c.sourceCodeFiles} 个，语义绑定 ${c.semanticallyBoundFiles} 个。`,'meta'));
  const states={DECLARED:'直接声明',CONDITIONAL:'条件未求值',UNRESOLVED:'无法确定',COLLECTED_NOT_BOUND:'引用目标已采集，未绑定调用',UNCOLLECTED:'引用目标未采集',DECLARED_NOT_RESOLVED:'声明未解析为有效配置'};
  for(const p of inventory.projects){
    const card=el('article',null,'finding');card.append(el('h4',p.origin.path),el('p',`${p.language} · ${p.projectStyle==='SDK_STYLE_DECLARATION'?'SDK 风格声明':'旧式或未指定 SDK'} · 整文件证据`));
    listBlock(card,'目标框架',p.targetFrameworks.map(f=>`${f.declaredValue} → ${f.family}${f.version?' '+f.version:''}${f.platform?' / '+f.platform:''}（${states[f.state]||f.state}）`));
    listBlock(card,'语言与项目配置',p.declarations.map(d=>`${d.name}: ${d.value}（${states[d.state]||d.state}）`));
    listBlock(card,'应用类型线索（待核实）',p.applicationHints);
    listBlock(card,'依赖声明',p.dependencies.map(d=>`${d.kind} · ${d.name}${d.version?' '+d.version:''}（${states[d.state]||d.state}）`));
    listBlock(card,'项目引用',p.references.map(r=>`${r.declaredPath}（${states[r.state]||r.state}）`));
    listBlock(card,'项目缺口',p.gaps);raw(card,'来源与 SHA-256',p.origin);block.append(card);
  }
  for(const s of inventory.solutions)raw(block,`解决方案声明：${s.origin.path}`,s);
  for(const s of inventory.supportingFiles)raw(block,`辅助配置结构：${s.origin.path}`,s);
  parent.append(block);
}
function renderCurrentStateAssessment(parent,report){
  const findings=(report.findings||[]).filter(f=>f.ruleRef?.startsWith('dotnet-current-state.'));
  if(!findings.length)return;
  const block=el('section',null,'report-block');
  block.append(el('h3','现状分析（工程声明）'),el('p','以下结论由已采集工程文件确定性归纳，未验证源码行为或目标技术兼容性。','report-warning'));
  const sources=new Map((report.sources||[]).map(s=>[s.sourceId,s.path]));
  for(const finding of findings){
    const card=el('article',null,'finding');card.append(el('p',finding.summary));
    listBlock(card,'依据工程',finding.evidenceIds.map(id=>sources.get(id)||id));
    listBlock(card,'下一步核实',finding.recommendations);
    block.append(card);
  }
  parent.append(block);
}
$('all-runs').addEventListener('click',()=>{filterCase=null;offset=0;loadHistory().catch(fail);});
$('prev-page').addEventListener('click',()=>{offset=Math.max(0,offset-25);loadHistory().catch(fail);});
$('next-page').addEventListener('click',()=>{offset+=25;loadHistory().catch(fail);});
$('export-report').addEventListener('click',()=>{if(!selected)return;const a=el('a');a.href=`/api/investigations/${selected.runId}/report`;a.download=`archlens-${selected.runId}.json`;a.click();});
$('export-markdown').addEventListener('click',()=>{if(!selected?.report)return;const a=el('a');a.href=`/api/investigations/${selected.runId}/report.md`;a.download=`archlens-${selected.runId}.md`;a.click();});
api('/api/investigations/model-config').then(config=>{
  $('model-status').textContent=config.configured?`分析模型：${config.provider} / ${config.model}（后端配置）`:'尚未配置分析模型。请在后端设置 ARCHLENS_LLM_ENDPOINT、ARCHLENS_LLM_MODEL 和密钥；未配置时只能生成本地现状。';
}).catch(()=>{$('model-status').textContent='无法读取模型配置，请检查后端配置和构建状态。';});
updateSourceRequirements();loadHistory().catch(fail);
const initial=/^#run=([0-9a-f-]{36})$/.exec(location.hash);if(initial)openRun(initial[1]).catch(fail);else schedule();

const $ = id => document.getElementById(id);
const lines = id => $(id).value.split(/\r?\n/).map(s => s.trim()).filter(Boolean);
const labels = { RUNNING:'运行中', NEEDS_CLARIFICATION:'待补充信息', PARTIAL:'已生成部分结果', FAILED:'运行失败', CANCELLED:'已取消', COMPLETED:'已完成' };
const errors = { DATASOURCE_CREDENTIALS_REQUIRED:"请填写本次业务数据库连接参数。", DATASOURCE_CONFIG_INVALID:"数据库连接或目标环境填写不完整，请检查。", DATASOURCE_IDENTITY_CHANGED:"连接与原调查的主机、端口、数据库、账号或加密设置不一致，请使用原连接或新建调查。", DATASOURCE_SCOPE_INVALID:"请填写单个数据库名称，不支持通配符或连接 URL。", TARGET_CONTEXT_CONFLICT:"数据库场景中的源/目标技术与业务数据库或目标环境不一致。", DOTNET_PROJECT_REQUIRED:'目录内未发现 .NET 项目或解决方案，请选择包含 .sln/.slnx 或 .csproj/.vbproj/.fsproj 的目录。', PROJECT_DISCOVERY_LIMIT:'项目候选清单超过 1000 文件、50 MB、10000 目录项或扫描时间/深度上限。请选择更小的模块目录，未返回截断清单。', CSHARP_PROJECT_REQUIRED:'目录内未发现 .csproj，请选择 C# 项目目录。', PROJECT_SOURCE_CHANGED:'发现过程中来源发生变化，请重新发现。', STORAGE_CONFIG:'后端尚未配置调查存储，请使用 start.ps1 启动。', STORAGE_UNAVAILABLE:'调查存储暂不可用，请检查服务后刷新。', SOURCE_ROOT_INVALID:'授权根目录不存在或不可读。', SOURCE_ROOT_CHANGED:'恢复调查必须沿用原授权根目录；更换目录请新建调查。', STALE_ANSWERS:'这份回答已过期。请刷新并打开最新修订。', AGENT_NOT_RESUMABLE:'该修订已不能继续回答，请查看最新修订。', INVALID_ANSWERS:'请回答本轮全部问题，且不要改写原先已声明的技术字段。', SERVER_BUSY:'服务正在处理其他请求，请稍后重试。', BACKEND_UNAVAILABLE:'无法启动后端，请检查 JDK 21 并重新构建。', COLUMN_WEB_UNSUPPORTED:'网页暂不支持旧列分析适配，请使用原 CLI。', PATH_OUTSIDE_ROOT:'文件必须是授权目录内的相对路径。', INVALID_REQUEST:'输入格式不正确，请检查必填项。', SOURCE_FILES_REQUIRED:'联合调查需要至少一个代码文件；若只分析数据库，请切换到“仅数据库”。', REQUEST_TIMEOUT:'请求超时，提交结果尚不确定。请先刷新历史记录确认，避免重复提交。' };
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
function scopeLabel(report){const code=report.sources?.length>0,db=Boolean(report.databaseInventory);return code&&db?'代码＋数据库':db?'仅数据库':'仅代码';}
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
    $('answers-form').hidden=false;$('answer-submit').disabled=false;$('resume-root').value=run.sourceRoot||'';$('resume-root').readOnly=Boolean(run.sourceRoot);$('resume-root').required=!(run.report.request.businessContext?.source&&run.report.request.files.length===0);
    if(run.report.request.businessContext?.source)setAnalysisMode(run.report.request.files.length?'joint':'database');
    for(const q of r.questions) {const wrap=el('div',null,'question'),label=el('label',q.prompt),input=el('input');input.id=`answer-${q.questionId}`;input.dataset.question=q.questionId;input.required=true;input.maxLength=500;label.htmlFor=input.id;wrap.append(label,input,el('p',q.field,'meta'));if(q.field==='sourceProfile.version'&&['c#','csharp'].includes((r.interpretedTarget?.sourceProfile?.product||r.request?.target?.sourceProfile?.product||'').toLowerCase()))wrap.append(el('p','此处填写 C# 语言版本（例如 12），不是 .NET 版本（例如 net8.0）。当前规则矩阵为 C# 12 → Java 21。'));$('questions').append(wrap);}
  } else if(r.status==='NEEDS_CLARIFICATION')$('report').append(el('p','该修订已过期或达到澄清上限，请查看最新修订或新建调查。','report-warning'));
  listBlock($('report'),'诊断',r.diagnostics);
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
  raw($('report'),'工具调用审计',r.trace);raw($('report'),'完整报告',r);
}
function schedule(){clearTimeout(timer);timer=setTimeout(async()=>{if(!selectedId||submitting){schedule();return;}try{await openRun(selectedId,true);await loadHistory();}catch(e){fail(e);}schedule();},polling?3000:15000);}
function profile(prefix){const product=$(`${prefix}-product`).value.trim();return product?{product,version:$(`${prefix}-version`).value.trim()||null}:null;}
$('investigation-form').addEventListener('submit',async event=>{
  event.preventDefault();if(submitting)return;submitting=true;$('submit').disabled=true;
  try {const scenario=$('scenario').value;const source=profile('source'),destination=profile('target');const target=scenario||source||destination?{scenario:scenario||null,sourceProfile:source,targetProfile:destination}:null;
    const request={schemaVersion:'archlens.agent.v1',objective:$('objective').value.trim(),target,constraints:lines('constraints'),invariants:lines('invariants'),files:scopedFiles(),columnRequest:null,collectionBudget:{maxFiles:1000,maxBytes:50000000,timeoutMillis:30000},agentBudget:{maxModelCalls:16,maxToolCalls:24,timeoutMillis:120000}};
    const business=readBusiness();if(business.businessContext){request.schemaVersion='archlens.agent.v2';request.businessContext=business.businessContext;}
    const ticket=await api('/api/investigations',{sourceRoot:scopedRoot(),request,connection:business.connection});filterCase=ticket.caseId;offset=0;notice('调查已提交。运行状态和结果会自动更新。');await openRun(ticket.runId);$('run-panel').scrollIntoView({block:'start'});
  }catch(e){fail(e);}finally{submitting=false;$('submit').disabled=false;}
});
function optional(id){return $(id).value.trim()||null;}
function envProfile(product,version){if(!optional(product)&&optional(version))throw Error('DATASOURCE_CONFIG_INVALID');return optional(product)?{product:optional(product),version:optional(version)}:null;}
function analysisMode(){return $('analysis-mode').value;}
function scopedFiles(){return analysisMode()==='database'?[]:lines('source-files');}
function scopedRoot(){return analysisMode()==='database'?null:$('source-root').value.trim();}
function updateSourceRequirements(){const code=analysisMode()!=='database';$('source-root').required=code;$('source-files').required=code;
  $('source-root').disabled=!code;$('source-files').disabled=!code;
  $('discover-dotnet').hidden=!code;$('discover-csharp').hidden=!code;
  $('preview-dotnet').hidden=analysisMode()!=='code';$('preview-joint').hidden=analysisMode()==='code';
  $('mode-hint').textContent=code?(analysisMode()==='joint'?'读取明确列出的代码文件，并采集指定业务数据库的可见结构。':'只读取授权目录中明确列出的代码文件。'):'仅采集指定业务数据库的可见结构，代码目录和文件清单无需填写。';}
function setAnalysisMode(mode){$('analysis-mode').value=mode;$('db-enabled').checked=mode!=='code';$('db-fields').disabled=mode==='code';updateSourceRequirements();invalidatePreview();}
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
  if(!testOnly&&analysisMode()==='joint'&&scopedFiles().length===0)throw Error('SOURCE_FILES_REQUIRED');
  const business=readBusiness();if(!business.businessContext?.source)throw Error('DATASOURCE_CONFIG_INVALID');
  return {sourceRoot:testOnly?null:scopedRoot(),connection:business.connection,request:{schemaVersion:'archlens.agent.v2',objective:optional('objective')||'代码与业务数据库结构现状调查',target:{scenario:'CURRENT_STATE',sourceProfile:{product:'.NET',version:null},targetProfile:null},constraints:lines('constraints'),invariants:lines('invariants'),files:testOnly?[]:scopedFiles(),columnRequest:null,collectionBudget:{maxFiles:1000,maxBytes:50000000,timeoutMillis:25000},agentBudget:{maxModelCalls:1,maxToolCalls:1,timeoutMillis:30000},businessContext:business.businessContext}};
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
$('db-enabled').addEventListener('change',()=>setAnalysisMode($('db-enabled').checked?(lines('source-files').length?'joint':'database'):'code'));
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
$('source-root').addEventListener('input',invalidatePreview);$('source-files').addEventListener('input',invalidatePreview);
$('preview-dotnet').addEventListener('click',async()=>{
  const button=$('preview-dotnet'),key=previewKey(),seq=++previewGeneration;
  button.disabled=true;previewResult=null;$('export-preview').hidden=true;$('preview-panel').hidden=false;
  $('preview-report').replaceChildren();$('preview-status').textContent='正在读取授权清单并生成本地现状报告…';
  try {
    const result=await api('/api/investigations/preview-dotnet',JSON.parse(key));
    if(seq!==previewGeneration||key!==previewKey())return;
    previewResult=result;$('preview-status').textContent=`${result.report.status} · 未保存 · 仅分析清单中的静态声明`;
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
  const a=el('a');a.href=url;a.download=previewResult.mode==='LOCAL_JOINT_PREVIEW'?'archlens-joint-preview.json':previewResult.mode==='LOCAL_DATABASE_PREVIEW'?'archlens-database-preview.json':'archlens-dotnet-preview.json';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);
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
// 新平台入口保留已填写的改造目的与目标；候选清单由用户审阅后提交。
$('discover-dotnet').addEventListener('click',async()=>{
  const button=$('discover-dotnet');button.disabled=true;
  const root=$('source-root').value.trim(), previousFiles=$('source-files').value;
  try {
    const d=await api('/api/investigations/discover-dotnet',{sourceRoot:root});
    if($('source-root').value.trim()!==root||$('source-files').value!==previousFiles)return;
    setAnalysisMode('code');$('source-root').value=d.sourceRoot;$('source-files').value=d.files.join('\n');
    if(!$('source-product').value.trim()){
      $('source-product').value='.NET';$('source-version').value='';
      $('scenario').value=$('objective').value.trim()?'':'CURRENT_STATE';
    }
    if(!$('objective').value.trim())$('objective').value='分析 .NET 解决方案的平台、目标框架、项目依赖及应用类型，列出来源证据与未覆盖范围。';
    $('discovery-summary').replaceChildren(el('p',`发现 ${d.projects.length} 个项目、${d.solutions.length} 个解决方案，候选文件 ${d.files.length} 个。检查清单后提交。`));
    listBlock($('discovery-summary'),'发现范围说明',d.warnings);raw($('discovery-summary'),'已排除目录和生成文件',d.excluded);
    updateVersionHints();notice('.NET 候选清单已生成；不同项目的平台和语言版本会分别识别。');
  }catch(e){fail(e);}finally{button.disabled=false;}
});
function renderDotnetInventory(parent,inventory){
  const block=el('section',null,'report-block'),c=inventory.coverage;
  block.append(el('h3','.NET 平台现状'),el('p','以下是静态项目声明；条件、SDK 默认值、实际编译配置和业务行为尚未验证。','report-warning'));
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
$('all-runs').addEventListener('click',()=>{filterCase=null;offset=0;loadHistory().catch(fail);});
$('prev-page').addEventListener('click',()=>{offset=Math.max(0,offset-25);loadHistory().catch(fail);});
$('next-page').addEventListener('click',()=>{offset+=25;loadHistory().catch(fail);});
$('export-report').addEventListener('click',()=>{if(!selected)return;const a=el('a');a.href=`/api/investigations/${selected.runId}/report`;a.download=`archlens-${selected.runId}.json`;a.click();});
function updateVersionHints(){
  const csharp=['c#','csharp'].includes($('source-product').value.trim().toLowerCase());
  $('source-version').placeholder=csharp?'12（C# 语言版本）':['.net','dotnet','.net framework','.net core'].includes($('source-product').value.trim().toLowerCase())?'可留空，逐项目识别目标框架':'例如 8.0.36';
  $('target-version').placeholder=$('target-product').value.trim().toLowerCase()==='java'?'21':'例如 16';
}
$('source-product').addEventListener('input',updateVersionHints);$('target-product').addEventListener('input',updateVersionHints);
async function fillExample(scenario, button) {
  button.disabled=true;
  try {
    const example=await api(`/api/investigations/example?scenario=${scenario}`),r=example.request;
    setAnalysisMode('code');$('source-root').value=example.sourceRoot;$('source-files').value=r.files.join('\n');$('objective').value=r.objective;$('scenario').value=r.target.scenario;
    $('source-product').value=r.target.sourceProfile.product;$('source-version').value=r.target.sourceProfile.version||'';$('target-product').value=r.target.targetProfile?.product||'';$('target-version').value=r.target.targetProfile?.version||'';
    $('constraints').value=r.constraints.join('\n');$('invariants').value=r.invariants.join('\n');$('discovery-summary').replaceChildren();
    notice(scenario==='dotnet-platform'?'已填入混合 .NET 合成解决方案，包含 Framework、Core、现代 .NET 和共享类库。平台声明可分析，完整迁移语义仍待实现。':scenario==='csharp-java'?'已填入 C# 12 / .NET 8 合成项目。可先发现项目文件；提交后对 C# 语言版本问题回答 12。':'已填入 MySQL 合成样例。提交后对源版本问题回答 8.0.36。');updateVersionHints();$('submit').focus();
  } catch(e) {fail(e);} finally {button.disabled=false;}
}
$('example').addEventListener('click',()=>fillExample('mysql-postgresql',$('example')));
$('dotnet-example').addEventListener('click',()=>fillExample('dotnet-platform',$('dotnet-example')));
$('csharp-example').addEventListener('click',()=>fillExample('csharp-java',$('csharp-example')));
$('discover-csharp').addEventListener('click',async()=>{
  $('discover-csharp').disabled=true;
  try {
    const root=$('source-root').value.trim(), d=await api('/api/investigations/discover-csharp',{sourceRoot:root});
    if($('source-root').value.trim()!==root)return;
    setAnalysisMode('code');$('source-root').value=d.sourceRoot;$('source-files').value=d.files.join('\n');
    $('scenario').value='LANGUAGE_MIGRATION';$('source-product').value='C#';$('source-version').value='';$('target-product').value='Java';$('target-version').value='21';
    if(!$('objective').value.trim())$('objective').value='调查 C# 项目迁移到 Java 21 的源码特征、框架及依赖，列出证据和待验证行为。';
    $('discovery-summary').replaceChildren(el('p',`发现 ${d.files.length} 个候选文件，${d.bytes} 字节。检查清单后提交；C# 语言版本未知时留空。`));
    listBlock($('discovery-summary'),'发现范围说明',d.warnings);raw($('discovery-summary'),'已排除目录和生成文件',d.excluded);
    updateVersionHints();notice('文件清单已更新；C# 语言版本与 .NET 版本不同，请按项目实际情况填写或等待澄清。');
  } catch(e) {fail(e);} finally {$('discover-csharp').disabled=false;}
});
updateSourceRequirements();loadHistory().catch(fail);
const initial=/^#run=([0-9a-f-]{36})$/.exec(location.hash);if(initial)openRun(initial[1]).catch(fail);else schedule();

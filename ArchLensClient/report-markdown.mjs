// Reader-facing projection of a sealed report. Raw fact IDs and hashes remain in JSON.
import { assessMigration } from './migration-assessment.mjs';
const gapText={
  PROJECT_REFERENCES_NOT_RESOLVED:'部分工程引用尚未绑定，跨工程调用可能缺失。',
  PROJECT_COMPILE_ITEMS_NOT_EVALUATED:'尚未执行工程的实际编译项求值，条件包含或排除的源码待核对。',
  CONDITIONAL_SYMBOLS_NOT_EVALUATED:'条件编译符号未求值，部分 #if 分支可能未覆盖。',
  PROJECT_LANGUAGE_VERSION_NOT_EVALUATED:'C# 实际语言版本尚未确认。',
  HOST_REFERENCES_NOT_TARGET_FRAMEWORK:'部分引用只绑定到取证工具的宿主程序集，不能视为目标框架兼容性证明。',
  GENERATED_SOURCES_NOT_INCLUDED:'构建时生成的源码尚未纳入。',
  COMPILATION_ERRORS_OR_MISSING_REFERENCES:'当前分析缺少部分编译引用，或源码在此配置下存在编译诊断。',
  RUNTIME_BEHAVIOR_NOT_VERIFIED:'未运行原程序，实际业务行为与功能等价性尚未验证。',
  FACT_LIMIT_REACHED:'事实达到采集上限，后续内容未覆盖。'
};
const statusText={PARTIAL:'已生成部分结果',COMPLETED:'已完成',NEEDS_CLARIFICATION:'待补充信息',FAILED:'运行失败',CANCELLED:'已取消'};
function safe(value){return String(value??'').replace(/[\r\n\t]+/g,' ').replace(/\\/g,'\\\\').replace(/([`*_\[\]<>|])/g,'\\$1').trim();}
function line(value){return safe(value)||'未提供';}
function prose(value){
  const terms={HOST_REFERENCE_BOUND:'仅绑定到取证环境的外部引用',SOURCE_BOUND:'已定位到已采集源码',
    UNRESOLVED:'尚未解析',TYPE_DECLARATION:'类型声明',METHOD_DECLARATION:'方法声明',
    PROPERTY_DECLARATION:'属性声明',CONSTRUCTOR_DECLARATION:'构造函数声明',
    OBJECT_CREATION:'对象创建',INVOCATION:'调用',DECLARED:'源码已有声明',
    PROJECT_REFERENCES_NOT_RESOLVED:'工程引用尚未解析',COMPILATION_ERRORS_OR_MISSING_REFERENCES:'编译引用或诊断尚未排清',
    GENERATED_SOURCES_NOT_INCLUDED:'生成源码未纳入',RUNTIME_BEHAVIOR_NOT_VERIFIED:'运行行为未验证',
    PROJECT_LANGUAGE_VERSION_NOT_EVALUATED:'C# 语言版本待核实',DECLARATIONS_NOT_RUNTIME_PROOF:'工程声明不是运行证据',
    TARGET_FRAMEWORK_DECLARATION:'目标框架声明',inspect_dotnet_semantics:'本地语义取证'};
  return line(String(value??'').replace(/（如\s*[0-9a-f]{6,}…[^）]*）/g,'')
    .replace(/status=PARTIAL/g,'报告仍有缺口').replace(/gap:\s*/g,'')
    .replace(/factIds/g,'证据编号')
    .replace(/TYPE_DECLARATION:DECLARED/g,'类型声明')
    .replace(/HOST_REFERENCE_BOUND|SOURCE_BOUND|UNRESOLVED|TYPE_DECLARATION|METHOD_DECLARATION|PROPERTY_DECLARATION|CONSTRUCTOR_DECLARATION|OBJECT_CREATION|INVOCATION|DECLARED|PROJECT_REFERENCES_NOT_RESOLVED|COMPILATION_ERRORS_OR_MISSING_REFERENCES|GENERATED_SOURCES_NOT_INCLUDED|RUNTIME_BEHAVIOR_NOT_VERIFIED|PROJECT_LANGUAGE_VERSION_NOT_EVALUATED|DECLARATIONS_NOT_RUNTIME_PROOF|TARGET_FRAMEWORK_DECLARATION|inspect_dotnet_semantics/g,x=>terms[x]));
}
function count(items,predicate){return (items||[]).filter(predicate).length;}
export function renderMarkdownReport(report,{runId=null}={}){
  if(!report||typeof report!=='object')throw new TypeError('Report required');
  const request=report.request||{},semantics=report.dotnetSemantics,plan=report.migrationPlan;
  const files=request.files||[],facts=semantics?.facts||[];
  const binding={};for(const fact of facts)binding[fact.binding]=(binding[fact.binding]||0)+1;
  const sections=['# ArchLens 分析报告','',`**调查目标：** ${line(request.objective)}`,
    `**报告状态：** ${statusText[report.status]||line(report.status)}`,
    `**运行编号：** ${line(runId||'见原始 JSON 报告')}`,''];
  if(report.status==='PARTIAL')sections.push('> 这份报告已有分析结果，但证据覆盖尚未闭环；下文的迁移方案不能作为功能等价或上线可行性的证明。','');
  sections.push('## 已确认的分析范围','');
  if(files.length)sections.push(`- 授权文件：${files.length} 个，其中 C# 源码 ${count(files,f=>/\.cs$/i.test(f))} 个。`);
  if(semantics){
    sections.push(`- 本地语义事实：${facts.length} 条，覆盖 ${new Set(facts.map(f=>f.path)).size} 个 C# 文件。`);
    sections.push(`- 源码内可定位的调用或创建：${binding.SOURCE_BOUND||0} 条；仍未解析：${binding.UNRESOLVED||0} 条。`);
    sections.push('- “声明”仅表示源码出现了类型或方法定义；“可定位”也不等于迁移后行为已验证。');
  }
  const tech=[...new Set((report.repositoryInventory?.signals||[])
    .filter(x=>x.kind!=='FILE_EXTENSION').map(x=>`${x.product}${x.version?' '+x.version:''}`))];
  if(tech.length)sections.push(`- 工程与包声明中的技术：${tech.slice(0,16).map(safe).join('、')}。这些是声明，不代表实际运行环境。`);
  sections.push('');
  if(plan){
    sections.push('## 迁移方案（待验证）','',prose(plan.targetArchitecture),'');
    for(const [i,step] of (plan.steps||[]).entries()){
      const phase=String(step.phase||'').replace(/^(?:阶段\s*)?\d+\s*[-.、：:]?\s*/,'').trim()||'迁移步骤';
      sections.push(`### ${i+1}. ${line(phase)}`,'',prose(step.action),'',`**如何验证：** ${prose(step.validation)}`,'');
    }
  }
  const assessment=assessMigration(report);
  if(assessment){
    sections.push('## 风险分析','',assessment.evidenceNote,'');
    for(const risk of assessment.risks)sections.push(`### ${line(risk.title)}（${risk.level}优先级）`,'',
      `- **依据：** ${line(risk.evidence)}`,`- **可能影响：** ${line(risk.impact)}`,`- **处理与验证：** ${line(risk.response)}`,'');
    sections.push('## 主要难点','');
    for(const difficulty of assessment.difficulties)sections.push(`- ${line(difficulty)}`);
    sections.push('','## 成功率评估','',assessment.success,'',assessment.method,'',
      '**可量化前需完成：**','');
    for(const gate of assessment.gates)sections.push(`- ${line(gate)}`);
    sections.push('');
  }
  const gaps=semantics?.gaps||[];
  if(gaps.length){sections.push('## 尚未闭环的证据','');for(const gap of gaps)sections.push(`- ${gapText[gap]||line(gap)}`);sections.push('');}
  if(plan?.openQuestions?.length){
    sections.push('## 待确认事项','');
    for(const q of plan.openQuestions)sections.push(`- ${prose(q)}`);
    if(plan.openQuestions.some(q=>/168\s*个授权\s*\.NET\/C#/.test(q)))
      sections.push(`- **文件数口径说明：** 168 是全部授权文件数，其中 C# 源码为 ${count(files,f=>/\.cs$/i.test(f))} 个。`);
    sections.push('');
  }
  sections.push('## 阅读说明','',
    '- 此文优先呈现结论、验证步骤和缺口；原始符号、来源位置、哈希与工具审计保留在同一运行的 JSON 报告中。',
    '- 本地静态取证不构建或运行被分析项目；运行行为与跨平台等价性需另行验证。','');
  return sections.join('\n');
}

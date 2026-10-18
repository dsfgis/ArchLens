// A reader-facing assessment derived from sealed evidence. It never changes the report.
export function assessMigration(report) {
  if (!report?.migrationPlan) return null;
  const facts = report.dotnetSemantics?.facts || [];
  const gaps = new Set(report.dotnetSemantics?.gaps || []);
  const signals = report.repositoryInventory?.signals || [];
  const products = new Set(signals.map(signal => signal.product));
  const target = `${report.migrationPlan.targetArchitecture || ''} ${report.request?.objective || ''}`;
  const cpp = /C\+\+/i.test(target) || report.interpretedTarget?.targetProfile?.product === 'C++';
  const electron = /Electron/i.test(target);
  const desktop = products.has('WPF') || products.has('Windows Forms');
  const encryptedStore = products.has('SQLCipher');
  const sqlite = products.has('SQLite');
  const unresolved = facts.filter(fact => fact.binding === 'UNRESOLVED').length;
  const risks = [];
  if (gaps.has('RUNTIME_BEHAVIOR_NOT_VERIFIED')) risks.push({
    title: '功能等价缺少运行基线', level: '高',
    evidence: '当前仅有静态源码与工程声明，原程序的实际业务路径尚未运行验证。',
    impact: '迁移后即使能编译，也可能遗漏交互、边界条件或后台任务。',
    response: '先列出关键业务场景，用相同输入记录旧系统输出，再逐项比对新系统。'
  });
  if (desktop && electron) risks.push({
    title: '桌面界面重写与行为偏差', level: '高',
    evidence: `工程声明包含${[products.has('WPF') ? 'WPF' : '', products.has('Windows Forms') ? 'Windows Forms' : ''].filter(Boolean).join('、')}；目标包含 Electron。声明不能证明实际使用范围。`,
    impact: '窗口生命周期、事件顺序、快捷键及后台操作可能与旧界面不一致。',
    response: '逐屏建立交互清单，先迁移代表性复杂界面并由用户走查。'
  });
  if (cpp && electron) risks.push({
    title: 'C++ 核心与 Electron 的接口边界', level: '高',
    evidence: '目标方案需要跨语言通信；现有静态事实不能确定哪些 .NET 方法应成为核心接口。',
    impact: '接口划分错误会造成频繁跨进程调用、错误处理不一致或职责反复调整。',
    response: '先按业务职责筛选核心候选，做一条端到端接口样例，验证数据结构、异常、取消与生命周期。'
  });
  if (sqlite || encryptedStore) risks.push({
    title: '已有数据与加密兼容', level: encryptedStore ? '高' : '中',
    evidence: `工程包声明包含${[sqlite ? 'SQLite' : '', encryptedStore ? 'SQLCipher' : ''].filter(Boolean).join('、')}；实际数据库文件、密钥和读写行为尚未验证。`,
    impact: '新实现可能无法读取原文件，或在事务、加密参数和升级路径上产生差异。',
    response: '用脱敏数据库副本验证读取、写入、事务、加解密与回滚，再确定 C++ 数据访问实现。'
  });
  if (unresolved || gaps.has('PROJECT_COMPILE_ITEMS_NOT_EVALUATED') || gaps.has('GENERATED_SOURCES_NOT_INCLUDED')) risks.push({
    title: '静态分析范围尚不完整', level: '高',
    evidence: `${unresolved} 条语义事实尚未解析；实际编译项${gaps.has('PROJECT_COMPILE_ITEMS_NOT_EVALUATED') ? '未求值' : '已核对'}，生成源码${gaps.has('GENERATED_SOURCES_NOT_INCLUDED') ? '未纳入' : '已纳入'}。`,
    impact: '当前清单可能漏掉真实依赖，迁移范围和工期估计会偏小。',
    response: '在可编译的目标配置下补齐引用、条件编译与生成源码，重跑取证并人工抽查未解析项。'
  });
  const difficulties = [];
  if (cpp) difficulties.push('把业务逻辑与界面、测试、基础设施代码分开；源码内绑定不等于“适合迁入 C++ 核心”。');
  if (desktop && electron) difficulties.push('逐一映射桌面窗口与交互到 Electron，并保留原有操作顺序和用户可见结果。');
  if (cpp && electron) difficulties.push('设计跨语言数据结构、错误传播和后台任务边界，并通过端到端样例证明可行。');
  if (sqlite || encryptedStore) difficulties.push('核对现有数据库格式、事务与加密参数，保证旧数据可读且迁移失败可回滚。');
  if (unresolved) difficulties.push(`消除或解释 ${unresolved} 条未解析语义事实，再据此收敛真实改造范围。`);
  const gates = [
    '明确必须保留的业务场景、验收样例和失败判定标准。',
    '旧系统在目标配置下可构建并运行，取得关键场景的行为基线。',
    '完成代表性 C++／Electron 端到端样例；涉及已有数据时验证数据库兼容和回滚。',
    '按相同输入执行迁移前后回归，关键场景全部通过，未通过项逐条处置。'
  ];
  return {
    risks, difficulties, gates,
    success: '当前无法给出可信的成功率百分比：没有已定义的验收场景分母，也没有旧系统运行基线、目标样例或迁移后回归结果。静态绑定数量不能换算成迁移成功概率。',
    method: '完成上述条件后，可报告“关键验收场景通过率＝通过场景数／已定义场景数”，并另列阻断问题；这是实测验收通过率，不是未来项目成功概率。',
    evidenceNote: '风险等级表示建议处理优先级，不表示发生概率；风险是基于现有声明与证据缺口的推断。'
  };
}

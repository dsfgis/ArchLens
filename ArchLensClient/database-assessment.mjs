// Deterministic first-pass feasibility assessment for database/code migration.
// It never claims compatibility or a delivery commitment; it only summarizes
// visible structure, static associations, gaps and the user's requested change.

export function assessDatabaseMigration(report) {
  const db = report?.databaseInventory;
  if (!db) return null;

  const tables = Array.isArray(db.tables) ? db.tables : [];
  const columns = tables.reduce((sum, table) => sum + (table.columns?.length || 0), 0);
  const primaryKeys = [];
  const foreignKeys = [];
  for (const table of tables) {
    for (const key of table.keys || []) {
      if (key.type === 'PRIMARY KEY' || key.type === 'PK') {
        primaryKeys.push({ table: table.name, column: key.column });
      }
      if (key.referencedTable || key.type === 'FOREIGN KEY' || key.type === 'FK') {
        foreignKeys.push({
          table: table.name, column: key.column,
          referencedTable: key.referencedTable || null,
          referencedColumn: key.referencedColumn || null,
        });
      }
    }
    if (!table.keys?.length && table.columns?.some(column => column.name?.toLowerCase() === 'id')) {
      primaryKeys.push({ table: table.name, column: 'id', inferred: true });
    }
  }

  const associations = report.codeDatabaseAssociation?.links || [];
  const paths = report.codeDatabaseAssociation?.accessPaths || [];
  const gaps = new Set([
    ...(db.coverageGaps || []),
    ...(report.codeDatabaseAssociation?.coverageGaps || []),
    ...(report.investigation?.coverageGaps || []).map(gap => gap.code),
  ]);
  const findings = report.investigation?.findings || [];
  const target = report.request?.businessContext?.targetEnvironment?.database
    || report.targetEnvironment?.database || null;
  const targetProduct = target?.product || '目标数据库';
  const targetVersion = target?.version || '版本未声明';
  const targetIsKingbase = /kingbase|金仓/i.test(targetProduct);
  const objective = String(report.request?.objective || '');
  const requestsKeyChange = /(?:event.?id|event_id|主键|primary\s*key|id\s*(?:改|->|→|to)\s*event)/i.test(objective);
  const hasTargetRules = findings.some(finding =>
    /^(?:KINGBASE|MYSQL_|ORACLE_)/.test(finding.ruleRef || ''));

  const scale = tables.length <= 5 && columns <= 80 ? 'SMALL'
    : tables.length <= 25 && columns <= 400 ? 'MEDIUM' : 'LARGE';
  const base = { SMALL: [1, 3], MEDIUM: [3, 8], LARGE: [8, 18] }[scale];
  const keyExtra = requestsKeyChange ? [1, 3] : [0, 0];
  const unknownExtra = (targetIsKingbase || gaps.has('RULE_MATRIX_UNSUPPORTED_OR_VERSION_UNKNOWN'))
    ? [1, 4] : [0, 2];
  const workload = {
    unit: '人月',
    low: base[0] + keyExtra[0] + unknownExtra[0],
    high: base[1] + keyExtra[1] + unknownExtra[1],
    confidence: targetIsKingbase || !hasTargetRules ? 'LOW' : 'MEDIUM',
    basis: `${tables.length} 张表、${columns} 个字段、${foreignKeys.length} 个外键、${associations.length} 条静态代码关联；未执行实际迁移。`,
  };

  const compatibility = !target ? 'UNKNOWN'
    : targetIsKingbase && !hasTargetRules ? 'UNKNOWN' : 'CONDITIONAL';
  const riskLevel = targetIsKingbase || requestsKeyChange || foreignKeys.length > 0
    || gaps.has('DB_CODE_STATIC_CANDIDATES_ONLY') ? '高' : scale === 'SMALL' ? '低' : '中';

  const risks = [];
  if (targetIsKingbase && !hasTargetRules) risks.push({
    title: '人大金仓版本兼容规则尚未发布',
    level: '高',
    evidence: `目标声明为 ${targetProduct} ${targetVersion}，当前规则目录没有对应的 KingbaseES 正反例。`,
    impact: '不能把 MySQL 语法可执行、JDBC 可连接或 PostgreSQL 规则当作金仓兼容性证明。',
    response: '先固定金仓版本/兼容模式，补充官方能力矩阵和真实测试库正反例；在此之前保持 UNKNOWN。',
  });
  if (requestsKeyChange) risks.push({
    title: '业务主键 Id → eventId 的跨层影响',
    level: '高',
    evidence: `用户目标包含主键变更。可见主键 ${primaryKeys.length} 个、外键 ${foreignKeys.length} 个；仍需逐项核对实体、DTO/VO、JSON、Mapper、索引和迁移顺序。`,
    impact: '只改数据库列名可能破坏外键、路由参数、序列化字段、缓存键和外部接口。',
    response: '把字段重命名、数据回填、外键/索引重建、代码字段映射和 API 兼容作为同一变更包，先做回滚演练。',
  });
  if (foreignKeys.length) risks.push({
    title: '外键与数据迁移顺序',
    level: '中高',
    evidence: `采集到 ${foreignKeys.length} 个外键关联；当前没有业务数据样本和迁移窗口。`,
    impact: '删除/重建约束时的顺序、孤儿记录、锁表和回滚失败会导致数据不一致。',
    response: '在脱敏副本上验证约束顺序、批量回填、失败回滚和校验查询。',
  });
  if (associations.length || paths.length) risks.push({
    title: '代码与数据库关联仍是静态候选',
    level: '中高',
    evidence: `静态代码关联 ${associations.length} 条、路径 ${paths.length} 条；存在 ${[...gaps].filter(g => /DB_CODE|DB_DYNAMIC|DB_METHOD/.test(g)).length} 项关联缺口。`,
    impact: '动态 SQL、ORM 生成 SQL、反射和未采集入口可能遗漏真实使用点。',
    response: '按入口/服务/Mapper/对象逐段人工核对，并用真实查询日志或测试覆盖补证。',
  });
  if (gaps.has('RULE_MATRIX_UNSUPPORTED_OR_VERSION_UNKNOWN')) risks.push({
    title: '目标规则矩阵未覆盖',
    level: '高',
    evidence: '当前调查返回 RULE_MATRIX_UNSUPPORTED_OR_VERSION_UNKNOWN。',
    impact: '报告只能给出待核实项，不能给出确定的兼容结论。',
    response: '补齐目标版本和规则后重新修订，不用模型常识替代规则证据。',
  });

  return {
    decision: tables.length ? 'CONDITIONAL_GO' : 'UNKNOWN',
    compatibility,
    riskLevel,
    targetProduct,
    targetVersion,
    metrics: {
      tables: tables.length, columns, primaryKeys: primaryKeys.length,
      foreignKeys: foreignKeys.length, codeAssociations: associations.length,
      codePaths: paths.length, findings: findings.length, gaps: gaps.size,
    },
    workload,
    primaryKeys: primaryKeys.slice(0, 80),
    foreignKeys: foreignKeys.slice(0, 80),
    changeImpact: requestsKeyChange ? {
      requested: objective,
      likelyPrimaryKeys: primaryKeys.slice(0, 40),
      likelyForeignKeys: foreignKeys.slice(0, 40),
      codeCandidates: associations.filter(link =>
        /id|event|ledger/i.test(`${link.token || ''} ${link.databaseObject || ''}`)).slice(0, 40),
    } : null,
    risks,
    gates: [
      '固定金仓版本、兼容模式、字符集和排序规则，并取得真实测试库。',
      '确认 Id → eventId 的影响范围：主键、外键、索引、实体、DTO/VO、JSON、Mapper 和外部 API。',
      '在脱敏数据副本上验证约束重建、数据回填、失败回滚和一致性校验。',
      '用动态 SQL、ORM 生成 SQL 和真实入口补齐静态关联缺口。',
      '完成迁移前后回归与性能/锁等待基线后，才能评估 A1/A2。',
    ],
    method: '基于可见表/字段/主外键、代码静态候选和覆盖缺口的确定性初筛；工作量是量级参考，不是承诺工期。',
  };
}

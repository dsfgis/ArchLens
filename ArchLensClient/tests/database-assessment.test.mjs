import test from 'node:test';
import assert from 'node:assert/strict';
import { assessDatabaseMigration } from '../database-assessment.mjs';
import { renderMarkdownReport } from '../report-markdown.mjs';

const report = {
  request: {
    objective: 'MySQL 迁移到人大金仓，并将 gxgl_ledger.id 改为 event_id，附件关联同步改为 event_id。',
    businessContext: {
      targetEnvironment: { database: { product: 'KingbaseES', version: 'V9' } },
    },
  },
  databaseInventory: {
    product: 'MySQL', observedVersion: '8.0', database: 'changeline', status: 'PARTIAL',
    coverageGaps: ['DB_ACCOUNT_VISIBLE_OBJECTS_ONLY'],
    tables: [
      {
        name: 'gxgl_ledger',
        columns: [{ name: 'id' }, { name: 'project_name' }, { name: 'org_id' }],
        keys: [{ type: 'PRIMARY KEY', column: 'id' }],
      },
      {
        name: 'gxgl_attachment',
        columns: [{ name: 'id' }, { name: 'ledger_id' }, { name: 'file_name' }],
        keys: [
          { type: 'PRIMARY KEY', column: 'id' },
          { type: 'FOREIGN KEY', column: 'ledger_id', referencedTable: 'gxgl_ledger', referencedColumn: 'id' },
        ],
      },
    ],
  },
  codeDatabaseAssociation: {
    links: [{ sourcePath: 'src/Ledger.java', line: 42, operation: 'SELECT', token: 'gxgl_ledger', databaseObject: 'gxgl_ledger' }],
    coverageGaps: ['DB_CODE_STATIC_CANDIDATES_ONLY', 'DB_DYNAMIC_SQL_UNRESOLVED'],
  },
  investigation: {
    findings: [],
    coverageGaps: [{ code: 'RULE_MATRIX_UNSUPPORTED_OR_VERSION_UNKNOWN' }],
  },
};

test('database assessment exposes Kingbase and primary-key risks without claiming compatibility', () => {
  const assessment = assessDatabaseMigration(report);
  assert.equal(assessment.decision, 'CONDITIONAL_GO');
  assert.equal(assessment.compatibility, 'UNKNOWN');
  assert.equal(assessment.riskLevel, '高');
  assert.deepEqual(assessment.metrics, {
    tables: 2, columns: 6, primaryKeys: 2, foreignKeys: 1,
    codeAssociations: 1, codePaths: 0, findings: 0, gaps: 4,
  });
  assert.ok(assessment.workload.low >= 1);
  assert.ok(assessment.workload.high > assessment.workload.low);
  assert.equal(assessment.workload.confidence, 'LOW');
  assert.equal(assessment.primaryKeys[0].table, 'gxgl_ledger');
  assert.equal(assessment.foreignKeys[0].column, 'ledger_id');
  assert.ok(assessment.risks.some(risk => risk.title.includes('人大金仓')));
  assert.ok(assessment.risks.some(risk => risk.title.includes('Id → eventId')));
  assert.ok(assessment.gates.some(gate => gate.includes('Id → eventId')));
  assert.equal(assessment.changeImpact.likelyForeignKeys.length, 1);
});

test('database assessment is absent for code-only reports', () => {
  assert.equal(assessDatabaseMigration({ request: { objective: '代码分析' } }), null);
});

test('Markdown export carries the database feasibility section', () => {
  const markdown = renderMarkdownReport(report, { runId: 'demo-run' });
  assert.match(markdown, /信创迁移可行性初筛/);
  assert.match(markdown, /人大金仓版本兼容规则尚未发布/);
  assert.match(markdown, /Id → eventId/);
  assert.match(markdown, /6–10 人月|初步工作量/);
  assert.match(markdown, /CONDITIONAL/);
});

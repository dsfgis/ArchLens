const $ = (id) => document.getElementById(id);
const form = $('analysis-form');
const fields = { repository: $('repository-path'), database: $('database-url'), user: $('database-user'), password: $('database-password'), target: $('change-target') };
const errorIds = { repository: 'repository-error', database: 'database-error', user: 'user-error', target: 'target-error' };
const touched = new Set();
let draft = null;
let toastTimer;
let parsedTarget = null;
let parsedDescription = '';
let parseController = null;
let parsingDescription = '';

const templates = {
  rename: '将 public.device 表的 event_id 字段改名为 global_id，保持字段类型、可空性和业务含义不变，分析需要修改的 SQL、Mapper 和回归范围。',
  drop: '删除 public.device 表的 legacy_code 字段，定位仍在读取或写入该字段的 SQL 和 Mapper，并列出需要确认的兼容性问题与回归范围。',
  type: '将 public.device 表的 event_id 字段从 integer 改为 bigint，保持字段名称和业务含义不变，分析类型映射、SQL 和回归范围。',
};

function parseDatabase(value) {
  const normalized = value.trim().replace(/^jdbc:/, '');
  if (!/^postgresql:\/\//.test(normalized)) throw new Error('请使用 postgresql://主机:端口/数据库 或 jdbc:postgresql:// 格式。');
  let url;
  try { url = new URL(normalized); } catch { throw new Error('连接地址格式不正确，请检查主机、端口和数据库名称。'); }
  if (url.username || url.password || url.search || url.hash || /\s/.test(normalized)) throw new Error('请使用不含账号、密码、查询参数或片段的连接地址，账号密码请单独填写。');
  if (!url.hostname || !/^\/[^/]+$/.test(url.pathname)) throw new Error('请填写主机和单个数据库名称，例如 postgresql://localhost:5432/app。');
  if (url.port && (!/^\d+$/.test(url.port) || Number(url.port) < 1 || Number(url.port) > 65535)) throw new Error('数据库端口必须在 1–65535 之间。');
  let database;
  try { database = decodeURIComponent(url.pathname.slice(1)); } catch { throw new Error('数据库名称包含无效的 URL 编码。'); }
  if (/[\s/\\\x00-\x1f]/.test(database)) throw new Error('数据库名称不能包含空白或路径分隔符。');
  return { engine: 'postgresql', host: url.hostname, port: Number(url.port || 5432), database, username: fields.user.value.trim() };
}

function validate() {
  const errors = {};
  const repository = fields.repository.value.trim();
  if (!repository) errors.repository = '请填写代码库的绝对路径。';
  else if (!/^(?:[A-Za-z]:[\\/]|\/|\\\\[^\\/]+[\\/][^\\/]+)/.test(repository) || /[\x00-\x1f]/.test(repository)) errors.repository = '请填写绝对路径，例如 D:\\work\\my-project 或 /srv/my-project。';
  if (!fields.database.value.trim()) errors.database = '请填写 PostgreSQL 连接地址。';
  else { try { parseDatabase(fields.database.value); } catch (error) { errors.database = error.message; } }
  if (!fields.user.value.trim()) errors.user = '请填写数据库用户名。';
  else if (/[\x00-\x1f]/.test(fields.user.value)) errors.user = '用户名不能包含控制字符。';
  if (!fields.target.value.trim()) errors.target = '请描述修改对象和具体修改内容。';
  else if (fields.target.value.trim().length < 10) errors.target = '请补充具体的修改对象和修改方式（至少 10 个字符）。';
  else if (fields.target.value.length > 4000) errors.target = '修改目标不能超过 4000 个字符。';
  return errors;
}

function update() {
  if ((parsedTarget && parsedDescription !== fields.target.value.trim()) || (parseController && parsingDescription !== fields.target.value.trim())) invalidateProposal();
  const errors = validate();
  for (const [key, errorId] of Object.entries(errorIds)) {
    const error = touched.has(key) && errors[key];
    $(errorId).textContent = error || '';
    $(errorId).hidden = !error;
    fields[key].setAttribute('aria-invalid', String(Boolean(error)));
  }
  $('character-count').textContent = `${fields.target.value.length} / 4000`;
  const groups = [
    ['repo-ready', !errors.repository, fields.repository.value.trim(), '等待填写路径'],
    ['db-ready', !errors.database && !errors.user, '连接信息已填写', '等待填写连接信息'],
    ['target-ready', !errors.target, '变更描述已填写', '等待描述变更'],
  ];
  let count = 0;
  groups.forEach(([id, ready, value, fallback]) => {
    const row = $(id);
    row.classList.toggle('ready', ready);
    row.querySelector('p').textContent = ready ? value : fallback;
    row.querySelector('.readiness-status').textContent = ready ? '已填写' : '待填写';
    if (ready) count++;
  });
  $('ready-count').textContent = `${count} / 3`;
  // A class avoids inline styles under the local server's Content Security Policy.
  $('progress-fill').className = `progress-${count}`;
  return errors;
}

function toast(message) {
  clearTimeout(toastTimer);
  $('toast').textContent = message;
  $('toast').hidden = false;
  toastTimer = setTimeout(() => { $('toast').hidden = true; }, 4000);
}

for (const [key, field] of Object.entries(fields)) {
  field.addEventListener('input', update);
  if (key !== 'password') field.addEventListener('blur', () => { touched.add(key); update(); });
}
$('fill-example').addEventListener('click', () => {
  // Preserve existing user input and credentials; examples only fill empty fields.
  if (!fields.repository.value.trim()) fields.repository.value = 'D:\\work\\device-service';
  if (!fields.database.value.trim()) fields.database.value = 'postgresql://localhost:5432/device_db';
  if (!fields.user.value.trim()) fields.user.value = 'archlens_reader';
  if (!fields.target.value.trim()) fields.target.value = templates.rename;
  update();
  toast('已补充空白项的示例，请按实际项目调整。');
});
document.querySelectorAll('[data-template]').forEach((button) => button.addEventListener('click', () => {
  fields.target.value = templates[button.dataset.template];
  touched.add('target');
  update();
  fields.target.focus();
}));
$('toggle-password').addEventListener('click', () => {
  const reveal = fields.password.type === 'password';
  fields.password.type = reveal ? 'text' : 'password';
  $('toggle-password').setAttribute('aria-label', reveal ? '隐藏密码' : '显示密码');
  $('toggle-password').setAttribute('aria-pressed', String(reveal));
});
$('reset-form').addEventListener('click', () => {
  form.reset();
  fields.password.type = 'password';
  $('toggle-password').setAttribute('aria-label', '显示密码');
  $('toggle-password').setAttribute('aria-pressed', 'false');
  touched.clear();
  draft = null;
  $('request-preview').textContent = '';
  update();
  fields.repository.focus();
  toast('表单已清空。');
});
form.addEventListener('submit', (event) => {
  event.preventDefault();
  Object.keys(errorIds).forEach((key) => touched.add(key));
  const errors = update();
  if (Object.keys(errors).length) {
    fields[Object.keys(errors)[0]].focus();
    toast('请先完善标红的输入项。');
    return;
  }
  draft = {
    schemaVersion: 'archlens.frontend-draft.v1',
    status: 'NOT_SUBMITTED',
    repository: { path: fields.repository.value.trim() },
    database: parseDatabase(fields.database.value),
    change: { description: fields.target.value.trim() },
  };
  if (parsedTarget && parsedDescription === fields.target.value.trim()) draft.modelProposal = parsedTarget;
  $('request-preview').textContent = JSON.stringify(draft, null, 2);
  $('request-dialog').showModal();
});
for (const id of ['close-dialog', 'back-to-edit']) $(id).addEventListener('click', () => $('request-dialog').close());
$('download-request').addEventListener('click', () => {
  if (!draft) return;
  const url = URL.createObjectURL(new Blob([JSON.stringify(draft, null, 2) + '\n'], { type: 'application/json' }));
  const link = document.createElement('a');
  link.href = url;
  link.download = 'archlens-analysis-request.json';
  link.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
  toast('请求文件已生成，未包含数据库密码。');
});
function invalidateProposal() {
  parseController?.abort();
  parseController = null;
  parsedTarget = null;
  parsedDescription = '';
  $('ai-proposal').textContent = '';
  $('ai-proposal').hidden = true;
  $('ai-status').textContent = '目标已更新，AI 草稿已清除。可重新梳理目标。';
  $('parse-target').disabled = false;
  $('parse-target').textContent = 'AI 梳理目标';
}
const modelErrors = {
  MODEL_NOT_CONFIGURED: '后端尚未配置 DeepSeek 密钥，请通过 start.ps1 启动服务。',
  MODEL_AUTH_FAILED: 'DeepSeek 密钥无效或无权限，请检查后端配置。',
  MODEL_BALANCE_INSUFFICIENT: 'DeepSeek 账户余额不足。',
  MODEL_RATE_LIMITED: 'DeepSeek 请求频率受限，请稍后重试。',
  MODEL_BUSY: '已有目标正在解析，请稍后重试。',
  MODEL_TIMEOUT: 'DeepSeek 响应超时，请稍后重试。',
  INVALID_MODEL_OUTPUT: '模型返回内容未通过校验，请补充目标描述后重试。',
  MODEL_BACKEND_UNAVAILABLE: '模型后端启动失败，请检查 JDK 21 路径并构建后端。',
};
$('parse-target').addEventListener('click', async () => {
  touched.add('target');
  if (update().target) { fields.target.focus(); return; }
  invalidateProposal();
  const description = fields.target.value.trim();
  const controller = new AbortController();
  parseController = controller;
  parsingDescription = description;
  $('parse-target').disabled = true;
  $('parse-target').textContent = '正在梳理…';
  $('ai-status').textContent = 'DeepSeek 正在解析变更描述，通常需要数秒，请稍候。';
  const timeout = setTimeout(() => controller.abort(), 75000);
  try {
    const response = await fetch('/api/parse-target', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ description }), signal: controller.signal,
    });
    const result = await response.json();
    if (!response.ok || result.error) throw new Error(result.error || 'MODEL_UNAVAILABLE');
    if (result.status !== 'UNVERIFIED_PROPOSAL' || !result.proposal) throw new Error('INVALID_MODEL_OUTPUT');
    if (parseController !== controller || fields.target.value.trim() !== description) return;
    parsedTarget = result;
    parsedDescription = description;
    $('ai-proposal').textContent = JSON.stringify(result.proposal, null, 2);
    $('ai-proposal').hidden = false;
    $('ai-status').textContent = `已由 ${result.model} 生成待核实草稿。请检查目标和澄清问题；生成请求时会附带此草稿。`;
  } catch (error) {
    if (parseController === controller) $('ai-status').textContent = error.name === 'AbortError'
      ? '解析已超时，可重试。原始描述已保留。'
      : modelErrors[error.message] || '暂时无法调用 DeepSeek，原始描述已保留，可继续生成请求草稿。';
  } finally {
    clearTimeout(timeout);
    if (parseController === controller) {
      parseController = null;
      $('parse-target').disabled = false;
      $('parse-target').textContent = 'AI 梳理目标';
    }
  }
});
update();

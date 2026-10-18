import { opendir, realpath, lstat } from 'node:fs/promises';
import path from 'node:path';

// 通用目录发现：只读取目录项，不执行项目；内容及证据由 Java 在本地采集。
export async function discoverRepository(root, limits = {}) {
  if (typeof root !== 'string' || !path.isAbsolute(root)) throw new Error('SOURCE_ROOT_INVALID');
  const base = await realpath(root).catch(() => { throw new Error('SOURCE_ROOT_INVALID'); });
  if (!(await lstat(base)).isDirectory()) throw new Error('SOURCE_ROOT_INVALID');
  const files = [], excluded = [];
  const maxFiles = limits.maxFiles ?? 1000, maxBytes = limits.maxBytes ?? 50000000;
  if (!Number.isInteger(maxFiles) || maxFiles < 1 || maxFiles > 1000 || !Number.isInteger(maxBytes) || maxBytes < 1 || maxBytes > 50000000) throw new Error('INVALID_BUDGET');
  const ignored = new Set(['bin', 'obj', '.git', '.vs', '.idea', 'node_modules', 'packages', '.local', 'target', 'build', 'dist', 'vendor', '.venv', 'venv', '__pycache__', '.gradle', '.next', 'coverage', '.codex']);
  let entries = 0, bytes = 0;
  const deadline = Date.now() + 10000;
  async function walk(dir, depth) {
    if (depth > 20) throw new Error('PROJECT_DISCOVERY_LIMIT');
    for await (const entry of await opendir(dir)) {
      if (++entries > 10000 || Date.now() > deadline) throw new Error('PROJECT_DISCOVERY_LIMIT');
      const candidate = path.join(dir, entry.name), relative = path.relative(base, candidate).replaceAll('\\', '/');
      // 先排除符号链接，损坏链接也不能中断发现或被跟随。
      if (entry.isSymbolicLink()) { excluded.push(relative); continue; }
      const actual = await realpath(candidate), rel = path.relative(base, actual);
      if (rel === '..' || rel.startsWith(`..${path.sep}`) || path.isAbsolute(rel)) { excluded.push(relative); continue; }
      if (entry.isDirectory()) {
        if (ignored.has(entry.name.toLowerCase())) excluded.push(relative);
        else await walk(candidate, depth + 1);
      } else if (entry.isFile()) {
        const name = entry.name.toLowerCase();
        const supported = /\.(cs|vb|fs|fsx|csproj|vbproj|fsproj|sln|slnx|props|targets|java|kt|kts|scala|groovy|gradle|xml|sql|js|jsx|ts|tsx|mjs|cjs|vue|svelte|py|go|rs|rb|php|c|h|cc|cpp|hpp|swift|xaml)$/.test(name)
          || ['package.json', 'composer.json', 'global.json', 'packages.config', 'web.config', 'app.config', 'pyproject.toml', 'cargo.toml', 'go.mod', 'gemfile', 'requirements.txt'].includes(name)
          || /^appsettings(?:\.[a-z0-9_-]+)?\.json$/.test(name);
        if (!supported) continue;
        if (/\.(g|generated|designer)\.(cs|vb|fs)$/.test(name)) { excluded.push(relative); continue; }
        const info = await lstat(candidate);
        if (info.isSymbolicLink() || !info.isFile()) throw new Error('PROJECT_SOURCE_CHANGED');
        bytes += info.size;
        if (files.length >= maxFiles || bytes > maxBytes) throw new Error('PROJECT_DISCOVERY_LIMIT');
        files.push(relative);
      }
    }
  }
  await walk(base, 0);
  if (!files.length) throw new Error('SOURCE_FILES_REQUIRED');
  return { sourceRoot: base, files: files.sort(), bytes, excluded: excluded.sort(), warnings: [
    '自动发现源码和项目声明，排除构建产物、依赖缓存和符号链接；不运行构建脚本。',
    '发现文件不代表相应语言已有完整语义分析；报告会保留不支持的范围。',
    '源码和配置内容保留在本地，模型仅接收经过筛选的技术声明与匿名证据。',
  ] };
}

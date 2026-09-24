import { opendir, realpath, lstat } from 'node:fs/promises';
import path from 'node:path';

// 仅发现候选文件，不执行 MSBuild，不跟随链接，不读取配置正文。提交时仍由 Java 重新校验每个来源。
export async function discoverCSharpProject(root) {
  if (typeof root !== 'string' || !path.isAbsolute(root)) throw new Error('SOURCE_ROOT_INVALID');
  const base = await realpath(root).catch(() => { throw new Error('SOURCE_ROOT_INVALID'); });
  if (!(await lstat(base)).isDirectory()) throw new Error('SOURCE_ROOT_INVALID');
  const files = [], excluded = [], warnings = ['这是目录候选清单，不是 MSBuild 有效编译清单；条件、导入、生成代码和目录外引用未求值。'];
  const ignored = new Set(['bin', 'obj', '.git', '.vs', '.idea', 'node_modules', 'packages']);
  let entries = 0, bytes = 0;
  const deadline = Date.now() + 10000;
  async function walk(dir, depth) {
    if (depth > 20) throw new Error('PROJECT_DISCOVERY_LIMIT');
    const handle = await opendir(dir);
    for await (const entry of handle) {
      if (++entries > 10000 || Date.now() > deadline) throw new Error('PROJECT_DISCOVERY_LIMIT');
      const candidate = path.join(dir, entry.name), relative = path.relative(base, candidate).replaceAll('\\', '/');
      const actual = await realpath(candidate);
      const rel = path.relative(base, actual);
      if (entry.isSymbolicLink() || rel === '..' || rel.startsWith(`..${path.sep}`) || path.isAbsolute(rel)) { excluded.push(relative); continue; }
      if (entry.isDirectory()) {
        if (ignored.has(entry.name.toLowerCase())) excluded.push(relative);
        else await walk(candidate, depth + 1);
      } else if (entry.isFile() && (/\.(cs|csproj)$/i.test(entry.name) || /^Directory\.(Build\.(props|targets)|Packages\.props)$/i.test(entry.name))) {
        if (/\.(g|generated|designer)\.cs$/i.test(entry.name)) { excluded.push(relative); continue; }
        const info = await lstat(candidate);
        if (info.isSymbolicLink() || !info.isFile()) throw new Error('PROJECT_SOURCE_CHANGED');
        bytes += info.size;
        if (files.length >= 1000 || bytes > 50000000) throw new Error('PROJECT_DISCOVERY_LIMIT');
        files.push(relative);
      }
    }
  }
  await walk(base, 0);
  files.sort(); excluded.sort();
  if (!files.some(f => f.toLowerCase().endsWith('.csproj'))) throw new Error('CSHARP_PROJECT_REQUIRED');
  return { sourceRoot: base, files, bytes, excluded, warnings };
}

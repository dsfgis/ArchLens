import { opendir, realpath, lstat } from 'node:fs/promises';
import path from 'node:path';

// 仅读取目录项；清单需用户审阅，内容及证据由 Java 重新采集。
export async function discoverDotnetProject(root) {
  if (typeof root !== 'string' || !path.isAbsolute(root)) throw new Error('SOURCE_ROOT_INVALID');
  const base = await realpath(root).catch(() => { throw new Error('SOURCE_ROOT_INVALID'); });
  if (!(await lstat(base)).isDirectory()) throw new Error('SOURCE_ROOT_INVALID');
  const files = [], excluded = [], projects = [], solutions = [];
  const ignored = new Set(['bin', 'obj', '.git', '.vs', '.idea', 'node_modules', 'packages']);
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
        const isProject = /\.(csproj|vbproj|fsproj)$/.test(name), isSolution = /\.(sln|slnx)$/.test(name);
        const supported = isProject || isSolution || /\.(cs|vb|fs|fsx)$/.test(name)
          || /^directory\.(build\.(props|targets)|packages\.props)$/.test(name)
          || ['global.json', 'packages.config', 'web.config', 'app.config'].includes(name)
          || /^appsettings(?:\.[a-z0-9_-]+)?\.json$/.test(name);
        if (!supported) continue;
        if (/\.(g|generated|designer)\.(cs|vb|fs)$/.test(name)) { excluded.push(relative); continue; }
        const info = await lstat(candidate);
        if (info.isSymbolicLink() || !info.isFile()) throw new Error('PROJECT_SOURCE_CHANGED');
        bytes += info.size;
        if (files.length >= 1000 || bytes > 50000000) throw new Error('PROJECT_DISCOVERY_LIMIT');
        files.push(relative);
        if (isProject) projects.push(relative);
        if (isSolution) solutions.push(relative);
      }
    }
  }
  await walk(base, 0);
  if (!projects.length && !solutions.length) throw new Error('DOTNET_PROJECT_REQUIRED');
  return { sourceRoot: base, files: files.sort(), projects: projects.sort(), solutions: solutions.sort(), bytes,
    excluded: excluded.sort(), warnings: [
      '候选清单包含 .NET Framework、.NET Core 和现代 .NET 项目；各项目的语言、SDK 与目标框架将分别识别。',
      '不执行 MSBuild、restore 或源码；条件、导入、生成文件、外部引用及运行时行为仍需核实。',
      '配置文件仅用于本地结构识别，连接串和配置值不会加入现状报告或模型请求。',
    ] };
}

/*
 * 构建后把 dmg 另存一份到 ~/Downloads，文件名带构建时间（便于回滚/对比）。
 * 规则见 AGENTS.md §3.1 规则 11。
 */
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';

const here = path.dirname(new URL(import.meta.url).pathname);
const distDir = path.resolve(here, '..', 'dist');
if (!fs.existsSync(distDir)) {
  console.log('[copy-dmg] 无 dist 目录，跳过');
  process.exit(0);
}
const dmgs = fs.readdirSync(distDir).filter((f) => f.toLowerCase().endsWith('.dmg'));
if (dmgs.length === 0) {
  console.log('[copy-dmg] 未找到 *.dmg，跳过');
  process.exit(0);
}
const now = new Date();
const pad = (n) => String(n).padStart(2, '0');
const stamp = `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}-${pad(now.getHours())}${pad(now.getMinutes())}${pad(now.getSeconds())}`;

for (const f of dmgs) {
  const src = path.join(distDir, f);
  const base = f.replace(/\.dmg$/i, '');
  const dest = path.join(os.homedir(), 'Downloads', `${base}-${stamp}.dmg`);
  try {
    fs.copyFileSync(src, dest);
    console.log(`[copy-dmg] ${dest}`);
  } catch (e) {
    console.warn(`[copy-dmg] 复制失败: ${f}: ${e.message}`);
  }
}

/*
 * 构建后把 dmg 另存一份到 ~/Downloads，文件名带构建时间（便于回滚/对比）。
 * 规则见 AGENTS.md §3.1 规则 11。
 */
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { execFileSync } from 'node:child_process';

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

// 简述本包实现的功能：优先环境变量 PKG_DESC，否则取最近一次 commit 标题（截断）
function pkgDesc() {
  let raw = process.env.PKG_DESC || '';
  if (!raw) {
    try {
      raw = execFileSync('git', ['log', '-1', '--pretty=%s'], { cwd: path.resolve(here, '..', '..'), encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }).trim();
    } catch (e) { raw = ''; }
  }
  // 只保留字母数字与 '-'，压缩空白；截断到 48 字符
  const slug = raw
    .replace(/[^0-9A-Za-z]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 48)
    .replace(/-+$/g, '');
  return slug || 'sftp-desktop';
}
const desc = pkgDesc();

for (const f of dmgs) {
  const src = path.join(distDir, f);
  const base = f.replace(/\.dmg$/i, '');
  const dest = path.join(os.homedir(), 'Downloads', `${base}-${desc}-${stamp}.dmg`);
  try {
    fs.copyFileSync(src, dest);
    console.log(`[copy-dmg] ${dest}`);
  } catch (e) {
    console.warn(`[copy-dmg] 复制失败: ${f}: ${e.message}`);
  }
}

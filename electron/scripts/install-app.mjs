/*
 * 覆盖安装到本机：把 electron/dist 里的 .app 覆盖到 /Applications/Kuikly SFTP.app
 *   npm run install:app
 *
 * 与 AGENTS §3.1 规则 11 配套：在 zhaojian 分支上每次开发/修复功能收尾都要执行
 *   npm run dist && npm run install:app
 * 保证本机运行/自动化验证使用的始终是最新构建。
 */
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';

const here = path.dirname(new URL(import.meta.url).pathname);
const electronDir = path.resolve(here, '..');
const distDir = path.join(electronDir, 'dist');
const APP_NAME = 'Kuikly SFTP.app';
const target = path.join('/Applications', APP_NAME);

function findBuiltApp() {
  if (!fs.existsSync(distDir)) return null;
  // electron-builder 产物：dist/mac 或 dist/mac-arm64 下的 .app
  const candidates = fs.readdirSync(distDir)
    .filter((d) => d.startsWith('mac'))
    .map((d) => path.join(distDir, d, APP_NAME));
  for (const c of candidates) {
    if (fs.existsSync(c)) return c;
  }
  const direct = path.join(distDir, APP_NAME);
  return fs.existsSync(direct) ? direct : null;
}

function quitRunning() {
  try { execFileSync('osascript', ['-e', 'quit app "Kuikly SFTP"'], { stdio: 'ignore' }); } catch (e) { /* 未运行 */ }
  try { execFileSync('pkill', ['-f', APP_NAME], { stdio: 'ignore' }); } catch (e) { /* 未运行 */ }
  // 给归档进程一点退出时间，避免 cp 时文件被占用
  try { execFileSync('sleep', ['1'], { stdio: 'ignore' }); } catch (e) { /* ignore */ }
}

const built = findBuiltApp();
if (!built) {
  console.error(`[install:app] 未找到构建产物，请先执行 npm run dist（期望 ${distDir}/mac*/\u0022${APP_NAME}\u0022）`);
  process.exit(1);
}

console.log(`[install:app] 源: ${built}`);
console.log(`[install:app] 目标: ${target}`);
quitRunning();

try {
  fs.rmSync(target, { recursive: true, force: true });
  // ditto 保留符号链接/权限/扩展属性，比 cp -R 更接近安装器行为
  execFileSync('ditto', [built, target], { stdio: 'inherit' });
  console.log('[install:app] 覆盖安装完成');
} catch (e) {
  console.error('[install:app] 安装失败：' + String((e && e.message) || e));
  process.exit(1);
}

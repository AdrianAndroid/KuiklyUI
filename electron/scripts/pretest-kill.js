/*
 * pretest/posttest 钩子：清理**本 worktree 实例**残留的 Electron 测试进程，避免多 worktree 并行互相干扰
 * （AGENTS.md §3.1 规则 12/13，隔离规则见 electron/test/env.mjs）。
 *
 * 做法：只按实例 userData 标记（`ud-<instance>`）匹配 —— 所有套件启动时都带
 * `--user-data-dir=<repo>/.kr-test/ud-<instance>`，因此该标记天然只命中本实例。
 *
 * ❗不要用以下过宽/全局模式：
 *   - `pkill -f "remote-debugging-port="` → 会误杀其它 worktree 的测试（且曾误杀外部网关）
 *   - `pkill -f "Kuikly SFTP.app"` / `osascript quit app "Kuikly SFTP"` → 全局，会关掉其它并行实例
 *   - 杀本实例的**外部网关端口**（`npm run gateway`）→ 那是测试自己的前置进程，杀了会 ECONNREFUSED
 */
'use strict';

const path = require('node:path');
const { execFileSync } = require('node:child_process');

function run(cmd, args) {
  try { execFileSync(cmd, args, { stdio: 'ignore' }); } catch (e) { /* ignore（无匹配进程时退出码非 0） */ }
}

function sanitize(s) {
  return String(s).toLowerCase().replace(/[^a-z0-9_-]+/g, '-').replace(/^-+|-+$/g, '') || 'default';
}

const repoRoot = path.resolve(__dirname, '..', '..');
const INSTANCE = sanitize(process.env.KR_INSTANCE || path.basename(repoRoot));

// 只杀本实例的 Electron（主进程 + 其 Chromium 子进程命令行都带 --user-data-dir=.../ud-<instance>）
run('pkill', ['-f', `ud-${INSTANCE}`]);

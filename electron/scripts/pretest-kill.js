/*
 * pretest/posttest 钩子：清理**本 worktree 实例**残留的 Electron 测试进程，避免多 worktree 并行互相干扰
 * （AGENTS.md §3.1 规则 12/13，隔离规则见 electron/test/env.mjs）。
 *
 * 做法：启动测试时让 Electron 主进程成为独立进程组；清理时先杀进程组，再按**完整 userData 路径**兜底清理 Chromium 子进程。
 * 这样不会误杀 MyFlicker / Kim / Kit 等其它 Electron 应用，也不会留下孤立的测试图标。
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
const USER_DATA_DIR = path.resolve(process.env.KR_USER_DATA_DIR || path.join(repoRoot, '.kr-test', `ud-${INSTANCE}`));
const USER_DATA_MARKER = path.resolve(USER_DATA_DIR);

// 只清本 worktree 的测试 Electron：主进程独立进程组 + 完整 userData 路径双重兜底。
// 不使用 "pkill -f Electron"、"pkill -f remote-debugging-port" 等全局模式。
// ⚠️ 匹配串以 `--` 开头，必须显式给 `--` 分隔符，否则 pkill 会把匹配串当选项：
//    `pkill -TERM -f --user-data-dir=/x` → "-TERM: illegal option -- -"（exit 2，一个进程都不会被杀）
run('pkill', ['-TERM', '-f', '--', `--user-data-dir=${USER_DATA_MARKER}`]);
run('pkill', ['-KILL', '-f', '--', `--user-data-dir=${USER_DATA_MARKER}`]);

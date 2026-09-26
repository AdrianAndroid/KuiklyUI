/*
 * pretest/posttest 钩子：清理**本 worktree 实例**残留的测试进程，避免多个 worktree 并行时互相干扰。
 *
 * 隔离规则见 electron/test/env.mjs（单一事实来源）。本文件保持同样的实例/slot 计算：
 *   - 只杀带本实例 userData 标记（`ud-<instance>`）的 Electron（所有套件都传 --user-data-dir）
 *   - 只释放本实例的外部网关端口（18090 + slot*100，或 KR_GATEWAY_PORT/SFTP_GATEWAY_PORT/GATEWAY_URL 指定）
 *
 * 切勿再使用无差别的 `pkill -f "Kuikly SFTP.app"` / `pkill -f "remote-debugging-port="` /
 * `osascript quit app "Kuikly SFTP"` —— 那些会杀掉其它 worktree 正在跑的用例（AGENTS.md §3.1 规则 13）。
 */
'use strict';

const path = require('node:path');
const fs = require('node:fs');
const { execFileSync } = require('node:child_process');

const electronDir = path.resolve(__dirname, '..');
const repoRoot = path.resolve(electronDir, '..');

function sanitize(s) {
  return String(s).toLowerCase().replace(/[^a-z0-9_-]+/g, '-').replace(/^-+|-+$/g, '') || 'default';
}
function fnv1a(s) {
  let h = 2166136261;
  for (let i = 0; i < s.length; i++) { h ^= s.charCodeAt(i); h = Math.imul(h, 16777619); }
  return h >>> 0;
}
function isLinkedWorktree(root) {
  try { return fs.statSync(path.join(root, '.git')).isFile(); } catch (e) { return false; }
}

const INSTANCE = sanitize(process.env.KR_INSTANCE || path.basename(repoRoot));
const SLOT = isLinkedWorktree(repoRoot) ? (fnv1a(INSTANCE) % 99) + 1 : 0;

function run(cmd, args) {
  try { execFileSync(cmd, args, { stdio: 'ignore' }); } catch (e) { /* 无匹配进程/命令缺失均可忽略 */ }
}

// 1) 本实例的 Electron（所有套件都传 --user-data-dir=<repo>/.kr-test/ud-<instance>）
run('pkill', ['-f', `ud-${INSTANCE}`]);

// 2) 本实例的外部网关端口（若被占用则释放）
let gatewayPort = Number(process.env.KR_GATEWAY_PORT || process.env.SFTP_GATEWAY_PORT || 0);
if (!gatewayPort && process.env.GATEWAY_URL) {
  const m = String(process.env.GATEWAY_URL).match(/:(\d+)/);
  if (m) gatewayPort = Number(m[1]);
}
if (!gatewayPort) gatewayPort = 18090 + SLOT * 100;
try {
  const pids = execFileSync('lsof', ['-ti', `tcp:${gatewayPort}`], { encoding: 'utf8' }).trim();
  if (pids) for (const pid of pids.split(/\s+/)) run('kill', ['-9', pid]);
} catch (e) { /* 端口未被占用 */ }

// 说明：不再无差别清理 userData / 本地夹具 —— 它们是**实例私有**的（.kr-test/ud-<instance>、
// local-<instance>），不会跨 worktree 干扰；保留可让 Electron 缓存预热、启动更快。
// 单个套件的数据清理由用例自身的 cleanup 负责（如 features/dual 的夹具自建自清）。

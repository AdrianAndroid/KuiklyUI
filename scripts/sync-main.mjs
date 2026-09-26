#!/usr/bin/env node
/*
 * sync-main.mjs —— 把当前 worktree 的成果同步到主工作分支 `zhaojian`（AGENTS.md §3 规则 7）。
 *
 * 用法：node scripts/sync-main.mjs
 * 覆盖：KR_MAIN_BRANCH=xxx node scripts/sync-main.mjs
 *
 * 行为：
 *   1) 当前分支 == zhaojian        → git push origin zhaojian
 *   2) 当前分支 == 单独分支：
 *      a. 若本地主 clone 有未提交改动：
 *         - 改动开始 < 30min → 退出码 2（等待，稍后重试；可用 schedule_wakeup）
 *         - 改动开始 ≥ 30min → 退出码 3（按规则本轮先不同步）
 *      b. 主 clone 干净 → fetch → 若 origin/zhaojian 是 HEAD 祖先则 push HEAD:zhaojian，
 *         再在干净主 clone 上 `merge --ff-only` 快进本地 zhaojian。
 *
 * 退出码：0 已同步 / 1 本 worktree 有未提交改动 / 2 需等待 / 3 超过 30min 跳过 / 4 分叉需人工 / 5 其它错误
 *
 * 绝不强行合并：只做 fast-forward；分叉/冲突一律报人工处理。
 */
import { execFileSync } from 'node:child_process';
import path from 'node:path';
import fs from 'node:fs';

const MAIN_BRANCH = process.env.KR_MAIN_BRANCH || 'zhaojian';
const WAIT_MIN = 30;
const DRY = process.env.KR_SYNC_DRY === '1' || process.argv.includes('--dry-run');
if (DRY) console.log('[sync-main] DRY-RUN：只检查与打印，不 push / 不 merge');

function git(args, opts = {}) {
  return execFileSync('git', args, { encoding: 'utf8', ...opts }).trim();
}
function gitOk(args) {
  try { execFileSync('git', args, { stdio: 'ignore' }); return true; } catch (e) { return false; }
}
function log(msg) { console.log('[sync-main] ' + msg); }
function die(code, msg) { console.error('[sync-main] ' + msg); process.exit(code); }

/* ---------- 基本校验 ---------- */
const repoRoot = git(['rev-parse', '--show-toplevel']);
const current = git(['rev-parse', '--abbrev-ref', 'HEAD']);

const dirtyHere = git(['status', '--porcelain']);
if (dirtyHere) die(1, `当前 worktree 有未提交改动，请先提交：${repoRoot}`);

/* ---------- 1) 当前就是主分支 ---------- */
if (current === MAIN_BRANCH) {
  if (DRY) { log(`[dry] 当前分支就是 ${MAIN_BRANCH}，将 push origin ${MAIN_BRANCH}`); process.exit(0); }
  log(`当前分支就是 ${MAIN_BRANCH}，直接推送 origin/${MAIN_BRANCH}`);
  if (!gitOk(['push', 'origin', MAIN_BRANCH])) die(5, `push origin ${MAIN_BRANCH} 失败（可能是分叉/无权限）`);
  log(`已同步：origin/${MAIN_BRANCH} ✓`);
  process.exit(0);
}

/* ---------- 2) 单独分支：找本地 zhaojian 主 clone ---------- */
function findMainWorktree() {
  const out = git(['worktree', 'list', '--porcelain']);
  const blocks = out.split('\n\n');
  for (const b of blocks) {
    const lines = b.split('\n');
    const w = lines.find((l) => l.startsWith('worktree '));
    const br = lines.find((l) => l.startsWith('branch '));
    if (w && br && br.trim() === `branch refs/heads/${MAIN_BRANCH}`) {
      return w.slice('worktree '.length).trim();
    }
  }
  return null;
}

const mainPath = findMainWorktree();
if (!mainPath) {
  die(4, `未找到检出 ${MAIN_BRANCH} 的本地 worktree；请人工同步（或先 git fetch origin ${MAIN_BRANCH}）`);
}
log(`主 worktree（${MAIN_BRANCH}）= ${mainPath}`);

/* ---------- 2a) 主 clone 未提交改动 → 等待 / 跳过 ---------- */
const mainDirty = git(['-C', mainPath, 'status', '--porcelain', '--untracked-files=all']);
if (mainDirty) {
  // 用「最近一次改动的文件 mtime」估计未提交改动的起始时间
  let newest = 0;
  for (const line of mainDirty.split('\n')) {
    const p = line.slice(3).trim().replace(/^"|"$/g, '');
    const abs = path.isAbsolute(p) ? p : path.join(mainPath, p);
    try { const st = fs.lstatSync(abs); newest = Math.max(newest, st.mtimeMs); }
    catch (e) { /* 删除/重命名目标不存在：忽略 */ }
  }
  if (newest === 0) newest = Date.now(); // 只有删除等无法取 mtime 的情况：视为刚发生
  const ageMin = (Date.now() - newest) / 60000;
  if (ageMin < WAIT_MIN) {
    die(2, `主 clone 有未提交改动且开始不到 ${WAIT_MIN} 分钟（约 ${ageMin.toFixed(1)} 分钟）→ 先等待，稍后重试`);
  }
  die(3, `主 clone 未提交改动已超过 ${WAIT_MIN} 分钟（约 ${ageMin.toFixed(1)} 分钟）→ 按规则本轮先不同步`);
}

/* ---------- 2b) 主 clone 干净 → 同步 ---------- */
gitOk(['fetch', 'origin', MAIN_BRANCH]);

// 远端 zhaojian 必须是 HEAD 的祖先，才允许 fast-forward 推送
if (!gitOk(['merge-base', '--is-ancestor', `origin/${MAIN_BRANCH}`, 'HEAD'])) {
  die(4, `origin/${MAIN_BRANCH} 与当前分支分叉（非 fast-forward）；请人工 rebase/merge 后再同步`);
}
if (DRY) {
  log(`[dry] 将推送 origin/${MAIN_BRANCH} ← ${current}`);
} else {
  if (!gitOk(['push', 'origin', `HEAD:${MAIN_BRANCH}`])) {
    die(4, `push HEAD:${MAIN_BRANCH} 失败（远端可能已前进）；请人工处理`);
  }
  log(`已推送：origin/${MAIN_BRANCH} ← ${current} ✓`);
}

// 本地主 clone 快进（仅 fast-forward，绝不产生 merge commit / 冲突）
const ourHead = git(['rev-parse', 'HEAD']);
if (gitOk(['-C', mainPath, 'merge-base', '--is-ancestor', 'HEAD', ourHead])) {
  if (DRY) {
    log(`[dry] 将快进本地主 clone ${MAIN_BRANCH} → ${ourHead.slice(0, 8)}`);
  } else if (gitOk(['-C', mainPath, 'merge', '--ff-only', ourHead])) {
    log(`已快进本地主 clone ${MAIN_BRANCH} → ${ourHead.slice(0, 8)} ✓`);
  } else {
    log('警告：本地主 clone 快进失败（已推送远端，可稍后手动 pull）');
  }
} else {
  log('警告：本地主 clone 与当前分支分叉，未改动它（远端已同步，请人工 pull/merge）');
}

process.exit(0);

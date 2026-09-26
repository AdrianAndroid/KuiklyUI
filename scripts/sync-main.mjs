#!/usr/bin/env node
/*
 * sync-main.mjs —— 与主工作分支 `zhaojian` 同步（AGENTS.md §3 规则 7）。
 *
 * 两种模式：
 *   node scripts/sync-main.mjs            # 成果同步：把当前 worktree 的成果同步到 zhaojian
 *   node scripts/sync-main.mjs --pull     # 开发前同步：先把最新 zhaojian 合入当前分支，再开发
 * 覆盖：KR_MAIN_BRANCH=xxx（默认 zhaojian）、KR_SYNC_WAIT_MIN（默认 30 分钟）、
 *       KR_SYNC_PULL_MODE=merge|rebase（默认 rebase）、--dry-run 预演。
 *
 * 成果同步（默认）：
 *   1) 当前分支 == zhaojian → git push origin zhaojian
 *   2) 单独分支 → 若本地主 clone 有未提交改动：<30min 退出 2（等待）；≥30min 退出 3（跳过）；
 *      干净则 push HEAD:zhaojian + 快进本地主 clone（仅 fast-forward）
 *
 * 开发前同步（--pull）：
 *   以「较新的 zhaojian（本地主 clone 或 origin/zhaojian）」为基线，把当前分支 rebase（或 merge）上去；
 *   冲突则 abort 并报人工（绝不强行合并）。
 *
 * 退出码：0 成功 / 1 本 worktree 有未提交改动 / 2 需等待(<30min) / 3 超过 30min 跳过 /
 *         4 分叉需人工 / 5 push 失败 / 6 pull 冲突需人工
 */
import { execFileSync } from 'node:child_process';
import path from 'node:path';
import fs from 'node:fs';

const MAIN_BRANCH = process.env.KR_MAIN_BRANCH || 'zhaojian';
const WAIT_MIN = Number(process.env.KR_SYNC_WAIT_MIN || 30);   // 可覆盖（测试用）
const PULL_MODE = (process.env.KR_SYNC_PULL_MODE || 'rebase').toLowerCase();
const DRY = process.env.KR_SYNC_DRY === '1' || process.argv.includes('--dry-run');
const PULL = process.argv.includes('--pull') || process.argv[2] === 'pull';

function git(args, opts = {}) {
  return execFileSync('git', args, { encoding: 'utf8', ...opts }).trim();
}
function gitOk(args, opts = {}) {
  try { execFileSync('git', args, { stdio: 'ignore', ...opts }); return true; } catch (e) { return false; }
}
function rev(ref) {
  try {
    return execFileSync('git', ['rev-parse', '--verify', ref], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }).trim();
  } catch (e) { return null; }
}
function fetchMain() {
  try { execFileSync('git', ['fetch', 'origin', MAIN_BRANCH], { stdio: 'ignore', timeout: 30000 }); return true; }
  catch (e) { return false; }
}
function log(msg) { console.log('[sync-main] ' + msg); }
function die(code, msg) { console.error('[sync-main] ' + msg); process.exit(code); }

/* ---------- 基本校验（两种模式都要求当前工作区干净） ---------- */
const repoRoot = git(['rev-parse', '--show-toplevel']);
const current = git(['rev-parse', '--abbrev-ref', 'HEAD']);
if (DRY) log('DRY-RUN：只检查与打印，不 push / 不 rebase / 不 merge');
if (git(['status', '--porcelain'])) die(1, `当前 worktree 有未提交改动，请先提交：${repoRoot}`);

/* ================================================================
 * 开发前同步：把较新的 zhaojian 合入当前分支
 * ================================================================ */
function resolveBase() {
  fetchMain(); // best-effort（离线时忽略）
  const localMain = rev(`refs/heads/${MAIN_BRANCH}`);
  const remoteMain = rev(`refs/remotes/origin/${MAIN_BRANCH}`);
  if (localMain && remoteMain) {
    if (gitOk(['merge-base', '--is-ancestor', localMain, remoteMain])) return { base: remoteMain, name: `origin/${MAIN_BRANCH}` };
    if (gitOk(['merge-base', '--is-ancestor', remoteMain, localMain])) return { base: localMain, name: MAIN_BRANCH };
    die(4, `本地 ${MAIN_BRANCH} 与 origin/${MAIN_BRANCH} 分叉；请人工处理后再开发`);
  }
  if (remoteMain) return { base: remoteMain, name: `origin/${MAIN_BRANCH}` };
  if (localMain) return { base: localMain, name: MAIN_BRANCH };
  die(4, `找不到 ${MAIN_BRANCH}（本地与远端都没有）`);
}

function runPull() {
  log(`开发前同步：把最新 ${MAIN_BRANCH} 合入当前分支（${current}）`);
  const { base, name } = resolveBase();

  if (current === MAIN_BRANCH) {
    if (git(['rev-parse', 'HEAD']) === git(['rev-parse', base])) { log(`${MAIN_BRANCH} 已是最新，无需同步`); process.exit(0); }
    if (DRY) { log(`[dry] 将把 ${MAIN_BRANCH} 快进到 ${name}`); process.exit(0); }
    if (!gitOk(['merge', '--ff-only', base])) die(4, `本地 ${MAIN_BRANCH} 无法快进到 ${name}（分叉）；请人工处理`);
    log(`已快进 ${MAIN_BRANCH} → ${name} ✓`);
    process.exit(0);
  }

  if (gitOk(['merge-base', '--is-ancestor', base, 'HEAD'])) {
    log(`当前分支已包含最新 ${name}，无需同步`);
    process.exit(0);
  }
  if (DRY) { log(`[dry] 将把 ${name} 以 ${PULL_MODE} 方式合入 ${current}`); process.exit(0); }

  if (PULL_MODE === 'merge') {
    if (!gitOk(['merge', '--no-edit', base])) die(6, `merge ${name} 冲突；请人工解决（git merge --abort 可放弃）`);
  } else {
    if (!gitOk(['rebase', base])) {
      gitOk(['rebase', '--abort']);   // 冲突：回退，保持干净
      die(6, `rebase 到 ${name} 冲突，已 abort；请人工 rebase 后再开发`);
    }
  }
  log(`已同步 ${name} → ${current} ✓（HEAD ${git(['rev-parse', '--short', 'HEAD'])}）`);
  process.exit(0);
}

/* ================================================================
 * 成果同步：把当前 worktree 的成果同步到 zhaojian
 * ================================================================ */
function findMainWorktree() {
  const out = git(['worktree', 'list', '--porcelain']);
  for (const b of out.split('\n\n')) {
    const lines = b.split('\n');
    const w = lines.find((l) => l.startsWith('worktree '));
    const br = lines.find((l) => l.startsWith('branch '));
    if (w && br && br.trim() === `branch refs/heads/${MAIN_BRANCH}`) return w.slice('worktree '.length).trim();
  }
  return null;
}

function runSync() {
  if (current === MAIN_BRANCH) {
    if (DRY) { log(`[dry] 当前分支就是 ${MAIN_BRANCH}，将 push origin ${MAIN_BRANCH}`); process.exit(0); }
    log(`当前分支就是 ${MAIN_BRANCH}，直接推送 origin/${MAIN_BRANCH}`);
    if (!gitOk(['push', 'origin', MAIN_BRANCH])) die(5, `push origin ${MAIN_BRANCH} 失败（可能是分叉/无权限）`);
    log(`已同步：origin/${MAIN_BRANCH} ✓`);
    process.exit(0);
  }

  const mainPath = findMainWorktree();
  if (!mainPath) die(4, `未找到检出 ${MAIN_BRANCH} 的本地 worktree；请人工同步（或先 git fetch origin ${MAIN_BRANCH}）`);
  log(`主 worktree（${MAIN_BRANCH}）= ${mainPath}`);

  // 主 clone 未提交改动 → 等待 / 跳过
  const mainDirty = git(['-C', mainPath, 'status', '--porcelain', '--untracked-files=all']);
  if (mainDirty) {
    let newest = 0;
    for (const line of mainDirty.split('\n')) {
      const p = line.slice(3).trim().replace(/^"|"$/g, '');
      const abs = path.isAbsolute(p) ? p : path.join(mainPath, p);
      try { const st = fs.lstatSync(abs); newest = Math.max(newest, st.mtimeMs); } catch (e) { /* 删除目标不存在 */ }
    }
    if (newest === 0) newest = Date.now(); // 只有删除等无法取 mtime：视为刚发生
    const ageMin = (Date.now() - newest) / 60000;
    if (ageMin < WAIT_MIN) die(2, `主 clone 有未提交改动且开始不到 ${WAIT_MIN} 分钟（约 ${ageMin.toFixed(1)} 分钟）→ 先等待，稍后重试`);
    die(3, `主 clone 未提交改动已超过 ${WAIT_MIN} 分钟（约 ${ageMin.toFixed(1)} 分钟）→ 按规则本轮先不同步`);
  }

  fetchMain();

  if (!gitOk(['merge-base', '--is-ancestor', `origin/${MAIN_BRANCH}`, 'HEAD'])) {
    die(4, `origin/${MAIN_BRANCH} 与当前分支分叉（非 fast-forward）；请人工 rebase/merge 后再同步`);
  }
  if (DRY) {
    log(`[dry] 将推送 origin/${MAIN_BRANCH} ← ${current}`);
  } else {
    if (!gitOk(['push', 'origin', `HEAD:${MAIN_BRANCH}`])) die(4, `push HEAD:${MAIN_BRANCH} 失败（远端可能已前进）；请人工处理`);
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
}

if (PULL) runPull(); else runSync();

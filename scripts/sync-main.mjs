#!/usr/bin/env node
/*
 * sync-main.mjs —— 与主工作分支 `zhaojian` **双向**同步（AGENTS.md §3 规则 7）。
 *
 * 三种模式：
 *   node scripts/sync-main.mjs            # 成果同步：当前分支成果 → zhaojian（推送 + 快进本地主 clone）
 *   node scripts/sync-main.mjs --pull     # 拉取同步：zhaojian 最新 → 当前分支（rebase/merge）
 *   node scripts/sync-main.mjs --both     # 双向：先 --pull（拉最新），再成果同步（推回去）——一键保证一致
 * 覆盖：KR_MAIN_BRANCH=xxx（默认 zhaojian）、KR_SYNC_WAIT_MIN（默认 30 分钟）、
 *       KR_SYNC_PULL_MODE=merge|rebase（默认 rebase）、--dry-run 预演。
 *
 * 铁律（AGENTS.md §3 规则 7）：
 *   - 双向同步，确保所有分支提交一致；
 *   - 遇冲突：先人工解决冲突 → 重跑受影响用例验证（构建/编译也要过）→ 才同步；
 *     未解决冲突或未验证通过绝不推送。
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
const flags = new Set(process.argv.slice(2));
const DRY = process.env.KR_SYNC_DRY === '1' || flags.has('--dry-run');
const PULL = flags.has('--pull') || flags.has('pull');
const BOTH = flags.has('--both') || flags.has('both');

function git(args, opts = {}) { return execFileSync('git', args, { encoding: 'utf8', ...opts }).trim(); }
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
function fail(code, msg) { console.error('[sync-main] ' + msg); return code; }

/* ---------- 基本校验（所有模式都要求当前工作区干净） ---------- */
const repoRoot = git(['rev-parse', '--show-toplevel']);
const current = git(['rev-parse', '--abbrev-ref', 'HEAD']);
if (DRY) log('DRY-RUN：只检查与打印，不 push / 不 rebase / 不 merge');
if (git(['status', '--porcelain'])) {
  fail(1, `当前 worktree 有未提交改动，请先提交：${repoRoot}`);
  process.exit(1);
}

/* ================================================================
 * 拉取同步：把较新的 zhaojian 合入当前分支（保证拿到主干最新提交）
 * ================================================================ */
function resolveBase() {
  fetchMain(); // best-effort（离线时忽略）
  const localMain = rev(`refs/heads/${MAIN_BRANCH}`);
  const remoteMain = rev(`refs/remotes/origin/${MAIN_BRANCH}`);
  if (localMain && remoteMain) {
    if (gitOk(['merge-base', '--is-ancestor', localMain, remoteMain])) return { base: remoteMain, name: `origin/${MAIN_BRANCH}` };
    if (gitOk(['merge-base', '--is-ancestor', remoteMain, localMain])) return { base: localMain, name: MAIN_BRANCH };
    return { error: fail(4, `本地 ${MAIN_BRANCH} 与 origin/${MAIN_BRANCH} 分叉；请人工处理后再开发`) };
  }
  if (remoteMain) return { base: remoteMain, name: `origin/${MAIN_BRANCH}` };
  if (localMain) return { base: localMain, name: MAIN_BRANCH };
  return { error: fail(4, `找不到 ${MAIN_BRANCH}（本地与远端都没有）`) };
}

function pull() {
  log(`拉取同步：把最新 ${MAIN_BRANCH} 合入当前分支（${current}）`);
  const r = resolveBase();
  if (r.error !== undefined) return r.error;
  const { base, name } = r;

  if (current === MAIN_BRANCH) {
    if (git(['rev-parse', 'HEAD']) === git(['rev-parse', base])) { log(`${MAIN_BRANCH} 已是最新，无需同步`); return 0; }
    if (DRY) { log(`[dry] 将把 ${MAIN_BRANCH} 快进到 ${name}`); return 0; }
    if (!gitOk(['merge', '--ff-only', base])) return fail(4, `本地 ${MAIN_BRANCH} 无法快进到 ${name}（分叉）；请人工处理`);
    log(`已快进 ${MAIN_BRANCH} → ${name} ✓`);
    return 0;
  }

  if (gitOk(['merge-base', '--is-ancestor', base, 'HEAD'])) { log(`当前分支已包含最新 ${name}，无需同步`); return 0; }
  if (DRY) { log(`[dry] 将把 ${name} 以 ${PULL_MODE} 方式合入 ${current}`); return 0; }

  if (PULL_MODE === 'merge') {
    if (!gitOk(['merge', '--no-edit', base])) {
      return fail(6, `merge ${name} 冲突；请人工解决冲突 → 重跑受影响用例验证 → 再 commit（git merge --abort 可放弃）。规则见 AGENTS.md §3 规则 7`);
    }
  } else {
    if (!gitOk(['rebase', base])) {
      gitOk(['rebase', '--abort']);   // 冲突：回退，保持干净
      return fail(6, `rebase 到 ${name} 冲突，已 abort；请人工 rebase 解决冲突 → 重跑受影响用例验证 → 再同步（规则 AGENTS.md §3 规则 7）`);
    }
  }
  log(`已同步 ${name} → ${current} ✓（HEAD ${git(['rev-parse', '--short', 'HEAD'])}）`);
  return 0;
}

/* ================================================================
 * 成果同步：把当前 worktree 成果同步到 zhaojian，并快进本地主 clone
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

function sync() {
  if (current === MAIN_BRANCH) {
    if (DRY) { log(`[dry] 当前分支就是 ${MAIN_BRANCH}，将 push origin ${MAIN_BRANCH}`); return 0; }
    log(`当前分支就是 ${MAIN_BRANCH}，直接推送 origin/${MAIN_BRANCH}`);
    if (!gitOk(['push', 'origin', MAIN_BRANCH])) return fail(5, `push origin ${MAIN_BRANCH} 失败（可能是分叉/无权限）`);
    log(`已同步：origin/${MAIN_BRANCH} ✓`);
    return 0;
  }

  const mainPath = findMainWorktree();
  if (!mainPath) return fail(4, `未找到检出 ${MAIN_BRANCH} 的本地 worktree；请人工同步（或先 git fetch origin ${MAIN_BRANCH}）`);
  log(`主 worktree（${MAIN_BRANCH}）= ${mainPath}`);

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
    if (ageMin < WAIT_MIN) return fail(2, `主 clone 有未提交改动且开始不到 ${WAIT_MIN} 分钟（约 ${ageMin.toFixed(1)} 分钟）→ 先等待，稍后重试`);
    return fail(3, `主 clone 未提交改动已超过 ${WAIT_MIN} 分钟（约 ${ageMin.toFixed(1)} 分钟）→ 按规则本轮先不同步`);
  }

  fetchMain();

  if (!gitOk(['merge-base', '--is-ancestor', `origin/${MAIN_BRANCH}`, 'HEAD'])) {
    return fail(4, `origin/${MAIN_BRANCH} 与当前分支分叉（非 fast-forward）；请先 \`node scripts/sync-main.mjs --pull\` 解决后再同步`);
  }
  if (DRY) {
    log(`[dry] 将推送 origin/${MAIN_BRANCH} ← ${current}`);
  } else {
    if (!gitOk(['push', 'origin', `HEAD:${MAIN_BRANCH}`])) return fail(4, `push HEAD:${MAIN_BRANCH} 失败（远端可能已前进）；请人工处理`);
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
  return 0;
}

/* ---------- 调度 ---------- */
if (BOTH) {
  const c1 = pull();
  if (c1 !== 0) process.exit(c1);
  process.exit(sync());
} else if (PULL) {
  process.exit(pull());
} else {
  process.exit(sync());
}

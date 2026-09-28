#!/usr/bin/env node
/*
 * 校验「测试客户端是否只在启动时那个桌面（= Agent 所在桌面）」—— macOS 多桌面 / Spaces。
 *
 * 背景：macOS 上新建窗口落在**创建瞬间的活动桌面**，所以从 Agent 终端启动的测试客户端
 * 默认就与 Agent 同桌面。但这一点以前只能靠肉眼确认；本脚本用系统的客观数据核对：
 *   1. Electron 主进程在测试态把「启动桌面 ID + 各窗口 CGWindowID」写进
 *      <userData>/test-window-space.json（见 electron/main.js）；
 *   2. 本脚本读 `defaults read com.apple.spaces` 的「桌面 → 窗口 ID」映射（Dock 维护，无需额外权限），
 *      核对每个窗口**只属于一个桌面**，且该桌面 == 启动桌面。
 *
 * 判定标准：
 *   ✅ PASS：所有已记录窗口都只在一个桌面，且等于启动桌面
 *   ❌ FAIL：某窗口出现在多个桌面（例如误用了 setVisibleOnAllWorkspaces → 每个桌面都弹）
 *            或窗口所在桌面 ≠ 启动桌面
 *   ⚠️  WARN：窗口未出现在任何桌面列表（窗口尚未显示 / 已关闭）——默认不算失败，`--strict` 下算失败
 *
 * 用法（在 electron/ 下）：
 *   npm run check:space                      # 用 .kr-test/ud-<instance> 里的记录
 *   KR_USER_DATA_DIR=/tmp/xx npm run check:space
 *   node scripts/check-window-space.mjs --strict
 *
 * 局限：macOS 没有公开 API 能把窗口**移动**到指定桌面，故测试运行期间用户切走桌面后新建的窗口
 * 会落在当时的活动桌面（本脚本会如实报出来，而不是假装一直同桌面）。
 */
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(__dirname, '..', '..');
const STRICT = process.argv.includes('--strict');

/** 与 electron/test/env.mjs 一致的默认 userData 位置 */
function defaultUserDataDir() {
  const name = process.env.KR_INSTANCE || path.basename(REPO_ROOT);
  return path.join(REPO_ROOT, '.kr-test', `ud-${name}`);
}

const USER_DATA_DIR = path.resolve(process.env.KR_USER_DATA_DIR || defaultUserDataDir());
const INFO_FILE = path.join(USER_DATA_DIR, 'test-window-space.json');

function readSpaces() {
  const out = execFileSync('defaults', ['read', 'com.apple.spaces'], { encoding: 'utf8', timeout: 5000 });
  const current = /"Current Space"[\s\S]{0,200}?ManagedSpaceID = (\d+)/.exec(out);
  const listed = [...out.matchAll(/ManagedSpaceID = (\d+);[\s\S]{0,200}?uuid = "([0-9A-F-]+)"/g)]
    .map((m) => ({ id: m[1], uuid: m[2] }));
  const uuidToId = new Map(listed.map((s) => [s.uuid, s.id]));
  const byWindow = new Map();
  for (const m of out.matchAll(/name = "([0-9A-F-]+)";\s*windows\s*=\s*\(([\s\S]*?)\);/g)) {
    const spaceId = uuidToId.get(m[1]) || '?';
    for (const raw of m[2].split(',')) {
      const id = raw.trim();
      if (!id) continue;
      if (!byWindow.has(id)) byWindow.set(id, []);
      byWindow.get(id).push(spaceId);
    }
  }
  return { currentSpaceId: current ? current[1] : null, spaces: [...new Set(listed.map((s) => s.id))], byWindow };
}

function main() {
  if (process.platform !== 'darwin') {
    console.log('[check:space] 非 macOS，跳过（多桌面仅 macOS 相关）');
    process.exit(0);
  }
  if (!fs.existsSync(INFO_FILE)) {
    console.log(`[check:space] 没有窗口记录：${INFO_FILE}`);
    console.log('              需先用测试态启动应用（KR_TEST_NOFOCUS=1，测试套件默认开启）。');
    process.exit(1);
  }

  const info = JSON.parse(fs.readFileSync(INFO_FILE, 'utf8'));
  const { currentSpaceId, spaces, byWindow } = readSpaces();

  const entries = [];
  if (info.windows.main) entries.push({ label: '主窗口', id: String(info.windows.main) });
  for (const [i, id] of (info.windows.player || []).entries()) {
    entries.push({ label: `播放窗口${i + 1}`, id: String(id) });
  }

  console.log(`[check:space] instance=${info.instance || '(无)'} 启动桌面=${info.launchSpaceId || '未知'}` +
    ` 当前桌面=${currentSpaceId || '未知'} 系统桌面=[${spaces.join(', ')}]`);

  let failed = 0;
  let warned = 0;
  for (const { label, id } of entries) {
    const ownerSpaces = byWindow.get(id);
    if (!ownerSpaces || ownerSpaces.length === 0) {
      warned += 1;
      console.log(`  ⚠️  ${label} (id=${id})：未出现在任何桌面列表（可能未显示/已关闭）`);
      continue;
    }
    if (ownerSpaces.length > 1) {
      failed += 1;
      console.log(`  ❌ ${label} (id=${id})：出现在 ${ownerSpaces.length} 个桌面 [${ownerSpaces.join(', ')}]` +
        ' → 疑似误用 setVisibleOnAllWorkspaces(true)');
      continue;
    }
    const spaceId = ownerSpaces[0];
    if (info.launchSpaceId && spaceId !== String(info.launchSpaceId)) {
      failed += 1;
      console.log(`  ❌ ${label} (id=${id})：所在桌面=${spaceId}，但启动桌面=${info.launchSpaceId}` +
        '（多因测试运行期间用户切换了桌面后新建窗口）');
      continue;
    }
    console.log(`  ✅ ${label} (id=${id})：只在桌面 ${spaceId}（= 启动桌面 = Agent 所在桌面）`);
  }

  if (entries.length === 0) {
    console.log('  ⚠️  没有任何窗口记录');
    process.exit(1);
  }
  if (failed > 0 || (STRICT && warned > 0)) {
    console.log(`[check:space] FAIL（失败 ${failed} 项，警告 ${warned} 项）`);
    process.exit(1);
  }
  console.log(`[check:space] PASS（窗口 ${entries.length} 个，警告 ${warned} 项）`);
  process.exit(0);
}

main();

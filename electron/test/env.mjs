/*
 * 并行 worktree 测试隔离 —— 单一事实来源（所有套件必须从这里取端口/数据目录/夹具路径）
 *
 * 背景（AGENTS.md §3.1 规则 13）：多个 Agent Manager worktree 会同时跑 Electron 测试。
 * 构建产物（electron/resources、demo/h5App/build、electron/test/artifacts）本身按 worktree 隔离，
 * 但**运行期资源是全局共享的**，不隔离就会互相干扰：
 *   1) CDP 调试端口写死 → 端口撞车 / 连到别的 worktree 的 app
 *   2) Electron userData 共享 → 连接库/收藏/播放历史串数据
 *   3) 外部网关端口写死 → EADDRINUSE
 *   4) 远端/本地夹具同名 → 并行互删
 *
 * 隔离策略：用 `KR_INSTANCE`（默认取仓库/worktree 目录名）作为实例标识，派生四类资源：
 *   - CDP 端口：每个 suite 一个基址（9330~9380，步进 10）+ 实例 slot*100，互不重叠
 *   - userData：<repo>/.kr-test/ud-<instance>（经 `--user-data-dir` 传入，隔离网关数据/连接/历史）
 *   - 本地文件根：<repo>/.kr-test/local-<instance>（经 `KR_LOCAL_ROOT` 传给主进程，双栏本地栏用）
 *   - 外部网关：18090 + slot*100（用 `npm run gateway` 按同一公式启动）
 *
 * 兼容性：主 clone 不是 linked worktree（`.git` 是目录）→ slot=0，端口与历史行为完全一致。
 * 所有套件统一 `import` 本文件，保证规则一致；不要在各套件里再写死端口/目录。
 */
import path from 'node:path';
import fs from 'node:fs';
import net from 'node:net';
import { execFileSync, spawn } from 'node:child_process';

const here = path.dirname(new URL(import.meta.url).pathname);
export const ELECTRON_DIR = path.resolve(here, '..');
export const REPO_ROOT = path.resolve(ELECTRON_DIR, '..');

function sanitize(s) {
  return String(s).toLowerCase().replace(/[^a-z0-9_-]+/g, '-').replace(/^-+|-+$/g, '') || 'default';
}

/** 实例标识：优先 KR_INSTANCE，否则取仓库/worktree 目录名 */
export const INSTANCE = sanitize(process.env.KR_INSTANCE || path.basename(REPO_ROOT));

/** linked worktree 判定：`git worktree` 的 .git 是文件；主 clone 是目录 */
function isLinkedWorktree(repoRoot) {
  try { return fs.statSync(path.join(repoRoot, '.git')).isFile(); } catch (e) { return false; }
}

function fnv1a(s) {
  let h = 2166136261;
  for (let i = 0; i < s.length; i++) { h ^= s.charCodeAt(i); h = Math.imul(h, 16777619); }
  return h >>> 0;
}

/** 端口 slot：主 clone = 0（保持旧端口），linked worktree = 1..99（稳定、几乎不撞） */
export const SLOT = isLinkedWorktree(REPO_ROOT) ? (fnv1a(INSTANCE) % 99) + 1 : 0;

/** 每个 suite 的 CDP 基址（同一实例内互不重叠；加 slot*100 后跨实例也不撞） */
const SUITE_BASE = {
  smoke: 9330,
  dual: 9340,
  player: 9350,
  text: 9360,
  terminal: 9370,
  features: 9380,
};

/** 取某套件的 CDP 调试端口；可用 KR_CDP_PORT 强制覆盖（单套件调试用） */
export function cdpPort(suite) {
  const forced = Number(process.env.KR_CDP_PORT);
  if (forced) return forced;
  const base = SUITE_BASE[suite];
  if (!base) throw new Error('[kr-test-env] unknown suite: ' + suite);
  return base + SLOT * 100;
}

export const TEST_ROOT = path.resolve(process.env.KR_TEST_ROOT || path.join(REPO_ROOT, '.kr-test'));
export const USER_DATA_DIR = path.join(TEST_ROOT, `ud-${INSTANCE}`);
export const LOCAL_ROOT = path.resolve(process.env.KR_LOCAL_ROOT || path.join(TEST_ROOT, `local-${INSTANCE}`));

/** 外部网关（fixture 建目录/上传用）：与实例绑定的端口，避免 EADDRINUSE */
export const GATEWAY_PORT = Number(
  process.env.KR_GATEWAY_PORT || process.env.SFTP_GATEWAY_PORT || (18090 + SLOT * 100)
);
export const GATEWAY_URL = process.env.GATEWAY_URL || `http://127.0.0.1:${GATEWAY_PORT}`;

/** 远端（测试机）账号与家目录：默认共享，但夹具名带实例前缀避免互删 */
export const SFTP_HOME = process.env.SFTP_HOME || '/home/zhaojian';
/** 远端夹具命名空间：<home>/kr_<instance>_<name> */
export function remoteFixture(name) {
  return `${SFTP_HOME}/kr_${INSTANCE}_${name}`;
}

/*
 * 内置 SSH/SFTP 夹具服务（自包含端到端测试用，不依赖外部测试机）
 *
 * 背景：内网测试机不可达时，依赖真实 SFTP 的套件会在第一步就失败，功能正确性无法验证。
 * 夹具由 `sftp-gateway/test/fixture-sftp-server.mjs` 提供（ssh2 服务端 + 沙盒目录），
 * 端口同样按实例隔离，避免多 worktree 并行时撞车（AGENTS §3.1 规则 13）。
 */
export const SSH_FIXTURE_PORT = Number(process.env.KR_SSH_FIXTURE_PORT || 19000 + SLOT * 100);
export const FIXTURE_ROOT = path.resolve(process.env.KR_FIXTURE_ROOT || path.join(TEST_ROOT, `sftp-fixture-${INSTANCE}`));
/** 主机密钥持久化：页面连接恒为 TOFU，密钥必须跨运行稳定，否则第二次指纹不匹配 */
export const FIXTURE_KEY = path.join(TEST_ROOT, `sftp-fixture-hostkey-${INSTANCE}`);
export const FIXTURE_USER = 'fixture';
export const FIXTURE_PASS = 'fixture';
export const FIXTURE_HOME = '/home/fixture';

/** 探测某端口是否已有服务在监听（用于复用已在运行的夹具） */
export function isPortListening(port, host = '127.0.0.1', timeout = 600) {
  return new Promise((resolve) => {
    const sock = net.connect({ port, host });
    const done = (v) => {
      try { sock.destroy(); } catch (e) { /* ignore */ }
      resolve(v);
    };
    sock.setTimeout(timeout);
    sock.on('connect', () => done(true));
    sock.on('timeout', () => done(false));
    sock.on('error', () => done(false));
  });
}

/** 建好本实例目录（userData / 本地文件根 / 夹具根），幂等 */
export function ensureDirs() {
  fs.mkdirSync(USER_DATA_DIR, { recursive: true });
  fs.mkdirSync(LOCAL_ROOT, { recursive: true });
  fs.mkdirSync(FIXTURE_ROOT, { recursive: true });
}

/**
 * 子进程环境：注入实例隔离变量（child 主进程据此设置窗口标题 / 本地文件根）。
 * 注意：调用方仍需删除 ELECTRON_RUN_AS_NODE（见各套件）。
 *
 * KR_TEST_NOFOCUS=1（默认）：被测应用用 showInactive 显示窗口，**不抢用户当前窗口/键盘焦点**，
 * 并关闭「遮挡即后台化」（见 electron/main.js 的 TEST_NO_FOCUS 注释）。
 * 需要对比原行为时：`KR_TEST_NOFOCUS=0 npm test`。
 */
export function buildChildEnv() {
  const env = {
    ...process.env,
    KR_INSTANCE: INSTANCE,
    KR_TEST_ROOT: TEST_ROOT,
    KR_USER_DATA_DIR: USER_DATA_DIR,
    KR_LOCAL_ROOT: LOCAL_ROOT,
    KR_TEST_NOFOCUS: process.env.KR_TEST_NOFOCUS === '0' ? '0' : '1',
  };
  // 外部网关（若本套件用）也指向本实例端口，避免误连别的 worktree
  env.SFTP_GATEWAY_PORT = env.SFTP_GATEWAY_PORT || String(GATEWAY_PORT);
  return env;
}

/** 启动参数：CDP 端口 + 本实例 userData（隔离连接/收藏/历史） */
export function cdpArgs(suite, port) {
  return [`--remote-debugging-port=${port || cdpPort(suite)}`, `--user-data-dir=${USER_DATA_DIR}`];
}

/** 打印一次实例信息，便于并行时定位 */
export function logInstance(suite) {
  console.log(`[kr-test] instance=${INSTANCE} slot=${SLOT} suite=${suite} cdp=${cdpPort(suite)} gateway=${GATEWAY_PORT} userData=${USER_DATA_DIR}`);
}

export function buildElectronSpawnOptions(env = buildChildEnv()) {
  const childEnv = { ...env };
  delete childEnv.ELECTRON_RUN_AS_NODE;
  // macOS: 让主进程成为独立进程组，清理时可一次杀掉 renderer/GPU/utility 子进程。
  // detached 不会把测试放到后台，也不改变窗口前台策略。
  return { env: childEnv, detached: process.platform === 'darwin' };
}

/** 启动测试 Electron，并统一使用独立进程组。 */
export function spawnTestElectron(electronBin, args, options = {}) {
  return spawn(electronBin, args, { ...options, ...buildElectronSpawnOptions(options.env) });
}

/** 按本实例的完整 userData 路径兜底清理，避免只杀父进程留下 Chromium 子进程。 */
function killByUserData() {
  const marker = `--user-data-dir=${path.resolve(USER_DATA_DIR)}`;
  // ⚠️ 匹配串以 `--` 开头，必须用 `--` 分隔符，否则 pkill 会把它当成选项：
  //    `pkill -TERM -f --user-data-dir=/x` → "-TERM: illegal option -- -"（exit 2，什么都没杀）
  for (const sig of ['-TERM', '-KILL']) {
    try { execFileSync('pkill', [sig, '-f', '--', marker], { stdio: 'ignore' }); } catch (e) { /* no match */ }
  }
}

/** 立即清掉本实例的测试应用（进程组 + 完整 userData 路径）。 */
export function killInstanceApp(child) {
  try {
    if (child && child.pid && !child.killed) {
      if (process.platform === 'darwin') {
        try { process.kill(-child.pid, 'SIGKILL'); } catch (e) { /* group may already be gone */ }
      }
      try { child.kill('SIGKILL'); } catch (e) { /* ignore */ }
    }
  } catch (e) { /* ignore */ }
  killByUserData();
}

/**
 * 给测试套件挂「退出/信号兜底清理」：即使用例被 watchdog/ctrl-c 强杀，也会杀掉本实例应用。
 * 返回一个可显式调用的清理函数（用例 finally 里调用）。
 */
export function registerCleanup(child) {
  let cleaned = false;
  const cleanup = () => {
    if (cleaned) return;
    cleaned = true;
    killInstanceApp(child);
  };
  process.on('exit', cleanup);
  for (const sig of ['SIGINT', 'SIGTERM', 'SIGHUP']) {
    process.on(sig, () => { cleanup(); process.exit(0); });
  }
  return cleanup;
}

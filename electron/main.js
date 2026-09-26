/*
 * Kuikly SFTP 桌面壳 —— Electron 主进程（**只做宿主**，不含任何业务逻辑）
 *
 * 职责（严格限定，见 devDocs/kuikly-app-development-plan.md §3.3 / §6.8）：
 *   1. 以 utilityProcess 托管 sftp-gateway（SFTP_GATEWAY_PORT=0 → OS 分配端口）
 *   2. 收到网关实际端口后，创建 BrowserWindow 加载既有 H5 产物
 *   3. 通过 preload 注入 window.__SFTP_GATEWAY_URL__（H5 模块无需任何改动）
 *   4. 提供极少量桌面能力（另存为 / 通知）——后续经 Kuikly host Module 调用
 *
 * 禁止：在渲染层开启 nodeIntegration；在本进程实现 SFTP/文件/播放业务逻辑。
 */
'use strict';

const { app, BrowserWindow, utilityProcess, ipcMain, dialog, shell, Notification } = require('electron');

// 常见环境坑：从 VS Code / 某些终端启动时会带 ELECTRON_RUN_AS_NODE=1，
// 此时 Electron 以纯 Node 运行，require('electron') 只返回二进制路径（拿不到 API）。
if (!app) {
  console.error('[electron] 未拿到 Electron API：请清除 ELECTRON_RUN_AS_NODE 后启动，例如');
  console.error('           env -u ELECTRON_RUN_AS_NODE electron .');
  process.exit(1);
}
const path = require('path');

// 并行 worktree 隔离（AGENTS.md §3.1 规则 13）：实例标识来自 KR_INSTANCE，
// 窗口标题带实例名便于区分多个测试应用；userData 指向实例私有目录（隔离连接/收藏/历史/网关数据）。
const INSTANCE = process.env.KR_INSTANCE ? String(process.env.KR_INSTANCE) : '';
const WINDOW_TITLE = INSTANCE ? `Kuikly SFTP [${INSTANCE}]` : 'Kuikly SFTP';
// Debug 构建：开发态（未打包）为 true → 界面显示 DEBUG 标识；打包（dmg）后为 false → 不显示
const IS_DEBUG = !app.isPackaged;
if (process.env.KR_USER_DATA_DIR) {
  try { app.setPath('userData', path.resolve(process.env.KR_USER_DATA_DIR)); }
  catch (e) { console.warn('[electron] 设置 userData 失败：', e.message); }
}

const RES_DIR = path.join(__dirname, 'resources');

/**
 * 网关入口：开发态直接用仓库里的 sftp-gateway（同一份，不复制）；
 * 打包态用 electron-builder extraResources 带进来的 resources/gateway。
 */
function gatewayEntry() {
  return app.isPackaged
    ? path.join(process.resourcesPath, 'gateway', 'server.js')
    : path.join(__dirname, '..', 'sftp-gateway', 'server.js');
}

let mainWindow = null;
let gatewayProc = null;
let gatewayUrl = 'http://127.0.0.1:18090';

/** 启动网关并等待其回报实际端口 */
function startGateway() {
  return new Promise((resolve, reject) => {
    const entry = gatewayEntry();
    // 数据目录必须可写：安装到 /Applications 后 App 包是只读的，故指向 userData
    const dataDir = path.join(app.getPath('userData'), 'gateway-data');
    gatewayProc = utilityProcess.fork(entry, [], {
      env: {
        ...process.env,
        SFTP_GATEWAY_PORT: '0',
        SFTP_GATEWAY_DATA_DIR: dataDir,
        SFTP_GATEWAY_LOCAL_ROOT: LOCAL_ROOT,
      },
      stdio: 'inherit',
    });
    const timer = setTimeout(() => reject(new Error('gateway start timeout')), 15000);
    gatewayProc.on('message', (msg) => {
      if (msg && msg.type === 'listening' && msg.port) {
        clearTimeout(timer);
        resolve('http://127.0.0.1:' + msg.port);
      }
    });
    gatewayProc.on('exit', (code) => {
      if (code !== 0) console.error('[electron] gateway exited with code', code);
    });
  });
}

async function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1180,
    height: 820,
    title: WINDOW_TITLE,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      // 把网关地址传给 preload（sandbox 下 preload 可读 process.argv）
      additionalArguments: ['--gateway=' + gatewayUrl, '--debug=' + (IS_DEBUG ? '1' : '0')],
    },
  });

  // 安全：拦截外部导航与新窗口
  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    if (/^https?:/.test(url)) shell.openExternal(url);
    return { action: 'deny' };
  });
  mainWindow.webContents.on('will-navigate', (e, url) => {
    if (!url.startsWith('file://')) { e.preventDefault(); shell.openExternal(url); }
  });


  // 已知问题：Web 根视图尺寸/layout 在打包态首帧可能不触发，导致 #root 高 0、窗口空白。
  // 页面加载完成后主动派发一次 resize，触发 Kuikly 重新 layout（幂等）。
  const nudgeResize = () => {
    try { mainWindow.webContents.executeJavaScript("try{window.dispatchEvent(new Event('resize'));}catch(e){}").catch(() => { }); } catch (e) { }
  };
  mainWindow.webContents.on('did-finish-load', () => { nudgeResize(); setTimeout(nudgeResize, 400); setTimeout(nudgeResize, 1200); });

  await mainWindow.loadFile(path.join(RES_DIR, 'index.html'), {
    query: { page_name: 'SftpHomePage' },
  });
  nudgeResize();
}

/* ---- 桌面能力（IPC）：仅这些；业务页面应经 Kuikly host Module 调用 ---- */
ipcMain.handle('host:saveFile', async (_e, { suggestedName, base64 }) => {
  const { canceled, filePath } = await dialog.showSaveDialog(mainWindow, { defaultPath: suggestedName });
  if (canceled || !filePath) return { ok: false };
  require('fs').writeFileSync(filePath, Buffer.from(base64 || '', 'base64'));
  return { ok: true, path: filePath };
});

ipcMain.handle('host:notify', async (_e, { title, body }) => {
  if (Notification.isSupported()) new Notification({ title: title || '', body: body || '' }).show();
  return { ok: true };
});

ipcMain.handle('host:getInfo', async () => ({
  version: app.getVersion(),
  platform: process.platform,
  gatewayUrl,
}));

/* ---- 本地文件系统（双栏「本地栏」）：只允许访问 root 之下 ----
 * 默认用户主目录；并行 worktree 测试用 KR_LOCAL_ROOT 指向实例私有目录（隔离夹具/缓存）。 */
const LOCAL_ROOT = process.env.KR_LOCAL_ROOT ? path.resolve(process.env.KR_LOCAL_ROOT) : app.getPath('home');
let localRootReal = null;
/** 校验并返回真实路径：realpath 解析（拒绝主目录内指向外部的符号链接）。 */
async function assertWithinRoot(p) {
  if (localRootReal === null) {
    try { localRootReal = await require('fs').promises.realpath(LOCAL_ROOT); } catch (e) { localRootReal = LOCAL_ROOT; }
  }
  const r = path.resolve(p || LOCAL_ROOT);
  let real = null;
  try {
    real = await require('fs').promises.realpath(r);
  } catch (e) {
    // 目标可能尚不存在（新建目录/文件/重命名目标）：解析父目录真实路径后拼接
    const parent = await require('fs').promises.realpath(path.dirname(r)).catch(() => null);
    if (parent) real = path.join(parent, path.basename(r));
  }
  if (real === null) throw new Error('path outside root: ' + r);
  if (real !== localRootReal && !real.startsWith(localRootReal + path.sep)) {
    throw new Error('path outside root: ' + real);
  }
  return { target: r, real };
}
function entryOf(name, abs) {
  const st = require('fs').lstatSync(abs);
  const type = st.isDirectory() ? 'dir' : st.isFile() ? 'file' : st.isSymbolicLink() ? 'symlink' : 'other';
  return { name, path: abs, type, size: st.size, mtime: st.mtimeMs, mode: st.mode };
}

ipcMain.handle('localfs:home', async () => LOCAL_ROOT);
ipcMain.on('localfs:homeSync', (e) => { e.returnValue = LOCAL_ROOT; });
ipcMain.handle('localfs:list', async (_e, dir) => {
  const { target: d } = await assertWithinRoot(dir || LOCAL_ROOT);
  const ents = await require('fs').promises.readdir(d, { withFileTypes: true });
  const entries = [];
  for (const e of ents) {
    try { entries.push(entryOf(e.name, path.join(d, e.name))); } catch (err) { /* skip unreadable */ }
  }
  return { cwd: d, entries };
});
ipcMain.handle('localfs:stat', async (_e, p) => {
  try { const { real } = await assertWithinRoot(p); return entryOf(path.basename(real), real); } catch (err) { return null; }
});
ipcMain.handle('localfs:mkdir', async (_e, p) => { const { target } = await assertWithinRoot(p); await require('fs').promises.mkdir(target, { recursive: true }); return true; });
ipcMain.handle('localfs:rename', async (_e, from, to) => {
  const { target: f } = await assertWithinRoot(from);
  const { target: t } = await assertWithinRoot(to);
  await require('fs').promises.rename(f, t); return true;
});
ipcMain.handle('localfs:remove', async (_e, p, recursive) => { const { target } = await assertWithinRoot(p); await require('fs').promises.rm(target, { recursive: !!recursive, force: true }); return true; });
// 读写文件（下载落盘 / 远程编辑用；base64 传二进制）
ipcMain.handle('localfs:readFile', async (_e, p) => {
  const { real } = await assertWithinRoot(p);
  const buf = await require('fs').promises.readFile(real);
  return buf.toString('base64');
});
ipcMain.handle('localfs:writeFile', async (_e, p, base64) => {
  // 写入不跟随符号链接：用 resolve 后的路径，且要求父目录已通过 realpath 校验
  const { target, real } = await assertWithinRoot(p);
  const st = await require('fs').promises.lstat(target).catch(() => null);
  if (st && st.isSymbolicLink()) throw new Error('refuse to write through symlink: ' + target);
  await require('fs').promises.mkdir(path.dirname(real), { recursive: true });
  await require('fs').promises.writeFile(target, Buffer.from(base64 || '', 'base64'));
  return true;
});

/* ---- 独立播放窗口（可同时播放多个视频，互不影响主窗口浏览）---- */
const playerWindows = new Set();
const MAX_PLAYER_WINDOWS = 8;

/** 以播放页 pageData 为 query 打开一个新的播放窗口；返回是否成功创建。 */
function createPlayerWindow(playerQuery) {
  if (playerWindows.size >= MAX_PLAYER_WINDOWS) {
    console.warn('[electron] 播放窗口已达上限', MAX_PLAYER_WINDOWS);
    return false;
  }
  const win = new BrowserWindow({
    width: 1024,
    height: 640,
    minWidth: 420,
    minHeight: 260,
    title: WINDOW_TITLE,
    backgroundColor: '#000000',
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      additionalArguments: ['--gateway=' + gatewayUrl, '--debug=' + (IS_DEBUG ? '1' : '0')],
    },
  });
  playerWindows.add(win);
  win.on('closed', () => playerWindows.delete(win));
  win.webContents.setWindowOpenHandler(({ url }) => {
    if (/^https?:/.test(url)) shell.openExternal(url);
    return { action: 'deny' };
  });
  const query = { standalone: '1' };   // 标记独立窗口：只有它才允许「返回=关窗」
  Object.keys(playerQuery || {}).forEach((k) => {
    if (playerQuery[k] != null) query[k] = String(playerQuery[k]);
  });
  if (query.name) win.setTitle(WINDOW_TITLE + ' - ' + query.name);
  win.loadFile(path.join(RES_DIR, 'index.html'), { query });
  return true;
}

ipcMain.handle('shell:open-player-window', async (_e, playerQuery) => createPlayerWindow(playerQuery));
ipcMain.handle('shell:close-window', async (e) => {
  const w = BrowserWindow.fromWebContents(e.sender);
  if (!w) return false;
  w.close();
  return true;
});

/* ---- 生命周期 ---- */
app.whenReady().then(async () => {
  try {
    gatewayUrl = await startGateway();
    console.log('[electron] gateway ready at', gatewayUrl);
  } catch (e) {
    console.error('[electron] gateway 启动失败，将退回默认端口：', e.message);
  }
  await createWindow();
  app.on('activate', () => { if (BrowserWindow.getAllWindows().length === 0) createWindow(); });
});

app.on('window-all-closed', () => { if (process.platform !== 'darwin') app.quit(); });
app.on('before-quit', () => { try { gatewayProc && gatewayProc.kill(); } catch (e) {} });

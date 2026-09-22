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
    title: 'Kuikly SFTP',
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      // 把网关地址传给 preload（sandbox 下 preload 可读 process.argv）
      additionalArguments: ['--gateway=' + gatewayUrl],
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

  await mainWindow.loadFile(path.join(RES_DIR, 'index.html'), {
    query: { page_name: 'SftpHomePage' },
  });
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

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
const path = require('path');

const RES_DIR = path.join(__dirname, 'resources');
const GATEWAY_ENTRY = path.join(__dirname, '..', 'sftp-gateway', 'server.js');

let mainWindow = null;
let gatewayProc = null;
let gatewayUrl = 'http://127.0.0.1:18090';

/** 启动网关并等待其回报实际端口 */
function startGateway() {
  return new Promise((resolve, reject) => {
    gatewayProc = utilityProcess.fork(GATEWAY_ENTRY, [], {
      env: { ...process.env, SFTP_GATEWAY_PORT: '0' },
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

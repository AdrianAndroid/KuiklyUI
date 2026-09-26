/*
 * preload：安全桥（contextIsolation 开启，仅暴露白名单）
 *
 * 1) 向页面注入网关地址 —— 现有 H5 模块读取 window.__SFTP_GATEWAY_URL__，零改动
 * 2) 暴露极少量桌面能力 window.kuiklyHost（页面应经 Kuikly host Module 调用，不直连）
 */
'use strict';

const { contextBridge, ipcRenderer } = require('electron');

const arg = (process.argv.find((a) => a.startsWith('--gateway=')) || '').replace('--gateway=', '');
const gatewayUrl = arg || 'http://127.0.0.1:18090';

// Debug 标识：主进程按 app.isPackaged 传入 `--debug=1/0`（Release 打包版为 0）
const debugArg = process.argv.find((a) => a.startsWith('--debug=')) || '';
const isDebug = debugArg.endsWith('=1');

contextBridge.exposeInMainWorld('__SFTP_GATEWAY_URL__', gatewayUrl);
contextBridge.exposeInMainWorld('__KR_DEBUG__', isDebug);

contextBridge.exposeInMainWorld('kuiklyHost', {
  // 另存为：{ suggestedName, base64 } -> { ok, path }
  saveFile: (payload) => ipcRenderer.invoke('host:saveFile', payload),
  // 系统通知：{ title, body }
  notify: (payload) => ipcRenderer.invoke('host:notify', payload),
  // 只读信息：{ version, platform, gatewayUrl }
  getInfo: () => ipcRenderer.invoke('host:getInfo'),
  // 独立播放窗口：query 即播放页 pageData（支持同时开多个窗口播放不同视频）
  openPlayerWindow: (query) => ipcRenderer.invoke('shell:open-player-window', query),
  // 关闭调用方所在的窗口（独立播放窗口的返回键走这里）
  closeWindow: () => ipcRenderer.invoke('shell:close-window'),
});

// 本地文件系统（双栏「本地栏」用；仅限用户主目录之下）
contextBridge.exposeInMainWorld('localFs', {
  home: () => ipcRenderer.invoke('localfs:home'),
  // 同步取主目录（Kuikly 的 cacheRoot 是同步通道，异步 home() 拿不到值）
  homeSync: () => ipcRenderer.sendSync('localfs:homeSync'),
  list: (dir) => ipcRenderer.invoke('localfs:list', dir),
  stat: (p) => ipcRenderer.invoke('localfs:stat', p),
  mkdir: (p) => ipcRenderer.invoke('localfs:mkdir', p),
  rename: (from, to) => ipcRenderer.invoke('localfs:rename', from, to),
  remove: (p, recursive) => ipcRenderer.invoke('localfs:remove', p, recursive),
  readFile: (p) => ipcRenderer.invoke('localfs:readFile', p),
  writeFile: (p, base64) => ipcRenderer.invoke('localfs:writeFile', p, base64),
});

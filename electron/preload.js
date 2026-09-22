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

contextBridge.exposeInMainWorld('__SFTP_GATEWAY_URL__', gatewayUrl);

contextBridge.exposeInMainWorld('kuiklyHost', {
  // 另存为：{ suggestedName, base64 } -> { ok, path }
  saveFile: (payload) => ipcRenderer.invoke('host:saveFile', payload),
  // 系统通知：{ title, body }
  notify: (payload) => ipcRenderer.invoke('host:notify', payload),
  // 只读信息：{ version, platform, gatewayUrl }
  getInfo: () => ipcRenderer.invoke('host:getInfo'),
});

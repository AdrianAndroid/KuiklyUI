/*
 * Electron 桌面壳冒烟测试（M4-5）
 *   前置：npm install（含 electron 二进制）；resources 已 sync（未 sync 会自动提示）
 *   运行：npm test
 */
import { spawn } from 'node:child_process';
import path from 'node:path';
import fs from 'node:fs';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const electronDir = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..');

let electronBin;
try {
  electronBin = require('electron'); // electron 包导出二进制路径（Node 上下文）
} catch (e) {
  console.error('[smoke] electron 未安装：cd electron && npm install');
  process.exit(2);
}
if (!fs.existsSync(path.join(electronDir, 'resources', 'index.html'))) {
  console.error('[smoke] resources 未同步：npm run sync');
  process.exit(2);
}

const PORT = 9333;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const results = [];
const check = (n, ok, d) => { results.push({ n, ok: !!ok, d }); console.log(`${ok ? 'PASS' : 'FAIL'} | ${n}${d ? ' | ' + d : ''}`); };

async function waitCdp() {
  for (let i = 0; i < 80; i++) {
    try { const r = await fetch(`http://127.0.0.1:${PORT}/json/version`); if (r.ok) return true; } catch (e) {}
    await sleep(250);
  }
  return false;
}

(async () => {
  const child = spawn(electronBin, ['.', `--remote-debugging-port=${PORT}`], { cwd: electronDir, stdio: 'inherit' });
  try {
    check('S1 Electron 启动 + CDP 可用', await waitCdp());
    const list = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json();
    const page = list.find((t) => t.type === 'page');
    check('S2 找到渲染进程页面', !!page, page && page.url);
    if (page) {
      const ws = new WebSocket(page.webSocketDebuggerUrl);
      let id = 0; const pend = new Map(); const errs = [];
      const send = (m, p = {}) => new Promise((r) => { const i = ++id; pend.set(i, r); ws.send(JSON.stringify({ id: i, method: m, params: p })); });
      await new Promise((r) => { ws.onopen = r; });
      ws.onmessage = (e) => {
        const m = JSON.parse(e.data);
        if (m.id && pend.has(m.id)) { pend.get(m.id)(m.result); pend.delete(m.id); }
        if (m.method === 'Runtime.exceptionThrown') errs.push(m.params.exceptionDetails.text || 'exception');
      };
      await send('Runtime.enable');
      const ev = async (expr) => (await send('Runtime.evaluate', { expression: expr, returnByValue: true })).result.value;
      const text = await ev('document.body.innerText');
      check('S3 首页渲染(SFTP 客户端)', typeof text === 'string' && text.includes('SFTP 客户端'), 'len=' + (text || '').length);
      const gw = await ev('window.__SFTP_GATEWAY_URL__');
      check('S4 注入网关地址', /^http:\/\/127\.0\.0\.1:\d+$/.test(String(gw)), String(gw));
      check('S5 无 JS 异常', errs.length === 0, errs.slice(0, 2).join(';'));
      ws.close();
    }
  } catch (e) {
    check('S0 冒烟执行异常', false, String(e && e.message));
  } finally {
    try { child.kill(); } catch (e) {}
  }
  const pass = results.filter((r) => r.ok).length;
  console.log(`\n=== smoke: ${pass}/${results.length} 通过 ===`);
  process.exit(results.some((r) => !r.ok) ? 1 : 0);
})();

/*
 * Electron 桌面壳冒烟测试（M4-5）
 *   前置：npm install（含 electron 二进制）；resources 已 sync（未 sync 会自动提示）
 *   运行：npm test
 */
import { spawn } from 'node:child_process';
import path from 'node:path';
import fs from 'node:fs';
import { pathToFileURL } from 'node:url';
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
  // 关键：宿主环境可能带 ELECTRON_RUN_AS_NODE=1（VS Code 等），会让 Electron 以纯 Node 运行
  const childEnv = { ...process.env };
  delete childEnv.ELECTRON_RUN_AS_NODE;
  const child = spawn(electronBin, ['.', `--remote-debugging-port=${PORT}`], { cwd: electronDir, stdio: 'inherit', env: childEnv });
  try {
    check('S1 Electron 启动 + CDP 可用', await waitCdp());
    // 等待渲染进程页面出现（窗口创建/首帧可能晚于 CDP 就绪）
    let page = null;
    let listAll = [];
    for (let i = 0; i < 60; i++) {
      listAll = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json();
      page = listAll.find((t) => t.type === 'page');
      if (page) break;
      await sleep(500);
    }
    check('S2 找到渲染进程页面', !!page, page ? page.url : ('targets=' + listAll.map((t) => t.type).join(',')));
    if (page) {
      const ws = new WebSocket(page.webSocketDebuggerUrl);
      let id = 0; const pend = new Map(); const errs = []; const logs = [];
      const send = (m, p = {}) => new Promise((r) => { const i = ++id; pend.set(i, r); ws.send(JSON.stringify({ id: i, method: m, params: p })); });
      await new Promise((r) => { ws.onopen = r; });
      ws.onmessage = (e) => {
        const m = JSON.parse(e.data);
        if (m.id && pend.has(m.id)) { pend.get(m.id)(m.result); pend.delete(m.id); }
        if (m.method === 'Runtime.exceptionThrown') errs.push(m.params.exceptionDetails.text || 'exception');
        if (m.method === 'Runtime.consoleAPICalled') {
          logs.push((m.params.args || []).map((a) => a.value !== undefined ? a.value : a.description || a.type).join(' '));
        }
        if (m.method === 'Log.entryAdded') logs.push('[log] ' + m.params.entry.text);
      };
      await send('Runtime.enable');
      await send('Log.enable');
      await send('Page.enable');
      // 支持 await 的求值（fetch 等异步表达式）
      const ev = async (expr, awaitPromise = false) => {
        const r = await send('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise });
        if (r && r.exceptionDetails) return 'EVAL_ERR: ' + (r.exceptionDetails.text || '');
        return r && r.result ? r.result.value : undefined;
      };
      // 等待 H5 渲染（36MB bundle 冷启动较慢）
      const waitText = async (pred, ms = 40000) => { const t0 = Date.now(); let t = await ev('document.body.innerText'); while (!pred(String(t || '')) && Date.now() - t0 < ms) { await sleep(500); t = await ev('document.body.innerText'); } return String(t || ''); };
      const text = await waitText((t) => t.includes('SFTP 客户端'));
      check('S3 首页渲染(SFTP 客户端)', text.includes('SFTP 客户端'), 'len=' + text.length + (text ? '' : ' | readyState=' + (await ev('document.readyState'))));
      const gw = await ev('window.__SFTP_GATEWAY_URL__');
      check('S4 注入网关地址', /^http:\/\/127\.0\.0\.1:\d+$/.test(String(gw)), String(gw));
      check('S5 无 JS 异常', errs.length === 0, errs.slice(0, 2).join(';') || (logs.length ? ('logs=' + logs.slice(-3).join(' | ')) : ''));

      // S6 渲染进程 → 网关 真实 RPC（验证 CORS/端口注入可用）
      const health = await ev(`fetch(${JSON.stringify(String(gw))} + '/health').then(r=>r.json()).then(j=>JSON.stringify(j)).catch(e=>'ERR:'+e)`, true);
      check('S6 渲染进程可访问网关', /"ok":true/.test(String(health)), String(health));


      // 功能验证：真实服务器（可用 SFTP_* 覆盖）
      const HOST = process.env.SFTP_HOST || '192.168.2.2';
      const PORT_SSH = process.env.SFTP_PORT || '22';
      const USER = process.env.SFTP_USER || 'zhaojian';
      const PASS = process.env.SFTP_PASSWORD || 'zhaojian';
      const HOME = process.env.SFTP_HOME || '/home/zhaojian';
      const MEDIA = process.env.SFTP_MEDIA || HOME + '/sftp_kuikly_media.mp4';
      const mediaName = MEDIA.split('/').pop();
      const MEDIA_SIZE = process.env.SFTP_MEDIA_SIZE || '95627';

      const rpc = async (module, method, params) => {
        const r = await fetch(String(gw) + '/rpc', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ module, method, params }) });
        return r.json();
      };
      const base = pathToFileURL(path.join(electronDir, 'resources', 'index.html')).href;

      // S7 连接真实服务器（Node 侧经网关）+ 浏览页渲染真实目录
      const conn = await rpc('sftp', 'connect', { host: HOST, port: Number(PORT_SSH), user: USER, password: PASS });
      check('S7 网关连接真实服务器', !!conn.sessionId, conn.sessionId || JSON.stringify(conn).slice(0, 120));
      await send('Page.navigate', { url: `${base}?page_name=SftpBrowserPage&host=${HOST}&port=${PORT_SSH}&user=${USER}&password=${PASS}&remotePath=${HOME}` });
      const browseText = await waitText((t) => t.includes(mediaName));
      check('S8 桌面壳内渲染真实远端目录', browseText.includes(mediaName), 'hasMedia=' + browseText.includes(mediaName));

      // S9 播放页：时间前进
      await send('Page.navigate', { url: `${base}?page_name=SftpPlayerPage&sessionId=${conn.sessionId}&connectionId=c1&connectionLabel=test&remotePath=${MEDIA}&name=${mediaName}&size=${MEDIA_SIZE}` });
      const pickCur = "(()=>{const t=(document.body.innerText.match(/\\d\\d:\\d\\d/g)||[]);return t.length?t[0]:'none';})()";
      const isTime = (s) => /^\d\d:\d\d$/.test(String(s || ''));
      const t1 = await (async () => { const t0 = Date.now(); let v = await ev(pickCur); while (!isTime(v) && Date.now() - t0 < 30000) { await sleep(500); v = await ev(pickCur); } return v; })();
      await sleep(2500);
      const t2 = await ev(pickCur);
      const toSec = (s) => { const m = (s || '').match(/(\d\d):(\d\d)/); return m ? (+m[1]) * 60 + (+m[2]) : -1; };
      check('S9 桌面壳内播放(时间前进)', isTime(t1) && isTime(t2) && toSec(t2) > toSec(t1), `${t1} -> ${t2}`);
      check('S10 功能验证后仍无 JS 异常', errs.length === 0, errs.slice(0, 2).join(';'));

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

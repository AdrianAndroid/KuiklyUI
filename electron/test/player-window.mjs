/*
 * 独立播放窗口（多视频同时播放）——真实点击自动化验证
 *
 *   前置：cd electron && npm run sync；网关已起（sftp-gateway，127.0.0.1:18090）
 *   运行：cd electron && npm run test:player
 *
 * 验证点：
 *   P1  主窗口在浏览页，点击视频 → 打开**新窗口**（主窗口保持不动，可继续点开其它视频）
 *   P2  新窗口内播放器真实起播（currentTime 推进）
 *   P3  再点第二个视频 → 第二个播放窗口（多视频同时播放）
 *   P4  播放窗口的返回键关闭该窗口，另一个窗口不受影响
 *   P5  全程无 JS 未捕获异常
 */
import { spawn } from 'node:child_process';
import path from 'node:path';
import fs from 'node:fs';
import { pathToFileURL } from 'node:url';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const electronDir = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..');
const artifacts = path.join(electronDir, 'test', 'artifacts');
fs.mkdirSync(artifacts, { recursive: true });

const PORT = 9381;
const GW = process.env.GATEWAY_URL || 'http://127.0.0.1:18090';
const HOST = process.env.SFTP_HOST || '192.168.2.2';
const PORT_SSH = process.env.SFTP_PORT || '22';
const USER = process.env.SFTP_USER || 'zhaojian';
const PASS = process.env.SFTP_PASSWORD || 'zhaojian';
const HOME = process.env.SFTP_HOME || '/home/zhaojian';

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const results = [];
  // 点击画面中心以显示自动隐藏的控制条/标题（页面 toggleControls 仅在 hidden→show）
  const tapVideoToShowControls = async (view) => {
    const info = await view.ev("(()=>{const v=document.querySelector('video');if(!v)return null;const r=v.getBoundingClientRect();return Math.round(r.left+r.width/2)+','+Math.round(r.top+r.height/2);})()");
    if (!info) return false;
    const [x, y] = info.split(',').map(Number);
    const press = (type) => view.send('Input.dispatchMouseEvent', { type, x, y, button: 'left', buttons: type === 'mouseMoved' ? 0 : 1, clickCount: 1 });
    await press('mouseMoved');
    await new Promise((r) => setTimeout(r, 200));
    await press('mousePressed');
    await press('mouseReleased');
    await new Promise((r) => setTimeout(r, 200));
    return true;
  };

  const check = (n, ok, d) => { results.push({ n, ok: !!ok, d }); console.log(`${ok ? 'PASS' : 'FAIL'} | ${n}${d ? ' | ' + d : ''}`); };
const rpc = async (module, method, params) => (await fetch(GW + '/rpc', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ module, method, params }) })).json();

/** 连一个 CDP target，返回 {send, ev, body, shot, mouseAt, close} */
async function attach(wsUrl) {
  const ws = new WebSocket(wsUrl);
  let id = 0; const pend = new Map(); const errs = []; const logs = [];
  const send = (m, p = {}) => new Promise((r) => { const i = ++id; pend.set(i, r); ws.send(JSON.stringify({ id: i, method: m, params: p })); });
  await new Promise((r) => { ws.onopen = r; });
  ws.onmessage = (e) => {
    const m = JSON.parse(e.data);
    if (m.id && pend.has(m.id)) { pend.get(m.id)(m.result); pend.delete(m.id); }
    if (m.method === 'Runtime.exceptionThrown') errs.push(String(m.params.exceptionDetails?.exception?.description || m.params.exceptionDetails?.text || '').slice(0, 300));
    if (m.method === 'Runtime.consoleAPICalled') { const t = (m.params.args || []).map((a) => (a.value !== undefined ? a.value : a.description || '')).join(' '); if (/SftpPlayerLauncher|supportsPlayerWindow/.test(t)) logs.push(t.slice(0, 160)); }
  };
  await send('Runtime.enable');
  const ev = async (x) => { const r = await send('Runtime.evaluate', { expression: x, returnByValue: true }); return r && r.result ? r.result.value : ''; };
  const body = async () => String(await ev('document.body.innerText'));
  const shot = async (n) => { const s = await send('Page.captureScreenshot', { format: 'png' }); fs.writeFileSync(path.join(artifacts, n), Buffer.from(s.data, 'base64')); };
  return { send, ev, body, shot, errs, logs, mouseAt: (type, x, y, buttons) => send('Input.dispatchMouseEvent', { type, x, y, button: 'left', buttons, clickCount: 1 }), close: () => ws.close() };
}

const listTargets = async () => { try { return (await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json()).filter((t) => t.type === 'page'); } catch (e) { return []; } };
const playerTargets = async () => (await listTargets()).filter((t) => t.url.includes('page_name=SftpPlayerPage'));
const waitFor = async (fn, ms, step = 500) => { const t0 = Date.now(); for (;;) { let v = null; try { v = await fn(); } catch (e) { v = null; } if (v) return v; if (Date.now() - t0 > ms) return null; await sleep(step); } };

// WATCHDOG：无人值守时避免无限等待（超时即失败退出，便于自动化）
let __finished = false;
setTimeout(() => {
  if (__finished) return;
  console.log('FAIL | WATCHDOG 全局超时 900s，强制退出');
  process.exit(1);
}, 900 * 1000);

(async () => {
  const childEnv = { ...process.env }; delete childEnv.ELECTRON_RUN_AS_NODE;
  const child = spawn(require('electron'), ['.', `--remote-debugging-port=${PORT}`], { cwd: electronDir, stdio: 'ignore', env: childEnv });
  try {
    const page = await waitFor(async () => (await listTargets())[0], 40000);
    if (!page) { check('P0 启动渲染进程', false); return; }
    const main = await attach(page.webSocketDebuggerUrl);
    await main.send('Page.enable');
    await waitFor(async () => (await main.body()).includes('SFTP 客户端'), 60000, 1000);

    // 夹具：远端建一个只含 2 个视频的目录（两条目必在首屏可见，
    // 因为 Kuikly 的 Scroller 不是原生滚动容器，scrollIntoView 对其无效）
    const conn = await rpc('sftp', 'connect', { host: HOST, port: Number(PORT_SSH), user: USER, password: PASS });
    const sid = conn.sessionId;
    const FIX_DIR = `${HOME}/kr_pw_fixture`;
    const CLIP_A = 'a_first_60s.mp4';
    const CLIP_B = 'b_second_60s.mp4';
    const cleanup = async () => { try { await rpc('sftp', 'rm', { sessionId: sid, remotePath: FIX_DIR, recursive: true }); } catch (e) {} };
    await cleanup();
    const src = await rpc('sftp', 'openRead', { sessionId: sid, remotePath: `${HOME}/kr_long.mp4` });
    const srcB64 = src && src.fileHandleId ? (await rpc('sftp', 'read', { fileHandleId: src.fileHandleId, offset: 0, length: 4 * 1024 * 1024 })).base64 : '';
    if (src && src.fileHandleId) await rpc('sftp', 'close', { fileHandleId: src.fileHandleId });
    if (srcB64) {
      await rpc('sftp', 'mkdir', { sessionId: sid, remotePath: FIX_DIR });
      for (const n of [CLIP_A, CLIP_B]) await rpc('sftp', 'upload', { sessionId: sid, remotePath: `${FIX_DIR}/${n}`, content: srcB64 });
    }
    check('P0a 夹具目录就绪（2 个视频）', !!srcB64, `${FIX_DIR} (源 ${Math.round(srcB64.length / 1024)}KB base64)`);

    // 进入浏览页（夹具目录）
    const base = page.url.split('?')[0];
    await main.send('Page.navigate', { url: `${base}?page_name=SftpBrowserPage&host=${HOST}&port=${PORT_SSH}&user=${USER}&password=${PASS}&remotePath=${FIX_DIR}` });
    const listed = await waitFor(async () => (await main.body()).includes('.mp4'), 45000, 700);
    check('P0 浏览页列出真实远端视频', !!listed, listed ? 'has .mp4' : '未列出');

    // 真实点击视频 → 应打开新窗口
    // 点「包含该文本的最小元素」（文件名唯一 → 即该行内的名称文本，事件冒泡到行）
    const clickText = async (txt, target = main) => {
      const info = await target.ev(
        "(()=>{const all=[...document.querySelectorAll('*')].map(e=>({e,r:e.getBoundingClientRect()})).filter(o=>o.e.textContent&&o.e.textContent.trim().includes(" + JSON.stringify(txt) + ")&&o.r.width>1&&o.r.height>1);if(!all.length)return null;all.sort((a,b)=>a.r.width*a.r.height-b.r.width*b.r.height);const el=all[0].e;el.scrollIntoView({block:'center'});const r=el.getBoundingClientRect();return JSON.stringify({x:r.left+r.width/2,y:r.top+r.height/2,tag:el.tagName});})()");
      if (!info) return false;
      const p = JSON.parse(info);
      await target.mouseAt('mousePressed', p.x, p.y, 1); await target.mouseAt('mouseReleased', p.x, p.y, 0);
      await sleep(1200);
      return true;
    };
    const v1 = CLIP_A, v2 = CLIP_B;
    const clicked1 = v1 ? await clickText(v1) : false;
    const win1 = await waitFor(async () => (await playerTargets())[0], 20000);
    const mainStillList = (await main.body()).includes('.mp4');
    check('P1 浏览页点视频 → 打开独立播放窗口（主窗口留在列表）', clicked1 && !!win1 && mainStillList,
      `${v1} clicked=${clicked1} 新窗口=${!!win1} 主窗口仍在列表=${mainStillList} | 探测=${main.logs.join(' || ') || '(无)'}`);

    if (win1) {
      const pl1 = await attach(win1.webSocketDebuggerUrl);
      const playing = await waitFor(async () => { const t = Number(await pl1.ev("(()=>{const v=document.querySelector('video');return v?(v.currentTime>1?1:0):0;})()")); return t === 1 ? true : null; }, 40000, 800);
      const st = await pl1.ev("(()=>{const v=document.querySelector('video');return v?JSON.stringify({t:+v.currentTime.toFixed(1),d:+(v.duration||0).toFixed(1),err:v.error?v.error.code:0}):'none';})()");
      check('P2 新窗口内播放器真实起播', !!playing, String(st));
      await pl1.shot('player-window-1.png');

      // 第二个视频 → 第二个播放窗口
      if (v2) {
        await clickText(v2);
        const two = await waitFor(async () => ((await playerTargets()).length >= 2 ? true : null), 20000);
        check('P3 再点第二个视频 → 第二个播放窗口（多视频同播）', !!two, `窗口数=${(await playerTargets()).length} (${v2})`);
      } else {
        check('P3 第二个视频存在', false, '测试目录需 ≥2 个 .mp4');
      }

      // 在第一个窗口点「<」返回 → 该窗口关闭，其它窗口不受影响
      const before = (await playerTargets()).length;
      // 续播对话框（"继续播放？/从头播放"）若还在，会盖在画面上拦截点击 → 先按"从头播放"关掉
      const resumeShown = (await pl1.body()).includes('继续播放？');
      if (resumeShown) {
        await clickText('从头播放', pl1);
        await waitFor(async () => ((await pl1.body()).includes('继续播放？') ? null : true), 5000, 200);
      }
      // 控制条/标题自动隐藏 → 点击画面先显示（几秒后再隐），然后点「<」关闭
      const visShown = await tapVideoToShowControls(pl1);
      // 等渲染把「<」挂出来
      const backReady = await waitFor(async () => ((await pl1.body()).includes('<') ? true : null), 5000, 100);
      if (!backReady) {
        const dbg = await pl1.body();
        console.log('      [debug P4a] body head=' + dbg.slice(0, 200).replace(/\n/g, ' | '));
      }
      const backClicked = await clickText('<', pl1);
      check('P4a 播放窗口内「<」可点击', visShown && backReady && backClicked, 'visible=' + visShown + ' ready=' + !!backReady + ' click=' + backClicked);
      const closed = await waitFor(async () => ((await playerTargets()).length < before ? true : null), 12000, 600);
      check('P4 播放窗口「<」关闭自身，其它窗口不受影响', !!closed, `${before} → ${(await playerTargets()).length}`);
      pl1.close();
    }
    check('P5 无 JS 未捕获异常', main.errs.length === 0, main.errs.slice(0, 2).join('; '));
  } catch (e) {
    console.log('ERROR | ' + String((e && e.stack) || e).slice(0, 600));
    results.push({ n: '意外异常', ok: false, d: String(e).slice(0, 120) });
  } finally {
    try { await cleanup(); } catch (e) {}
    try { child.kill('SIGTERM'); } catch (e) {}
    const fail = results.filter((r) => !r.ok).length;
    console.log(`\n[player-window] ${results.length - fail}/${results.length} 通过；截图目录 ${artifacts}`);
    __finished = true;
    setTimeout(() => process.exit(fail ? 1 : 0), 150);
  }
})();

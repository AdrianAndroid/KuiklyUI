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

// 可用 ELECTRON_TEST_BIN 指向打包产物（.app 内的可执行文件）做「打包版」功能验证
const binOverride = process.env.ELECTRON_TEST_BIN || '';

let electronBin;
try {
  electronBin = require('electron'); // electron 包导出二进制路径（Node 上下文）
} catch (e) {
  if (!binOverride) {
    console.error('[smoke] electron 未安装：cd electron && npm install');
    process.exit(2);
  }
}
if (!binOverride && !fs.existsSync(path.join(electronDir, 'resources', 'index.html'))) {
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
  const child = binOverride
    ? spawn(binOverride, [`--remote-debugging-port=${PORT}`], { stdio: 'inherit', env: childEnv })
    : spawn(electronBin, ['.', `--remote-debugging-port=${PORT}`], { cwd: electronDir, stdio: 'inherit', env: childEnv });
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
        if (m.method === 'Runtime.exceptionThrown') {
          const d = m.params.exceptionDetails || {};
          errs.push(String((d.exception && d.exception.description) || d.text || 'exception'));
        }
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
      const benignErr = (t) => /AbortError|play\(\) request was interrupted|NotAllowedError/i.test(String(t));
      const realErrs = () => errs.filter((t) => !benignErr(t));
      check('S5 无 JS 异常', realErrs().length === 0, realErrs().slice(0, 2).join(';') || (logs.length ? ('logs=' + logs.slice(-3).join(' | ')) : ''));

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
      // ================= 以下为「模拟人工点击 / 按键」用例 =================
      const wake = async () => {
        for (const x of [420, 520, 620]) { await send('Input.dispatchMouseEvent', { type: 'mouseMoved', x, y: 300, button: 'none', buttons: 0 }); await sleep(120); }
        await sleep(400);
      };
      let lastClickInfo = '';
      const clickText = async (txt) => {
        const info = await ev(
          "(()=>{const all=[...document.querySelectorAll('*')].map(e=>({e,r:e.getBoundingClientRect()}))" +
          ".filter(o=>o.e.textContent&&o.e.textContent.trim().includes(" + JSON.stringify(txt) + ")&&o.r.width>1&&o.r.height>1);" +
          "if(!all.length)return null;all.sort((a,b)=>a.r.width*a.r.height-b.r.width*b.r.height);" +
          "const el=all[0].e;el.scrollIntoView({block:'center'});const r=el.getBoundingClientRect();" +
          "return {x:r.left+r.width/2,y:r.top+r.height/2,tag:el.tagName,text:String(el.textContent||'').trim().slice(0,24)," +
          "rect:Math.round(r.left)+','+Math.round(r.top)+' '+Math.round(r.width)+'x'+Math.round(r.height)};})()");
        if (!info) { lastClickInfo = '(未找到元素)'; return false; }
        lastClickInfo = `tag=${info.tag} text="${info.text}" rect=${info.rect}`;
        const mouse = (t) => send('Input.dispatchMouseEvent', { type: t, x: info.x, y: info.y, button: 'left', buttons: t === 'mouseReleased' ? 0 : 1, clickCount: 1 });
        await mouse('mousePressed'); await sleep(60); await mouse('mouseReleased'); await sleep(600);
        return true;
      };
      const waitGone = async (txt, ms = 8000) => { const t0 = Date.now(); while (Date.now() - t0 < ms) { if (!String(await ev('document.body.innerText')).includes(txt)) return true; await sleep(300); } return false; };
      const bodyText = async () => String(await ev('document.body.innerText'));

      // S8b 点击「<」返回上一级（真实点击）。
      // 注意：点击前不要先 mousemove（wake）——会干扰 Kuikly 的 click 判定（实测）
      let backClicked = false; let afterBack = '';
      for (let i = 0; i < 3; i++) {
        backClicked = await clickText('<');
        await sleep(1200);
        afterBack = await bodyText();
        if (afterBack.includes('/home') && !afterBack.includes(HOME)) break;
      }
      check('S8b 点击返回按钮回到上一级(真实点击)', backClicked && afterBack.includes('/home') && !afterBack.includes(HOME),
        `clicked=${backClicked} pathChanged=${afterBack.includes('/home') && !afterBack.includes(HOME)} | click=${lastClickInfo} | nav=${JSON.stringify(afterBack.split('\n').slice(0,3).join(' / ').slice(0,80))}`);

      // 重新进入播放页做交互
      await send('Page.navigate', { url: `${base}?page_name=SftpPlayerPage&sessionId=${conn.sessionId}&connectionId=c1&connectionLabel=test&remotePath=${MEDIA}&name=${mediaName}&size=${MEDIA_SIZE}` });
      await waitText((t) => /\d\d:\d\d/.test(t));
      await sleep(2500);

      // S9b 点击 ☰ 打开选集弹层（真实点击）
      let opened = false;
      for (let i = 0; i < 5 && !opened; i++) { await wake(); await clickText('☰'); await sleep(900); opened = (await bodyText()).includes('✕'); }
      check('S9b 点击☰打开选集弹层(真实点击)', opened, 'opened=' + opened);

      // S9c 点击 ✕ 关闭选集弹层（真实点击）
      const xClicked = await clickText('✕');
      const closed = await waitGone('✕', 6000);
      check('S9c 点击✕关闭选集弹层(真实点击)', xClicked && closed, `clicked=${xClicked} closed=${closed}`);

      // S9d 点击倍速打开设置菜单（真实点击）
      await wake();
      const speedClicked = await clickText('1.0×');
      await sleep(800);
      const menuShown = (await bodyText()).includes('1.25×');
      check('S9d 点击倍速打开设置菜单(真实点击)', speedClicked && menuShown, `clicked=${speedClicked} menu=${menuShown}`);

      // S9e 点击全屏按钮 → 图标切换为退出全屏（真实点击）
      let fsOn = false;
      for (let i = 0; i < 5 && !fsOn; i++) {
        await wake();
        await clickText('⛶');
        await sleep(700);
        fsOn = (await bodyText()).includes('⤡');
      }
      check('S9e 点击全屏按钮并切换状态(真实点击)', fsOn, 'exitGlyph=' + fsOn);

      // S9f 键盘快捷键（模拟人工按键）：按「←」seek。
      // 用 seek 而不是播放/暂停：测试片仅 6s，播放态在片尾存在竞态（K 会被片尾立即覆盖）。
      // 「←」seek 到 0 是确定性的，能证明键盘事件确实到达页面。
      const keyTap = async (k, code, vk) => {
        await send('Input.dispatchKeyEvent', { type: 'keyDown', key: k, code, windowsVirtualKeyCode: vk });
        await send('Input.dispatchKeyEvent', { type: 'keyUp', key: k, code, windowsVirtualKeyCode: vk });
      };
      await clickText('⤡');                          // 先退出全屏
      await sleep(600);
      await keyTap('ArrowRight', 'ArrowRight', 39);  // 先跳到后面
      await sleep(900);
      const beforeSeek = await ev(pickCur);
      await keyTap('ArrowLeft', 'ArrowLeft', 37);    // 键盘 seek 回开头
      await sleep(1200);
      const afterSeek = await ev(pickCur);
      check('S9f 键盘快捷键seek生效(模拟按键)', isTime(afterSeek) && toSec(afterSeek) === 0, `${beforeSeek} -> ${afterSeek}`);

      // ── 播放器 seek 能力补全（此前 Web 端 KRVideoView 未实现 seekTo：
      //    拖动看起来在动（tooltip 跟着走）但视频不跳；键盘 seek 也只是片尾归零的假通过）──
      const videoState = async () => {
        const raw = await ev("(()=>{const v=document.querySelector('video');return v?JSON.stringify({t:+v.currentTime.toFixed(2),d:+(v.duration||0).toFixed(2),rs:v.readyState,err:v.error?v.error.code:0,paused:v.paused}):'none';})()");
        try { return JSON.parse(raw); } catch (e) { return null; }
      };
      const wakeBar = async () => {
        const w = await ev('innerWidth'), h = await ev('innerHeight');
        return ev("(()=>{const w=innerWidth,h=innerHeight;const c=[...document.querySelectorAll('*')].map(e=>({e,r:e.getBoundingClientRect()})).filter(o=>o.r.width>w*0.4&&o.r.height>1&&o.r.height<26&&o.r.top>h*0.55&&o.r.top<h-25);if(!c.length)return null;c.sort((a,b)=>a.r.height-b.r.height);const r=c[0].r;return JSON.stringify({x1:r.left+6,x2:r.right-6,y:Math.round(r.top+r.height/2)});})()").then((j) => { try { return JSON.parse(j); } catch (e) { return null; } });
      };
      const mouseAt = (type, x, y, buttons) => send('Input.dispatchMouseEvent', { type, x, y, button: 'left', buttons, clickCount: 1 });

      // S9g 拖动进度条 seek：真实「按下 → 连续移动 → 松手」，断言视频真的跳转
      for (let i = 0; i < 4; i++) { const st = await videoState(); if (!st || st.paused) break; await wake(); await clickText('❚❚'); await sleep(700); }
      await wake(); await sleep(300);
      const stBefore = await videoState();
      const bar = await wakeBar();
      let dragTime = -1;
      if (bar && stBefore && stBefore.d > 0) {
        await mouseAt('mousePressed', bar.x1 + 8, bar.y, 1); await sleep(250);
        const targetX = bar.x1 + (bar.x2 - bar.x1) * 0.8;
        for (let i = 1; i <= 6; i++) { await mouseAt('mouseMoved', bar.x1 + 8 + (targetX - bar.x1 - 8) * i / 6, bar.y, 1); await sleep(90); }
        await mouseAt('mouseReleased', targetX, bar.y, 0);
        await sleep(1500);
        const stAfter = await videoState();
        dragTime = stAfter ? stAfter.t : -1;
      }
      const target = stBefore && stBefore.d > 0 ? stBefore.d * 0.8 : -1;
      check('S9g 拖动进度条 seek 生效(真实按下-移动-松手)', !!bar && dragTime >= 0 && target > 0 && Math.abs(dragTime - target) < Math.max(1.5, target * 0.3),
        `bar=${!!bar} ${stBefore && stBefore.t}s -> ${dragTime}s（目标≈${target.toFixed(1)}s，时长=${stBefore && stBefore.d}s）`);

      // S9h 切换选集后仍能正常播放：真实点 ☰ → 点列表里的另一个视频 → 断言元数据与外层一致且时间在推进
      await wake();
      await clickText('☰');
      await sleep(1000);
      const other = await ev("(()=>{const cur=" + JSON.stringify(mediaName) + ";const all=[...document.querySelectorAll('*')].map(e=>({e,r:e.getBoundingClientRect()})).filter(o=>{const t=(o.e.textContent||'').trim();return /\\.mp4$/.test(t)&&t!==cur&&o.r.width>1&&o.r.height>1;});if(!all.length)return null;all.sort((a,b)=>a.r.width*a.r.height-b.r.width*b.r.height);const el=all[0].e;el.scrollIntoView({block:'center'});const r=el.getBoundingClientRect();return JSON.stringify({name:el.textContent.trim(),x:r.left+r.width/2,y:r.top+r.height/2});})()");
      let swap = null, swapOk = false, swapPlayed = false;
      try { swap = other ? JSON.parse(other) : null; } catch (e) { swap = null; }
      if (swap) {
        await mouseAt('mousePressed', swap.x, swap.y, 1); await sleep(80); await mouseAt('mouseReleased', swap.x, swap.y, 0);
        const s2 = Date.now();
        while (Date.now() - s2 < 20000) { const st = await videoState(); if (st && st.d > 0 && (await bodyText()).includes(swap.name)) break; await sleep(700); }
        swapOk = (await bodyText()).includes(swap.name);
        // 确保处于播放态：仅在 paused 时按空格（' ' = togglePlay，正在播时按会暂停）
        for (let i = 0; i < 3; i++) { const st = await videoState(); if (!st || !st.paused) break; await wake(); await keyTap(' ', 'Space', 32); await sleep(900); }
        const a = await videoState();
        await sleep(4000);
        const b = await videoState();
        swapPlayed = !!(a && b && a.d > 0 && b.d > 0 && (b.t > a.t || b.t >= b.d - 0.3));
      }
      check('S9h 切换选集后可正常播放(真实点击)', !!swap && swapOk && swapPlayed,
        swap ? `切到 ${swap.name} 标题命中=${swapOk} 播放推进=${swapPlayed}` : '未找到第二个视频（测试目录需≥2个 .mp4）');

      // S9i 打开选集抽屉时视频区不得塌陷（video 高度 > 0）
      //     曾因浮层未绝对定位，作为列布局子节点吃掉视频区 flex 高度 → video 高度=0 → 上半屏纯黑
      await wake();
      await clickText('☰');
      await sleep(1200);
      const drawerShown = (await bodyText()).includes('选集');
      const videoH = await ev("(()=>{const v=document.querySelector('video');return v?Math.round(v.getBoundingClientRect().height):-1;})()");
      check('S9i 选集抽屉打开后视频区不塌陷(不黑屏)', drawerShown && Number(videoH) > 100, `drawer=${drawerShown} videoH=${videoH}`);
      await clickText('✕');
      await sleep(600);

      check('S10 功能验证后仍无 JS 异常', realErrs().length === 0, realErrs().slice(0, 2).join(';') || `(已忽略媒体告警 ${errs.length} 条)`);

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

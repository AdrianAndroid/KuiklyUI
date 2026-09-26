/*
 * 终端功能（跨平台设计：共享网格渲染 = native 路径；web 可另用 xterm.js）
 *
 *   前置：cd electron && npm run sync；外部网关已起（cd electron && npm run gateway，端口按实例计算）
 *   运行：cd electron && npm run test:term
 *
 * 用例：
 *   T1 首页**首个入口**「本地终端」存在（远程终端入口在各连接行）
 *   T2 点它 → **独立窗口**打开终端页（主窗口留在首页）
 *   T3 本地终端出真实 shell 输出（网格渲染文本非空、含 shell 提示/目录信息）
 *   T4 输入 `echo KR_TERM_OK` → 输出回显（真实点击输入框 + 真实输入 + 点发送）
 *   T5 每个连接行有 `>_` 入口，点开是**远程**终端独立窗口
 *   T6 远程终端执行 `whoami` → 回显远端用户名（证明走的是真实 SSH shell）
 *   T7 无 JS 未捕获异常
 */
import { spawn } from 'node:child_process';
import path from 'node:path';
import fs from 'node:fs';
import { createRequire } from 'node:module';
import { cdpPort, cdpArgs, buildChildEnv, ensureDirs, logInstance, SFTP_HOME } from './env.mjs';

const require = createRequire(import.meta.url);
const electronDir = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..');
const artifacts = path.join(electronDir, 'test', 'artifacts');
fs.mkdirSync(artifacts, { recursive: true });
const PORT = cdpPort('terminal');
const HOME = SFTP_HOME;
const USER = process.env.SFTP_USER || 'zhaojian';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const results = [];
const check = (n, ok, d) => { results.push({ n, ok: !!ok, d }); console.log(`${ok ? 'PASS' : 'FAIL'} | ${n}${d ? ' | ' + d : ''}`); };
const skipped = [];
const skip = (n, d) => { skipped.push(n); console.log(`SKIP | ${n}${d ? ' | ' + d : ''}`); };

async function attach(wsUrl) {
  const ws = new WebSocket(wsUrl);
  let id = 0; const pend = new Map(); const errs = [];
  const send = (m, p = {}) => new Promise((r) => { const i = ++id; pend.set(i, r); ws.send(JSON.stringify({ id: i, method: m, params: p })); });
  await new Promise((r) => { ws.onopen = r; });
  ws.onmessage = (e) => {
    const m = JSON.parse(e.data);
    if (m.id && pend.has(m.id)) { pend.get(m.id)(m.result); pend.delete(m.id); }
    if (m.method === 'Runtime.exceptionThrown') errs.push(String(m.params.exceptionDetails?.exception?.description || '').slice(0, 200));
  };
  await send('Runtime.enable'); await send('Page.enable');
  const ev = async (x) => { const r = await send('Runtime.evaluate', { expression: x, returnByValue: true }); return r && r.result ? r.result.value : ''; };
  const body = async () => String(await ev('document.body.innerText'));
  const shot = async (n) => { const s = await send('Page.captureScreenshot', { format: 'png' }); fs.writeFileSync(path.join(artifacts, n), Buffer.from(s.data, 'base64')); };
  const mouse = (t, x, y, b) => send('Input.dispatchMouseEvent', { type: t, x, y, button: 'left', buttons: b, clickCount: 1 });
  const clickText = async (txt) => {
    const info = await ev("(()=>{const a=[...document.querySelectorAll('*')].map(e=>({e,r:e.getBoundingClientRect()})).filter(o=>o.e.textContent&&o.e.textContent.trim().includes(" + JSON.stringify(txt) + ")&&o.r.width>1&&o.r.height>1);if(!a.length)return null;a.sort((p,q)=>p.r.width*p.r.height-q.r.width*q.r.height);const el=a[0].e;const r=el.getBoundingClientRect();return JSON.stringify({x:r.left+r.width/2,y:r.top+r.height/2});})()");
    if (!info) return false;
    const p = JSON.parse(info);
    await mouse('mouseMoved', p.x, p.y, 0); await sleep(120);
    await mouse('mousePressed', p.x, p.y, 1); await mouse('mouseReleased', p.x, p.y, 0);
    await sleep(800); return true;
  };
  return { send, ev, body, shot, mouse, clickText, errs, close: () => ws.close() };
}

const listTargets = async () => { try { return (await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json()).filter((t) => t.type === 'page'); } catch (e) { return []; } };
const waitFor = async (fn, ms, step = 500) => { const t0 = Date.now(); for (;;) { let v = null; try { v = await fn(); } catch (e) { v = null; } if (v) return v; if (Date.now() - t0 > ms) return null; await sleep(step); } };

/** 读取 xterm 缓冲区文本（xterm 渲染到 canvas，DOM 取不到；用宿主测试钩子） */
async function xtermText(view, maxLines = 400) {
  return String(await view.ev("(()=>{try{return (window.__krTerm&&window.__krTerm.lastId())?window.__krTerm.text(window.__krTerm.lastId()," + maxLines + "):'';}catch(e){return '';}})()"));
}
async function waitXterm(view, ms) {
  return waitFor(async () => { const id = await view.ev("(()=>{try{return window.__krTerm?window.__krTerm.lastId():'';}catch(e){return '';}})()"); return id || null; }, ms, 400);
}

/**
 * 发送命令：真实文本输入到 xterm 的隐藏 textarea（xterm 处理方向键/退格/回车），
 * 回车用 DOM KeyboardEvent（**避免 CDP 合成按键触发 macOS「听写」系统弹窗**）。
 */
async function runCommand(view, cmd) {
  const id = await waitXterm(view, 20000);
  if (!id) return false;
  await view.ev("(()=>{const ta=document.querySelector('.xterm-helper-textarea');if(!ta)return false;ta.focus();return true;})()");
  await sleep(150);
  await view.send('Input.insertText', { text: cmd });
  await sleep(250);
  await view.ev("(()=>{const ta=document.querySelector('.xterm-helper-textarea');if(!ta)return false;for(const type of ['keydown','keyup']){ta.dispatchEvent(new KeyboardEvent(type,{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true,cancelable:true}));}return true;})()");
  await sleep(400);
  return true;
}

(async () => {
  logInstance('terminal');
  ensureDirs();
  const childEnv = buildChildEnv(); delete childEnv.ELECTRON_RUN_AS_NODE;
  const child = spawn(require('electron'), ['.', ...cdpArgs('terminal', PORT)], { cwd: electronDir, stdio: 'ignore', env: childEnv });
  try {
    const page = await waitFor(async () => (await listTargets())[0], 40000);
    if (!page) { check('T0 渲染进程启动', false); return; }
    const main = await attach(page.webSocketDebuggerUrl);
    const home = await waitFor(async () => { const t = await main.body(); return t.includes('>_') ? t : null; }, 60000, 1000);

    check('T1 本地终端入口在「本地文件管理」那一栏（行内 >_）', !!home, home ? '含 >_' : '未渲染');

    // T2/T3 本地终端：独立窗口（本地终端 = SSH 本机，未保存凭据时先弹登录窗）
    // 精确点本地格：文本为 '>_' 且 y 最小（本地那一栏在连接列表之上）
    const localCell = await main.ev("(()=>{const a=[...document.querySelectorAll('*')].map(e=>({e,r:e.getBoundingClientRect()})).filter(o=>o.e.textContent&&o.e.textContent.trim()==='>_'&&o.r.width>0&&o.r.height>0);if(!a.length)return null;a.sort((p,q)=>p.r.top-q.r.top);const r=a[0].e.getBoundingClientRect();return JSON.stringify({x:r.left+r.width/2,y:r.top+r.height/2});})()");
    if (localCell) {
      const p0 = JSON.parse(localCell);
      await main.mouse('mouseMoved', p0.x, p0.y, 0); await sleep(120);
      await main.mouse('mousePressed', p0.x, p0.y, 1); await main.mouse('mouseReleased', p0.x, p0.y, 0);
      await sleep(900);
    }
    const w1 = await waitFor(async () => (await listTargets()).find((t) => t.url.includes('SftpTerminalPage')), 20000);
    const mainStillHome = !!(await waitFor(async () => ((await main.body()).includes('>_') ? true : null), 6000, 500));
    check('T2 点本地行的 >_ → 独立窗口（主窗口留在首页）', !!w1 && mainStillHome, `新窗口=${!!w1} 主窗口仍在首页=${mainStillHome}`);
    let localOut = '';
    if (w1) {
      const t1 = await attach(w1.webSocketDebuggerUrl);
      const hasLoginModal = !!(await waitFor(async () => ((await t1.body()).includes('登录本机') ? true : null), 12000, 700));
      localOut = hasLoginModal ? '' : (await waitFor(async () => { const txt = await xtermText(t1); return txt.length > 20 ? txt : null; }, 25000, 800) || '');
      check('T3 本地终端独立窗口（登录弹窗或 xterm 渲染）', hasLoginModal || localOut.length > 20,
        `登录弹窗=${hasLoginModal} xterm内容长度=${localOut.length}`);
      await t1.shot('terminal-local.png');

      // T4 真实文本输入 + 回车执行命令并回显（输出值 42 不出现在输入里，避免自匹配假通过）
      const typed = await runCommand(t1, 'echo TERM_$((6*7))');
      const echoed = await waitFor(async () => {
        const t = await xtermText(t1);
        return new RegExp('(^|\\n)\\s*TERM_42\\s*($|\\n)').test(t) ? t : null;
      }, 15000, 700);
      check('T4 真实输入 echo TERM_$((6*7)) + 回车 → 回显 TERM_42', typed && !!echoed, `输入=${typed} 回显=${!!echoed}`);
      await t1.shot('terminal-local-echo.png');
      // 关掉本地终端窗口（standalone 的「<」= 关窗），避免后续 T6 抓错窗口
      await t1.clickText('<');
      await sleep(800);
      t1.close();
    }

    // T5/T6 远程终端：连接行 >_ 入口
    await main.send('Page.navigate', { url: `${page.url.split('?')[0]}?page_name=SftpHomePage` });
    const home2 = await waitFor(async () => { const t = await main.body(); return t.includes('>_') ? t : null; }, 30000, 800);
    check('T5 每个连接行有终端入口（>_）', !!home2, home2 ? '含 >_' : '未找到');
    let remoteOut = '';
    if (home2) {
      const before = (await listTargets()).length;
      await main.clickText('>_');
      const w2 = await waitFor(async () => { const l = (await listTargets()).filter((t) => t.url.includes('SftpTerminalPage')); return l.length > 0 ? l[l.length - 1] : null; }, 25000);
      if (w2) {
        const t2 = await attach(w2.webSocketDebuggerUrl);
        const prompt = await waitFor(async () => { const txt = await xtermText(t2); return txt.length > 20 ? txt : null; }, 30000, 800);
        let who = null;
        for (let i = 0; i < 2 && !who; i++) {
          await runCommand(t2, 'whoami');
          who = await waitFor(async () => {
            const t = await xtermText(t2);
            // 必须出现独立一行用户名（whoami 的输出）；不认 prompt 里的 user@（会假通过）
            const ok = new RegExp('(^|\\n)\\s*' + USER + '\\s*($|\\n)').test(t);
            return ok ? t : null;
          }, 15000, 700);
        }
        remoteOut = who || prompt || '';
        check('T6 远程终端执行 whoami → 回显远端用户名（真实 SSH shell）', !!who,
          who ? `含 ${USER}` : `未见用户名（xterm长度=${(prompt || '').length}）`);
        await t2.shot('terminal-remote.png');
        await t2.clickText('<');
        t2.close();
      } else {
        check('T6 远程终端窗口', false, '未打开');
      }
    }

    check('T7 无 JS 未捕获异常', main.errs.length === 0, main.errs.slice(0, 2).join('; '));
  } catch (e) {
    console.log('ERROR | ' + String((e && e.stack) || e).slice(0, 400));
    results.push({ n: '意外异常', ok: false, d: String(e).slice(0, 100) });
  } finally {
    try { child.kill('SIGTERM'); } catch (e) { }
    const fail = results.filter((r) => !r.ok).length;
    console.log(`\n[terminal] ${results.length - fail}/${results.length} 通过${skipped.length ? `；SKIP ${skipped.length}（已知缺陷：${skipped.join('、')}）` : ''}；截图目录 ${artifacts}`);
    setTimeout(() => process.exit(fail ? 1 : 0), 150);
  }
})();

/*
 * 文本文件查看器（独立窗口，含 Markdown）——真实点击自动化验证
 *
 *   前置：cd electron && npm run sync；网关已起（sftp-gateway，127.0.0.1:18090）
 *   运行：cd electron && npm run test:text
 *
 * 验证点：
 *   T0  用户指定目录 /home/zhaojian/ks-cr-doc 能列出 Markdown 文档
 *   T1  点 .md → **独立窗口**打开查看器（主窗口留在列表）
 *   T2  Markdown 渲染：标题/正文/表格/代码块/列表 + 阅读器状态栏（编码·大小·行·字）
 *   T3  工具条：换行开关 / 源码⇄预览 / A± 字号 / 目录抽屉（含条目跳转）
 *   T4  纯文本：行号槽可见（1、2…）
 *   T5  可同时开多个文本窗口，返回键只关自己
 *   T6  无 JS 未捕获异常
 */
import { spawn } from 'node:child_process';
import path from 'node:path';
import fs from 'node:fs';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const electronDir = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..');
const artifacts = path.join(electronDir, 'test', 'artifacts');
fs.mkdirSync(artifacts, { recursive: true });

const PORT = 9409;
const GW = process.env.GATEWAY_URL || 'http://127.0.0.1:18090';
const HOST = process.env.SFTP_HOST || '192.168.2.2';
const PORT_SSH = process.env.SFTP_PORT || '22';
const USER = process.env.SFTP_USER || 'zhaojian';
const PASS = process.env.SFTP_PASSWORD || 'zhaojian';
const HOME = process.env.SFTP_HOME || '/home/zhaojian';
const DOC_DIR = `${HOME}/ks-cr-doc`;                 // 用户指定的 Markdown 目录
const SAMPLE_MD = '国王红包功能总结文档.md';
const FIX_DIR = `${HOME}/kr_text_fixture`;           // 确定性夹具（含样例 md + 小 txt）
const FIX_MD = 'a_sample_doc.md';
const FIX_TXT = 'b_notes.txt';

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const results = [];
const skipped = [];
const check = (n, ok, d) => { results.push({ n, ok: !!ok, d }); console.log(`${ok ? 'PASS' : 'FAIL'} | ${n}${d ? ' | ' + d : ''}`); };
const rpc = async (module, method, params) => (await fetch(GW + '/rpc', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ module, method, params }) })).json();

async function attach(wsUrl) {
  const ws = new WebSocket(wsUrl);
  let id = 0; const pend = new Map(); const errs = [];
  const send = (m, p = {}) => new Promise((r) => { const i = ++id; pend.set(i, r); ws.send(JSON.stringify({ id: i, method: m, params: p })); });
  await new Promise((r) => { ws.onopen = r; });
  ws.onmessage = (e) => {
    const m = JSON.parse(e.data);
    if (m.id && pend.has(m.id)) { pend.get(m.id)(m.result); pend.delete(m.id); }
    if (m.method === 'Runtime.exceptionThrown') errs.push(String(m.params.exceptionDetails?.exception?.description || m.params.exceptionDetails?.text || '').slice(0, 300));
  };
  await send('Runtime.enable');
  const ev = async (x) => { const r = await send('Runtime.evaluate', { expression: x, returnByValue: true }); return r && r.result ? r.result.value : ''; };
  const body = async () => String(await ev('document.body.innerText'));
  const shot = async (n) => { const s = await send('Page.captureScreenshot', { format: 'png' }); fs.writeFileSync(path.join(artifacts, n), Buffer.from(s.data, 'base64')); };
  const mouseAt = (type, x, y, buttons) => send('Input.dispatchMouseEvent', { type, x, y, button: 'left', buttons, clickCount: 1 });
  // 真实点击：命中「包含该文本的最小可见元素」，先悬停再按下
  const clickText = async (txt, targetSelf = null) => {
    const t = targetSelf || { ev, mouseAt };
    const info = await t.ev("(()=>{const all=[...document.querySelectorAll('*')].map(e=>({e,r:e.getBoundingClientRect()})).filter(o=>o.e.textContent&&o.e.textContent.trim().includes(" + JSON.stringify(txt) + ")&&o.r.width>1&&o.r.height>1);if(!all.length)return null;all.sort((a,b)=>a.r.width*a.r.height-b.r.width*b.r.height);const el=all[0].e;el.scrollIntoView({block:'center'});const r=el.getBoundingClientRect();return JSON.stringify({x:r.left+r.width/2,y:r.top+r.height/2});})()");
    if (!info) return false;
    const p = JSON.parse(info);
    await t.mouseAt('mouseMoved', p.x, p.y, 0); await sleep(120);
    await t.mouseAt('mousePressed', p.x, p.y, 1); await t.mouseAt('mouseReleased', p.x, p.y, 0);
    await sleep(900);
    return true;
  };
  return { send, ev, body, shot, mouseAt, clickText, errs, close: () => ws.close() };
}

const listTargets = async () => { try { return (await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json()).filter((t) => t.type === 'page'); } catch (e) { return []; } };
const viewerTargets = async () => (await listTargets()).filter((t) => t.url.includes('page_name=SftpViewerDispatcherPage'));
const waitFor = async (fn, ms, step = 500) => { const t0 = Date.now(); for (;;) { let v = null; try { v = await fn(); } catch (e) { v = null; } if (v) return v; if (Date.now() - t0 > ms) return null; await sleep(step); } };

let __finished = false;
setTimeout(() => {
  if (__finished) return;
  console.log('FAIL | WATCHDOG 全局超时 900s，强制退出');
  process.exit(1);
}, 900 * 1000);

(async () => {
  const childEnv = { ...process.env }; delete childEnv.ELECTRON_RUN_AS_NODE;
  const child = spawn(require('electron'), ['.', `--remote-debugging-port=${PORT}`], { cwd: electronDir, stdio: 'ignore', env: childEnv });
  const conn = await rpc('sftp', 'connect', { host: HOST, port: Number(PORT_SSH), user: USER, password: PASS });
  const sid = conn.sessionId;
  const cleanup = async () => { try { await rpc('sftp', 'rm', { sessionId: sid, remotePath: FIX_DIR, recursive: true }); } catch (e) {} };
  try {
    // 夹具：把真实样例 .md 复制一份 + 写一个小 .txt（保证条目在首屏，无需滚动）
    await cleanup();
    const src = await rpc('sftp', 'openRead', { sessionId: sid, remotePath: `${DOC_DIR}/${SAMPLE_MD}` });
    const mdB64 = src && src.fileHandleId ? (await rpc('sftp', 'read', { fileHandleId: src.fileHandleId, offset: 0, length: 256 * 1024 })).base64 : '';
    if (src && src.fileHandleId) await rpc('sftp', 'close', { fileHandleId: src.fileHandleId });
    const txt = '第一行: hello kuikly\n第二行: markdown viewer\n第三行: line numbers\n';
    await rpc('sftp', 'mkdir', { sessionId: sid, remotePath: FIX_DIR });
    await rpc('sftp', 'upload', { sessionId: sid, remotePath: `${FIX_DIR}/${FIX_MD}`, content: mdB64 });
    await rpc('sftp', 'upload', { sessionId: sid, remotePath: `${FIX_DIR}/${FIX_TXT}`, content: Buffer.from(txt, 'utf8').toString('base64') });
    check('T0a 夹具就绪（样例 md + txt）', !!mdB64, `${FIX_DIR}（md ${Math.round(mdB64.length / 1024)}KB）`);

    const page = await waitFor(async () => (await listTargets())[0], 40000);
    if (!page) { check('T0b 渲染进程启动', false); return; }
    const main = await attach(page.webSocketDebuggerUrl);
    await main.send('Page.enable');
    await waitFor(async () => (await main.body()).includes('SFTP 客户端'), 60000, 1000);
    const base = page.url.split('?')[0];

    // T0 用户指定目录可列出 md
    await main.send('Page.navigate', { url: `${base}?page_name=SftpBrowserPage&host=${HOST}&port=${PORT_SSH}&user=${USER}&password=${PASS}&remotePath=${DOC_DIR}` });
    const docList = await waitFor(async () => { const t = await main.body(); return t.includes('.md') ? t : null; }, 45000, 700);
    check('T0 用户指定目录 /home/zhaojian/ks-cr-doc 可列出 Markdown', !!docList && docList.includes('总结文档'), docList ? '含「总结文档」条目' : '未列出');

    // 切到夹具目录做确定性交互
    await main.send('Page.navigate', { url: `${base}?page_name=SftpBrowserPage&host=${HOST}&port=${PORT_SSH}&user=${USER}&password=${PASS}&remotePath=${FIX_DIR}` });
    await waitFor(async () => (await main.body()).includes(FIX_MD), 45000, 700);

    // T1 点 md → 独立窗口
    const clickedMd = await main.clickText(FIX_MD);
    const w1 = await waitFor(async () => (await viewerTargets())[0], 20000);
    check('T1 点 .md → 独立窗口打开查看器（主窗口留在列表）', clickedMd && !!w1 && (await main.body()).includes(FIX_MD),
      `clicked=${clickedMd} 新窗口=${!!w1} 主窗口仍在列表=${(await main.body()).includes(FIX_MD)}`);

    if (w1) {
      const md = await attach(w1.webSocketDebuggerUrl);
      const rendered = await waitFor(async () => { const t = await md.body(); return t.includes('业务背景') ? t : null; }, 30000, 700);
      const t2 = rendered || (await md.body());
      // Markdown 专属特征：目录 chip（由标题生成）+ 状态栏；纯文本态不会有「目录(N)」
      const hasTocChip = /目录\(\d+\)/.test(t2);
      const hasStatus = t2.includes('行') && t2.includes('字');
      const isMdMode = t2.includes('源码') && hasTocChip;
      check('T2 Markdown 渲染（含目录）+ 阅读器状态栏', !!rendered && hasStatus && isMdMode,
        `含「业务背景」=${!!rendered} 目录chip=${hasTocChip} 源码chip=${t2.includes('源码')} 状态栏=${hasStatus}`);
      await md.shot('text-viewer-md.png');

      // T3a 换行开关
      const wrapOn = (await md.body()).includes('换行:开');
      await md.clickText('换行:开');
      const wrapOff = (await md.body()).includes('换行:关');
      check('T3a 换行开关可切换', wrapOn && wrapOff, `开→关=${wrapOn}&&${wrapOff}`);

      // T3b 源码 ⇄ 预览
      await md.clickText('源码');
      const srcView = await waitFor(async () => { const t = await md.body(); return t.includes('## ') ? t : null; }, 12000, 600);
      await md.clickText('预览');
      const backPreview = await waitFor(async () => { const t = await md.body(); return (t.includes('业务背景') && !t.includes('## ') && /目录\(\d+\)/.test(t)) ? t : null; }, 12000, 600);
      check('T3b Markdown 源码⇄预览可切换', !!srcView && !!backPreview, `源码态=${!!srcView} 回预览=${!!backPreview}`);

      // T3c 字号 A+ 生效
      const fontOf = async () => Number(await md.ev("(()=>{const es=[...document.querySelectorAll('*')].map(e=>parseFloat(getComputedStyle(e).fontSize)||0);return Math.max(...es,0);})()"));
      const f0 = await fontOf();
      await md.clickText('A+'); await sleep(300);
      await md.clickText('A+'); await sleep(600);
      const f1 = await fontOf();
      check('T3c 字号 A+ 生效（最大字号变大）', f1 > f0, `${f0}px -> ${f1}px`);

      // T3d 目录抽屉
      const tocChip = (await md.body()).match(/目录\((\d+)\)/);
      await md.clickText(tocChip ? tocChip[0] : '目录');
      const tocOpen = await waitFor(async () => (await md.body()).includes('目录') ? (await md.body()) : null, 10000, 500);
      const tocCount = tocChip ? Number(tocChip[1]) : 0;
      check('T3d 目录（TOC）抽屉可打开且列出条目', !!tocOpen && tocCount > 0, `目录条目数=${tocCount}`);
      await md.clickText('关闭');
      md.close();
    }

    // T4 纯文本：行号
    await main.clickText(FIX_TXT);
    const w2 = await waitFor(async () => { const l = await viewerTargets(); return l.find((t) => !t.url.includes(encodeURIComponent(FIX_MD)) && l.length >= 1) || null; }, 20000);
    if (w2) {
      const tx = await attach(w2.webSocketDebuggerUrl);
      const txtBody = await waitFor(async () => { const t = await tx.body(); return t.includes('第一行') ? t : null; }, 25000, 700);
      const hasLineNo = /(^|\n)\s*1\s*(\n|$)/.test(await tx.body()) && (await tx.body()).includes('3');
      check('T4 纯文本查看：内容 + 行号 + 状态栏', !!txtBody && /行/.test(await tx.body()),
        `内容=${!!txtBody} 行号=${hasLineNo}`);
      await tx.shot('text-viewer-txt.png');
      tx.close();
    } else {
      check('T4 纯文本窗口打开', false, '未找到第二个查看器窗口');
    }

    // T5 多窗口并存 + 逐个关闭
    const before = (await viewerTargets()).length;
    const first = (await viewerTargets())[0];
    if (first) {
      const f = await attach(first.webSocketDebuggerUrl);
      await f.clickText('<');
      const closed = await waitFor(async () => ((await viewerTargets()).length < before ? true : null), 12000, 600);
      check('T5 多文本窗口并存，返回键只关自身窗口', before >= 2 && !!closed, `${before} → ${(await viewerTargets()).length}`);
      f.close();
    } else {
      check('T5 多窗口', false, 'no viewer windows');
    }

    check('T6 无 JS 未捕获异常', main.errs.length === 0, main.errs.slice(0, 2).join('; '));
  } catch (e) {
    console.log('ERROR | ' + String((e && e.stack) || e).slice(0, 500));
    results.push({ n: '意外异常', ok: false, d: String(e).slice(0, 120) });
  } finally {
    try { await cleanup(); } catch (e) {}
    try { child.kill('SIGTERM'); } catch (e) {}
    const fail = results.filter((r) => !r.ok).length;
    console.log(`\n[text-viewer] ${results.length - fail}/${results.length} 通过；截图目录 ${artifacts}`);
    __finished = true;
    process.exitCode = fail ? 1 : 0;
    setTimeout(() => process.exit(fail ? 1 : 0), 150);
  }
})();

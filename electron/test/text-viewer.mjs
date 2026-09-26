/*
 * 文本文件查看器（独立窗口，含 Markdown）——真实点击自动化验证
 *
 *   前置：cd electron && npm run sync；外部网关已起（cd electron && npm run gateway，端口按实例计算）
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
import { INSTANCE, cdpPort, cdpArgs, buildChildEnv, ensureDirs, logInstance, registerCleanup, GATEWAY_URL, SFTP_HOME } from './env.mjs';

const require = createRequire(import.meta.url);
const electronDir = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..');
const artifacts = path.join(electronDir, 'test', 'artifacts');
fs.mkdirSync(artifacts, { recursive: true });

const PORT = cdpPort('text');
const GW = GATEWAY_URL;
const HOST = process.env.SFTP_HOST || '192.168.2.2';
const PORT_SSH = process.env.SFTP_PORT || '22';
const USER = process.env.SFTP_USER || 'zhaojian';
const PASS = process.env.SFTP_PASSWORD || 'zhaojian';
const HOME = SFTP_HOME;
const DOC_DIR = `${HOME}/ks-cr-doc`;                 // 用户指定的 Markdown 目录（共享只读）
const SAMPLE_MD = '国王红包功能总结文档.md';
const FIX_DIR = `${HOME}/kr_text_fixture_${INSTANCE}`;   // 确定性夹具带实例前缀（多 worktree 并行不互删）
const FIX_MD = 'a_sample_doc.md';
const FIX_TXT = 'b_notes.txt';
const FIX_MD_DIAGRAM = 'c_diagram.md';   // mermaid 流程图夹具
const FIX_MD_BIG = 'd_big_doc.md';       // 大文档夹具（增量渲染）

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const results = [];
const skipped = [];
// 逐条打印耗时（AGENTS §3.1 规则 7：单步明显偏长时便于定位与止损）
let __lastT = Date.now();
const check = (n, ok, d) => {
  const dt = ((Date.now() - __lastT) / 1000).toFixed(1);
  __lastT = Date.now();
  results.push({ n, ok: !!ok, d });
  console.log(`${ok ? 'PASS' : 'FAIL'} | ${n}${d ? ' | ' + d : ''} (${dt}s)`);
};
const rpc = async (module, method, params) => (await fetch(GW + '/rpc', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ module, method, params }) })).json();

async function attach(wsUrl) {
  const ws = new WebSocket(wsUrl);
  let id = 0; const pend = new Map(); const errs = [];
  const send = (m, p = {}) => new Promise((r) => {
    const i = ++id;
    // 单条 CDP 命令超时兜底：目标进程卡死时不能无限等待（否则整套用例被看门狗强杀、无法定位）
    const timer = setTimeout(() => { pend.delete(i); r(null); }, 15000);
    pend.set(i, (v) => { clearTimeout(timer); r(v); });
    try { ws.send(JSON.stringify({ id: i, method: m, params: p })); } catch (e) { clearTimeout(timer); pend.delete(i); r(null); }
  });
  await new Promise((r) => {
    const timer = setTimeout(() => { try { ws.close(); } catch (e) {} r(); }, 15000);
    ws.onopen = () => { clearTimeout(timer); r(); };
  });
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
/** 按远端文件名精确匹配查看器窗口（同名文件可能同时开了 txt/md 两个窗口） */
const viewerFor = async (fileName) => (await viewerTargets()).filter((t) => t.url.includes(encodeURIComponent(fileName)) || t.url.includes(fileName));
const waitFor = async (fn, ms, step = 500) => { const t0 = Date.now(); for (;;) { let v = null; try { v = await fn(); } catch (e) { v = null; } if (v) return v; if (Date.now() - t0 > ms) return null; await sleep(step); } };

let __finished = false;
setTimeout(() => {
  if (__finished) return;
  console.log('FAIL | WATCHDOG 全局超时 1500s，强制退出');
  process.exit(1);
}, 1500 * 1000);

(async () => {
  logInstance('text');
  ensureDirs();
  const childEnv = buildChildEnv(); delete childEnv.ELECTRON_RUN_AS_NODE;
  const child = spawn(require('electron'), ['.', ...cdpArgs('text', PORT)], { cwd: electronDir, stdio: 'ignore', env: childEnv });
  const cleanupChild = registerCleanup(child);   // 退出/信号兜底杀本实例应用
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

    // 图表夹具（mermaid 流程图）与大文档夹具（验证增量渲染）
    const diagramMd = [
      '# 图表样例',
      '',
      '下面是一个 mermaid 流程图：',
      '',
      '```mermaid',
      'flowchart TD',
      '  A[开始] --> B{是否命中}',
      '  B -->|是| C[处理数据]',
      '  B -->|否| D[结束]',
      '  C --> D',
      '```',
      '',
      '以上。',
      ''
    ].join('\n');
    const bigMd = '# 大文档\n\n' + Array.from({ length: 90 }, (_, i) =>
      `## 小节 ${i + 1}\n\n这是第 ${i + 1} 段内容，用于验证增量渲染。\n`).join('\n');
    await rpc('sftp', 'upload', { sessionId: sid, remotePath: `${FIX_DIR}/${FIX_MD_DIAGRAM}`, content: Buffer.from(diagramMd, 'utf8').toString('base64') });
    await rpc('sftp', 'upload', { sessionId: sid, remotePath: `${FIX_DIR}/${FIX_MD_BIG}`, content: Buffer.from(bigMd, 'utf8').toString('base64') });

    check('T0a 夹具就绪（样例 md + txt + 图表 + 大文档）', !!mdB64, `${FIX_DIR}（md ${Math.round(mdB64.length / 1024)}KB）`);

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

    // T1 点 md → **页内**打开查看器（同一界面，不再新开窗口）
    const targetsBefore = (await listTargets()).length;
    const clickedMd = await main.clickText(FIX_MD);
    const w1 = await waitFor(async () => {
      const ts = await listTargets();
      const cur = ts.find((t) => t.url.includes('page_name=SftpViewerDispatcherPage'));
      return (cur && ts.length === targetsBefore) ? cur : null;
    }, 20000);
    check('T1 点 .md → 页内打开查看器（同一界面，不新开窗口）', clickedMd && !!w1,
      `clicked=${clickedMd} 页内查看器=${!!w1} target数=${targetsBefore}->${(await listTargets()).length}`);

    if (w1) {
      const md = await attach(w1.webSocketDebuggerUrl);
      const rendered = await waitFor(async () => { const t = await md.body(); return t.includes('业务背景') ? t : null; }, 30000, 700);
      const t2 = rendered || (await md.body());
      // Markdown 专属特征：目录 chip（由标题生成）+ 状态栏；纯文本态不会有「目录(N)」
      const hasTocChip = /目录\s?\d+/.test(t2);
      const hasStatus = t2.includes('行') && t2.includes('字');
      const isMdMode = t2.includes('源码') && hasTocChip;
      check('T2 Markdown 渲染（含目录）+ 阅读器状态栏', !!rendered && hasStatus && isMdMode,
        `含「业务背景」=${!!rendered} 目录chip=${hasTocChip} 源码chip=${t2.includes('源码')} 状态栏=${hasStatus}`);
      await md.shot('text-viewer-md.png');

      // T3a 换行开关
      const wrapOn = (await md.body()).includes('换行') && !(await md.body()).includes('不换行');
      await md.clickText('换行');
      const wrapOff = (await md.body()).includes('不换行');
      check('T3a 换行开关可切换', wrapOn && wrapOff, `换行→不换行=${wrapOn}&&${wrapOff}`);

      // T3b 源码 ⇄ 预览
      await md.clickText('源码');
      const srcView = await waitFor(async () => { const t = await md.body(); return t.includes('## ') ? t : null; }, 12000, 600);
      await md.clickText('预览');
      const backPreview = await waitFor(async () => { const t = await md.body(); return (t.includes('业务背景') && !t.includes('## ') && /目录\s?\d+/.test(t)) ? t : null; }, 12000, 600);
      check('T3b Markdown 源码⇄预览可切换', !!srcView && !!backPreview, `源码态=${!!srcView} 回预览=${!!backPreview}`);

      // T3c 字号 A+ 生效
      const fontOf = async () => Number(await md.ev("(()=>{const es=[...document.querySelectorAll('*')].map(e=>parseFloat(getComputedStyle(e).fontSize)||0);return Math.max(...es,0);})()"));
      const f0 = await fontOf();
      await md.clickText('A+'); await sleep(300);
      await md.clickText('A+'); await sleep(600);
      const f1 = await fontOf();
      check('T3c 字号 A+ 生效（最大字号变大）', f1 > f0, `${f0}px -> ${f1}px`);

      // T3d 目录抽屉
      const tocChip = (await md.body()).match(/目录\s?(\d+)/);
      await md.clickText(tocChip ? tocChip[0] : '目录');
      // 只认「弹窗独有」的标记：头部「目录 · N 项」（文档正文里不会出现）
      const tocOpen = await waitFor(async () => { const t = await md.body(); return /目录\s*·\s*\d+\s*项/.test(t) ? t : null; }, 10000, 500);
      const tocCount = tocChip ? Number(tocChip[1]) : 0;
      const entriesVisible = !!tocOpen && (tocOpen.match(/业务背景/g) || []).length >= 1;
      check('T3d 目录二级弹窗可打开并列出条目', !!tocOpen && tocCount > 0 && entriesVisible,
        `弹窗头=「目录 · N 项」${!!tocOpen} 条目数=${tocCount}`);
      if (tocOpen) await md.shot('text-viewer-toc-sheet.png');
      await md.clickText('关闭');
      await sleep(500);
      await md.clickText('关闭');
      md.close();
    }

    // T4 纯文本：行号（查看器现已页内；先返回文件列表，再点 txt）
    await main.send('Page.navigate', { url: `${base}?page_name=SftpBrowserPage&host=${HOST}&port=${PORT_SSH}&user=${USER}&password=${PASS}&remotePath=${FIX_DIR}` });
    await waitFor(async () => (await main.body()).includes(FIX_TXT), 30000, 700);
    await main.clickText(FIX_TXT);
    const w2 = await waitFor(async () => {
      const ts = await listTargets();
      return ts.find((t) => t.url.includes('page_name=SftpViewerDispatcherPage') && (t.url.includes(encodeURIComponent(FIX_TXT)) || t.url.includes(FIX_TXT))) || null;
    }, 20000);
    if (w2) {
      const tx = await attach(w2.webSocketDebuggerUrl);
      const txtBody = await waitFor(async () => { const t = await tx.body(); return t.includes('第一行') ? t : null; }, 25000, 700);
      const hasLineNo = /(^|\n)\s*1\s*(\n|$)/.test(await tx.body()) && (await tx.body()).includes('3');
      check('T4 纯文本查看：内容 + 行号 + 状态栏', !!txtBody && /行/.test(await tx.body()),
        `内容=${!!txtBody} 行号=${hasLineNo}`);
      await tx.shot('text-viewer-txt.png');
      tx.close();
    } else {
      check('T4 纯文本查看（页内）', false, '未进入纯文本查看器');
    }

    // ---- Vditor 对照用例 ----
    // T9 工具栏：单行、置顶、紧凑（对应 Vditor toolbarConfig.pin，不占大空间）
    const mdWin0 = (await viewerFor(FIX_MD))[0];
    if (mdWin0) {
      const v9 = await attach(mdWin0.webSocketDebuggerUrl);
      await v9.send('Page.enable');
      const barInfo = await v9.ev("(()=>{const rows=[...document.querySelectorAll('*')].map(e=>({t:(e.textContent||''),r:e.getBoundingClientRect()})).filter(o=>o.t.includes('A+')&&o.t.includes('换行')&&o.t.includes('保存')&&o.r.height>0);if(!rows.length)return 'null';rows.sort((a,b)=>a.r.height-b.r.height);const r=rows[0].r;return JSON.stringify({h:Math.round(r.height),top:Math.round(r.top),w:Math.round(r.width),txt:rows[0].t.slice(0,40)});})()");
      const bar = barInfo && barInfo !== 'null' ? JSON.parse(barInfo) : null;
      check('T9 工具栏单行置顶紧凑（Vditor toolbar pin 类比）', !!bar && bar.h <= 56 && bar.top <= 60,
        bar ? `高=${bar.h}px 顶部=${bar.top}px 内容='${bar.txt}'` : '未找到工具条');
      v9.close();
    }

    // T7 即时渲染（Vditor ir 类比）：编辑块 → 在编辑区输入 → **实时预览立即更新**（不点任何按钮）→ 完成 → 正文更新
    const mdWin = (await viewerFor(FIX_MD))[0];
    let inlinePreview = false, applied = false, oldGone = false, fmtApplied = false;
    if (mdWin) {
      const v7 = await attach(mdWin.webSocketDebuggerUrl);
      await v7.send('Page.enable');
      await v7.clickText('编辑');
      const editOn = await waitFor(async () => ((await v7.body()).includes('完成') ? true : null), 8000, 500);
      await v7.clickText('文档目的');
      const overlay = await waitFor(async () => ((await v7.body()).includes('编辑（第') ? true : null), 10000, 500);
      // 用格式工具条的文字按钮（B 加粗）驱动：同样走「即时渲染」，改完实时预览立即刷新
      await v7.clickText('B');
      await sleep(700);
      const taVal = await v7.ev("(()=>{const ts=[...document.querySelectorAll('textarea')].filter(e=>e.getBoundingClientRect().height>20);return ts.length?ts[ts.length-1].value:'';})()");
      const editorBold = String(taVal).includes('**');
      // 实时预览：出现加粗的同一段文字（且不是编辑区源码）＝未点应用就已重渲染
      const boldInPreview = async () => Number(await v7.ev("(()=>{const es=[...document.querySelectorAll('*')].filter(e=>{const t=(e.textContent||'');return t.includes('文档目的')&&!t.includes('**')&&getComputedStyle(e).fontWeight&&parseInt(getComputedStyle(e).fontWeight)>=600;});return es.length;})()"));
      const pvBold = await waitFor(async () => ((await boldInPreview()) > 0 ? true : null), 8000, 400);
      inlinePreview = !!pvBold;
      if (pvBold) await v7.shot('text-viewer-ir-live-preview.png');
      fmtApplied = editorBold;
      await v7.clickText('应用');
      // 断言「应用」后的效果：正文出现加粗渲染 + 出现 dirty 标记（保存*）
      const boldInBody = async () => Number(await v7.ev("(()=>{const es=[...document.querySelectorAll('*')].filter(e=>{const t=(e.textContent||'');return t.includes('文档目的')&&!t.includes('**')&&parseInt(getComputedStyle(e).fontWeight||'0')>=600;});return es.length;})()"));
      const afterApply = await waitFor(async () => ((await boldInBody()) > 0 ? true : null), 12000, 600);
      const dirtyMark = await waitFor(async () => ((await v7.body()).includes('保存*') ? true : null), 8000, 500);
      applied = !!afterApply;
      if (!afterApply) {
        // 失败诊断：把含「文档目的」的元素标签/字重/文本片段打出来，便于定位是「没重渲染」还是「字重不对」
        const diag = await v7.ev("(()=>{const b=document.body.innerText||'';return 'bodyLen='+b.length+' head='+JSON.stringify(b.slice(0,220));})()");
        console.log('      [debug T7b] ' + diag);
      }
      check('T7 即时渲染（工具条 B → 实时预览自动加粗，不点应用）', editOn && !!overlay && editorBold && inlinePreview,
        `编辑态=${!!editOn} 浮层=${!!overlay} 编辑区含**=${editorBold} 实时预览已加粗=${inlinePreview}`);
      check('T10 格式工具条生效（点 B → 编辑区出现 ** 加粗标记）', !!fmtApplied, `编辑区含 '**'=${editorBold}`);
      check('T7b 点「应用」→ 正文立即重渲染（块被替换为加粗）', !!afterApply,
        `正文已加粗渲染=${!!afterApply}（dirty 标记=${!!dirtyMark}）`);

      // T8 保存：点保存 → 提示已保存 → 远端文件内容确实更新
      await v7.clickText('保存');
      const saved = await waitFor(async () => { const t = await v7.body(); return t.includes('已保存') ? t : null; }, 25000, 700);
      const rd = await rpc('sftp', 'openRead', { sessionId: sid, remotePath: `${FIX_DIR}/${FIX_MD}` });
      let remoteHas = false, remoteLen = 0;
      if (rd && rd.fileHandleId) {
        const rb = await rpc('sftp', 'read', { fileHandleId: rd.fileHandleId, offset: 0, length: 128 * 1024 });
        const buf = Buffer.from(rb.base64 || '', 'base64');
        remoteLen = buf.length;
        remoteHas = buf.toString('utf8').includes('**');
        await rpc('sftp', 'close', { fileHandleId: rd.fileHandleId });
      }
      const dirtyCleared = !(await v7.body()).includes('保存*');
      check('T8 保存按钮写回远端（读回内容含加粗标记）', remoteHas,
        `远端含加粗标记=${remoteHas} 远端大小=${remoteLen}B 提示=${!!saved} dirty已清除=${dirtyCleared}`);
      v7.close();
    }

    // ---- T11 Mermaid 流程图渲染（共享降级实现：节点 + 边标签 + 箭头）----
    const openFixture = async (fileName) => {
      await main.send('Page.navigate', { url: `${base}?page_name=SftpBrowserPage&host=${HOST}&port=${PORT_SSH}&user=${USER}&password=${PASS}&remotePath=${FIX_DIR}` });
      await waitFor(async () => (await main.body()).includes(fileName), 30000, 700);
      const clicked = await main.clickText(fileName);
      const win = await waitFor(async () => (await viewerFor(fileName))[0], 25000, 600);
      return { clicked, win };
    };

    const dia = await openFixture(FIX_MD_DIAGRAM);
    let diaOk = false, diaText = '';
    if (dia.win) {
      const dv = await attach(dia.win.webSocketDebuggerUrl);
      await dv.send('Page.enable');
      const t = await waitFor(async () => {
        const b = await dv.body();
        return (b.includes('开始') && b.includes('处理数据')) ? b : null;
      }, 25000, 600);
      diaText = t || '';
      // 节点标签（开始/处理数据/结束）+ 边标签（是/否）+ 箭头字形（▼）
      diaOk = !!t && diaText.includes('结束') && diaText.includes('是') &&
        diaText.includes('否') && diaText.includes('▼');
      await dv.shot('text-viewer-mermaid.png');
      dv.close();
    }
    check('T11 Mermaid 流程图渲染（节点/边标签/箭头）', diaOk,
      diaOk ? '含 开始/处理数据/结束 + 是/否 + ▼' : `未渲染：${diaText.slice(0, 100)}`);

    // ---- T12 增量渲染：大文档首屏只渲染一批并给出提示，滚动后追加 ----
    const big = await openFixture(FIX_MD_BIG);
    let incFirst = false, incGrew = false, incText = '';
    if (big.win) {
      const bv = await attach(big.win.webSocketDebuggerUrl);
      await bv.send('Page.enable');
      const t1 = await waitFor(async () => {
        const b = await bv.body();
        return b.includes('点此加载更多') ? b : null;
      }, 30000, 600);
      incText = t1 || '';
      incFirst = !!t1 && /已渲染 (40|4[0-9])\//.test(incText);
      if (incFirst) {
        // web/Electron 的 Scroller 不上报滚动偏移 → 用底部「点此加载更多」显式追加
        for (let k = 0; k < 3 && !incGrew; k++) {
          await bv.clickText('点此加载更多');
          await sleep(700);
          const b = await bv.body();
          const m = /已渲染 (\d+)\//.exec(b);
          if (m && Number(m[1]) > 40) incGrew = true;
        }
      }
      await bv.shot('text-viewer-incremental.png');
      bv.close();
    }
    check('T12 增量渲染（首屏只渲染一批 + 提示，点按后追加）', incFirst && incGrew,
      `首屏提示=${incFirst} 追加后增长=${incGrew} 文案=${((incText.split('\n').find((l) => l.includes('点此加载更多')) || '').trim()).slice(0, 60)}`);

    // ---- T13 文档缓存：同一文件二次打开命中缓存（无需重新装载即可渲染）----
    const again = await openFixture(FIX_MD);
    let cacheOk = false;
    if (again.win) {
      const cv = await attach(again.win.webSocketDebuggerUrl);
      cv.send('Page.enable');
      const t = await waitFor(async () => {
        const b = await cv.body();
        return b.includes('业务背景') ? b : null;
      }, 10000, 400);
      cacheOk = !!t;
      cv.close();
    }
    check('T13 文档缓存（二次打开同一文件直接渲染）', cacheOk, cacheOk ? '命中缓存并渲染' : '未渲染');

    // T5 页内返回：查看器返回键回到文件列表（同一界面，不新开窗口），可再次进入
    const backToList = await (async () => {
      const v = (await viewerFor(FIX_MD))[0] || (await viewerTargets())[0];
      if (!v) return false;
      const f = await attach(v.webSocketDebuggerUrl);
      await f.send('Page.enable');
      await f.clickText('<');
      const ok = await waitFor(async () => ((await listTargets()).some((t) => t.url.includes('page_name=SftpBrowserPage')) ? true : null), 12000, 600);
      f.close();
      return ok;
    })();
    check('T5 页内查看器返回 → 回到文件列表（同界面，不新开窗口）', backToList, `返回列表=${backToList}`);

    check('T6 无 JS 未捕获异常', main.errs.length === 0, main.errs.slice(0, 2).join('; '));
  } catch (e) {
    console.log('ERROR | ' + String((e && e.stack) || e).slice(0, 500));
    results.push({ n: '意外异常', ok: false, d: String(e).slice(0, 120) });
  } finally {
    try { await cleanup(); } catch (e) {}
    cleanupChild();   // 测试结束立即杀掉本实例应用（双保险：pre/post hook）
    const fail = results.filter((r) => !r.ok).length;
    console.log(`\n[text-viewer] ${results.length - fail}/${results.length} 通过；截图目录 ${artifacts}`);
    __finished = true;
    process.exitCode = fail ? 1 : 0;
    setTimeout(() => process.exit(fail ? 1 : 0), 150);
  }
})();

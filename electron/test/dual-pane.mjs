/*
 * 双栏文件管理器：真实点击自动化验证（可见窗口 + 截图）
 *
 *   前置：cd electron && npm run sync；外部网关已起（cd electron && npm run gateway，端口按实例计算）
 *   运行：cd electron && npm run test:dual
 *
 * 说明：全程用 CDP Input.dispatchMouseEvent 模拟人工点击（不调页面内部 API），
 * 并在关键步骤用 Page.captureScreenshot 存图，便于人工复核。
 */
import { spawn } from 'node:child_process';
import path from 'node:path';
import fs from 'node:fs';
import { pathToFileURL } from 'node:url';
import { createRequire } from 'node:module';
import crypto from 'node:crypto';
import { INSTANCE, cdpPort, cdpArgs, buildChildEnv, ensureDirs, logInstance, registerCleanup, GATEWAY_URL, LOCAL_ROOT, REPO_ROOT, SSH_FIXTURE_PORT, FIXTURE_ROOT, FIXTURE_KEY, FIXTURE_USER, FIXTURE_PASS, FIXTURE_HOME, isPortListening, spawnTestElectron } from './env.mjs';

const require = createRequire(import.meta.url);
const electronDir = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..');
const artifacts = path.join(electronDir, 'test', 'artifacts');
fs.mkdirSync(artifacts, { recursive: true });

const PORT = cdpPort('dual');
const GW = GATEWAY_URL;
// 远端目标：默认使用**内置 SSH/SFTP 夹具**（127.0.0.1，自包含、不依赖内网测试机）。
// 需要连真实机器时显式指定 SFTP_HOST（或 KR_DUAL_REAL=1）。
const USE_FIXTURE = !process.env.SFTP_HOST && process.env.KR_DUAL_REAL !== '1';
const HOST = process.env.SFTP_HOST || '127.0.0.1';
const PORT_SSH = process.env.SFTP_PORT || (USE_FIXTURE ? String(SSH_FIXTURE_PORT) : '50122');
const USER = process.env.SFTP_USER || (USE_FIXTURE ? FIXTURE_USER : 'zhaojian');
const PASS = process.env.SFTP_PASSWORD || (USE_FIXTURE ? FIXTURE_PASS : 'zhaojian');
const HOME = process.env.SFTP_HOME || (USE_FIXTURE ? FIXTURE_HOME : '/home/zhaojian');
const LOCAL_HOME = LOCAL_ROOT;   // 本实例隔离的本地文件根（经 KR_LOCAL_ROOT 传给主进程）

// 夹具名带实例前缀：多个 worktree 并行时在共享测试机上不互删
const UPLOAD_TXT = `000_kuikly_dual_${INSTANCE}.txt`;
const UPLOAD_BIN = `000_kuikly_dual_${INSTANCE}.bin`;
const DL_TXT = `000_kuikly_dual_dl_${INSTANCE}.txt`;
const NEW_DIR = `000_dual_new_${INSTANCE}`;
const LOCAL_NEW_DIR = `000_dual_local_${INSTANCE}`;
// 隐藏文件开关用例的夹具（`.` 开头）：本地栏与远端栏各一个
const HIDDEN_LOCAL = `.000_kuikly_dual_hidden_${INSTANCE}.txt`;
const HIDDEN_REMOTE = `.000_kuikly_dual_hidden_remote_${INSTANCE}.txt`;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const escapeRe = (s) => String(s).replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
const results = [];
const check = (n, ok, d) => { results.push({ n, ok: !!ok, d }); console.log(`${ok ? 'PASS' : 'FAIL'} | ${n}${d ? ' | ' + d : ''}`); };

const rpc = async (module, method, params) => {
  const r = await fetch(GW + '/rpc', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ module, method, params }) });
  return r.json();
};

/*
 * 拉起内置 SSH/SFTP 夹具（ssh2 服务端 + 沙盒目录）。
 * 目的：让双栏端到端用例**不依赖内网测试机**，任何机器上都能跑出真实 SFTP 链路结果。
 * 若端口已有夹具在监听（并发/复跑）则直接复用，不重复启动。
 */
async function startFixture() {
  if (!USE_FIXTURE) return null;
  if (await isPortListening(SSH_FIXTURE_PORT)) {
    console.log(`      夹具已在监听 127.0.0.1:${SSH_FIXTURE_PORT}，复用`);
    return null;
  }
  const gatewayDir = path.join(REPO_ROOT, 'sftp-gateway');
  const script = path.join(gatewayDir, 'test', 'fixture-sftp-server.mjs');
  const child = spawn(
    process.execPath,
    [script, '--port', String(SSH_FIXTURE_PORT), '--root', FIXTURE_ROOT, '--key', FIXTURE_KEY, '--user', FIXTURE_USER, '--pass', FIXTURE_PASS, '--home', FIXTURE_HOME],
    { cwd: gatewayDir, stdio: ['ignore', 'pipe', 'pipe'] }
  );
  const ready = await new Promise((resolve) => {
    let out = '';
    const timer = setTimeout(() => resolve(false), 15000);
    child.stdout.on('data', (d) => {
      out += d.toString();
      if (out.includes('FIXTURE_READY')) { clearTimeout(timer); resolve(true); }
    });
    child.stderr.on('data', (d) => { const s = d.toString().trim(); if (s) console.log('      [fixture]', s); });
    child.on('exit', () => { clearTimeout(timer); resolve(false); });
  });
  if (!ready) { try { child.kill('SIGKILL'); } catch (e) { /* ignore */ } }
  if (!ready) throw new Error(`SFTP 夹具启动失败（端口 ${SSH_FIXTURE_PORT}）`);
  console.log(`      夹具已启动 127.0.0.1:${SSH_FIXTURE_PORT} home=${FIXTURE_HOME} root=${FIXTURE_ROOT}`);
  return child;
}

async function waitCdp() {
  for (let i = 0; i < 80; i++) {
    try { const r = await fetch(`http://127.0.0.1:${PORT}/json/version`); if (r.ok) return true; } catch (e) {}
    await sleep(250);
  }
  return false;
}

// WATCHDOG：无人值守时避免无限等待（超时即失败退出，便于自动化）
let __finished = false;
setTimeout(() => {
  if (__finished) return;
  console.log('FAIL | WATCHDOG 全局超时 900s，强制退出');
  process.exit(1);
}, 900 * 1000);

(async () => {
  logInstance('dual');
  ensureDirs();
  const fixture = await startFixture();   // 自包含 SFTP 服务（USE_FIXTURE=false 时为 null）
  // ---------- 夹具（本脚本自建自清，运行结束不留残余）----------
  const localFixtures = [UPLOAD_TXT, UPLOAD_BIN, DL_TXT, HIDDEN_LOCAL].map((n) => path.join(LOCAL_HOME, n));
  fs.rmSync(path.join(LOCAL_HOME, LOCAL_NEW_DIR), { recursive: true, force: true });
  fs.writeFileSync(localFixtures[0], 'hello dual pane\n'.repeat(10));
  if (!fs.existsSync(localFixtures[1])) fs.writeFileSync(localFixtures[1], crypto.randomBytes(262144));
  // 隐藏文件（默认不应该出现在双栏列表里；点开关后才出现）
  fs.writeFileSync(localFixtures[3], 'hidden local\n'.repeat(4));
  const binBuf = fs.readFileSync(localFixtures[1]);
  const txtBuf = fs.readFileSync(localFixtures[0]);
  const dlContent = Buffer.from('download-by-real-click\n'.repeat(64));
  const conn = await rpc('sftp', 'connect', { host: HOST, port: Number(PORT_SSH), user: USER, password: PASS });
  check(`D0 网关连接${USE_FIXTURE ? '内置夹具' : '真实服务器'}（${HOST}:${PORT_SSH}）`, !!conn.sessionId, conn.sessionId || JSON.stringify(conn).slice(0, 120));
  const sid = conn.sessionId;
  const cleanRemote = async () => { for (const p of [`${HOME}/${UPLOAD_TXT}`, `${HOME}/${UPLOAD_BIN}`, `${HOME}/${DL_TXT}`, `${HOME}/${NEW_DIR}`, `${HOME}/${HIDDEN_REMOTE}`]) { try { await rpc('sftp', 'rm', { sessionId: sid, remotePath: p, recursive: true }); } catch (e) {} } };
  await cleanRemote();
  // 造一个远端文件用于「下载」用例
  await rpc('sftp', 'upload', { sessionId: sid, remotePath: `${HOME}/${DL_TXT}`, content: dlContent.toString('base64') });
  // 远端隐藏夹具（同名的 `.` 前缀文件）
  await rpc('sftp', 'upload', { sessionId: sid, remotePath: `${HOME}/${HIDDEN_REMOTE}`, content: Buffer.from('hidden remote\n').toString('base64') });

  const childEnv = buildChildEnv();
  delete childEnv.ELECTRON_RUN_AS_NODE;
  const electronBin = require('electron');
  const child = spawnTestElectron(electronBin, ['.', ...cdpArgs('dual', PORT)], { cwd: electronDir, stdio: 'inherit', env: childEnv });
  const cleanupChild = registerCleanup(child);   // 退出/信号兜底杀本实例应用

  let failures = 0;
  try {
    check('D1 Electron 启动（可见窗口）+ CDP 可用', await waitCdp());

    let page = null;
    for (let i = 0; i < 60; i++) {
      const list = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json();
      page = list.find((t) => t.type === 'page');
      if (page) break;
      await sleep(500);
    }
    if (!page) { check('D2 找到渲染进程页面', false); return; }

    const ws = new WebSocket(page.webSocketDebuggerUrl);
    let id = 0; const pend = new Map(); const errs = [];
    const send = (m, p = {}) => new Promise((r) => { const i = ++id; pend.set(i, r); ws.send(JSON.stringify({ id: i, method: m, params: p })); });
    await new Promise((r) => { ws.onopen = r; });
    ws.onmessage = (e) => {
      const m = JSON.parse(e.data);
      if (m.id && pend.has(m.id)) { pend.get(m.id)(m.result); pend.delete(m.id); }
      if (m.method === 'Runtime.exceptionThrown') errs.push(String(m.params.exceptionDetails?.exception?.description || m.params.exceptionDetails?.text || 'exception'));
    };
    await send('Runtime.enable'); await send('Page.enable');
    const ev = async (expr, awaitPromise = false) => {
      const r = await send('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise });
      if (r && r.exceptionDetails) return 'EVAL_ERR: ' + (r.exceptionDetails.text || '');
      return r && r.result ? r.result.value : undefined;
    };
    const bodyText = async () => String(await ev('document.body.innerText'));
    const waitText = async (pred, ms = 45000) => { const t0 = Date.now(); let t = await bodyText(); while (!pred(t) && Date.now() - t0 < ms) { await sleep(500); t = await bodyText(); } return t; };
    const shot = async (name) => {
      const r = await send('Page.captureScreenshot', { format: 'png' });
      const f = path.join(artifacts, name + '.png');
      fs.writeFileSync(f, Buffer.from(r.data, 'base64'));
      return f;
    };
    // —— 真实点击：找页面中包含该文本的最小元素，用 CDP 派发鼠标事件 ——
    let lastClick = '';
    const clickText = async (txt, nth = 0) => {
      const info = await ev(
        "(()=>{const all=[...document.querySelectorAll('*')].map(e=>({e,r:e.getBoundingClientRect()}))" +
        ".filter(o=>o.e.textContent&&o.e.textContent.trim().includes(" + JSON.stringify(txt) + ")&&o.r.width>1&&o.r.height>1);" +
        "if(!all.length)return null;all.sort((a,b)=>a.r.width*a.r.height-b.r.width*b.r.height);" +
        "const el=all[" + nth + "].e;el.scrollIntoView({block:'center'});const r=el.getBoundingClientRect();" +
        "return {x:r.left+r.width/2,y:r.top+r.height/2,tag:el.tagName,text:String(el.textContent||'').trim().slice(0,24)};})()");
      if (!info) { lastClick = `(未找到「${txt}」)`; return false; }
      lastClick = `「${info.text}」@${Math.round(info.x)},${Math.round(info.y)}`;
      const mouse = (t) => send('Input.dispatchMouseEvent', { type: t, x: info.x, y: info.y, button: 'left', buttons: t === 'mouseReleased' ? 0 : 1, clickCount: 1 });
      await mouse('mousePressed'); await sleep(70); await mouse('mouseReleased'); await sleep(700);
      return true;
    };
    // 在「祖先文本包含 anc」的元素里，点击文本为 txt 的元素（用于区分左右栏同名按钮）
    const clickIn = async (txt, anc) => {
      const info = await ev(
        "(()=>{const cands=[...document.querySelectorAll('*')].filter(e=>e.textContent&&e.textContent.trim()===" + JSON.stringify(txt) + "&&e.getBoundingClientRect().width>1&&e.getBoundingClientRect().height>1);" +
        "const ok=(e)=>{let a=e.parentElement;for(let i=0;i<3&&a;i++,a=a.parentElement){const t=a.textContent||\"\";if(t.includes(" + JSON.stringify(anc) + "))return t.length<=200;}return false;};" +
        "const el=cands.find(ok);if(!el)return null;el.scrollIntoView({block:'center'});const r=el.getBoundingClientRect();" +
        "return {x:r.left+r.width/2,y:r.top+r.height/2,text:String(el.textContent||'').trim()};})()");
      if (!info) { lastClick = `(未找到「${txt}」∈「${anc}」)`; return false; }
      lastClick = `「${info.text}」@${Math.round(info.x)},${Math.round(info.y)}`;
      const mouse = (t) => send('Input.dispatchMouseEvent', { type: t, x: info.x, y: info.y, button: 'left', buttons: t === 'mouseReleased' ? 0 : 1, clickCount: 1 });
      await mouse('mousePressed'); await sleep(70); await mouse('mouseReleased'); await sleep(700);
      return true;
    };
    // 精确文本点击（`trim()===txt`）：用于区分「显示隐藏:关/开」这类**可能互为子串**的开关标签，
    // 也避免 clickText（includes 匹配）误命中包含该文案的父容器。
    const clickExact = async (txt) => {
      const info = await ev(
        "(()=>{const all=[...document.querySelectorAll('*')].map(e=>({e,r:e.getBoundingClientRect()}))" +
        ".filter(o=>o.e.textContent&&o.e.textContent.trim()===" + JSON.stringify(txt) + "&&o.r.width>1&&o.r.height>1);" +
        "if(!all.length)return null;all.sort((a,b)=>a.r.width*a.r.height-b.r.width*b.r.height);" +
        "const el=all[0].e;el.scrollIntoView({block:'center'});const r=el.getBoundingClientRect();" +
        "return {x:r.left+r.width/2,y:r.top+r.height/2,text:String(el.textContent||'').trim()};})()");
      if (!info) { lastClick = `(未找到精确文本「${txt}」)`; return false; }
      lastClick = `「${info.text}」@${Math.round(info.x)},${Math.round(info.y)}`;
      const mouse = (t) => send('Input.dispatchMouseEvent', { type: t, x: info.x, y: info.y, button: 'left', buttons: t === 'mouseReleased' ? 0 : 1, clickCount: 1 });
      await mouse('mousePressed'); await sleep(70); await mouse('mouseReleased'); await sleep(700);
      return true;
    };
    // 真实点击输入框 + 真实键盘输入（兜底：写值并派发 input，等价输入法文本插入）
    const typeInto = async (text) => {
      const sel = `(()=>{const els=[...document.querySelectorAll('input,textarea,[contenteditable="true"]')].filter(e=>{const r=e.getBoundingClientRect();return r.width>2&&r.height>2;});return els.length?els[els.length-1]:null;})()`;
      const pos = await ev(`(()=>{const el=${sel};if(!el)return null;const r=el.getBoundingClientRect();el.scrollIntoView({block:'center'});return {x:r.left+r.width/2,y:r.top+r.height/2,tag:el.tagName};})()`);
      if (!pos) return 'no-input';
      const mouse = (t) => send('Input.dispatchMouseEvent', { type: t, x: pos.x, y: pos.y, button: 'left', buttons: t === 'mouseReleased' ? 0 : 1, clickCount: 1 });
      await mouse('mousePressed'); await sleep(60); await mouse('mouseReleased'); await sleep(400);
      for (const ch of text) {
        await send('Input.dispatchKeyEvent', { type: 'keyDown', text: ch, unmodifiedText: ch, key: ch });
        await send('Input.dispatchKeyEvent', { type: 'keyUp', key: ch });
        await sleep(30);
      }
      await sleep(400);
      const readVal = `(()=>{const el=${sel};return el?(el.value!==undefined?el.value:el.textContent):'';})()`;
      let v = await ev(readVal);
      if (String(v || '').includes(text)) return 'keys:' + pos.tag;
      await ev(`(()=>{const el=${sel};if(!el)return null;el.focus();if(el.value!==undefined){el.value=${JSON.stringify(text)};}else{el.textContent=${JSON.stringify(text)};}el.dispatchEvent(new Event('input',{bubbles:true}));return true;})()`);
      await sleep(600);
      v = await ev(readVal);
      return (String(v || '').includes(text) ? 'value+input:' : 'failed:') + pos.tag;
    };
    // 点击某目录/文件「所在行」（按 ▶ 定位行，点行左部），避免误命中状态栏文字
    const clickRow = async (name) => {
      const info = await ev(
        "(()=>{const cands=[...document.querySelectorAll('*')].filter(e=>e.textContent&&e.textContent.trim()==='▶');" +
        "const hit=cands.find(e=>{const p=e.parentElement;return p&&p.textContent.includes(" + JSON.stringify(name) + ");});" +
        "if(!hit)return null;const row=hit.parentElement;row.scrollIntoView({block:'center'});const r=row.getBoundingClientRect();" +
        "return {x:r.left+Math.min(120,r.width*0.3),y:r.top+r.height/2};})()");
      if (!info) { lastClick = `(未找到行「${name}」)`; return false; }
      lastClick = `行「${name}」@${Math.round(info.x)},${Math.round(info.y)}`;
      const mouse = (t) => send('Input.dispatchMouseEvent', { type: t, x: info.x, y: info.y, button: 'left', buttons: t === 'mouseReleased' ? 0 : 1, clickCount: 1 });
      await mouse('mousePressed'); await sleep(70); await mouse('mouseReleased'); await sleep(700);
      return true;
    };
    const existsRemote = async (p) => { const r = await rpc('sftp', 'stat', { sessionId: sid, remotePath: p }); return !(r && r.error); };
    const remoteBytes = async (p) => {
      const o = await rpc('sftp', 'openRead', { sessionId: sid, remotePath: p });
      if (!o.fileHandleId) return null;
      const rd = await rpc('sftp', 'read', { fileHandleId: o.fileHandleId, offset: 0, length: 4 * 1024 * 1024 });
      await rpc('sftp', 'close', { fileHandleId: o.fileHandleId });
      return Buffer.from(rd.base64 || '', 'base64');
    };

    // ---------- 打开双栏页 ----------
    const base = pathToFileURL(path.join(electronDir, 'resources', 'index.html')).href;
    await send('Page.navigate', { url: `${base}?page_name=FilesDualPanePage&host=${HOST}&port=${PORT_SSH}&user=${USER}&password=${PASS}&remoteHome=${HOME}` });
    const t0 = await waitText((t) => t.includes('文件管理') && t.includes('本地') && t.includes('远端'));
    check('D2 双栏页渲染（本地/远端两栏）', t0.includes('文件管理') && t0.includes('本地') && t0.includes('远端'), `len=${t0.length}`);
    const listedLocal = await waitText((t) => t.includes(UPLOAD_TXT));
    check('D3 本地栏列出真实本地根（本实例隔离目录）', listedLocal.includes(UPLOAD_TXT), `has ${UPLOAD_TXT}=${listedLocal.includes(UPLOAD_TXT)}`);
    const listedRemote = await waitText((t) => t.includes(DL_TXT));
    check('D4 远端栏列出真实远端目录', listedRemote.includes(DL_TXT), `has ${DL_TXT}=${listedRemote.includes(DL_TXT)}`);
    console.log('      截图:', await shot('D0-双栏初始'));

    // ---------- D5 真实点击：选中本地文件 ----------
    await clickText(UPLOAD_TXT);
    let t = await bodyText();
    const selOk = /已选\s*1\s*项/.test(t);
    check('D5 点击本地文件 → 选中 1 项（真实点击）', selOk, `${lastClick} | status=${(t.match(/本地已选[^\n]*/) || ['?'])[0]}`);

    // ---------- D6 真实点击：上传（本地→远端），字节级校验 ----------
    await clickText('上传 →');
    const upOk = await (async () => { const t1 = Date.now(); while (Date.now() - t1 < 30000) { if (await existsRemote(`${HOME}/${UPLOAD_TXT}`)) return true; await sleep(800); } return false; })();
    const upBytes = upOk ? await remoteBytes(`${HOME}/${UPLOAD_TXT}`) : null;
    check('D6 点击「上传 →」→ 远端出现该文件（真实点击）', upOk, lastClick);
    check('D7 上传内容字节级一致', !!upBytes && upBytes.equals(txtBuf), upBytes ? `local=${txtBuf.length}B remote=${upBytes.length}B equal=${upBytes.equals(txtBuf)}` : 'no bytes');
    console.log('      截图:', await shot('D1-上传完成'));
    await clickText('关闭');

    // ---------- D8 二进制上传（256KB 随机）----------
    await clickText('刷新');                        // 刷新不改变核心选择，先显式取消 D6 的文本选择
    await waitText((x) => x.includes(UPLOAD_TXT) && x.includes(UPLOAD_BIN), 5000);
    await sleep(1200);                              // 等待列表重排完成，避免读取旧 DOM 状态
    await clickText(UPLOAD_TXT);
    await sleep(800);
    const d8Cleared = /本地已选\s*0\s*项/.test(await bodyText());
    const d8FileClicked = await clickText(UPLOAD_BIN);
    await sleep(1000);
    const d8Selected = /本地已选\s*1\s*项/.test(await bodyText());
    check('D8 选择二进制文件前清除上一笔选择', d8Cleared && d8FileClicked && d8Selected, `${lastClick} | cleared=${d8Cleared} file=${d8FileClicked} selected=${d8Selected}`);
    await clickText('上传 →');
    const upBinOk = await (async () => { const t1 = Date.now(); while (Date.now() - t1 < 60000) { if (await existsRemote(`${HOME}/${UPLOAD_BIN}`)) { const b = await remoteBytes(`${HOME}/${UPLOAD_BIN}`); if (b && b.length === binBuf.length) return true; } await sleep(1000); } return false; })();
    const upBinBytes = upBinOk ? await remoteBytes(`${HOME}/${UPLOAD_BIN}`) : null;
    check('D8 二进制文件上传(256KB)字节级一致', !!upBinBytes && upBinBytes.equals(binBuf), upBinBytes ? `local=${binBuf.length}B remote=${upBinBytes.length}B equal=${upBinBytes.equals(binBuf)}` : 'no bytes');
    // 传输任务详情标题必须与方向一致，不能把上传任务误标为「下载详情」。
    const uploadDetailSummary = await waitText((x) => x.includes('上传详情'), 5000);
    check('D8a 上传后底部显示「上传详情」入口', uploadDetailSummary.includes('上传详情'), uploadDetailSummary.slice(-300));
    const uploadDetailClicked = await clickText('上传详情');
    const uploadDetailText = await waitText((x) => x.includes('上传详情') && x.includes(UPLOAD_BIN), 5000);
    check('D8b 上传详情面板标题与任务方向一致', uploadDetailClicked && uploadDetailText.includes(UPLOAD_BIN), uploadDetailText.slice(-400));
    await clickText('关闭');

    // ---------- D9 真实点击：远端文件 → 下载到本地栏 ----------
    await clickText('刷新');
    await clickText(DL_TXT);
    await clickText('← 下载');
    const detailSummary = await waitText((x) => x.includes('下载详情'), 5000);
    check('D9a 下载后底部出现「下载详情」入口', detailSummary.includes('下载详情'), detailSummary.slice(-300));
    const detailClicked = await clickText('下载详情');
    const detailText = await waitText((x) => x.includes('下载详情') && x.includes(DL_TXT), 5000);
    check('D9b 点击底部状态打开下载详情面板', detailClicked && detailText.includes(DL_TXT), detailText.slice(-500));
    check('D9c 下载详情显示进度与速率字段', /\d+%|B\/s|KB\/s|MB\/s/.test(detailText), detailText.slice(-500));
    console.log('      截图:', await shot('D2-下载详情'));
    await clickText('关闭');
    const dlLocal = path.join(LOCAL_HOME, DL_TXT);
    const dlOk = await (async () => { const t1 = Date.now(); while (Date.now() - t1 < 30000) { try { const b = fs.readFileSync(dlLocal); if (b.length === dlContent.length) return true; } catch (e) {} await sleep(800); } return false; })();
    const dlBytes = dlOk ? fs.readFileSync(dlLocal) : null;
    check('D9 点击「← 下载」→ 本地出现远端文件（真实点击）', dlOk, lastClick);
    check('D10 下载内容字节级一致', !!dlBytes && dlBytes.equals(dlContent), dlBytes ? `remote=${dlContent.length}B local=${dlBytes.length}B equal=${dlBytes.equals(dlContent)}` : 'no file');
    console.log('      截图:', await shot('D2-下载完成'));

    // ---------- D11 真实点击：远端新建文件夹（弹层 + 输入 + 确定）----------
    const plusClicked = await clickIn('+', '远端');
    {
      const dlg = await waitText((x) => x.includes('新建文件夹'), 8000);
      check('D11 点击「远端」栏头 + → 出现新建弹层（真实点击）', plusClicked && dlg.includes('新建文件夹'), lastClick);
      const typed = await typeInto(NEW_DIR);
      await clickText('确定');
      const madeOk = await (async () => { const t1 = Date.now(); while (Date.now() - t1 < 15000) { if (await existsRemote(`${HOME}/${NEW_DIR}`)) return true; await sleep(600); } return false; })();
      check('D12 输入名称 + 确定 → 远端真实创建目录', madeOk, `${NEW_DIR} exists=${madeOk} | 输入=${typed}`);
      console.log('      截图:', await shot('D3-新建目录'));
    }

    // ---------- D13 真实点击：进入远端目录 ----------
    const before = await bodyText();
    const beforePath = (before.match(new RegExp(escapeRe(HOME) + '[^\\s\\n]*')) || [''])[0];
    const opened = await clickIn('▶', NEW_DIR);
    const afterTxt = await waitText((x) => x.includes(HOME + '/') && x.includes(NEW_DIR), 10000);
    const afterPath = (afterTxt.match(new RegExp(`${HOME}/${NEW_DIR}`)) || [''])[0];
    check('D13 点击目录行「▶」→ 进入下一级（真实点击）', opened && !!afterPath, `${beforePath} -> ${afterPath} | ${lastClick}`);

    // ---------- D14 真实点击：返回上级 → 选中新建目录 → 删除（弹层确认）----------
    const upClicked = await clickIn('↑', HOME);     // 限定包含远端当前路径的栏头，避免命中本地栏按钮
    const backTxt = await waitText((x) => x.includes('文件管理') && !x.includes(`${HOME}/${NEW_DIR}`), 10000);
    check('D14a 远端栏「↑」返回上级（真实点击）', upClicked && !backTxt.includes(`${HOME}/${NEW_DIR}`), lastClick);
    const rowSel = await clickRow(NEW_DIR);         // 行点击 = 选中（按 ▶ 定位所在行）
    const afterSel = await bodyText();
    const selOk2 = /远端已选\s*1\s*项/.test(afterSel);
    check('D14b 点击目录行 → 选中（真实点击）', rowSel && selOk2, `${lastClick} | ${(afterSel.match(/远端已选[^\n]*/) || ['?'])[0]}`);
    await clickText('删除');
    const confirmShown = await waitText((x) => x.includes('确认删除'), 8000);
    check('D14c 点击「删除」→ 出现确认弹层（真实点击）', confirmShown.includes('确认删除'), lastClick);
    await clickIn('删除', '确认删除');                // 弹层内的「删除」按钮
    await sleep(1500);
    const goneOk = !(await existsRemote(`${HOME}/${NEW_DIR}`));
    check('D15 确认删除 → 远端目录被删（真实点击）', goneOk, `${NEW_DIR} exists=${!goneOk}`);
    console.log('      截图:', await shot('D4-删除后'));

    // ---------- D20 安全回归：网关本地路径越界必须被拒（2001）----------
    // 网关返回的 error 是 JSON 字符串（也可能是对象），统一取 code
    const errCode = (r) => {
      if (!r || r.error == null) return null;
      let e = r.error;
      if (typeof e === 'string') { try { e = JSON.parse(e); } catch (_) { return null; } }
      return e && e.code != null ? Number(e.code) : null;
    };
    const denyUp = await rpc('sftp', 'upload', { sessionId: sid, remotePath: `${HOME}/${UPLOAD_TXT}`, localPath: '/etc/passwd' });
    const deniedR = errCode(denyUp) === 2001;
    check('D20 网关拒绝越界 localPath 上传（2001）', deniedR, JSON.stringify(denyUp).slice(0, 140));
    const denyDl = await rpc('sftp', 'download', { sessionId: sid, remotePath: `${HOME}/${DL_TXT}`, localName: '/etc/kr_escape_should_not_exist' });
    const denyDW = errCode(denyDl) === 2001;
    const escaped = fs.existsSync('/etc/kr_escape_should_not_exist');
    check('D21 网关拒绝越界绝对路径下载（2001，且未落盘）', denyDW && !escaped, `denied=${denyDW} leaked=${escaped}`);
    const okUp = await rpc('sftp', 'upload', { sessionId: sid, remotePath: `${HOME}/000_kuikly_dual_scope_${INSTANCE}.txt`, localPath: `${LOCAL_HOME}/${UPLOAD_TXT}` });
    check('D22 根内 localPath 上传仍可用（不被误拒）', !!okUp && !okUp.error, JSON.stringify(okUp).slice(0, 120));
    try { await rpc('sftp', 'rm', { sessionId: sid, remotePath: `${HOME}/000_kuikly_dual_scope_${INSTANCE}.txt` }); } catch (e) {}

    // ---------- D17 首页默认入口：本地文件管理 ----------
    await send('Page.navigate', { url: `${base}?page_name=SftpHomePage` });
    const homeTxt = await waitText((t) => t.includes('本地文件管理') && t.includes('SFTP 客户端'), 45000);
    check('D17 首页出现默认「本地文件管理」入口', homeTxt.includes('本地文件管理'), `len=${homeTxt.length}`);
    const homeClicked = await clickText('本地文件管理');
    const localMode = await waitText((t) => t.includes('文件管理') && t.includes('本地') && t.includes('未连接远端'), 20000);
    check('D18 点击后进入双栏（本地可用，远端待选主机）', homeClicked && localMode.includes('未连接远端'), `${lastClick} | hasHint=${localMode.includes('未连接远端')}`);
    console.log('      截图:', await shot('D5-本地模式'));

    // ---------- D23 本地栏操作（活动栏=本地 + realpath 后的 localfs:*）----------
    const plusLocal = await clickIn('+', '本地');
    await waitText((x) => x.includes('新建文件夹'), 8000);
    await typeInto(LOCAL_NEW_DIR);
    await clickText('确定');
    const localMade = await (async () => { const t1 = Date.now(); const p = path.join(LOCAL_HOME, LOCAL_NEW_DIR); while (Date.now() - t1 < 12000) { if (fs.existsSync(p)) return true; await sleep(500); } return false; })();
    check('D23 本地栏「+」新建目录 → 真实创建（活动栏默认本地）', localMade, `${LOCAL_NEW_DIR} exists=${localMade}`);
    const rowSelLocal = await clickRow(LOCAL_NEW_DIR);
    await clickText('删除');
    await waitText((x) => x.includes('确认删除'), 8000);
    await clickIn('删除', '确认删除');
    const localGone = await (async () => { const t1 = Date.now(); const p = path.join(LOCAL_HOME, LOCAL_NEW_DIR); while (Date.now() - t1 < 12000) { if (!fs.existsSync(p)) return true; await sleep(500); } return false; })();
    check('D24 本地栏删除目录 → 真实删除（真实点击）', rowSelLocal && localGone, lastClick);
    console.log('      截图:', await shot('D7-本地栏操作'));

    // ---------- D19 远端文件列表右上角双栏入口 ----------
    await send('Page.navigate', { url: `${base}?page_name=SftpBrowserPage&host=${HOST}&port=${PORT_SSH}&user=${USER}&password=${PASS}&remotePath=${HOME}` });
    await waitText((t) => t.includes('/home/'), 45000);
    const entryClicked = await clickText('⇄');
    const dualFromBrowser = await waitText((t) => t.includes('文件管理') && t.includes('远端') && t.includes(HOME), 20000);
    check('D19 浏览页右上角「⇄」→ 双栏（远端栏定位到当前目录，真实点击）', entryClicked && dualFromBrowser.includes(HOME), `${lastClick}`);
    // D25 安全回归：双栏 URL（SPA 路由会把 pageData 拼进 query）不得再出现凭据
    const href = String(await ev('location.href'));
    const noSecret = !/password=|privateKey=|passphrase=/.test(href);
    check('D25 双栏 URL 不含凭据（不再把密钥写进 history）', noSecret, href.length > 120 ? href.slice(0, 120) + '…' : href);
    console.log('      截图:', await shot('D6-浏览页入口'));

    // ---------- D26 隐藏文件开关（默认不显示 → 点击显示 → 再点隐藏）----------
    // 默认态：两栏都不应出现 `.` 开头的夹具，且按钮文案为「显示隐藏:关」
    const defTxt = await bodyText();
    const defLabel = defTxt.includes('显示隐藏:关');
    const defLocalHidden = defTxt.includes(HIDDEN_LOCAL);
    const defRemoteHidden = defTxt.includes(HIDDEN_REMOTE);
    check('D26a 默认不显示隐藏文件（两栏均无 . 开头条目，按钮为「显示隐藏:关」）',
      defLabel && !defLocalHidden && !defRemoteHidden,
      `label=${defLabel} localHidden=${defLocalHidden} remoteHidden=${defRemoteHidden}`);
    console.log('      截图:', await shot('D8-隐藏文件默认关'));

    const toggleOn = await clickExact('显示隐藏:关');
    const shownTxt = await waitText((x) => x.includes(HIDDEN_LOCAL) && x.includes(HIDDEN_REMOTE), 12000);
    const onLabel = shownTxt.includes('显示隐藏:开');
    check('D26b 点击「显示隐藏:关」→ 两栏显示隐藏文件且标签变「开」',
      toggleOn && onLabel && shownTxt.includes(HIDDEN_LOCAL) && shownTxt.includes(HIDDEN_REMOTE),
      `${lastClick} | local=${shownTxt.includes(HIDDEN_LOCAL)} remote=${shownTxt.includes(HIDDEN_REMOTE)} label=${onLabel}`);
    console.log('      截图:', await shot('D9-隐藏文件已显示'));

    const toggleOff = await clickExact('显示隐藏:开');
    await sleep(900);
    const offTxt = await bodyText();
    check('D26c 再次点击 → 隐藏文件重新隐藏且标签回「关」',
      toggleOff && offTxt.includes('显示隐藏:关') && !offTxt.includes(HIDDEN_LOCAL) && !offTxt.includes(HIDDEN_REMOTE),
      `${lastClick} | local=${offTxt.includes(HIDDEN_LOCAL)} remote=${offTxt.includes(HIDDEN_REMOTE)} label=${offTxt.includes('显示隐藏:关')}`);
    console.log('      截图:', await shot('D10-隐藏文件已重新隐藏'));

    check('D16 无 JS 未捕获异常', errs.length === 0, errs.slice(0, 2).join('; '));
  } finally {
    cleanupChild();   // 测试结束立即杀掉本实例应用（双保险：pre/post hook）
    await cleanRemote();
    for (const f of localFixtures) { try { fs.unlinkSync(f); } catch (e) {} }   // 删除测试文件
    if (fixture) { try { fixture.kill('SIGKILL'); } catch (e) { /* ignore */ } }
    const fail = results.filter((r) => !r.ok).length;
    console.log(`\n[dual-pane] ${results.length - fail}/${results.length} 通过；截图目录 ${artifacts}`);
    process.exit(fail ? 1 : 0);
  }
})();

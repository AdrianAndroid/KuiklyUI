/*
 * Kuikly SFTP Web 端自动化测试（网关 RPC + 浏览器 UI）
 *
 * 运行：
 *   npm test                 # 全量：网关 RPC + 浏览器（需 Chrome）
 *   npm run test:rpc         # 只跑网关 RPC/Range（不需要浏览器）
 *
 * 依赖：Node >= 22（需要全局 fetch / WebSocket）。
 * 前置：
 *   1) 网关已启动：  node server.js            （默认 127.0.0.1:18090）
 *   2) 全量测试还需要页面已起：8080(壳) + 8083(bundle)，见 AGENTS.md §13.1.3
 *
 * 可用环境变量覆盖（默认指向 AGENTS.md §13.6 的内网测试机）：
 *   GATEWAY_URL, WEB_URL, CHROME_PATH,
 *   SFTP_HOST, SFTP_PORT, SFTP_USER, SFTP_PASSWORD, SFTP_HOME,
 *   SFTP_MEDIA (默认 ${SFTP_HOME}/sftp_kuikly_media.mp4)
 */

'use strict';

const { spawn } = require('child_process');

const G = process.env.GATEWAY_URL || 'http://127.0.0.1:18090';
const WEB = process.env.WEB_URL || 'http://127.0.0.1:8080';
const CHROME = process.env.CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const CDP_PORT = parseInt(process.env.CDP_PORT || '9230', 10);

const HOST = process.env.SFTP_HOST || '192.168.2.2';
const PORT = parseInt(process.env.SFTP_PORT || '22', 10);
const USER = process.env.SFTP_USER || 'zhaojian';
const PASS = process.env.SFTP_PASSWORD || 'zhaojian';
const HOME = process.env.SFTP_HOME || '/home/zhaojian';
const MEDIA = process.env.SFTP_MEDIA || HOME + '/sftp_kuikly_media.mp4';

// 测试素材 sftp_kuikly_media.mp4（95627 字节）的字节级校验基准
const MEDIA_SIZE = parseInt(process.env.SFTP_MEDIA_SIZE || '95627', 10);
const HEAD16 = process.env.SFTP_MEDIA_HEAD16 || '000000206674797069736f6d00000200';
const MID16 = process.env.SFTP_MEDIA_MID16 || 'bf83b361bd4b46fbbca64bc57df7c2ef';

const RPC_ONLY = process.argv.includes('--rpc-only');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const hex = (buf) => Buffer.from(buf).toString('hex');

const results = [];
function check(name, ok, detail) {
  results.push({ name, ok: !!ok, detail });
  console.log(`${ok ? 'PASS' : 'FAIL'} | ${name}${detail ? ' | ' + detail : ''}`);
}

async function rpc(module, method, params) {
  const r = await fetch(G + '/rpc', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ module, method, params }),
  });
  return r.json();
}

async function reachable(url) {
  try { const r = await fetch(url); return r.status < 500; } catch (e) { return false; }
}

/* ---------------- A. 网关 RPC / 媒体 Range ---------------- */
async function partA() {
  let sid = '';
  try {
    const h = await (await fetch(G + '/health')).json();
    check('A1 网关健康', h && h.ok === true, 'port=' + h.port);
  } catch (e) {
    check('A1 网关健康', false, String(e) + ' —— 请先 `node server.js`');
    return { sid };
  }

  const c = await rpc('sftp', 'connect', { host: HOST, port: PORT, user: USER, password: PASS });
  sid = c.sessionId || '';
  check('A2 连接真实服务器', !!sid, sid || JSON.stringify(c));
  if (!sid) return { sid };

  const l = await rpc('sftp', 'list', { sessionId: sid, remotePath: HOME });
  const names = (l.entries || []).map((e) => e.name);
  check('A3 列目录(远端)', names.length > 0, 'entries=' + names.length);

  const st = await rpc('sftp', 'stat', { sessionId: sid, remotePath: MEDIA });
  check('A4 stat 媒体文件', !!(st.entry && st.entry.size === MEDIA_SIZE), 'size=' + (st.entry && st.entry.size));

  const fh = await rpc('sftp', 'openRead', { sessionId: sid, remotePath: MEDIA });
  const r0 = await rpc('sftp', 'read', { fileHandleId: fh.fileHandleId, offset: 0, length: 16 });
  const h0 = hex(Buffer.from(r0.base64 || '', 'base64'));
  check('A5 随机读 offset=0 字节校验', h0 === HEAD16, h0);
  const r5 = await rpc('sftp', 'read', { fileHandleId: fh.fileHandleId, offset: 50000, length: 16 });
  const h5 = hex(Buffer.from(r5.base64 || '', 'base64'));
  check('A6 随机读 offset=50000 字节校验', h5 === MID16, h5);
  await rpc('sftp', 'close', { fileHandleId: fh.fileHandleId });

  const rt = await rpc('mediaProxy', 'registerToken', { sessionId: sid, remotePath: MEDIA, totalSize: MEDIA_SIZE });
  const mr = await fetch(`${G}/${rt.token}/${encodeURIComponent(MEDIA.split('/').pop())}`, { headers: { Range: 'bytes=0-15' } });
  const mb = Buffer.from(await mr.arrayBuffer());
  check('A7 媒体 HTTP Range(206)', mr.status === 206 && (mr.headers.get('content-range') || '').includes('0-15/' + MEDIA_SIZE),
    mr.status + ' ' + mr.headers.get('content-range'));
  check('A8 媒体 Range 字节校验', hex(mb) === HEAD16, hex(mb));

  const dir = process.env.SFTP_TEST_DIR || HOME + '/kr_webtest_dir';
  const dir2 = dir + '2';
  await rpc('sftp', 'rm', { sessionId: sid, remotePath: dir, recursive: true });
  await rpc('sftp', 'rm', { sessionId: sid, remotePath: dir2, recursive: true });
  const mk = await rpc('sftp', 'mkdir', { sessionId: sid, remotePath: dir, recursive: true });
  const dstat = await rpc('sftp', 'stat', { sessionId: sid, remotePath: dir });
  check('A9 建目录 + stat', mk.ok === true && !!(dstat.entry && dstat.entry.isDir), JSON.stringify(dstat.entry || dstat));
  const rn = await rpc('sftp', 'rename', { sessionId: sid, oldPath: dir, newPath: dir2 });
  const d2 = await rpc('sftp', 'stat', { sessionId: sid, remotePath: dir2 });
  check('A10 重命名', rn.ok === true && !!d2.entry, 'newIsDir=' + (d2.entry && d2.entry.isDir));
  await rpc('sftp', 'rm', { sessionId: sid, remotePath: dir2, recursive: true });
  const gone = await rpc('sftp', 'stat', { sessionId: sid, remotePath: dir2 });
  check('A11 递归删除后不存在', !!gone.error, gone.error ? '已删除' : JSON.stringify(gone));

  const cn = await rpc('connection', 'add', { label: '__webtest__', host: HOST, port: PORT, user: USER, password: PASS });
  const cl = await rpc('connection', 'list', {});
  const inList = (cl.items || []).some((x) => x.id === cn.id);
  await rpc('connection', 'remove', { id: cn.id });
  check('A12 连接持久化 add/list/remove', inList, 'id=' + cn.id);

  return { sid };
}

/* ---------------- B. 浏览器 UI（CDP） ---------------- */
async function openTab(url) {
  const t = await (await fetch(`http://127.0.0.1:${CDP_PORT}/json/new?${encodeURIComponent(url)}`, { method: 'PUT' })).json();
  const ws = new WebSocket(t.webSocketDebuggerUrl);
  let id = 0;
  const pending = new Map();
  const consoleErrors = [];
  const send = (m, p = {}) => new Promise((res) => { const i = ++id; pending.set(i, res); ws.send(JSON.stringify({ id: i, method: m, params: p })); });
  await new Promise((r) => { ws.onopen = r; });
  ws.onmessage = (e) => {
    const m = JSON.parse(e.data);
    if (m.id && pending.has(m.id)) { pending.get(m.id)(m.result); pending.delete(m.id); }
    if (m.method === 'Runtime.exceptionThrown') consoleErrors.push(m.params.exceptionDetails.text || 'exception');
  };
  await send('Runtime.enable');
  await send('Page.enable');
  await send('Page.navigate', { url });
  const evalJs = async (expr) => (await send('Runtime.evaluate', { expression: expr, returnByValue: true })).result.value;
  return { send, evalJs, consoleErrors, close: () => { try { ws.close(); } catch (e) {} } };
}

const TIME_RE = `/\\d\\d:\\d\\d \\/ \\d\\d:\\d\\d/`;
const pickTime = `(document.body.innerText.match(${TIME_RE})||['none'])[0]`;

async function partB(sid) {
  const home = await openTab(`${WEB}/?page_name=SftpHomePage`);
  await sleep(8000);
  const homeText = await home.evalJs('document.body.innerText');
  check('B1 首页渲染(SFTP 客户端)', homeText.includes('SFTP 客户端'), 'len=' + homeText.length);
  check('B2 首页无 JS 异常', home.consoleErrors.length === 0, home.consoleErrors.slice(0, 2).join(';'));
  home.close();

  const browseUrl = `${WEB}/?page_name=SftpBrowserPage&host=${HOST}&port=${PORT}&user=${USER}&password=${PASS}&remotePath=${HOME}`;
  const browse = await openTab(browseUrl);
  await sleep(9000);
  const bText = await browse.evalJs('document.body.innerText');
  const mediaName = MEDIA.split('/').pop();
  check('B3 文件浏览渲染真实目录', bText.includes(mediaName), 'hasMedia=' + bText.includes(mediaName));
  check('B4 浏览页无 JS 异常', browse.consoleErrors.length === 0, browse.consoleErrors.slice(0, 2).join(';'));
  browse.close();

  const playerUrl = `${WEB}/?page_name=SftpPlayerPage&sessionId=${sid}&connectionId=c1&connectionLabel=test`
    + `&remotePath=${MEDIA}&name=${mediaName}&size=${MEDIA_SIZE}`;
  const player = await openTab(playerUrl);
  await sleep(5000);
  const t1 = await player.evalJs(pickTime);
  await sleep(2500);
  const t2 = await player.evalJs(pickTime);
  const total = (t2.match(/\/\s*(\d\d:\d\d)/) || [])[1] || '';
  check('B5 视频播放(时间前进)', t1 !== 'none' && t1 !== t2 && /\d\d:\d\d \/ 00:0[5-9]/.test(t2), `${t1} -> ${t2}`);

  const rect = await player.evalJs(
    "(()=>{const d=[...document.querySelectorAll('div')].filter(x=>{const r=x.getBoundingClientRect();return Math.abs(r.height-28)<1.5&&r.width>300;});if(!d.length)return null;const r=d[0].getBoundingClientRect();return {x:r.left,y:r.top,w:r.width,h:r.height};})()");
  let seekOk = false;
  let seekDetail = 'no-progress-bar';
  if (rect) {
    const y = rect.y + rect.h / 2;
    const x1 = rect.x + rect.w * 0.9;
    const x2 = rect.x + rect.w * 0.2;
    const mouse = (type, x) => player.send('Input.dispatchMouseEvent', { type, x, y, button: 'left', buttons: type === 'mouseReleased' ? 0 : 1, clickCount: 1 });
    await mouse('mousePressed', x1);
    await sleep(120);
    for (let i = 1; i <= 8; i++) { await mouse('mouseMoved', x1 + (x2 - x1) * i / 8); await sleep(60); }
    await mouse('mouseReleased', x2);
    await sleep(800);
    const after = await player.evalJs(pickTime);
    seekOk = /00:0[0-3] \/ 00:0[5-9]/.test(after);
    seekDetail = after;
  }
  check('B6 拖动进度条 seek', seekOk, seekDetail);

  const btn = await player.evalJs(
    "(()=>{const el=[...document.querySelectorAll('*')].find(e=>e.children.length===0&&e.textContent.trim()==='1.0×');if(!el)return null;const r=el.getBoundingClientRect();return {x:r.left+r.width/2,y:r.top+r.height/2};})()");
  let menuOk = false;
  if (btn) {
    const mouse = (type) => player.send('Input.dispatchMouseEvent', { type, x: btn.x, y: btn.y, button: 'left', buttons: type === 'mouseReleased' ? 0 : 1, clickCount: 1 });
    await mouse('mousePressed');
    await sleep(60);
    await mouse('mouseReleased');
    await sleep(700);
    menuOk = await player.evalJs("document.body.innerText.includes('1.25×') && document.body.innerText.includes('0.5×')");
  }
  check('B7 设置菜单(倍速)展开', menuOk, menuOk ? '含 0.5×/1.25×' : '未展开');
  check('B8 播放页无 JS 异常', player.consoleErrors.length === 0, player.consoleErrors.slice(0, 2).join(';'));
  player.close();
}

/* ---------------- 入口 ---------------- */
(async () => {
  console.log('=== Kuikly SFTP Web 端自动化测试 ===');
  console.log(`gateway=${G}  web=${WEB}  rpcOnly=${RPC_ONLY}`);

  let chrome = null;
  try {
    const { sid } = await partA();
    if (!RPC_ONLY) {
      if (!(await reachable(WEB))) {
        check('B0 页面服务可达', false, `${WEB} 不可达 —— 请先起 8080/8083（AGENTS.md §13.1.3）`);
      } else {
        chrome = spawn(CHROME, ['--headless=new', '--disable-gpu', '--no-sandbox', '--no-first-run',
          '--autoplay-policy=no-user-gesture-required', '--user-data-dir=/tmp/kr-webtest',
          '--remote-debugging-port=' + CDP_PORT, '--window-size=1000,760', 'about:blank'], { stdio: 'ignore' });
        for (let i = 0; i < 60; i++) {
          try { const r = await fetch(`http://127.0.0.1:${CDP_PORT}/json/version`); if (r.ok) break; } catch (e) {}
          await sleep(250);
        }
        await partB(sid);
      }
    }
  } catch (e) {
    console.error('ERROR', e);
  } finally {
    if (chrome) { try { chrome.kill(); } catch (e) {} }
  }

  const pass = results.filter((r) => r.ok).length;
  console.log(`\n=== 结果: ${pass}/${results.length} 通过 ===`);
  results.filter((r) => !r.ok).forEach((f) => console.log('FAILED -> ' + f.name + ' | ' + (f.detail || '')));
  process.exit(results.some((r) => !r.ok) ? 1 : 0);
})();

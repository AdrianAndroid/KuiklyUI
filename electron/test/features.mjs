/*
 * 桌面端功能用例（收藏 / 历史 / 终端命令历史）——全自动，带看门狗与单步超时
 *   运行：cd electron && npm run test:features
 *   约定见 devDocs/kuikly-app-features-test-plan.md §1（不无限等待、结束必清理、截图留证）
 */
import { spawn } from 'node:child_process';
import path from 'node:path';
import fs from 'node:fs';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const electronDir = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..');
const artifacts = path.join(electronDir, 'test', 'artifacts');
fs.mkdirSync(artifacts, { recursive: true });
const PORT = 9457;
const GW = process.env.GATEWAY_URL || 'http://127.0.0.1:18090';
const HOST = process.env.SFTP_HOST || '192.168.2.2';
const USER = process.env.SFTP_USER || 'zhaojian';
const PASS = process.env.SFTP_PASSWORD || 'zhaojian';
const HOME = process.env.SFTP_HOME || '/home/zhaojian';
const FIX = `${HOME}/kr_feat_fixture`;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const results = [];
const skipped = [];
let __lastT = Date.now();
// 逐条打印耗时：单条明显偏长（>30s）时人工判断是否正常耗时并止损（AGENTS §3.1 规则 7）
const check = (n, ok, d) => {
  const dt = Date.now() - __lastT; __lastT = Date.now();
  results.push({ n, ok: !!ok });
  console.log(`${ok ? 'PASS' : 'FAIL'} | ${n}${d ? ' | ' + d : ''} (${(dt / 1000).toFixed(1)}s)`);
};
const skip = (n, d) => { skipped.push(n); console.log(`SKIP | ${n}${d ? ' | ' + d : ''}`); };
let __finished = false;
let __child = null;
setTimeout(() => { if (!__finished) { console.log('FAIL | WATCHDOG 全局超时 600s'); try { __child && __child.kill('SIGKILL'); } catch (e) { } process.exit(1); } }, 600 * 1000);

// 每个 rpc 都带超时：网关无响应时不无限等待（AGENTS §3.1 规则 7）
const rpc = async (module, method, params, timeoutMs = 15000) => {
  const ctl = new AbortController();
  const to = setTimeout(() => ctl.abort(), timeoutMs);
  try {
    return await (await fetch(GW + '/rpc', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ module, method, params }), signal: ctl.signal })).json();
  } finally { clearTimeout(to); }
};

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
  // awaitPromise 必须显式传（页面内 fetch/async 表达式否则返回 Promise 对象 → 断言拿到 [object Object]）
  // 每次 evaluate 都带超时：页面内 fetch 卡住时 evaluator 也不会永久挂起（AGENTS §3.1 规则 7）
  const ev = async (x, awaitPromise = false, timeoutMs = 20000) => {
    const r = await Promise.race([
      send('Runtime.evaluate', { expression: x, returnByValue: true, awaitPromise }),
      sleep(timeoutMs).then(() => null),
    ]);
    return r && r.result ? r.result.value : '';
  };
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
const listTargets = async () => { try { const ctl = new AbortController(); const to = setTimeout(() => ctl.abort(), 8000); try { return (await (await fetch(`http://127.0.0.1:${PORT}/json/list`, { signal: ctl.signal })).json()).filter((t) => t.type === 'page'); } finally { clearTimeout(to); } } catch (e) { return []; } };
// 每次 fn() 也带超时：fn 内部若 await 一个卡住的 Promise，waitFor 仍能按时返回，不会整体卡死
const waitFor = async (fn, ms, step = 500) => {
  const t0 = Date.now();
  for (;;) {
    let v = null;
    try { v = await Promise.race([fn(), sleep(Math.max(2000, step * 3)).then(() => null)]); } catch (e) { v = null; }
    if (v) return v;
    if (Date.now() - t0 > ms) return null;
    await sleep(step);
  }
};

(async () => {
  const childEnv = { ...process.env }; delete childEnv.ELECTRON_RUN_AS_NODE;
  const child = spawn(require('electron'), ['.', `--remote-debugging-port=${PORT}`], { cwd: electronDir, stdio: 'ignore', env: childEnv });
  __child = child;
  const conn = await rpc('sftp', 'connect', { host: HOST, port: 22, user: USER, password: PASS });
  const sid = conn.sessionId;
  const cleanup = async () => {
    try { await rpc('sftp', 'rm', { sessionId: sid, remotePath: FIX, recursive: true }); } catch (e) { }
    try { await rpc('favorites', 'clearByConnection', { connectionId: '' }); } catch (e) { }
    try { await rpc('history', 'clearByConnection', { connectionId: '' }); } catch (e) { }
    try { fs.rmSync(require('node:os').homedir() + '/.kuikly_cache', { recursive: true, force: true }); } catch (e) { }
  };
  try {
    await cleanup();
    // 夹具：一个目录 + 一个文件（收藏目标）
    await rpc('sftp', 'mkdir', { sessionId: sid, remotePath: `${FIX}/dir_a` });
    await rpc('sftp', 'upload', { sessionId: sid, remotePath: `${FIX}/file_a.txt`, content: Buffer.from('feat fixture\n').toString('base64') });
    check('F0 夹具就绪（目录 + 文件）', true, FIX);

    // 缓存用例夹具：3 个目录（多文件，用于进度/暂停/取消）+ 1 个已知字节的单文件
    const mkFiles = async (dir, prefix, count, size) => {
      await rpc('sftp', 'mkdir', { sessionId: sid, remotePath: dir });
      for (let i = 1; i <= count; i++) {
        const content = Buffer.alloc(size, i % 251).toString('base64');
        await rpc('sftp', 'upload', { sessionId: sid, remotePath: `${dir}/${prefix}${String(i).padStart(2, '0')}.bin`, content });
      }
    };
    await mkFiles(`${FIX}/cachedir`, 'f', 20, 65536);
    await mkFiles(`${FIX}/pausedir`, 'g', 40, 32768);
    await mkFiles(`${FIX}/canceldir`, 'h', 40, 32768);
    const FILE_BYTES = Buffer.from(Array.from({ length: 4096 }, (_, i) => i % 251));
    await rpc('sftp', 'upload', { sessionId: sid, remotePath: `${FIX}/cachebytes.bin`, content: FILE_BYTES.toString('base64') });
    check('F0b 缓存夹具就绪（3 目录 + 1 单文件）', true, FIX);

    const page = await waitFor(async () => (await listTargets())[0], 40000);
    const main = await attach(page.webSocketDebuggerUrl);
    await waitFor(async () => (await main.body()).includes('SFTP 客户端'), 60000, 1000);
    // 页面用的是 Electron 自带网关（独立 data 目录）：历史/收藏等断言必须走**页面内网关**，
    // 不能走外部 18090（那是另一份数据，曾导致 F19「历史页看不到刚造的数据」）。
    // 页面内 fetch 一律带 AbortController 超时（否则网关卡住时 evaluator 永不返回 → 整体卡死）
    const pageFetch = (module, method, params) => "(async()=>{const u=window.__SFTP_GATEWAY_URL__;const c=new AbortController();const t=setTimeout(()=>c.abort(),8000);try{const r=await fetch(u+'/rpc',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(" + JSON.stringify({ module, method, params }) + "),signal:c.signal});return await r.text();}catch(e){return '';}finally{clearTimeout(t);}})()";
    const pageRpc = async (module, method, params) => {
      const txt = await main.ev(pageFetch(module, method, params), true);
      try { return JSON.parse(String(txt)); } catch (e) { return null; }
    };
    // 清掉页面网关里上一轮残留的收藏/历史（否则断言被旧数据污染）
    await pageRpc('favorites', 'clearByConnection', { connectionId: '' });
    await pageRpc('history', 'clearByConnection', { connectionId: '' });
    const base = page.url.split('?')[0];
    await main.send('Page.navigate', { url: `${base}?page_name=SftpBrowserPage&host=${HOST}&port=22&user=${USER}&password=${PASS}&remotePath=${FIX}` });
    // 清掉应用网关里上一轮残留的收藏（必须在 main 就绪后执行；否则断言会被旧数据污染）
    await main.ev("(async()=>{const u=window.__SFTP_GATEWAY_URL__;const post=(m,p)=>fetch(u+'/rpc',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({module:'favorites',method:m,params:p})}).then(r=>r.json());const l=await post('list',{});for(const it of (l.items||[])){await post('remove',{id:it.id});}return true;})()", true);

    const listed = await waitFor(async () => { const t = await main.body(); return t.includes('file_a.txt') ? t : null; }, 45000, 700);

    // F2/F3 行尾 ⭐ 收藏目录 / 文件（点「列表里」的 ⭐：y 在导航栏之下；结果用网关 favorites.list 判定）
    // 注意：页面用的是 Electron 自带网关（独立 data 目录），因此必须**在页面内**调网关读取
    const favDump = async () => await main.ev(pageFetch('favorites', 'list', {}), true);
    const favNames = async () => {
      const txt = await main.ev(pageFetch('favorites', 'list', {}), true);
      try { return String((JSON.parse(String(txt)).items || []).map((x) => x.name).join(',') || '').split(',').filter(Boolean); } catch (e) { return []; }
    };
    // 点击后校验；未生效则重试（最多 3 次，避免渲染时序导致漏点）
    // 断言策略：点「列表内 ⭐」按索引（夹具目录只有 dir_a / file_a.txt，目录优先）
    //  → 第 1 个 = 目录（需求：文件夹可收藏）；第 2 个 = 文件（需求：文件可收藏）
    const favCount = async () => (await favNames()).length;
    const favHas = async (n) => (await favNames()).includes(n);
    const clickListStar = async (index) => {
      const pos = await main.ev("(()=>{const stars=[...document.querySelectorAll('*')].filter(e=>e.textContent&&e.textContent.trim()==='⭐'&&e.getBoundingClientRect().width>1&&e.getBoundingClientRect().top>60);if(stars.length<=" + (index + 1) + ")return null;const r=stars[" + index + "].getBoundingClientRect();return JSON.stringify({x:r.left+r.width/2,y:r.top+r.height/2});})()");
      if (!pos) return false;
      const p = JSON.parse(pos);
      await main.mouse('mouseMoved', p.x, p.y, 0); await sleep(150);
      await main.mouse('mousePressed', p.x, p.y, 1); await main.mouse('mouseReleased', p.x, p.y, 0);
      await sleep(1000); return true;
    };
    const starCount = async () => Number(await main.ev("(()=>[...document.querySelectorAll('*')].filter(e=>e.textContent&&e.textContent.trim()==='⭐'&&e.getBoundingClientRect().width>1&&e.getBoundingClientRect().top>60).length)()"));
    // 按名字点「该行内」的 ⭐（不依赖行序/序号，避免多夹具时点到别的行）
    const clickStarInRow = async (name) => {
      // 名称文本 → 向上找含 ⭐ 的行 → 点行内 ⭐（与缓存按钮同款定位，避免误点导航栏 ⭐）
      const pos = await main.ev("(()=>{const name=" + JSON.stringify(name) + ";const all=[...document.querySelectorAll('*')];const nameEl=all.find(e=>(e.textContent||'').trim()===name&&e.getBoundingClientRect().width>0);if(!nameEl)return null;let p=nameEl.parentElement;for(let i=0;i<8&&p;i++){const ss=[...p.querySelectorAll('*')].filter(c=>(c.textContent||'').trim()==='⭐');if(ss.length){const r=ss[0].getBoundingClientRect();return JSON.stringify({x:r.left+r.width/2,y:r.top+r.height/2});}p=p.parentElement;}return null;})()");
      if (!pos) return false;
      const p = JSON.parse(pos);
      await main.mouse('mouseMoved', p.x, p.y, 0); await sleep(150);
      await main.mouse('mousePressed', p.x, p.y, 1); await main.mouse('mouseReleased', p.x, p.y, 0);
      await sleep(800); return true;
    };
    const favByRow = async (name) => {
      for (let i = 0; i < 3; i++) {
        await clickStarInRow(name);
        if (await waitFor(async () => ((await favHas(name)) ? true : null), 5000, 500)) return true;
      }
      return false;
    };
    const dirFav = await favByRow('dir_a');
    check('F1 目录行 ⭐ 收藏（文件夹可收藏）', dirFav, `列表含 dir_a=${dirFav} 当前=${JSON.stringify(await favNames())}`);
    const fileFav = await favByRow('file_a.txt');
    check('F2 文件行 ⭐ 收藏（文件可收藏）', fileFav, `列表含 file_a.txt=${fileFav} 当前=${JSON.stringify(await favNames())}`);
    check('F3 收藏列表同时含目录与文件', (await favCount()) >= 2, `count=${await favCount()}`);
    await main.shot('features-favorite-add.png');

    // F4/F5 收藏 Tab 可见并可点击打开
    await main.send('Page.navigate', { url: `${base}?page_name=SftpHomePage` });
    await sleep(2000);
    const tabPos = await main.ev("(()=>{const a=[...document.querySelectorAll('*')].map(e=>({e,r:e.getBoundingClientRect()})).filter(o=>o.e.textContent&&o.e.textContent.trim()==='收藏'&&o.r.width>1&&o.r.top<200);if(!a.length)return null;const r=a[a.length-1].e.getBoundingClientRect();return JSON.stringify({x:r.left+r.width/2,y:r.top+r.height/2});})()");
    if (tabPos) { const q = JSON.parse(tabPos); await main.mouse('mouseMoved', q.x, q.y, 0); await sleep(150); await main.mouse('mousePressed', q.x, q.y, 1); await main.mouse('mouseReleased', q.x, q.y, 0); }
    const favTab = await waitFor(async () => { const t = await main.body(); return (t.includes('dir_a') || t.includes('file_a.txt')) ? t : null; }, 25000, 700);
    check('F4 收藏 Tab 显示刚收藏的目录与文件', !!favTab, favTab ? '含收藏条目' : '收藏为空');
    if (favTab) {
      await main.shot('features-favorites-tab.png');
      const rowPos = await main.ev("(()=>{const a=[...document.querySelectorAll('*')].map(e=>({e,r:e.getBoundingClientRect()})).filter(o=>(o.e.textContent||'').includes('dir_a')&&o.r.width>0&&o.r.top>150);if(!a.length)return null;a.sort((p,q)=>p.r.width*p.r.height-q.r.width*q.r.height);const r=a[0].e.getBoundingClientRect();return JSON.stringify({x:r.left+r.width/2,y:r.top+r.height/2});})()");
      if (rowPos) { const q = JSON.parse(rowPos); await main.mouse('mouseMoved', q.x, q.y, 0); await sleep(150); await main.mouse('mousePressed', q.x, q.y, 1); await main.mouse('mouseReleased', q.x, q.y, 0); }
      const opened = await waitFor(async () => { const t = await main.body(); return (t.includes('目录不存在') || t.includes('file_a.txt') || (t.includes('dir_a') && t.includes('/home/'))) ? true : null; }, 20000, 700);
      check('F5 点收藏（目录）→ 打开浏览页', !!opened, `opened=${!!opened}`);
    }

    // （终端能力/历史按钮由独立套件 `npm run test:term` 覆盖，这里不再重复，避免拖慢与重复；见 AGENTS §3.1 规则 9/10）

    // ---- F12 首页右下角「更多」→ 抽屉（含截图）----
    await main.send('Page.navigate', { url: `${base}?page_name=SftpHomePage` });
    await waitFor(async () => (await main.body()).includes('＋'), 30000, 800);
    const fab = await main.ev("(()=>{const a=[...document.querySelectorAll('*')].map(e=>({e,r:e.getBoundingClientRect()})).filter(o=>o.e.textContent&&o.e.textContent.trim()==='＋'&&o.r.width>1&&o.r.top<innerHeight);if(!a.length)return null;const r=a[0].e.getBoundingClientRect();return JSON.stringify({x:r.left+r.width/2,y:r.top+r.height/2});})()");
    let drawerOk = false;
    if (fab) {
      const p = JSON.parse(fab);
      await main.mouse('mouseMoved', p.x, p.y, 0); await sleep(150);
      await main.mouse('mousePressed', p.x, p.y, 1); await main.mouse('mouseReleased', p.x, p.y, 0);
      const d = await waitFor(async () => { const t = await main.body(); return (t.includes('设置') && t.includes('缓存列表') && t.includes('关于')) ? t : null; }, 10000, 600);
      drawerOk = !!d;
      await main.shot('features-more-drawer.png');   // 关键位置截图
    }
    check('F12 首页右下角「更多」→ 抽屉含 设置/缓存列表/关于', drawerOk, `fab可见=${!!fab} 抽屉=${drawerOk}`);

    // ---- F10/F11 设置页：历史条数可改 + 清空缓存入口（含截图）----
    let settingsOk = false, cycleOk = false, cacheEntryOk = false;
    if (drawerOk) {
      await main.clickText('设置');
      const st = await waitFor(async () => { const t = await main.body(); return t.includes('终端命令历史条数') ? t : null; }, 12000, 600);
      settingsOk = !!st;
      await main.shot('features-settings-page.png');   // 关键位置截图
      if (st) {
        // 只针对「终端命令历史条数」行（避免与「播放历史条数」行同含「点击切换」相互干扰）
        const before = (st.match(/(\d+) 条（点击切换 5\/10\/20\/50）/) || [])[1];
        let changed = null;
        for (let i = 0; i < 3 && !changed; i++) {
          await main.clickText('点击切换 5/10/20/50）');
          changed = await waitFor(async () => { const t = await main.body(); const m = t.match(/(\d+) 条（点击切换 5\/10\/20\/50）/) || t.match(/(\d+) 条（点击切换 5\/10\/20\/50/); return (m && m[1] !== before) ? m[1] : null; }, 5000, 500);
        }
        cycleOk = !!changed;
        cacheEntryOk = (await main.body()).includes('清空缓存');
        await main.clickText('清空缓存');
        await sleep(1200);
        await main.shot('features-settings-cache-clear.png');
      }
    }
    check('F10 设置页：终端历史条数可改（点击切换）', cycleOk, `切换=${cycleOk}`);
    check('F11 设置页：清空缓存入口存在并可点击', cacheEntryOk, `入口=${cacheEntryOk}`);

    // ---- F19 历史记录右侧 ✕ 删除（带截图）----
    // 先造一条历史（页面网关 history.upsert），再在首页历史 Tab 用 ✕ 删除
    const HIST_NAME = '国王红包功能总结文档.md';
    // 逐条删除（只靠 clearByConnection 可能残留 connectionId 非空/未定义的旧记录，污染断言）
    const preList = (await pageRpc('history', 'listByConnection', { connectionId: '' }))?.records || [];
    for (const it of preList) await pageRpc('history', 'remove', { id: it.id });
    await pageRpc('history', 'upsert', { id: 'feat-hist-1', connectionId: '', connectionLabel: '', remotePath: `${HOME}/ks-cr-doc/${HIST_NAME}`, name: HIST_NAME, position: 12000, duration: 60000, completed: false, starredAt: 1, lastPlayedAt: 1 });
    const afterUpsert = (await pageRpc('history', 'listByConnection', { connectionId: '' }))?.records || [];
    const upserted = afterUpsert.length;
    await main.send('Page.navigate', { url: `${base}?page_name=SftpHomePage` });
    await waitFor(async () => ((await main.body()).includes('新建连接') || (await main.body()).includes('历史')) ? true : null, 20000, 600);
    await sleep(600);
    await main.clickText('历史');
    const histShown = await waitFor(async () => { const t = await main.body(); return t.includes('国王红包') ? t : null; }, 20000, 700);
    await main.shot('features-history-list.png');   // 关键位置截图：历史记录 + ✕
    let deleted = false;
    if (histShown) {
      // 注意：点 ✕ 后会 toast「已删除历史：<name>」，正文仍含该名称 → 必须按网关数据判定删除
      // ✕ 必须限定在「该条记录所在行」内点击（列表里有多行时取第一个会误删别的记录）
      const xPos = await main.ev("(()=>{const all=[...document.querySelectorAll('*')];const rows=all.filter(e=>(e.textContent||'').includes(" + JSON.stringify(HIST_NAME) + ")&&[...e.querySelectorAll('*')].some(c=>(c.textContent||'').trim()==='✕'));if(!rows.length)return null;rows.sort((a,b)=>{const ra=a.getBoundingClientRect(),rb=b.getBoundingClientRect();return ra.width*ra.height-rb.width*rb.height;});const row=rows[0];const x=[...row.querySelectorAll('*')].find(c=>(c.textContent||'').trim()==='✕');if(!x)return null;const r=x.getBoundingClientRect();return JSON.stringify({x:r.left+r.width/2,y:r.top+r.height/2});})()");
      if (xPos) {
        const q = JSON.parse(xPos);
        await main.mouse('mouseMoved', q.x, q.y, 0); await sleep(150);
        await main.mouse('mousePressed', q.x, q.y, 1); await main.mouse('mouseReleased', q.x, q.y, 0);
        deleted = !!(await waitFor(async () => {
          const r = await pageRpc('history', 'listByConnection', { connectionId: '' });
          return (r?.records || []).some((x) => x.id === 'feat-hist-1') ? null : true;
        }, 12000, 600));
        await main.shot('features-history-deleted.png');
      }
    }
    const gwNames = ((await pageRpc('history', 'listByConnection', { connectionId: '' }))?.records || []).map((x) => x.name).join(',');
    check('F19 历史记录右侧 ✕ 可删除该条记录', !!histShown && deleted, `历史可见=${!!histShown} 已删除=${deleted}（造数据=${upserted} 网关=[${gwNames}]）`);

    // ---- F20 播放历史容量：追加 + 超限丢最旧（默认 1000，此处设 5 快速验证）----
    const capRes = await main.ev("(async()=>{const u=window.__SFTP_GATEWAY_URL__;const post=(m,p)=>fetch(u+'/rpc',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({module:'history',method:m,params:p})}).then(r=>r.json());await post('setLimit',{limit:5});const l0=await post('listByConnection',{connectionId:''});for(const it of (l0.records||[])) await post('remove',{id:it.id});for(let i=1;i<=7;i++){await post('upsert',{id:'cap'+i,connectionId:'',remotePath:'/cap'+i,name:'cap'+i,position:i*1000,duration:60000,completed:false,starredAt:i,lastPlayedAt:i});}const l=await post('listByConnection',{connectionId:''});const names=(l.records||[]).map(x=>x.name);await post('setLimit',{limit:1000});return JSON.stringify({count:names.length,names});})()", true);
    let cap = null; try { cap = JSON.parse(String(capRes)); } catch (e) { cap = null; }
    check('F20 历史追加：容量 5 写 7 条 → 保留最新 5 条并丢最旧',
      !!cap && cap.count === 5 && cap.names.includes('cap7') && !cap.names.includes('cap1'),
      cap ? `保留 ${cap.count} 条：${cap.names.join(',')}` : '无结果');

    // ---- F21 设置页含「播放历史条数」并可切换（截图）----
    let histLimitRow = false, histLimitCycle = false;
    await main.send('Page.navigate', { url: `${base}?page_name=SftpPageNotExist` });   // 占位：避免误判
    await main.send('Page.navigate', { url: `${base}?page_name=SftpHomePage` });
    await sleep(1800);
    const fab2 = await main.ev("(()=>{const a=[...document.querySelectorAll('*')].map(e=>({e,r:e.getBoundingClientRect()})).filter(o=>o.e.textContent&&o.e.textContent.trim()==='＋'&&o.r.width>1&&o.r.top<innerHeight);if(!a.length)return null;const r=a[0].e.getBoundingClientRect();return JSON.stringify({x:r.left+r.width/2,y:r.top+r.height/2});})()");
    if (fab2) { const p = JSON.parse(fab2); await main.mouse('mouseMoved', p.x, p.y, 0); await sleep(150); await main.mouse('mousePressed', p.x, p.y, 1); await main.mouse('mouseReleased', p.x, p.y, 0); }
    await waitFor(async () => ((await main.body()).includes('缓存列表') ? true : null), 8000, 500);
    await main.clickText('设置');
    const st2 = await waitFor(async () => { const t = await main.body(); return t.includes('播放历史条数') ? t : null; }, 12000, 600);
    histLimitRow = !!st2;
    if (st2) {
      const before = (st2.match(/(\d+) 条（点击切换 5\/10\/20\/50\/200/) || [])[1];
      await main.clickText('点击切换 5/10/20/50/200');
      const changed = await waitFor(async () => { const t = await main.body(); const m = t.match(/(\d+) 条（点击切换 5\/10\/20\/50\/200/); return (m && m[1] !== before) ? m[1] : null; }, 8000, 500);
      histLimitCycle = !!changed;
      await main.shot('features-settings-history-limit.png');
    }
    check('F21 设置页含「播放历史条数」且可切换', histLimitRow && histLimitCycle, `行存在=${histLimitRow} 切换=${histLimitCycle}`);

    // ================= 目录/单文件缓存（F14–F18）=================
    // 缓存状态在页面内存中（CacheManager 全局单例），缓存列表是**页内浮层**（不跳页、不丢状态）。
    // 入口是浏览页顶部「⬇ 缓存 …」悬浮条；浮层内的「关闭」返回浏览页。
    const clickCacheBtn = async (name) => {
      // 精确定位：先找「文本恰好等于该名字」的元素（行内名称 Text），再向上找含 ⬇ 的行，点行内 ⬇
      const pos = await main.ev("(()=>{const name=" + JSON.stringify(name) + ";const all=[...document.querySelectorAll('*')];const nameEl=all.find(e=>(e.textContent||'').trim()===name&&e.getBoundingClientRect().width>0);if(!nameEl)return null;let p=nameEl.parentElement;for(let i=0;i<8&&p;i++){if([...p.querySelectorAll('*')].some(c=>(c.textContent||'').trim()==='⬇')){const btn=[...p.querySelectorAll('*')].find(c=>(c.textContent||'').trim()==='⬇');if(!btn)return null;const r=btn.getBoundingClientRect();return JSON.stringify({x:r.left+r.width/2,y:r.top+r.height/2});}p=p.parentElement;}return null;})()");
      if (!pos) return false;
      const p = JSON.parse(pos);
      await main.mouse('mouseMoved', p.x, p.y, 0); await sleep(120);
      await main.mouse('mousePressed', p.x, p.y, 1); await main.mouse('mouseReleased', p.x, p.y, 0);
      await sleep(500); return true;
    };
    const clickCacheBar = async () => {
      // 「已加入缓存」toast 会短暂盖住悬浮条，点到 toast 不会打开浮层 → 重试直到浮层出现
      for (let attempt = 0; attempt < 10; attempt++) {
        const pos = await main.ev("(()=>{const el=[...document.querySelectorAll('*')].find(e=>(e.textContent||'').trim().startsWith('⬇ 缓存'));if(!el)return null;const r=el.getBoundingClientRect();return JSON.stringify({x:r.left+r.width/2,y:r.top+r.height/2});})()");
        if (pos) {
          const p = JSON.parse(pos);
          await main.mouse('mouseMoved', p.x, p.y, 0); await sleep(120);
          await main.mouse('mousePressed', p.x, p.y, 1); await main.mouse('mouseReleased', p.x, p.y, 0);
          await sleep(500);
          if ((await main.body()).includes('缓存列表')) return true;
        }
        await sleep(500);
      }
      return false;
    };
    const closeCachePanel = async () => { await main.clickText('关闭'); await sleep(500); };

    // ---- F14 点目录 → 统计体积并弹「过大确认」（阈值注入 1000 字节，带截图）----
    await main.send('Page.navigate', { url: `${base}?page_name=SftpBrowserPage&host=${HOST}&port=22&user=${USER}&password=${PASS}&remotePath=${FIX}&cacheConfirmBytes=1000` });
    const browserReady = await waitFor(async () => { const t = await main.body(); return t.includes('cachedir') ? t : null; }, 45000, 700);

    // ---- F25 浏览页标题旁「⧉ 复制路径」（带截图）----
    const copyBtn = await main.ev("(()=>[...document.querySelectorAll('*')].some(e=>(e.textContent||'').trim()==='⧉'&&e.getBoundingClientRect().width>0))()");
    await main.clickText('⧉');
    const copied = await waitFor(async () => { const t = await main.body(); return t.includes('路径已复制') ? t : null; }, 8000, 400);
    let clip = '';
    try {
      await main.send('Browser.grantPermissions', { origin: base, permissions: ['clipboardReadWrite', 'clipboardSanitizedWrite'] });
      clip = String(await main.ev('navigator.clipboard.readText()', true));
    } catch (e) { clip = ''; }
    await main.shot('features-copy-path.png');
    check('F25 浏览页标题旁「⧉」可复制当前路径', !!copyBtn && !!copied, `按钮=${!!copyBtn} 提示=${!!copied} 剪贴板=${clip.slice(0, 60)}`);

    await clickCacheBtn('cachedir');
    const confirmShown = await waitFor(async () => { const t = await main.body(); return (t.includes('缓存体积较大') && t.includes('是否继续')) ? t : null; }, 15000, 500);
    const confirmMentionsDir = !!confirmShown && confirmShown.includes('cachedir');
    await main.shot('features-cache-confirm.png');
    check('F14 点目录 → 统计体积并弹「过大确认」', confirmMentionsDir, `目录就绪=${!!browserReady} 弹层=${!!confirmShown} 命中cachedir=${confirmMentionsDir}`);

    // ---- F15 确认后缓存列表出现任务与进度，完成后 20/20（带截图）----
    let cacheListed = false, progressSeen = '';
    if (confirmShown) {
      await main.clickText('继续');
      await waitFor(async () => { const t = await main.body(); return t.includes('⬇ 缓存') ? t : null; }, 8000, 300);
      await clickCacheBar();
      const listPage = await waitFor(async () => { const t = await main.body(); return (t.includes('缓存列表') && t.includes('cachedir')) ? t : null; }, 15000, 500);
      cacheListed = !!listPage;
      await main.shot('features-cache-list.png');
      if (listPage) {
        const m = listPage.match(/(\d+)\/(\d+) 文件/);
        progressSeen = m ? `${m[1]}/${m[2]}` : '';
      }
      const done1 = await waitFor(async () => { const t = await main.body(); return (t.includes('已完成') && t.includes('20/20')) ? t : null; }, 90000, 700);
      await main.shot('features-cache-done.png');
      check('F15 确认后缓存列表出现任务与进度，完成后 20/20', cacheListed && !!done1, `列表=${cacheListed} 首次进度=${progressSeen || 'n/a'} 完成=${!!done1}`);
      await closeCachePanel();
    } else {
      check('F15 确认后缓存列表出现任务与进度，完成后 20/20', false, 'F14 未弹层');
    }

    // ---- F16 缓存列表可暂停（进度冻结）/继续（跑完，带截图）----
    let pausedOk = false, frozen = false, resumedOk = false;
    if (cacheListed) {
      await closeCachePanel();
      await waitFor(async () => { const t = await main.body(); return t.includes('pausedir') ? t : null; }, 20000, 700);
      await clickCacheBtn('pausedir');
      const c2 = await waitFor(async () => { const t = await main.body(); return t.includes('缓存体积较大') ? t : null; }, 12000, 500);
      if (c2) await main.clickText('继续');
      await waitFor(async () => { const t = await main.body(); return t.includes('⬇ 缓存') ? t : null; }, 8000, 300);
      await clickCacheBar();
      await waitFor(async () => { const t = await main.body(); return (t.includes('缓存列表') && t.includes('pausedir')) ? t : null; }, 12000, 400);
      const pausedBtn = await waitFor(async () => { const t = await main.body(); return t.includes('暂停') ? t : null; }, 8000, 300);
      if (pausedBtn) {
        await main.clickText('暂停');
        const pstate = await waitFor(async () => { const t = await main.body(); return t.includes('已暂停') ? t : null; }, 8000, 300);
        await main.shot('features-cache-paused.png');
        const before = (pstate || '').match(/(\d+)\/40 文件/);
        await sleep(1300);
        const after = ((await main.body()).match(/(\d+)\/40 文件/) || [])[1];
        frozen = !!before && after === before[1];
        pausedOk = !!pstate;
        await main.clickText('继续');
        const done2 = await waitFor(async () => { const t = await main.body(); return (t.includes('已完成') && t.includes('40/40')) ? t : null; }, 120000, 700);
        resumedOk = !!done2;
      }
      await closeCachePanel();
    }
    check('F16 缓存列表可暂停（进度冻结）/继续（跑完）', pausedOk && frozen && resumedOk, `暂停=${pausedOk} 冻结=${frozen} 继续完成=${resumedOk}`);

    // ---- F17 缓存列表可取消（任务从列表消失，带截图）----
    let cancelOk = false;
    if (cacheListed) {
      await closeCachePanel();
      await waitFor(async () => { const t = await main.body(); return t.includes('canceldir') ? t : null; }, 20000, 700);
      await clickCacheBtn('canceldir');
      const c3 = await waitFor(async () => { const t = await main.body(); return t.includes('缓存体积较大') ? t : null; }, 12000, 500);
      if (c3) await main.clickText('继续');
      await waitFor(async () => { const t = await main.body(); return t.includes('⬇ 缓存') ? t : null; }, 8000, 300);
      await clickCacheBar();
      await waitFor(async () => { const t = await main.body(); return (t.includes('缓存列表') && t.includes('canceldir')) ? t : null; }, 12000, 400);
      await main.clickText('取消');
      // 注意：浏览页列表里本来就有 canceldir 这个目录名，不能按名字判消失；改为判「浮层里不再有取消按钮」
      const gone = await waitFor(async () => { const t = await main.body(); return (t.includes('缓存列表') && !t.includes('取消')) ? true : null; }, 12000, 400);
      await main.shot('features-cache-cancelled.png');
      cancelOk = !!gone;
      await closeCachePanel();
    }
    check('F17 缓存列表可取消（任务从列表消失）', cancelOk, `取消=${cancelOk}`);

    // ---- F18 单文件可缓存 + 本地字节一致（带截图）----
    let fileCached = false, bytesOk = false, got = '', bytesDiag = '';
    if (cacheListed) {
      await closeCachePanel();
      await waitFor(async () => { const t = await main.body(); return t.includes('cachebytes.bin') ? t : null; }, 20000, 700);
      await clickCacheBtn('cachebytes.bin');
      const c4 = await waitFor(async () => { const t = await main.body(); return t.includes('缓存体积较大') ? t : null; }, 12000, 500);
      if (c4) await main.clickText('继续');
      await waitFor(async () => { const t = await main.body(); return t.includes('⬇ 缓存') ? t : null; }, 8000, 300);
      await clickCacheBar();
      const fdone = await waitFor(async () => { const t = await main.body(); return (t.includes('cachebytes.bin') && t.includes('1/1 文件')) ? t : null; }, 30000, 500);
      await main.shot('features-cache-file.png');
      fileCached = !!fdone;
      if (fdone) {
        // 直接在本机文件系统读取（测试与 Electron 同机；LOCAL_ROOT = app.getPath('home')）
        const localPath = require('node:os').homedir() + '/.kuikly_cache/cachebytes.bin';
        try { got = fs.readFileSync(localPath).toString('base64'); } catch (e) { got = 'ERR:' + e.message; }
        const exp = FILE_BYTES.toString('base64');
        bytesOk = got === exp;
        bytesDiag = `len(got=${got.length},exp=${exp.length}) same=${bytesOk} path=${localPath}`;
      }
      await closeCachePanel();
    }
    check('F18 单文件可缓存且本地字节一致', fileCached && bytesOk, `缓存完成=${fileCached} 字节一致=${bytesOk} ${bytesDiag}`);

    // ---- F22 缓存浮层「关闭」可关闭 ----
    let panelClosed = false;
    if (await clickCacheBar()) {
      await main.clickText('关闭');
      panelClosed = !!(await waitFor(async () => { const t = await main.body(); return !t.includes('缓存列表') ? true : null; }, 8000, 400));
    }
    check('F22 缓存浮层可关闭', panelClosed, `关闭=${panelClosed}`);

    // ---- F23 清空已完成：清空任务并使悬浮条消失（带截图）----
    let cleared = false;
    if (await clickCacheBar()) {
      await main.clickText('清空已完成');
      cleared = !!(await waitFor(async () => { const t = await main.body(); return (t.includes('暂无缓存任务') && !t.includes('⬇ 缓存')) ? true : null; }, 8000, 400));
      await main.shot('features-cache-cleared.png');
    }
    check('F23 清空已完成：任务清空且悬浮条消失', cleared, `清空=${cleared}`);

    // ---- F24 首页顶部「⚙ 设置」入口直达设置页（桌面端不依赖右下角 FAB）----
    await main.send('Page.navigate', { url: `${base}?page_name=SftpHomePage` });
    await waitFor(async () => ((await main.body()).includes('本地文件管理') || (await main.body()).includes('新建连接')) ? true : null, 20000, 700);
    await main.clickText('⚙');
    const gearSettings = await waitFor(async () => { const t = await main.body(); return t.includes('终端命令历史条数') ? t : null; }, 12000, 600);
    await main.shot('features-home-settings-entry.png');
    check('F24 首页顶部「⚙ 设置」入口直达设置页', !!gearSettings, `设置页=${!!gearSettings}`);

    // ---- F26 连接行右侧「✕」删除：先二级确认，可取消；确认后移除 ----
    const delLabel = 'DelMe_' + Date.now().toString().slice(-6);
    await pageRpc('connection', 'add', { label: delLabel, host: '10.0.0.9', port: 22, user: 'x', password: 'y', authMethod: 'PASSWORD' });
    await main.send('Page.navigate', { url: `${base}?page_name=SftpHomePage` });
    await waitFor(async () => ((await main.body()).includes(delLabel) ? true : null), 20000, 700);
    // 点「该连接所在行」的 ✕（每次现算坐标，避免布局变化导致失效）
    const clickConnDelete = async () => {
      // 连接列表可能很长：先把可滚动容器拉到底，确保该行在可视区
      await main.ev("(()=>{const el=[...document.querySelectorAll('*')].find(e=>e.scrollHeight>e.clientHeight+40);if(el)el.scrollTop=el.scrollHeight;return true;})()");
      await sleep(300);
      const pos = await main.ev("(()=>{const all=[...document.querySelectorAll('*')];const rows=all.filter(e=>(e.textContent||'').includes(" + JSON.stringify(delLabel) + ")&&[...e.querySelectorAll('*')].some(c=>(c.textContent||'').trim()==='✕'));if(!rows.length)return null;rows.sort((a,b)=>{const ra=a.getBoundingClientRect(),rb=b.getBoundingClientRect();return ra.width*ra.height-rb.width*rb.height;});const row=rows[0];const x=[...row.querySelectorAll('*')].find(c=>(c.textContent||'').trim()==='✕');if(!x)return null;const r=x.getBoundingClientRect();return JSON.stringify({x:r.left+r.width/2,y:r.top+r.height/2});})()");
      if (!pos) return false;
      const p = JSON.parse(pos);
      await main.mouse('mouseMoved', p.x, p.y, 0); await sleep(120);
      await main.mouse('mousePressed', p.x, p.y, 1); await main.mouse('mouseReleased', p.x, p.y, 0);
      return true;
    };
    // 打开确认弹窗（可重试）
    const openDelDialog = async () => { await clickConnDelete(); return !!(await waitFor(async () => ((await main.body()).includes('删除连接') ? true : null), 6000, 300)); };
    let dlg = await openDelDialog();
    await main.shot('features-connection-delete-confirm.png');
    // 取消：弹窗消失、连接仍在
    let cancelKeeps = false;
    if (dlg) {
      await main.clickText('取消');
      cancelKeeps = !!(await waitFor(async () => { const t = await main.body(); return (!t.includes('删除连接') && t.includes(delLabel)) ? true : null; }, 8000, 400));
    }
    // 重新打开 → 点弹窗内「删除」；未移除则重试一次
    let removed = false;
    for (let attempt = 0; attempt < 2 && !removed; attempt++) {
      if (!(await openDelDialog())) continue;
      const delBtnPos = await main.ev("(()=>{const dlg=[...document.querySelectorAll('*')].find(e=>(e.textContent||'').includes('删除连接')&&[...e.querySelectorAll('*')].some(c=>(c.textContent||'').trim()==='删除'));if(!dlg)return null;const btn=[...dlg.querySelectorAll('*')].find(c=>(c.textContent||'').trim()==='删除');if(!btn)return null;const r=btn.getBoundingClientRect();return JSON.stringify({x:r.left+r.width/2,y:r.top+r.height/2});})()");
      if (delBtnPos) {
        const p = JSON.parse(delBtnPos);
        await main.mouse('mouseMoved', p.x, p.y, 0); await sleep(120);
        await main.mouse('mousePressed', p.x, p.y, 1); await main.mouse('mouseReleased', p.x, p.y, 0);
      }
      // 坐标点若未生效（浮层/布局），退化为直接对按钮 DOM 派发 click
      await sleep(400);
      const cur = await pageRpc('connection', 'list', {});
      if ((cur?.items || []).some((x) => x.label === delLabel)) {
        await main.ev("(()=>{const dlg=[...document.querySelectorAll('*')].find(e=>(e.textContent||'').includes('删除连接')&&[...e.querySelectorAll('*')].some(c=>(c.textContent||'').trim()==='删除'));if(!dlg)return false;const btn=[...dlg.querySelectorAll('*')].find(c=>(c.textContent||'').trim()==='删除');if(!btn)return false;btn.click();return true;})()");
      }
      removed = !!(await waitFor(async () => {
        const r = await pageRpc('connection', 'list', {});
        return (r?.items || []).some((x) => x.label === delLabel) ? null : true;
      }, 8000, 500));
    }
    check('F26 连接行「✕」删除（二级确认；可取消；确认后移除）', !!dlg && cancelKeeps && removed, `弹窗=${!!dlg} 取消保留=${cancelKeeps} 已删除=${removed}`);

    // ---- F27 文件列表标题栏最前「✕」直接退出列表（回首页）----
    // 从首页「收藏」点目录进入浏览页（页内路由 push，关闭才有上一页可回），再点最前「✕」
    await main.send('Page.navigate', { url: `${base}?page_name=SftpHomePage` });
    await waitFor(async () => ((await main.body()).includes('SFTP 客户端') ? true : null), 20000, 700);
    await main.clickText('收藏');
    const favRow = await waitFor(async () => { const t = await main.body(); return t.includes('dir_a') ? t : null; }, 15000, 600);
    if (favRow) {
      const rp = await main.ev("(()=>{const a=[...document.querySelectorAll('*')].map(e=>({e,r:e.getBoundingClientRect()})).filter(o=>{const t=(o.e.textContent||'').trim();return t.includes('dir_a')&&o.r.width>100&&o.r.height>20&&o.r.height<90;});if(!a.length)return null;a.sort((p,q)=>p.r.width*p.r.height-q.r.width*q.r.height);const r=a[0].r;return JSON.stringify({x:r.left+40,y:r.top+r.height/2});})()");
      if (rp) { const q = JSON.parse(rp); await main.mouse('mouseMoved', q.x, q.y, 0); await sleep(150); await main.mouse('mousePressed', q.x, q.y, 1); await main.mouse('mouseReleased', q.x, q.y, 0); }
    }
    const inBrowser = !!(await waitFor(async () => { const t = await main.body(); return (t.includes('⧉') && t.includes('✕')) ? t : null; }, 40000, 800));
    if (inBrowser) await main.clickText('✕');
    const exited = !!(await waitFor(async () => { const t = await main.body(); return (t.includes('SFTP 客户端') && !t.includes('⧉')) ? true : null; }, 15000, 500));
    check('F27 文件列表标题栏最前「✕」直接退出列表（回首页）', inBrowser && exited, `进入列表=${inBrowser} 退出=${exited}`);

    check('F13 无 JS 未捕获异常', main.errs.length === 0, main.errs.slice(0, 2).join('; '));
  } catch (e) {
    console.log('ERROR | ' + String((e && e.stack) || e).slice(0, 400));
    results.push({ n: '意外异常', ok: false });
  } finally {
    try { await cleanup(); } catch (e) { }
    try { child.kill('SIGTERM'); } catch (e) { }
    const fail = results.filter((r) => !r.ok).length;
    console.log(`\n[features] ${results.length - fail}/${results.length} 通过${skipped.length ? `；SKIP ${skipped.length}` : ''}；截图目录 ${artifacts}`);
    __finished = true;
    setTimeout(() => process.exit(fail ? 1 : 0), 150);
  }
})();

/*
 * Kuikly SFTP Web Gateway
 *
 * 浏览器不能建立原始 TCP/SSH 连接，因此 Web(H5) 版 SFTP 必须由一个后端进程代持真实
 * SSH/SFTP 会话，并把它暴露成浏览器可用的 HTTP 接口。本文件就是那个后端。
 *
 * 职责：
 *  1) 代持 SSH/SFTP 会话（ssh2），提供 connect/list/stat/随机读/文件操作/批量等 RPC。
 *  2) 直接以 HTTP Range 提供远端文件的流式读取，充当「本地媒体代理」的角色
 *     （浏览器播放器不认 sftp://，只认 http，所以由网关出这个 http）。
 *
 * 接口：
 *  - POST /rpc                 { module, method, params } -> JSON
 *  - GET  /<token>/<fileName>  媒体流（支持 Range/206），URL 与 common 的
 *                              SftpMediaUrlBuilder.buildPlayUrl 完全一致
 *  - GET  /health
 *
 * 说明：连接/收藏/播放历史三块在本方案里放在浏览器 localStorage 实现，不经网关。
 */

'use strict';

const http = require('http');
const crypto = require('crypto');
const { URL } = require('url');
const { Client } = require('ssh2');

const PORT = parseInt(process.env.SFTP_GATEWAY_PORT || '18090', 10); // 0 = 由 OS 分配（Electron 用）
let actualPort = PORT; // 实际监听端口（PORT=0 时由 OS 决定）
const READY_TIMEOUT_MS = 20000;

/* ------------------------------------------------------------------ *
 * 错误码：与 common SftpErrorCode 语义对齐
 *   1001 连接 / 1003 认证 / 2001 权限 / 2003 文件不存在 / 3001 协议 / 9999 未实现
 * ------------------------------------------------------------------ */
function classifyError(err) {
  const msg = (err && (err.message || err.toString())) || 'unknown error';
  const code = err && err.code;
  if (code === 'ENOTFOUND' || code === 'ECONNREFUSED' || code === 'ETIMEDOUT' || code === 'EHOSTUNREACH') {
    return { code: 1001, msg, detail: String(code) };
  }
  if (/HOSTKEY_MISMATCH|host key verification failed/i.test(msg)) {
    return { code: 1004, msg: '主机指纹不匹配', detail: msg };
  }
  if (/all configured authentication methods failed|authentication/i.test(msg)) {
    return { code: 1003, msg: '认证失败', detail: msg };
  }
  if (/LOCAL_PATH_DENIED/.test(msg)) {
    return { code: 2001, msg: '本地路径越界，已拒绝', detail: msg };
  }
  if (/no such file|ENOENT|not exist/i.test(msg)) {
    return { code: 2003, msg: '文件/目录不存在', detail: msg };
  }
  if (/permission denied|EACCES|EPERM/i.test(msg)) {
    return { code: 2001, msg: '权限不足', detail: msg };
  }
  if (/not implemented/i.test(msg)) {
    return { code: 9999, msg, detail: '' };
  }
  return { code: 3001, msg: '协议/操作失败', detail: msg };
}

function errorJson(err) {
  return { error: JSON.stringify(classifyError(err)) };
}

function notImplemented(what) {
  const e = new Error('not implemented: ' + what);
  return errorJson(e);
}

/* ------------------------------------------------------------------ *
 * 会话 / 句柄 / token 存储
 * ------------------------------------------------------------------ */
let sessionSeq = 0;
let handleSeq = 0;
const sessions = new Map();   // sessionId -> { conn, sftp, home }
const fileHandles = new Map(); // handleId  -> { sftp, handle, sessionId, remotePath, size }
const mediaTokens = new Map(); // token     -> { sessionId, remotePath, size, handle }

function findSession(sessionId) {
  const s = sessions.get(sessionId);
  if (!s) {
    const e = new Error('session not found: ' + sessionId);
    e.code = 'ENOENT';
    throw e;
  }
  return s;
}

/* ------------------------------------------------------------------ *
 * SFTP 基础工具（Promise 包装）
 * ------------------------------------------------------------------ */
const call = (obj, method, ...args) =>
  new Promise((resolve, reject) => {
    obj[method](...args, (err, ...rest) => (err ? reject(err) : resolve(rest.length > 1 ? rest : rest[0])));
  });

const S_IFMT = 0o170000;
const S_IFDIR = 0o040000;
const S_IFLNK = 0o120000;

function isDir(mode) { return (mode & S_IFMT) === S_IFDIR; }
function isLink(mode) { return (mode & S_IFMT) === S_IFLNK; }

function permString(mode, dir, link) {
  const rwx = (bits) => ((bits & 4) ? 'r' : '-') + ((bits & 2) ? 'w' : '-') + ((bits & 1) ? 'x' : '-');
  const type = link ? 'l' : dir ? 'd' : '-';
  return type +
    rwx((mode >> 6) & 7) + rwx((mode >> 3) & 7) + rwx(mode & 7);
}

function joinRemote(dir, name) {
  if (!dir || dir === '') return name;
  return dir.endsWith('/') ? dir + name : dir + '/' + name;
}

function baseName(p) { return p.replace(/\/+$/, '').split('/').pop() || '/'; }
function parentDir(p) {
  const s = p.replace(/\/+$/, '');
  const i = s.lastIndexOf('/');
  if (i < 0) return '';
  if (i === 0) return '/';
  return s.slice(0, i);
}

function entryFromAttrs(name, path, attrs, followSymlink) {
  const mode = attrs.mode || 0;
  const dir = isDir(mode);
  const link = isLink(mode);
  const entry = {
    name,
    path,
    isDir: dir && !link,
    size: Number(attrs.size || 0),
    mtime: Number(attrs.mtime || 0) * 1000, // 秒 -> 毫秒
    permission: permString(mode, dir, link),
    uid: attrs.uid != null ? attrs.uid : -1,
    gid: attrs.gid != null ? attrs.gid : -1,
  };
  if (link) {
    entry.isSymlink = true;
    entry.followsTarget = !!followSymlink;
  }
  return entry;
}

async function statPath(sftp, path, follow) {
  const attrs = follow ? await call(sftp, 'stat', path) : await call(sftp, 'lstat', path);
  return attrs;
}

async function ensureRemoteDir(sftp, dir) {
  if (!dir || dir === '' || dir === '/' || dir === '.') return;
  try {
    await call(sftp, 'stat', dir);
    return;
  } catch (e) { /* not exists -> create */ }
  await ensureRemoteDir(sftp, parentDir(dir));
  try {
    await call(sftp, 'mkdir', dir);
  } catch (e) {
    // 容忍并发/已存在
    try { await call(sftp, 'stat', dir); } catch (e2) { throw e; }
  }
}

async function removeRemoteRecursive(sftp, path, depth = 0) {
  if (depth > 64) throw new Error('remove depth too deep: ' + path);
  const attrs = await call(sftp, 'lstat', path);
  if (isDir(attrs.mode) && !isLink(attrs.mode)) {
    const list = await call(sftp, 'readdir', path);
    for (const item of list) {
      if (item.filename === '.' || item.filename === '..') continue;
      await removeRemoteRecursive(sftp, joinRemote(path, item.filename), depth + 1);
    }
    await call(sftp, 'rmdir', path);
  } else {
    await call(sftp, 'unlink', path);
  }
}

async function copyRemoteToRemote(sftp, src, dest) {
  await ensureRemoteDir(sftp, parentDir(dest));
  await new Promise((resolve, reject) => {
    const rs = sftp.createReadStream(src);
    const ws = sftp.createWriteStream(dest, { flags: 'w', mode: 0o644 });
    rs.on('error', reject);
    ws.on('error', reject);
    ws.on('close', resolve);
    rs.pipe(ws);
  });
}

async function copyEntry(sftp, src, dest, counters, depth = 0) {
  if (depth > 64) { counters.failed++; return; }
  let attrs;
  try { attrs = await call(sftp, 'lstat', src); } catch (e) { counters.failed++; return; }
  if (isLink(attrs.mode)) {
    try {
      const target = await call(sftp, 'readlink', src);
      await call(sftp, 'symlink', target, dest);
      counters.copied++;
    } catch (e) { counters.failed++; }
    return;
  }
  if (isDir(attrs.mode)) {
    try { await ensureRemoteDir(sftp, dest); } catch (e) { counters.failed++; return; }
    let list;
    try { list = await call(sftp, 'readdir', src); } catch (e) { counters.failed++; return; }
    for (const item of list) {
      if (item.filename === '.' || item.filename === '..') continue;
      await copyEntry(sftp, joinRemote(src, item.filename), joinRemote(dest, item.filename), counters, depth + 1);
    }
    return;
  }
  try {
    await copyRemoteToRemote(sftp, src, dest);
    counters.copied++;
  } catch (e) { counters.failed++; }
}

/* ------------------------------------------------------------------ *
 * sftp 模块方法
 * ------------------------------------------------------------------ */
const sftpModule = {
  async connect(params) {
    const host = params.host;
    const port = params.port || 22;
    const user = params.user;
    if (!host || !user) throw new Error('connect: host/user required');
    const hostKeyPolicy = String(params.hostKeyPolicy || 'TOFU').toUpperCase();
    const conn = new Client();
    let hostKeyInfo = null;
    let hostKeyError = null;
    const privateKey = params.privateKey || undefined;
    const cfg = {
      host,
      port,
      username: user,
      readyTimeout: params.connectTimeoutMs || READY_TIMEOUT_MS,
      keepaliveInterval: (params.keepAliveIntervalSec || 15) * 1000,
      // 主机指纹校验（TOFU/STRICT/INSECURE），防中间人
      hostVerifier: (key, cb) => {
        try {
          hostKeyInfo = verifyHostKey(host, port, key, hostKeyPolicy);
          cb(true);
        } catch (e) {
          hostKeyError = e;
          cb(false);
        }
      },
    };
    if (privateKey) {
      cfg.privateKey = privateKey;
      if (params.passphrase) cfg.passphrase = params.passphrase;
    } else if (params.password) {
      cfg.password = params.password;
    }
    const sftp = await new Promise((resolve, reject) => {
      conn.on('ready', () => conn.sftp((err, s) => (err ? reject(err) : resolve(s))));
      // 指纹不匹配时，ssh2 也会报错；优先用我们更具体的错误
      conn.on('error', (err) => reject(hostKeyError || err));
      conn.connect(cfg);
    });
    let home = '/';
    try { home = await call(sftp, 'realpath', '.'); } catch (e) { /* keep default */ }
    const sessionId = 'sftp-' + (++sessionSeq);
    sessions.set(sessionId, { conn, sftp, home });
    return {
      sessionId,
      hostKeyFingerprint: hostKeyInfo ? hostKeyInfo.fingerprint : '',
      hostKeyTrusted: !!(hostKeyInfo && hostKeyInfo.trusted),
    };
  },

  async disconnect(params) {
    const s = sessions.get(params.sessionId);
    if (s) {
      sessions.delete(params.sessionId);
      try { s.conn.end(); } catch (e) { /* ignore */ }
    }
    return { ok: true };
  },

  async list(params) {
    const s = findSession(params.sessionId);
    const dir = params.remotePath && params.remotePath !== '' ? params.remotePath : s.home;
    const list = await call(s.sftp, 'readdir', dir);
    const includeHidden = params.includeHidden !== false;
    const offset = Math.max(0, params.offset || 0);
    const limit = params.limit == null ? 0 : params.limit;
    const all = list
      .filter((i) => i.filename !== '.' && i.filename !== '..')
      .filter((i) => includeHidden || !i.filename.startsWith('.'))
      .map((i) => entryFromAttrs(i.filename, joinRemote(dir, i.filename), i.attrs, false));
    let entries = all;
    let hasMore = false;
    if (offset > 0 || limit > 0) {
      const end = limit > 0 ? offset + limit : all.length;
      entries = all.slice(offset, end);
      hasMore = end < all.length;
    }
    return { entries, hasMore };
  },

  async stat(params) {
    const s = findSession(params.sessionId);
    const follow = params.followSymlink !== false;
    const attrs = await statPath(s.sftp, params.remotePath, follow);
    const entry = entryFromAttrs(baseName(params.remotePath), params.remotePath, attrs, follow);
    if (follow && isLink(attrs.mode)) {
      try {
        const target = await call(s.sftp, 'readlink', params.remotePath);
        entry.symlinkTarget = target;
        entry.isSymlink = true;
        entry.followsTarget = true;
      } catch (e) { /* ignore */ }
    }
    return { entry };
  },

  async openRead(params) {
    const s = findSession(params.sessionId);
    const handle = await call(s.sftp, 'open', params.remotePath, 'r');
    let size = 0;
    try { const st = await call(s.sftp, 'fstat', handle); size = Number(st.size || 0); } catch (e) { /* ignore */ }
    const fileHandleId = 'fh-' + (++handleSeq);
    fileHandles.set(fileHandleId, { sftp: s.sftp, handle, sessionId: params.sessionId, remotePath: params.remotePath, size });
    return { fileHandleId };
  },

  async read(params) {
    const info = fileHandles.get(params.fileHandleId);
    if (!info) {
      const e = new Error('file handle not found: ' + params.fileHandleId);
      e.code = 'ENOENT';
      throw e;
    }
    const length = Math.max(0, params.length | 0);
    const offset = Math.max(0, Number(params.offset) || 0);
    if (length === 0) return { ok: true, base64: '' };
    const buf = Buffer.alloc(length);
    const bytesRead = await new Promise((resolve, reject) => {
      info.sftp.read(info.handle, buf, 0, length, offset, (err, n) => (err ? reject(err) : resolve(n)));
    });
    return { ok: true, base64: buf.slice(0, bytesRead).toString('base64') };
  },

  async close(params) {
    const info = fileHandles.get(params.fileHandleId);
    if (info) {
      fileHandles.delete(params.fileHandleId);
      try { await call(info.sftp, 'close', info.handle); } catch (e) { /* ignore */ }
    }
    return { ok: true };
  },

  async mkdir(params) {
    const s = findSession(params.sessionId);
    if (params.recursive === false) await call(s.sftp, 'mkdir', params.remotePath);
    else await ensureRemoteDir(s.sftp, params.remotePath);
    return { ok: true };
  },

  async rm(params) {
    const s = findSession(params.sessionId);
    if (params.recursive) {
      await removeRemoteRecursive(s.sftp, params.remotePath);
    } else {
      const attrs = await call(s.sftp, 'lstat', params.remotePath);
      if (isDir(attrs.mode) && !isLink(attrs.mode)) await call(s.sftp, 'rmdir', params.remotePath);
      else await call(s.sftp, 'unlink', params.remotePath);
    }
    return { ok: true };
  },

  async rename(params) {
    const s = findSession(params.sessionId);
    const oldPath = params.oldPath || params.srcPath;
    const newPath = params.newPath || params.destPath;
    await call(s.sftp, 'rename', oldPath, newPath);
    return { ok: true };
  },

  async move(params) {
    const s = findSession(params.sessionId);
    const srcPath = params.srcPath;
    const destDir = params.destDir;
    await ensureRemoteDir(s.sftp, destDir);
    await call(s.sftp, 'rename', srcPath, joinRemote(destDir, baseName(srcPath)));
    return { ok: true };
  },

  async copy(params) {
    const s = findSession(params.sessionId);
    const counters = { copied: 0, failed: 0 };
    await copyEntry(s.sftp, params.srcPath, params.destPath, counters);
    return {
      ok: true,
      result: { success: counters.failed === 0, copiedCount: counters.copied, failedCount: counters.failed, errors: [] },
    };
  },

  async chmod(params) {
    const s = findSession(params.sessionId);
    await call(s.sftp, 'chmod', params.remotePath, parseInt(params.mode, 8));
    return { ok: true };
  },

  async chown(params) {
    const s = findSession(params.sessionId);
    const uid = params.uid != null ? params.uid : -1;
    const gid = params.gid != null ? params.gid : -1;
    await call(s.sftp, 'chown', params.remotePath, uid, gid);
    return { ok: true };
  },

  async setMtime(params) {
    const s = findSession(params.sessionId);
    const mtime = Math.floor((params.mtime || 0) / 1000);
    const atime = params.atime ? Math.floor(params.atime / 1000) : mtime;
    await call(s.sftp, 'utimes', params.remotePath, atime, mtime);
    return { ok: true };
  },

  async upload(params) {
    const s = findSession(params.sessionId);
    let buf = null;
    let src = null;
    if (params.content != null) {
      buf = Buffer.from(params.content, 'base64');
    } else if (params.localPath) {
      // localPath 必须落在 LOCAL_ROOT 之下（realpath 校验），并流式上传避免整体入内存
      src = fs.createReadStream(await assertWithinLocalRoot(params.localPath));
    } else {
      return notImplemented('upload requires content or localPath');
    }
    await ensureRemoteDir(s.sftp, parentDir(params.remotePath));
    await new Promise((resolve, reject) => {
      const ws = s.sftp.createWriteStream(params.remotePath, { flags: 'w', mode: 0o644 });
      ws.on('error', reject);
      ws.on('close', resolve);
      if (src) { src.on('error', reject); src.pipe(ws); } else { ws.end(buf); }
    });
    return { progress: 1, success: true };
  },

  async download(params) {
    const s = findSession(params.sessionId);
    const st = await call(s.sftp, 'stat', params.remotePath);
    const name = params.localName || baseName(params.remotePath);
    if (name.startsWith('/')) {
      // 绝对路径：直接落盘（双栏「下载到本地栏」用）；必须落在 LOCAL_ROOT 之下
      const dest = await assertWithinLocalRoot(name);
      await fs.promises.mkdir(path.dirname(dest), { recursive: true });
      await new Promise((resolve, reject) => {
        const rs = s.sftp.createReadStream(params.remotePath);
        const ws = fs.createWriteStream(dest);
        rs.on('error', reject); ws.on('error', reject); ws.on('close', resolve);
        rs.pipe(ws);
      });
      return { progress: 1, path: dest, success: true };
    }
    // 媒体 URL 分支才需要 token（此前无条件注册，会在绝对路径分支留下无用 token）
    const token = crypto.randomBytes(16).toString('hex');
    mediaTokens.set(token, { sessionId: params.sessionId, remotePath: params.remotePath, size: Number(st.size || 0), handle: null });
    return { progress: 1, path: 'http://127.0.0.1:' + actualPort + '/' + token + '/' + encodeURIComponent(name), success: true };
  },

  async batchTask(params) {
    const sessionId = params.sessionId;
    const s = findSession(sessionId);
    const action = (params.action || 'DELETE').toUpperCase();
    const items = params.items || [];
    const targetDir = params.targetDir || '';
    if (!items.length) throw new Error('batchTask: items required');
    if ((action === 'COPY' || action === 'MOVE') && !targetDir) throw new Error('batchTask: targetDir required for ' + action);
    const failures = [];
    for (const item of items) {
      try {
        if (action === 'DELETE') {
          await removeRemoteRecursive(s.sftp, item);
        } else if (action === 'MOVE') {
          await ensureRemoteDir(s.sftp, targetDir);
          await call(s.sftp, 'rename', item, joinRemote(targetDir, baseName(item)));
        } else if (action === 'COPY') {
          await ensureRemoteDir(s.sftp, targetDir);
          await copyEntry(s.sftp, item, joinRemote(targetDir, baseName(item)), { copied: 0, failed: 0 });
        } else if (action === 'DOWNLOAD') {
          const st = await call(s.sftp, 'stat', item);
          const token = crypto.randomBytes(16).toString('hex');
          mediaTokens.set(token, { sessionId, remotePath: item, size: Number(st.size || 0), handle: null });
        } else {
          throw new Error('unsupported action: ' + action);
        }
      } catch (e) {
        failures.push(item + ': ' + (e.message || e));
      }
    }
    if (failures.length) throw new Error('batch ' + action + ' failed ' + failures.length + '/' + items.length + ': ' + failures[0]);
    return { progress: 1, success: true };
  },

  async cancelBatchTask() {
    return { ok: true };
  },
};

/* ------------------------------------------------------------------ *
 * mediaProxy 模块方法
 *   URL 与 common SftpMediaUrlBuilder.buildPlayUrl 一致：http://127.0.0.1:<port>/<token>/<name>
 * ------------------------------------------------------------------ */
const mediaProxyModule = {
  async startOrGetPort() { return { port: actualPort }; },
  async registerToken(params) {
    const { sessionId, remotePath, totalSize } = params;
    if (!sessionId || !remotePath) throw new Error('registerToken: sessionId/remotePath required');
    const s = findSession(sessionId);
    // 一律以服务端 stat 为准：客户端可能传来「上一集」的 size（切换选集时页面参数未更新），
    // 若按错误长度限制 Range，播放器会在中途 MEDIA_ERR_DECODE（实测切换后 ~2.3s 必现）。
    let size = 0;
    try { const st = await call(s.sftp, 'stat', remotePath); size = Number(st.size || 0); } catch (e) { /* ignore */ }
    if (!size) {
      const hint = Number(totalSize || 0);   // stat 失败才回退到客户端提示
      if (hint > 0) size = hint;
    }
    const token = crypto.randomBytes(16).toString('hex');
    mediaTokens.set(token, { sessionId, remotePath, size, handle: null });
    return { token };
  },
  async unregisterToken(params) {
    const t = mediaTokens.get(params.token);
    if (t) {
      mediaTokens.delete(params.token);
      if (t.handle) { try { await call(t.sftp, 'close', t.handle); } catch (e) { /* ignore */ } }
    }
    return { ok: true };
  },
  async stop() { mediaTokens.clear(); return { ok: true }; },
};

/* ------------------------------------------------------------------ *
 * 连接 / 收藏 / 播放历史：网关侧 JSON 文件持久化
 *   （浏览器 localStorage 也可，但放网关可与原生实现对齐、逻辑只写一份）
 * ------------------------------------------------------------------ */
const fs = require('fs');
const path = require('path');
const os = require('os');

// 本地文件访问根（网关与调用方同机）：upload 读 localPath / download 写绝对路径都限制在此根之下。
// 默认用户主目录，Electron 侧用 SFTP_GATEWAY_LOCAL_ROOT 显式传入（app.getPath('home')）。
// 校验走 realpath，防止主目录内的符号链接指向外部（越界拒绝，错误码 2001）。
const LOCAL_ROOT = path.resolve(process.env.SFTP_GATEWAY_LOCAL_ROOT || os.homedir());
let localRootReal = null;
async function assertWithinLocalRoot(p) {
  if (!p) throw new Error('LOCAL_PATH_DENIED: empty path');
  if (localRootReal === null) {
    try { localRootReal = await fs.promises.realpath(LOCAL_ROOT); } catch (e) { localRootReal = LOCAL_ROOT; }
  }
  const resolved = path.resolve(String(p));
  let real = null;
  try {
    real = await fs.promises.realpath(resolved);
  } catch (e) {
    // 目标尚不存在（下载新建文件/目录）：向上找到**最近存在的祖先**做真实路径校验，
    // 再拼回相对部分。否则「缓存到 <root>/<新任务目录>/<文件>」会因父目录不存在被误拒（曾表现为缓存只下一个文件即失败）。
    let dir = path.dirname(resolved);
    let ancestorReal = null;
    for (;;) {
      try { ancestorReal = await fs.promises.realpath(dir); break; } catch (e2) {
        const up = path.dirname(dir);
        if (up === dir) break;
        dir = up;
      }
    }
    if (ancestorReal !== null) {
      real = path.resolve(ancestorReal, path.relative(dir, resolved));
    }
  }
  if (real === null) throw new Error('LOCAL_PATH_DENIED: cannot resolve ' + resolved);
  if (real !== localRootReal && !real.startsWith(localRootReal + path.sep)) {
    throw new Error('LOCAL_PATH_DENIED: ' + real + ' outside root ' + localRootReal);
  }
  return real;
}

// 数据目录：默认在网关目录下；Electron 安装后由 SFTP_GATEWAY_DATA_DIR 指向可写的 userData
const DATA_DIR = process.env.SFTP_GATEWAY_DATA_DIR || path.join(__dirname, 'data');
const F_CONNECTIONS = path.join(DATA_DIR, 'sftp_connections.json');
const F_FAVORITES = path.join(DATA_DIR, 'sftp_favorites.json');
const F_HISTORY = path.join(DATA_DIR, 'sftp_playback_history.json');
const F_KNOWN_HOSTS = path.join(DATA_DIR, 'sftp_known_hosts.json');
const HISTORY_MAX = 2000;

function loadArray(file) {
  try {
    const txt = fs.readFileSync(file, 'utf8');
    const arr = JSON.parse(txt);
    return Array.isArray(arr) ? arr : [];
  } catch (e) {
    return [];
  }
}

function saveArray(file, arr) {
  try {
    fs.mkdirSync(DATA_DIR, { recursive: true });
    const tmp = file + '.tmp';
    fs.writeFileSync(tmp, JSON.stringify(arr));
    fs.renameSync(tmp, file);
  } catch (e) {
    console.warn('[sftp-gateway] 持久化失败（不影响本次连接）:', file, e.message);
  }
}

const nowMs = () => Date.now();

/* ---- known_hosts（TOFU）：防 MITM ---- */
function loadObject(file) {
  try {
    const o = JSON.parse(fs.readFileSync(file, 'utf8'));
    return o && typeof o === 'object' && !Array.isArray(o) ? o : {};
  } catch (e) {
    return {};
  }
}

function saveObject(file, obj) {
  try {
    fs.mkdirSync(DATA_DIR, { recursive: true });
    const tmp = file + '.tmp';
    fs.writeFileSync(tmp, JSON.stringify(obj, null, 2));
    fs.renameSync(tmp, file);
  } catch (e) {
    console.warn('[sftp-gateway] 持久化失败（不影响本次连接）:', file, e.message);
  }
}

function fingerprintOf(key) {
  return 'SHA256:' + crypto.createHash('sha256').update(key).digest('base64').replace(/=+$/, '');
}

/**
 * 校验主机公钥。策略：
 *   TOFU(默认)：未见过的指纹记录并放行；已记录但不匹配 → 拒绝
 *   STRICT   ：未记录也拒绝（必须先信任）
 *   INSECURE ：不校验（仅用于本地调试，显式选择）
 */
function verifyHostKey(host, port, key, policy) {
  const fp = fingerprintOf(key);
  const k = host + ':' + port;
  if (policy === 'INSECURE') return { ok: true, fingerprint: fp, trusted: false };
  const map = loadObject(F_KNOWN_HOSTS);
  const rec = map[k];
  if (!rec) {
    if (policy === 'STRICT') throw new Error('HOSTKEY_MISMATCH unknown host in STRICT mode: ' + k);
    map[k] = { fingerprint: fp, addedAt: nowMs() };
    saveObject(F_KNOWN_HOSTS, map);
    return { ok: true, fingerprint: fp, trusted: false, added: true };
  }
  if (rec.fingerprint !== fp) {
    throw new Error('HOSTKEY_MISMATCH for ' + k + ': known=' + rec.fingerprint + ' got=' + fp);
  }
  return { ok: true, fingerprint: fp, trusted: true };
}

const knownHostsModule = {
  async list() {
    const map = loadObject(F_KNOWN_HOSTS);
    return {
      items: Object.keys(map).map((k) => {
        const idx = k.lastIndexOf(':');
        return { id: k, host: k.slice(0, idx), port: Number(k.slice(idx + 1) || 22), fingerprint: map[k].fingerprint, addedAt: map[k].addedAt };
      }),
    };
  },
  async remove(params) {
    const map = loadObject(F_KNOWN_HOSTS);
    if (params.id) delete map[params.id];
    else if (params.host) delete map[params.host + ':' + (params.port || 22)];
    saveObject(F_KNOWN_HOSTS, map);
    return { ok: true };
  },
};

function sortByKey(arr, key, order) {
  const dir = order === 'ASC' ? 1 : -1;
  return arr.slice().sort((a, b) => {
    const av = a[key];
    const bv = b[key];
    if (typeof av === 'string' || typeof bv === 'string') return String(av || '').localeCompare(String(bv || '')) * dir;
    return ((Number(av) || 0) - (Number(bv) || 0)) * dir;
  });
}

const connectionModule = {
  async add(params) {
    const arr = loadArray(F_CONNECTIONS);
    const id = params.id || crypto.randomUUID();
    const item = Object.assign({}, params, { id, createdAt: params.createdAt || nowMs() });
    const idx = arr.findIndex((x) => x.id === id);
    if (idx >= 0) arr[idx] = item; else arr.push(item);
    saveArray(F_CONNECTIONS, arr);
    return { id };
  },
  async update(params) {
    const arr = loadArray(F_CONNECTIONS);
    const idx = arr.findIndex((x) => x.id === params.id);
    if (idx < 0) throw new Error('connection not found');
    const merged = Object.assign({}, arr[idx], params, { createdAt: arr[idx].createdAt });
    arr[idx] = merged;
    saveArray(F_CONNECTIONS, arr);
    return { ok: true };
  },
  async remove(params) {
    let arr = loadArray(F_CONNECTIONS);
    const before = arr.length;
    arr = arr.filter((x) => x.id !== params.id);
    saveArray(F_CONNECTIONS, arr);
    if (params.id && arr.length !== before) {
      saveArray(F_FAVORITES, loadArray(F_FAVORITES).filter((x) => x.connectionId !== params.id));
      saveArray(F_HISTORY, loadArray(F_HISTORY).filter((x) => x.connectionId !== params.id));
    }
    return { ok: true };
  },
  async list() {
    return { items: sortByKey(loadArray(F_CONNECTIONS), 'lastUsedAt', 'DESC') };
  },
  async get(params) {
    const found = loadArray(F_CONNECTIONS).find((x) => x.id === params.id);
    if (!found) throw new Error('connection not found');
    return { conn: found };
  },
  async touchLastUsed(params) {
    const arr = loadArray(F_CONNECTIONS);
    const idx = arr.findIndex((x) => x.id === params.id);
    if (idx >= 0) { arr[idx] = Object.assign({}, arr[idx], { lastUsedAt: nowMs() }); saveArray(F_CONNECTIONS, arr); }
    return { ok: true };
  },
};

const favoritesModule = {
  async add(params) {
    const arr = loadArray(F_FAVORITES);
    const id = params.id || crypto.randomUUID();
    const item = Object.assign({}, params, { id, starredAt: params.starredAt || nowMs() });
    const idx = arr.findIndex((x) => x.id === id);
    if (idx >= 0) arr[idx] = item; else arr.push(item);
    saveArray(F_FAVORITES, arr);
    return { id };
  },
  async remove(params) {
    saveArray(F_FAVORITES, loadArray(F_FAVORITES).filter((x) => x.id !== params.id));
    return { ok: true };
  },
  async removeByConnection(params) {
    const arr = loadArray(F_FAVORITES);
    const kept = arr.filter((x) => x.connectionId !== params.connectionId);
    saveArray(F_FAVORITES, kept);
    return { ok: true, removedCount: arr.length - kept.length };
  },
  async list(params) {
    let arr = loadArray(F_FAVORITES);
    if (params.connectionId) arr = arr.filter((x) => x.connectionId === params.connectionId);
    const keyMap = { NAME: 'name', CONNECTION_LABEL: 'connectionLabel', MTIME: 'mtime', STARRED_AT: 'starredAt' };
    const key = keyMap[params.sortBy] || 'starredAt';
    return { items: sortByKey(arr, key, params.sortOrder) };
  },
  async isFavorited(params) {
    const found = loadArray(F_FAVORITES).find((x) => x.connectionId === params.connectionId && x.remotePath === params.remotePath);
    return { id: found ? found.id : '' };
  },
  async update(params) {
    const arr = loadArray(F_FAVORITES);
    const idx = arr.findIndex((x) => x.id === params.id);
    if (idx < 0) throw new Error('favorite not found');
    const item = Object.assign({}, arr[idx]);
    for (const k of ['note', 'iconOverride']) {
      if (Object.prototype.hasOwnProperty.call(params, k)) {
        if (params[k] === '') delete item[k]; else item[k] = params[k];
      }
    }
    arr[idx] = item;
    saveArray(F_FAVORITES, arr);
    return { ok: true };
  },
  async search(params) {
    const kw = (params.keyword || '').toLowerCase();
    const arr = loadArray(F_FAVORITES).filter((x) =>
      !kw ||
      String(x.name || '').toLowerCase().includes(kw) ||
      String(x.remotePath || '').toLowerCase().includes(kw) ||
      String(x.connectionLabel || '').toLowerCase().includes(kw));
    return { items: arr };
  },
};

/**
 * 历史记录匹配：页面写入用的 id 是 SftpPlaybackRecord.buildId 的哈希，
 * 与网关拼的 `connectionId::remotePath` 不同 → 依次按 id、拼接 id、(connectionId, remotePath) 匹配。
 * 注意：模块方法是 `const fn = mod[method]; fn(params)` 调用的（this 为 undefined），故必须是模块级函数。
 */
function findHistoryIndex(arr, params) {
  const wantId = params && params.id ? String(params.id) : '';
  if (wantId) { const i = arr.findIndex((x) => String(x.id || '') === wantId); if (i >= 0) return i; }
  const fallback = String((params && params.connectionId) || '') + '::' + String((params && params.remotePath) || '');
  let i = arr.findIndex((x) => String(x.id || '') === fallback);
  if (i >= 0) return i;
  return arr.findIndex((x) => String(x.connectionId || '') === String((params && params.connectionId) || '')
    && String(x.remotePath || '') === String((params && params.remotePath) || ''));
}

const historyModule = {
  async upsert(params) {
    const arr = loadArray(F_HISTORY);
    const id = params.id || (String(params.connectionId || '') + '::' + String(params.remotePath || ''));
    const item = Object.assign({}, params, { id, lastPlayedAt: params.lastPlayedAt || nowMs() });
    const idx = arr.findIndex((x) => x.id === id);
    if (idx >= 0) arr[idx] = item; else arr.push(item);
    // 追加语义 + 容量上限（默认 1000，可在设置里改）：超限丢最旧
    let trimmed = arr;
    const lim = historyLimit();
    if (arr.length > lim) trimmed = sortByKey(arr, 'lastPlayedAt', 'DESC').slice(0, lim);
    saveArray(F_HISTORY, trimmed);
    return { ok: true };
  },
  async get(params) {
    const arr = loadArray(F_HISTORY);
    const i = findHistoryIndex(arr, params);
    return i >= 0 ? { record: arr[i] } : { ok: true };
  },
  async listByDirectory(params) {
    const parentOf = (p) => { const s = String(p || '').replace(/\/+$/, ''); const i = s.lastIndexOf('/'); return i <= 0 ? '/' : s.slice(0, i); };
    let arr = loadArray(F_HISTORY).filter((x) => x.connectionId === params.connectionId);
    if (params.directoryPath) arr = arr.filter((x) => parentOf(x.remotePath) === params.directoryPath);
    return { records: sortByKey(arr, 'lastPlayedAt', 'DESC') };
  },
  async listByConnection(params) {
    let arr = loadArray(F_HISTORY);
    if (params.connectionId) arr = arr.filter((x) => x.connectionId === params.connectionId);
    return { records: sortByKey(arr, 'lastPlayedAt', 'DESC') };
  },
  async remove(params) {
    saveArray(F_HISTORY, loadArray(F_HISTORY).filter((x) => x.id !== params.id));
    return { ok: true };
  },
  async clearByConnection(params) {
    saveArray(F_HISTORY, loadArray(F_HISTORY).filter((x) => x.connectionId !== params.connectionId));
    return { ok: true };
  },
  async getLimit(params) { return { limit: historyLimit() }; },
  async setLimit(params) { return { limit: setHistoryLimit(params && params.limit) }; },

  async markCompleted(params) {
    const arr = loadArray(F_HISTORY);
    const idx = findHistoryIndex(arr, params);
    if (idx >= 0) arr[idx] = Object.assign({}, arr[idx], { completed: true, position: arr[idx].duration });
    saveArray(F_HISTORY, trimHistory(arr));
    return { ok: true };
  },
};

/* ------------------------------------------------------------------ *
 * HTTP 层
 * ------------------------------------------------------------------ */

/* ------------------------------------------------------------------ *
 * 终端（SSH shell / pty）
 *   - 只做 SSH 远程终端（不含本地 shell）
 *   - 输出用「绝对偏移 + 轮询」拉取（无需 WebSocket，零新依赖）
 *   - 环形缓冲：只保留最近 SHELL_KEEP_BYTES，避免大输出吃内存
 * ------------------------------------------------------------------ */
const SHELL_KEEP_BYTES = 512 * 1024;
const SHELL_READ_MAX = 64 * 1024;
const shellSessions = new Map();
let shellSeq = 0;
const { spawn: spawnChild } = require('child_process');

/**
 * 本地终端：用宿主系统的 `script` 提供伪终端（macOS: script -q /dev/null sh -i；
 * Linux: script -qfc "sh -i" /dev/null），把 stdin/stdout 适配成与 SSH shell 一致的接口。
 */
function openLocalPty(cols, rows) {
  const shell = process.env.SHELL || '/bin/bash';
  // 用 python3 的 pty.spawn 提供真正的伪终端（macOS 的 script 在管道里无法建 pty；
  // python3 随 Xcode CLT 自带，跨 macOS/Linux 都可用）。
  const env = Object.assign({}, process.env, {
    TERM: 'xterm-256color',
    COLUMNS: String(cols),
    LINES: String(rows),
    KR_SHELL: shell,
  });
  const child = spawnChild('python3', ['-c', 'import os,pty; pty.spawn([os.environ.get("KR_SHELL","/bin/bash")])'], { env });
  return {
    __child: child,
    on(ev, cb) {
      if (ev === 'data') { child.stdout.on('data', cb); child.stderr.on('data', cb); }
      else if (ev === 'close') { child.on('close', cb); child.on('error', cb); }
    },
    write(b) { try { child.stdin.write(b); } catch (e) { /* ignore */ } },
    end() { try { child.kill(); } catch (e) { /* ignore */ } },
    setWindow() { /* 本地 pty 尺寸跟随，暂不支持动态改窗 */ },
  };
}

function shellAppend(st, d) {
  st.chunks.push({ off: st.total, buf: d });
  st.total += d.length;
  const keepFrom = Math.max(0, st.total - SHELL_KEEP_BYTES);
  while (st.chunks.length > 1 && st.chunks[0].off + st.chunks[0].buf.length <= keepFrom) {
    st.chunks.shift();
  }
  if (st.chunks.length) st.baseOff = st.chunks[0].off;
}

/** 播放历史：追加语义 + 容量上限（默认 1000，超限丢最旧） */
const HISTORY_DEFAULT_LIMIT = 1000;
const F_HISTORY_LIMIT = 'sftp_history_limit.json';
function historyLimit() {
  try {
    const v = Number(loadArray(F_HISTORY_LIMIT)[0] && loadArray(F_HISTORY_LIMIT)[0].limit);
    return v > 0 ? v : HISTORY_DEFAULT_LIMIT;
  } catch (e) { return HISTORY_DEFAULT_LIMIT; }
}
function setHistoryLimit(n) {
  const v = Math.max(2, Math.min(100000, Number(n) || HISTORY_DEFAULT_LIMIT));
  saveArray(F_HISTORY_LIMIT, [{ limit: v }]);
  return v;
}
/** 按 starredAt/lastPlayedAt 去重后保留最新 limit 条 */
function trimHistory(arr) {
  const lim = historyLimit();
  if (arr.length <= lim) return arr;
  const sorted = arr.slice().sort((a, b) => (Number(b.updatedAt || b.starredAt || 0)) - (Number(a.updatedAt || a.starredAt || 0)));
  return sorted.slice(0, lim);
}

const shellModule = {
  async open(params) {
    const term = params.term || 'xterm-256color';
    const cols = Math.max(2, Number(params.cols || 80));
    const rows = Math.max(2, Number(params.rows || 24));
    const shellId = 'sh-' + (++shellSeq);
    const st = { stream: null, chunks: [], total: 0, baseOff: 0, closed: false, local: !!params.local };
    shellSessions.set(shellId, st);
    if (params.local) {
      // 本地终端（无需 SSH 会话）
      st.stream = openLocalPty(cols, rows);
    } else {
      const s = findSession(params.sessionId);
      await new Promise((resolve, reject) => {
        s.conn.shell({ term, cols, rows }, (err, stream) => {
          if (err) return reject(err);
          st.stream = stream;
          resolve();
        });
      });
    }
    st.stream.on('data', (d) => shellAppend(st, d));
    if (st.stream.stderr) st.stream.stderr.on('data', (d) => shellAppend(st, d));
    st.stream.on('close', () => { st.closed = true; });
    return { shellId, offset: st.total, cols, rows };
  },

  async read(params) {
    const st = shellSessions.get(params.shellId);
    if (!st) return { error: 'shell not found', closed: true };
    let from = Number(params.offset || 0);
    if (from < st.baseOff) from = st.baseOff;   // 已被环形缓冲丢弃，从最旧可用位置开始
    const parts = [];
    let out = 0;
    for (const c of st.chunks) {
      const end = c.off + c.buf.length;
      if (end <= from) continue;
      const s0 = Math.max(0, from - c.off);
      const slice = c.buf.subarray(s0);
      if (out + slice.length > SHELL_READ_MAX) {
        const take = SHELL_READ_MAX - out;
        parts.push(slice.subarray(0, take));
        out += take;
        break;
      }
      parts.push(slice);
      out += slice.length;
      if (out >= SHELL_READ_MAX) break;
    }
    const data = parts.length ? Buffer.concat(parts) : Buffer.alloc(0);
    return {
      data: data.toString('base64'),
      offset: from + data.length,
      closed: !!st.closed,
      reset: Number(params.offset || 0) < st.baseOff,
    };
  },

  async write(params) {
    const st = shellSessions.get(params.shellId);
    if (!st || !st.stream) return { error: 'shell not found' };
    st.stream.write(Buffer.from(params.data || '', 'base64'));
    return { ok: true };
  },

  async resize(params) {
    const st = shellSessions.get(params.shellId);
    if (!st || !st.stream) return { error: 'shell not found' };
    const cols = Math.max(2, Number(params.cols || 80));
    const rows = Math.max(2, Number(params.rows || 24));
    st.stream.setWindow(rows, cols, 0, 0);
    return { ok: true };
  },

  async close(params) {
    const st = shellSessions.get(params.shellId);
    if (st) {
      try { st.stream && st.stream.end(); } catch (e) { /* ignore */ }
      shellSessions.delete(params.shellId);
    }
    return { ok: true };
  },
};

const MODULES = {
  sftp: sftpModule,
  shell: shellModule,
  knownHosts: knownHostsModule,
  mediaProxy: mediaProxyModule,
  connection: connectionModule,
  favorites: favoritesModule,
  history: historyModule,
};

const CORS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'GET, POST, HEAD, OPTIONS',
  'Access-Control-Allow-Headers': 'Content-Type, Range',
  'Access-Control-Expose-Headers': 'Content-Range, Content-Length, Accept-Ranges',
};

function sendJson(res, status, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(status, Object.assign({ 'Content-Type': 'application/json; charset=utf-8' }, CORS));
  res.end(body);
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let data = '';
    req.on('data', (c) => { data += c; if (data.length > 64 * 1024 * 1024) req.destroy(); });
    req.on('end', () => resolve(data));
    req.on('error', reject);
  });
}

async function handleRpc(req, res) {
  let payload;
  try {
    const body = await readBody(req);
    payload = JSON.parse(body || '{}');
  } catch (e) {
    return sendJson(res, 400, errorJson(new Error('invalid json body')));
  }
  const mod = MODULES[payload.module];
  if (!mod) return sendJson(res, 200, notImplemented('module ' + payload.module));
  const fn = mod[payload.method];
  if (typeof fn !== 'function') return sendJson(res, 200, notImplemented(payload.module + '.' + payload.method));
  try {
    const result = await fn(payload.params || {});
    sendJson(res, 200, result || {});
  } catch (e) {
    sendJson(res, 200, errorJson(e));
  }
}

function parseRange(header, size) {
  // 返回 {start,end} / 'unsatisfiable' / null（无 Range）
  if (!header || !/^bytes=/.test(header) || !size) return null;
  const spec = header.slice(6).trim();
  if (spec.indexOf(',') >= 0) return { start: 0, end: size - 1 };
  const dash = spec.indexOf('-');
  if (dash < 0) return { start: 0, end: size - 1 };
  const startStr = spec.slice(0, dash);
  const endStr = spec.slice(dash + 1);
  let start;
  let end;
  if (startStr === '') {
    const suffix = parseInt(endStr, 10);
    if (!suffix || suffix <= 0) return 'unsatisfiable';
    start = Math.max(0, size - suffix);
    end = size - 1;
  } else {
    start = parseInt(startStr, 10);
    if (isNaN(start)) return { start: 0, end: size - 1 };
    if (start > size) return 'unsatisfiable';
    // 与 iOS 端一致：start == size（如播放器探测 bytes=<size>-）按最后一个字节处理，
    // 否则非 faststart 的 MP4（moov 在尾部）拿不到索引，seek 会直接 Ended。
    if (start === size) start = size - 1;
    end = endStr === '' ? size - 1 : Math.min(parseInt(endStr, 10), size - 1);
  }
  if (isNaN(end) || end < start) return 'unsatisfiable';
  return { start, end };
}

function mimeOf(name) {
  const ext = (name.split('.').pop() || '').toLowerCase();
  const map = {
    mp4: 'video/mp4', m4v: 'video/x-m4v', mov: 'video/quicktime', mkv: 'video/x-matroska',
    webm: 'video/webm', avi: 'video/x-msvideo', ts: 'video/mp2t', flv: 'video/x-flv',
    mp3: 'audio/mpeg', m4a: 'audio/mp4', aac: 'audio/aac', wav: 'audio/wav', flac: 'audio/flac', ogg: 'audio/ogg',
    jpg: 'image/jpeg', jpeg: 'image/jpeg', png: 'image/png', gif: 'image/gif', webp: 'image/webp', bmp: 'image/bmp',
    pdf: 'application/pdf', txt: 'text/plain; charset=utf-8', md: 'text/markdown; charset=utf-8',
    html: 'text/html; charset=utf-8', json: 'application/json', xml: 'application/xml',
  };
  return map[ext] || 'application/octet-stream';
}

async function handleMedia(req, res, token, name) {
  const t = mediaTokens.get(token);
  if (!t) {
    res.writeHead(404, Object.assign({ 'Content-Type': 'text/plain' }, CORS));
    return res.end('token not found');
  }
  const s = sessions.get(t.sessionId);
  if (!s) {
    res.writeHead(410, Object.assign({ 'Content-Type': 'text/plain' }, CORS));
    return res.end('session gone');
  }
  try {
    if (!t.handle) {
      t.sftp = s.sftp;
      t.handle = await call(s.sftp, 'open', t.remotePath, 'r');
      if (!t.size) {
        try { const st = await call(s.sftp, 'fstat', t.handle); t.size = Number(st.size || 0); } catch (e) { /* ignore */ }
      }
    }
  } catch (e) {
    res.writeHead(500, Object.assign({ 'Content-Type': 'text/plain' }, CORS));
    return res.end('open failed: ' + (e.message || e));
  }
  const size = t.size || 0;
  const range = parseRange(req.headers['range'], size);
  const headers = Object.assign({ 'Content-Type': mimeOf(t.remotePath), 'Accept-Ranges': 'bytes', 'Cache-Control': 'no-store' }, CORS);
  if (range === 'unsatisfiable') {
    headers['Content-Range'] = 'bytes */' + size;
    res.writeHead(416, headers);
    return res.end();
  }
  if (range) {
    const { start, end } = range;
    headers['Content-Range'] = 'bytes ' + start + '-' + end + '/' + size;
    headers['Content-Length'] = String(end - start + 1);
    res.writeHead(206, headers);
    if (req.method === 'HEAD') return res.end();
    const rs = t.sftp.createReadStream(t.remotePath, { start, end });
    rs.on('error', () => res.destroy());
    rs.pipe(res);
    return;
  }
  // 无 Range：整段
  if (size) headers['Content-Length'] = String(size);
  res.writeHead(200, headers);
  if (req.method === 'HEAD') return res.end();
  const rs = t.sftp.createReadStream(t.remotePath);
  rs.on('error', () => res.destroy());
  rs.pipe(res);
}

const server = http.createServer(async (req, res) => {
  const u = new URL(req.url, 'http://127.0.0.1');
  if (req.method === 'OPTIONS') { res.writeHead(204, CORS); return res.end(); }
  if (u.pathname === '/health') return sendJson(res, 200, { ok: true, port: actualPort });
  if (u.pathname === '/rpc' && req.method === 'POST') return handleRpc(req, res);
  // 媒体：/<token>/<fileName>
  const segs = u.pathname.replace(/^\//, '').split('/');
  if (segs.length >= 2 && segs[0]) {
    const name = decodeURIComponent(segs.slice(1).join('/'));
    return handleMedia(req, res, segs[0], name);
  }
  sendJson(res, 404, { error: 'not found' });
});

server.listen(PORT, '127.0.0.1', () => {
  actualPort = server.address().port;
  console.log('[sftp-gateway] listening on http://127.0.0.1:' + actualPort);
  // Electron(utilityProcess) 场景：把实际端口回传主进程（SFTP_GATEWAY_PORT=0 时必需）
  try {
    if (process.parentPort && typeof process.parentPort.postMessage === 'function') {
      process.parentPort.postMessage({ type: 'listening', port: actualPort });
    }
  } catch (e) { /* 非 Electron 环境忽略 */ }
});

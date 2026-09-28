#!/usr/bin/env node
/*
 * 自包含 SSH/SFTP 夹具服务（自动化测试专用）
 *
 * 背景：`electron/test/dual-pane.mjs` 等用例原本硬依赖内网测试机（192.168.2.2:22）。
 * 该机器不可达时（防火墙/关机/换网络），整条 SFTP 链路用例会在 D0 直接失败，
 * 导致「代码是否改对」无法被验证。本夹具用 ssh2 的**服务端**能力在进程内起一个
 * 真实 SSH + SFTP 服务，文件系统沙盒到一个临时目录，从而让端到端用例完全自包含。
 *
 * 用法（由测试脚本作为子进程拉起，也可手工运行）：
 *   cd sftp-gateway && node test/fixture-sftp-server.mjs \
 *     --port 19000 --root /tmp/kr-fixture --key /tmp/kr-fixture-hostkey \
 *     --user fixture --pass fixture --home /home/fixture
 *
 * 就绪后在 stdout 打印一行（测试脚本据此判定可用）：
 *   FIXTURE_READY {"port":19000,"home":"/home/fixture","root":"..."}
 *
 * 设计要点：
 *   1. **稳定主机密钥**：密钥持久化到 `--key` 指定路径（默认复用已存在文件）。
 *      页面侧 `SftpConnectParam` 没有 hostKeyPolicy 字段（恒为 TOFU），若每次运行
 *      生成新密钥，第二次运行会因指纹变化被网关拒绝（HOSTKEY_MISMATCH/1004）。
 *   2. **沙盒**：所有虚拟路径解析后必须落在 `--root` 内，越界返回 PERMISSION_DENIED。
 *   3. 仅实现测试所需的 SFTP 子集：OPEN/READ/WRITE/CLOSE/OPENDIR/READDIR/
 *      STAT/LSTAT/FSTAT/SETSTAT/FSETSTAT/MKDIR/RMDIR/REMOVE/RENAME/REALPATH。
 *      未实现的操作显式返回 OP_UNSUPPORTED，**不伪报成功**（遵循 AGENTS §13.3）。
 */

import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const { Server, utils } = require('ssh2');
const { STATUS_CODE, OPEN_MODE } = utils.sftp;

// ---------------------------------------------------------------- CLI 参数
function arg(name, def) {
  const i = process.argv.indexOf(`--${name}`);
  return i >= 0 && process.argv[i + 1] != null ? process.argv[i + 1] : def;
}

const PORT = Number(arg('port', '0'));
const ROOT = path.resolve(arg('root', path.join(process.cwd(), '.fixture-root')));
const KEY_FILE = path.resolve(arg('key', path.join(ROOT, '..', 'fixture-hostkey')));
const USER = arg('user', 'fixture');
const PASS = arg('pass', 'fixture');
const HOME = arg('home', '/home/fixture');

// ---------------------------------------------------------------- 工具函数
const asStr = (v) => (Buffer.isBuffer(v) ? v.toString('utf8') : String(v));
const keyOf = (h) => Buffer.from(h).toString('hex');

/** 主机密钥：优先复用磁盘上的（保证指纹稳定，兼容 TOFU） */
function loadOrCreateHostKey() {
  try {
    const k = fs.readFileSync(KEY_FILE, 'utf8');
    if (k.includes('PRIVATE KEY')) return k;
  } catch (e) {
    /* 不存在则生成 */
  }
  const { private: privateKey } = utils.generateKeyPairSync('ed25519');
  fs.mkdirSync(path.dirname(KEY_FILE), { recursive: true });
  fs.writeFileSync(KEY_FILE, privateKey, { mode: 0o600 });
  return privateKey;
}

/** 虚拟路径 → 真实路径（沙盒：必须落在 ROOT 内） */
function realOf(v) {
  let p = asStr(v);
  if (!p) p = '/';
  if (p[0] !== '/') p = `/${p}`;
  const normalized = path.posix.normalize(p);
  const real = path.join(ROOT, normalized);
  const rel = path.relative(ROOT, real);
  if (rel.startsWith('..') || path.isAbsolute(rel)) {
    const err = new Error('path escapes fixture root');
    err.code = STATUS_CODE.PERMISSION_DENIED;
    throw err;
  }
  return real;
}

/** 真实路径 → 虚拟路径（REALPATH 用） */
function virtualOf(real) {
  const rel = path.relative(ROOT, real);
  const posixRel = rel.split(path.sep).join('/');
  return posixRel ? `/${posixRel}` : '/';
}

function modeString(mode) {
  const bits = (shift) => (((mode >> shift) & 4 ? 'r' : '-') + ((mode >> shift) & 2 ? 'w' : '-') + ((mode >> shift) & 1 ? 'x' : '-'));
  return bits(6) + bits(3) + bits(0);
}

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
function longName(name, st) {
  const type = st.isDirectory() ? 'd' : st.isSymbolicLink() ? 'l' : '-';
  const d = new Date(st.mtimeMs);
  const day = String(d.getDate()).padStart(2, ' ');
  const hh = String(d.getHours()).padStart(2, '0');
  const mm = String(d.getMinutes()).padStart(2, '0');
  return `${type}${modeString(st.mode)} 1 ${st.uid} ${st.gid} ${String(st.size).padStart(8, ' ')} ${MONTHS[d.getMonth()]} ${day} ${hh}:${mm} ${name}`;
}

/** SFTP ATTRS：时间为**秒**（ssh2 的 attrsToBytes 对 number 直接透传） */
function attrsOf(st) {
  return {
    mode: st.mode,
    uid: st.uid,
    gid: st.gid,
    size: st.size,
    atime: Math.floor(st.atimeMs / 1000),
    mtime: Math.floor(st.mtimeMs / 1000),
  };
}

/** SFTP pflags → Node fs.open 标志 */
function nodeOpenFlags(pflags) {
  const R = !!(pflags & OPEN_MODE.READ);
  const W = !!(pflags & OPEN_MODE.WRITE);
  const A = !!(pflags & OPEN_MODE.APPEND);
  const C = !!(pflags & OPEN_MODE.CREAT);
  const T = !!(pflags & OPEN_MODE.TRUNC);
  const X = !!(pflags & OPEN_MODE.EXCL);

  let flags;
  if (R && !W) flags = 'r';
  else if (A) flags = 'a';
  else if (W && R) flags = T ? 'w+' : 'r+';
  else if (T) flags = 'w';
  else if (C) flags = 'a';
  else flags = 'r+';
  if (X && (flags[0] === 'w' || flags[0] === 'a')) flags += 'x';
  return flags;
}

function applyAttrs(real, attrs) {
  if (!attrs) return;
  if (typeof attrs.mode === 'number') fs.chmodSync(real, attrs.mode & 0o7777);
  if (typeof attrs.mtime === 'number') {
    const t = attrs.mtime;
    fs.utimesSync(real, attrs.atime != null ? attrs.atime : t, t);
  }
}

/**
 * 错误 → SFTP 状态码。
 * 注意：Node 的 err.code 是**字符串**（'ENOENT' 等），不能直接当状态码用；
 * 否则「文件不存在」会变成 FAILURE(4)，客户端的错误码语义（2003 文件不存在）就错了。
 */
function statusFor(err) {
  if (err && typeof err.code === 'number') return err.code;   // 夹具内部自抛的 SFTP 码
  switch (err && err.code) {
    case 'ENOENT':
      return STATUS_CODE.NO_SUCH_FILE;
    case 'EACCES':
    case 'EPERM':
    case 'EROFS':
    case 'EISDIR':
      return STATUS_CODE.PERMISSION_DENIED;
    default:
      return STATUS_CODE.FAILURE;
  }
}

// ---------------------------------------------------------------- SFTP 处理器
function attachSftp(sftp) {
  const handles = new Map();
  let seq = 1;

  const add = (obj) => {
    const id = Buffer.alloc(4);
    id.writeUInt32BE(seq++, 0);
    handles.set(keyOf(id), obj);
    return id;
  };
  const get = (h) => handles.get(keyOf(h));
  const drop = (h) => handles.delete(keyOf(h));
  const ok = (reqid) => sftp.status(reqid, STATUS_CODE.OK);
  const fail = (reqid, err) => sftp.status(reqid, statusFor(err), err ? String(err.message) : undefined);
  const unsupported = (reqid) => sftp.status(reqid, STATUS_CODE.OP_UNSUPPORTED);

  sftp.on('OPEN', (reqid, filename, pflags) => {
    try {
      const real = realOf(filename);
      const fd = fs.openSync(real, nodeOpenFlags(pflags));
      sftp.handle(reqid, add({ type: 'file', fd, real }));
    } catch (e) {
      fail(reqid, e);
    }
  });

  sftp.on('READ', (reqid, handle, offset, len) => {
    const h = get(handle);
    if (!h || h.type !== 'file') return fail(reqid, new Error('bad handle'));
    const buf = Buffer.alloc(len);
    fs.read(h.fd, buf, 0, len, offset, (err, n) => {
      if (err) return fail(reqid, err);
      // 读 0 字节必须回 EOF，否则客户端会认为流未结束
      if (n === 0) return sftp.status(reqid, STATUS_CODE.EOF);
      sftp.data(reqid, buf.subarray(0, n));
    });
  });

  sftp.on('WRITE', (reqid, handle, offset, data) => {
    const h = get(handle);
    if (!h || h.type !== 'file') return fail(reqid, new Error('bad handle'));
    const buf = Buffer.isBuffer(data) ? data : Buffer.from(String(data), 'utf8');
    fs.write(h.fd, buf, 0, buf.length, offset, (err) => (err ? fail(reqid, err) : ok(reqid)));
  });

  sftp.on('CLOSE', (reqid, handle) => {
    const h = get(handle);
    if (!h) return fail(reqid, new Error('bad handle'));
    drop(handle);
    if (h.type === 'file') {
      try {
        fs.closeSync(h.fd);
      } catch (e) {
        /* ignore */
      }
    }
    ok(reqid);
  });

  sftp.on('OPENDIR', (reqid, p) => {
    try {
      const real = realOf(p);
      const names = fs.readdirSync(real);
      const entries = names.map((n) => ({ name: n, real: path.join(real, n) }));
      sftp.handle(reqid, add({ type: 'dir', entries, idx: 0 }));
    } catch (e) {
      fail(reqid, e);
    }
  });

  sftp.on('READDIR', (reqid, handle) => {
    const h = get(handle);
    if (!h || h.type !== 'dir') return fail(reqid, new Error('bad handle'));
    if (h.idx >= h.entries.length) return sftp.status(reqid, STATUS_CODE.EOF);
    const batch = h.entries.slice(h.idx, h.idx + 64);
    h.idx += batch.length;
    sftp.name(
      reqid,
      batch.map((e) => {
        let st = null;
        try {
          st = fs.lstatSync(e.real);
        } catch (err) {
          /* 竞态删除：仍然列出名字 */
        }
        return { filename: e.name, longname: st ? longName(e.name, st) : e.name, attrs: st ? attrsOf(st) : {} };
      })
    );
  });

  const statLike = (follow) => (reqid, p) => {
    try {
      const real = realOf(p);
      sftp.attrs(reqid, attrsOf(follow ? fs.statSync(real) : fs.lstatSync(real)));
    } catch (e) {
      fail(reqid, e);
    }
  };
  sftp.on('STAT', statLike(true));
  sftp.on('LSTAT', statLike(false));

  sftp.on('FSTAT', (reqid, handle) => {
    const h = get(handle);
    if (!h || h.type !== 'file') return fail(reqid, new Error('bad handle'));
    try {
      sftp.attrs(reqid, attrsOf(fs.fstatSync(h.fd)));
    } catch (e) {
      fail(reqid, e);
    }
  });

  sftp.on('SETSTAT', (reqid, p, attrs) => {
    try {
      applyAttrs(realOf(p), attrs);
      ok(reqid);
    } catch (e) {
      fail(reqid, e);
    }
  });

  sftp.on('FSETSTAT', (reqid, handle, attrs) => {
    const h = get(handle);
    if (!h || h.type !== 'file') return fail(reqid, new Error('bad handle'));
    try {
      applyAttrs(h.real, attrs);
      ok(reqid);
    } catch (e) {
      fail(reqid, e);
    }
  });

  sftp.on('MKDIR', (reqid, p, attrs) => {
    try {
      const real = realOf(p);
      fs.mkdirSync(real, { recursive: true });
      if (attrs) applyAttrs(real, attrs);
      ok(reqid);
    } catch (e) {
      fail(reqid, e);
    }
  });

  sftp.on('RMDIR', (reqid, p) => {
    try {
      fs.rmdirSync(realOf(p));
      ok(reqid);
    } catch (e) {
      fail(reqid, e);
    }
  });

  sftp.on('REMOVE', (reqid, p) => {
    try {
      fs.unlinkSync(realOf(p));
      ok(reqid);
    } catch (e) {
      fail(reqid, e);
    }
  });

  sftp.on('RENAME', (reqid, oldPath, newPath) => {
    try {
      fs.renameSync(realOf(oldPath), realOf(newPath));
      ok(reqid);
    } catch (e) {
      fail(reqid, e);
    }
  });

  sftp.on('REALPATH', (reqid, p) => {
    try {
      const real = realOf(p);
      const virt = virtualOf(real);
      let st = null;
      try {
        st = fs.lstatSync(real);
      } catch (e) {
        /* 路径可以不存在，仍返回规范化结果 */
      }
      sftp.name(reqid, [{ filename: virt, longname: virt, attrs: st ? attrsOf(st) : {} }]);
    } catch (e) {
      fail(reqid, e);
    }
  });

  sftp.on('SYMLINK', (reqid, targetPath, linkPath) => {
    try {
      fs.symlinkSync(realOf(targetPath), realOf(linkPath));
      ok(reqid);
    } catch (e) {
      fail(reqid, e);
    }
  });

  sftp.on('READLINK', (reqid) => unsupported(reqid));
  sftp.on('EXTENDED', (reqid) => unsupported(reqid));
}

// ---------------------------------------------------------------- 最小 shell（终端用例备用）
function attachShell(channel) {
  let buf = '';
  const prompt = () => channel.write('$ ');
  const run = (line) => {
    const cmd = line.trim();
    if (!cmd) return;
    if (cmd.startsWith('echo ')) {
      channel.write(`${cmd.slice(5)}\r\n`);
    } else if (cmd === 'whoami') {
      channel.write(`${USER}\r\n`);
    } else if (cmd === 'pwd') {
      channel.write(`${HOME}\r\n`);
    } else if (cmd === 'exit') {
      channel.write('bye\r\n');
      channel.exit(0);
      channel.end();
      return;
    } else {
      channel.write(`${cmd.split(' ')[0]}: not found\r\n`);
    }
  };
  channel.on('data', (d) => {
    const s = d.toString('utf8');
    for (const ch of s) {
      if (ch === '\r' || ch === '\n') {
        channel.write('\r\n');
        run(buf);
        buf = '';
        prompt();
      } else if (ch === '\u007f' || ch === '\b') {
        if (buf.length) {
          buf = buf.slice(0, -1);
          channel.write('\b \b');
        }
      } else {
        buf += ch;
        channel.write(ch); // 回显
      }
    }
  });
  channel.on('close', () => channel.end());
  prompt();
}

// ---------------------------------------------------------------- 启动服务
fs.mkdirSync(path.join(ROOT, HOME), { recursive: true });

const hostKey = loadOrCreateHostKey();

const server = new Server({ hostKeys: [hostKey] }, (client) => {
  client.on('authentication', (ctx) => {
    if (ctx.method === 'password' && ctx.username === USER && ctx.password === PASS) return ctx.accept();
    return ctx.reject(['password']);
  });

  client.on('ready', () => {
    client.on('session', (accept) => {
      const session = accept();
      session.on('sftp', (acceptSftp) => attachSftp(acceptSftp()));
      session.on('shell', (acceptShell) => attachShell(acceptShell()));
      session.on('exec', (acceptExec) => {
        const ch = acceptExec();
        ch.exit(0);
        ch.end();
      });
    });
    // 客户端 keepalive 等全局请求：一律接受，避免被误判为连接异常
    client.on('request', (accept) => {
      try {
        if (accept) accept();
      } catch (e) {
        /* ignore */
      }
    });
  });

  client.on('error', () => {
    /* 测试夹具：静默，避免噪声 */
  });
});

server.on('error', (err) => {
  if (err && err.code === 'EADDRINUSE') {
    // 端口被占用（并发/复跑）：明确退出码，测试脚本会先探测并复用已运行实例
    console.error(`[fixture] port ${PORT} already in use`);
    process.exit(2);
  }
  console.error(`[fixture] server error: ${err && err.message}`);
  process.exit(1);
});

server.listen(PORT, '127.0.0.1', () => {
  const actual = server.address().port;
  process.stdout.write(`FIXTURE_READY ${JSON.stringify({ port: actual, home: HOME, root: ROOT, user: USER })}\n`);
});

// 端口被占用时给出明确提示（测试脚本会先探测并复用）
process.on('uncaughtException', (err) => {
  if (err && err.code === 'EADDRINUSE') {
    console.error(`[fixture] port ${PORT} already in use`);
    process.exit(2);
  }
  console.error(`[fixture] ${err && err.stack}`);
  process.exit(1);
});

const shutdown = () => {
  try {
    server.close();
  } catch (e) {
    /* ignore */
  }
  process.exit(0);
};
process.on('SIGTERM', shutdown);
process.on('SIGINT', shutdown);

/*
 * 启动「本 worktree 实例」的外部网关（fixture 建目录/上传用）。
 *
 * 端口由 electron/test/env.mjs 统一计算（主 clone=18090，linked worktree=18090+slot*100），
 * 与各测试套件读取的 GATEWAY_URL 完全一致 —— 多个 worktree 并行不会 EADDRINUSE。
 *
 * 用法：cd electron && npm run gateway
 * 覆盖：KR_GATEWAY_PORT=xxxx npm run gateway
 */
import { spawn } from 'node:child_process';
import path from 'node:path';
import fs from 'node:fs';
import { GATEWAY_PORT, INSTANCE, ELECTRON_DIR, LOCAL_ROOT, TEST_ROOT } from '../test/env.mjs';

const entry = path.join(ELECTRON_DIR, '..', 'sftp-gateway', 'server.js');
const dataDir = path.join(TEST_ROOT, `gateway-data-${INSTANCE}`);
fs.mkdirSync(dataDir, { recursive: true });
try { fs.mkdirSync(LOCAL_ROOT, { recursive: true }); } catch (e) { /* ignore */ }
console.log(`[gateway] instance=${INSTANCE} port=${GATEWAY_PORT} localRoot=${LOCAL_ROOT} dataDir=${dataDir}`);

// 与 Electron 主进程一致：本地路径根 = 本实例隔离目录（避免本地栏/网关对根的理解不一致）
const child = spawn(process.execPath, [entry], {
  stdio: 'inherit',
  env: {
    ...process.env,
    SFTP_GATEWAY_PORT: String(GATEWAY_PORT),
    SFTP_GATEWAY_LOCAL_ROOT: LOCAL_ROOT,
    SFTP_GATEWAY_DATA_DIR: dataDir,
  },
});

const stop = (sig) => { try { child.kill(sig || 'SIGTERM'); } catch (e) { /* ignore */ } };
child.on('exit', (code) => process.exit(code || 0));
process.on('SIGINT', () => { stop('SIGINT'); });
process.on('SIGTERM', () => { stop('SIGTERM'); });

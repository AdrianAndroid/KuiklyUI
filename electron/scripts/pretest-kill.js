/*
 * pretest 钩子：先杀掉上次残留的 Kuikly SFTP 客户端和带调试端口的 Electron 进程，
 * 避免多个客户端并行占满 CPU、互相干扰（看门狗先挂的就是这种情形）。
 * 不可用命令忽略：跨平台容错（pkill 在非 macOS 缺失也无所谓）。
 */
const { execFileSync } = require('node:child_process');

function run(cmd) {
  try { execFileSync(cmd, { stdio: 'ignore' }); } catch (e) { /* ignore */ }
}

// 1) 主动退出 Kuikly SFTP（macOS 桌面）
try { execFileSync('osascript', ['-e', 'quit app "Kuikly SFTP"'], { stdio: 'ignore' }); } catch (e) {}

// 2) 直接杀残留进程（含开发态 `electron .`、CI 的测试实例、调试端口的）
run('pkill -f "Kuikly SFTP.app" || true');
run('pkill -f "remote-debugging-port=" || true');
run('pkill -f "kuikly-sftp-desktop" || true');

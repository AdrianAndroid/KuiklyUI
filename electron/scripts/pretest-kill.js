/*
 * pretest 钩子：杀掉上次残留的 Kuikly SFTP / Electron 客户端进程，避免多实例占满 CPU、互相干扰。
 *
 * 注意：**只按 Electron 可执行文件路径匹配**，不要用 "remote-debugging-port=" / "kuikly-sftp-desktop"
 * 这类过宽的模式 —— 它们会误杀调用方 shell / 外部测试网关（曾导致 features 用例连不上 18090）。
 */
const { execFileSync } = require('node:child_process');

function run(cmd, args) {
  try { execFileSync(cmd, args, { stdio: 'ignore' }); } catch (e) { /* ignore（无匹配进程时 pkill 退出码非 0） */ }
}

// 1) 主动退出已安装的 Kuikly SFTP（macOS）
try { execFileSync('osascript', ['-e', 'quit app "Kuikly SFTP"'], { stdio: 'ignore' }); } catch (e) {}

// 2) 只杀 Electron 客户端进程（按可执行路径精确匹配）
run('pkill', ['-f', 'Kuikly SFTP.app/Contents/MacOS']);
run('pkill', ['-f', 'Electron.app/Contents/MacOS/Electron']);
run('pkill', ['-f', 'electron/dist/Electron.app/Contents/MacOS']);

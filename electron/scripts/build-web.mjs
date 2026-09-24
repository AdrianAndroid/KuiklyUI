/*
 * 构建 Web 产物（供 electron/resources 使用）
 *   KUIKLY_WEB_MODE=debug（默认）→ :demo:packLocalJsBundleDebug + :h5App:jsBrowserDevelopmentWebpack
 *   KUIKLY_WEB_MODE=release        → :demo:packLocalJSBundleRelease + :h5App:jsBrowserProductionWebpack
 *
 * 自动探测 JDK 17（Gradle 7.6.3 不兼容 JDK 25）。
 */
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';

const here = path.dirname(new URL(import.meta.url).pathname);
const root = path.resolve(here, '..', '..');

function detectJavaHome() {
  if (process.env.JAVA_HOME && fs.existsSync(path.join(process.env.JAVA_HOME, 'bin/java'))) return process.env.JAVA_HOME;
  const base = path.join(process.env.HOME, 'Library/Java/JavaVirtualMachines');
  if (fs.existsSync(base)) {
    for (const d of fs.readdirSync(base)) {
      if (d.startsWith('corretto-17')) {
        const p = path.join(base, d, 'Contents/Home');
        if (fs.existsSync(path.join(p, 'bin/java'))) return p;
      }
    }
  }
  try {
    const out = spawnSync('/usr/libexec/java_home', ['-v', '17'], { encoding: 'utf8' });
    if (out.status === 0 && out.stdout.trim()) return out.stdout.trim();
  } catch (e) { /* ignore */ }
  return '';
}

const javaHome = detectJavaHome();
if (!javaHome) {
  console.error('[build:web] 未找到 JDK 17，请设置 JAVA_HOME');
  process.exit(1);
}
console.log('[build:web] JAVA_HOME =', javaHome);

function gradle(args) {
  const r = spawnSync('./gradlew', args, { cwd: root, stdio: 'inherit', env: { ...process.env, JAVA_HOME: javaHome } });
  if (r.status !== 0) process.exit(r.status || 1);
}

const MODE = (process.env.KUIKLY_WEB_MODE || 'debug').toLowerCase() === 'release' ? 'release' : 'debug';
console.log('[build:web] mode =', MODE);
gradle([MODE === 'release' ? ':demo:packLocalJSBundleRelease' : ':demo:packLocalJsBundleDebug', '-Pkuikly.useLocalKsp=false', '--console=plain']);
gradle([MODE === 'release' ? ':h5App:jsBrowserProductionWebpack' : ':h5App:jsBrowserDevelopmentWebpack', '--console=plain']);
console.log('[build:web] done');

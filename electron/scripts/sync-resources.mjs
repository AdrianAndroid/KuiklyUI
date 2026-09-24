/*
 * 把 Gradle 的 Web 产物同步到 electron/resources
 * 产物来源（AGENTS.md §13.1.3）：
 *   demo/build/outputs/kuikly/js/debug/local/nativevue2.zip
 *   h5App/build/kotlin-webpack/js/developmentExecutable/h5App.js
 *   h5App/src/jsMain/resources/index.html
 *
 * 注意：index.html 用**相对路径**引用 nativevue2.js / h5App.js，故必须与它们同目录。
 */
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';

const here = path.dirname(new URL(import.meta.url).pathname);
const electronDir = path.resolve(here, '..');
const root = path.resolve(electronDir, '..');
const res = path.join(electronDir, 'resources');

// MODE=release 时取 release 产物（打包正式版用；debug 产物带调试开销）
const MODE = (process.env.KUIKLY_WEB_MODE || 'debug').toLowerCase() === 'release' ? 'release' : 'debug';
const zip = path.join(root, `demo/build/outputs/kuikly/js/${MODE}/local/nativevue2.zip`);
const h5js = path.join(root, `h5App/build/kotlin-webpack/js/${MODE === 'release' ? 'productionExecutable' : 'developmentExecutable'}/h5App.js`);
const html = path.join(root, 'h5App/src/jsMain/resources/index.html');

for (const [what, p] of [['demo bundle', zip], ['h5App.js', h5js], ['index.html', html]]) {
  if (!fs.existsSync(p)) {
    console.error(`[sync] 缺少 ${what}: ${p}`);
    console.error('[sync] 请先执行 npm run build:web');
    process.exit(1);
  }
}

fs.rmSync(res, { recursive: true, force: true });
fs.mkdirSync(res, { recursive: true });
execFileSync('unzip', ['-oq', zip, '-d', res], { stdio: 'inherit' });
fs.copyFileSync(h5js, path.join(res, 'h5App.js'));
fs.copyFileSync(html, path.join(res, 'index.html'));

console.log(`[sync] mode=${MODE} 已同步到 electron/resources：`);
for (const f of fs.readdirSync(res)) console.log('  -', f);

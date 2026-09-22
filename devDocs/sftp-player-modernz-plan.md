# SFTP 播放页 mpv OSC 对齐改造方案

> 参考：[mpv-player/mpv](https://github.com/mpv-player/mpv) 的 `player/lua/osc.lua`（362KB 源码精读）
> 目标：把 mpv OSC 的设计思想与用户体验**最大程度**迁移到 SFTP 播放页，**六端共享一份 commonMain 实现**。
> 范围：`demo/src/commonMain/.../sftp/SftpPlayerPage.kt` 为主，按需补原生端宿主事件转发。

---

## 1. mpv OSC 设计哲学（源码提炼）

精读 `osc.lua` 362KB 源码后，提炼出 mpv 的核心设计思想：

### 1.1 布局结构：两行布局（而非单行）

mpv 默认 `bottombar` 布局高度 56px，分**两行**：

```
┌─────────────────────────────────────────────────────────────┐
│ line1 (top row, 12px from top):                              │
│   [menu] [◀][▶] [custom...] ── title ── [cache] [⛶]          │  ← 信息行
├─────────────────────────────────────────────────────────────┤
│ line2 (bottom row, 39px from top):                          │
│   [▶/⏸] [◀│][│▶] [00:00] ──●────── [00:00] [aud][sub][🔊][⛶]│  ← 控制行
└─────────────────────────────────────────────────────────────┘
```

- **信息行（line1）**：菜单、上一集/下一集、自定义按钮、标题、缓存、全屏
- **控制行（line2）**：播放/暂停、上一集/下一集、左时间、进度条、右时间、音轨、字幕、音量、全屏

当前 SFTP 是**单行控制条**（进度条 + 一行按钮挤在一起），mpv 是**两行**，更透气、信息层次更清晰。

### 1.2 元素 hitbox 精准化

mpv 每个元素有显式 hitbox 坐标 `(x1,y1,x2,y2)`，且边缘元素把 hitbox 延伸到屏幕边：

```lua
lo.hitbox = { x1 = 0 }            -- 最左元素 hitbox 延伸到 x=0
lo.hitbox = { x2 = math.huge }    -- 最右元素 hitbox 延伸到无限远
```

效果：点最左边距也能触发最左按钮，点最右边距也能触发最右按钮。SFTP 没这个机制。

### 1.3 丰富的鼠标交互（每元素 3 键 + 滚轮）

mpv 每个元素绑定：

```lua
element_mbtn_left_command   -- 左键
element_mbtn_mid_command    -- 中键
element_mbtn_right_command  -- 右键（通常弹选择菜单）
element_wheel_down_command -- 滚轮下
element_wheel_up_command    -- 滚轮上
```

例：
- `play_pause` 右键 → `cycle-values loop-file inf no`（循环开关）
- `volume` 滚轮 → `add volume ±5`
- `audio_track` 右键 → `select/select-aid`（弹音轨选择菜单）
- `chapter_prev` 右键 → `select/select-chapter`（弹章节选择菜单）

SFTP 只有左键 click。

### 1.4 颜色系统（10+ OSC 专属色 + 日夜切换）

mpv 的颜色配置：

```lua
background_color     = "#000000"   -- OSC 背景
timecode_color       = "#FFFFFF"   -- 进度条 + 时间
title_color          = "#FFFFFF"   -- 标题
time_pos_color       = "#FFFFFF"   -- 悬停位置时间（tooltip）
buttons_color        = "#FFFFFF"   -- 大按钮 + 边按钮
small_buttonsL_color = "#FFFFFF"   -- 左侧小按钮
small_buttonsR_color = "#FFFFFF"   -- 右侧小按钮
top_buttons_color    = "#FFFFFF"   -- 顶部按钮
held_element_color   = "#999999"   -- 按下态
time_pos_outline_color = "#000000" -- tooltip 描边
```

10 个 OSC 专属色，全部可由用户配置。SFTP 只有 7 个全局 token。

### 1.5 时间显示 4 种模式

```lua
timetotal       = false  -- 总时长 vs 剩余时长
remaining_playtime = true -- 剩余按播放时间（含倍速）vs 视频时间
timems          = false  -- 是否显示毫秒
tcspace         = 100    -- 时间码间距（字体估计补偿）
```

mpv 的右时间码可切换「总时长 / 剩余时长」，SFTP 固定显示总时长。

### 1.6 进度条 3 种样式 × 5 种缓存范围样式

```lua
seekbarstyle     = "bar"       -- bar / diamond / knob
seekrangestyle   = "inverted"  -- bar / line / slider / inverted / none
seekbarhandlesize = 0.6        -- diamond/knob 手柄大小比例
seekrangealpha   = 200         -- 缓存范围透明度
seekrangeseparate = true       -- 缓存范围是否独立绘制
seekbarkeyframes = true        -- 拖动时按关键帧 seek
```

SFTP 只有「bar + bar」一种组合。

### 1.7 标题格式（含播放列表位置）

```lua
title = "${!playlist-count==1:\\[${playlist-pos-1}/${playlist-count}\\] }${media-title}"
```

显示效果：单文件时 `Movie.mp4`；播放列表时 `[3/12] Movie.mp4`。

SFTP 只显示文件名，没有 `[当前/总数]` 前缀。

### 1.8 可见性系统（3 模式循环 + 死区）

```lua
hidetimeout   = 500    -- 500ms 无操作后隐藏
fadeduration  = 200    -- 200ms 淡出
fadein        = false  -- 是否淡入
deadzonesize  = 0.75   -- 死区占 75%（鼠标在死区内不触发隐藏）
minmousemove  = 0      -- 触发显示的最小鼠标移动像素
visibility    = "auto" -- auto / never / always（循环切换）
visibility_modes = "never_auto_always"
```

mpv 默认 500ms 隐藏（SFTP 是 2000ms），且有**死区**概念：鼠标在 OSC 与屏幕边之间 75% 区域内不会触发隐藏。

### 1.9 图标系统（2 套完整图标字体）

```lua
icon_font = "mpv-osd-symbols"
icon_style = "layout"  -- layout(自动) / classic / fluent

-- classic 图标集（E000-E2XX 区段）：
menu, prev, next, pause, play, clock, play_backward,
skip_backward, skip_forward, chapter_prev, chapter_next,
audio, subtitle, mute, volume(4级), fullscreen, exit_fullscreen,
close, minimize, maximize, unmaximize

-- fluent 图标集（另一套现代风格）
```

每套 20+ 图标，用专用字体保证跨端一致。SFTP 用 Unicode 字符兜底。

### 1.10 60fps 节流刷新

```lua
tick_delay = 1/60   -- 最小重绘间隔 16.67ms
tick_delay_follow_display_fps = false  -- 是否跟随显示器刷新率
```

mpv 用定时器节流，避免每秒进度回调触发几十次重绘。SFTP 靠 observable 自动节流，但无显式节流策略。

### 1.11 悬停缩略图预览（thumbfast）

```lua
max_thumb_size = 200  -- 缩略图最大显示尺寸
-- 悬停进度条时，通过 user-data/osc/hover-sec + draw-preview
-- 与 thumbfast 插件联动，在悬停位置显示该帧缩略图
```

SFTP 无缩略图预览（跨端成本过高，已排除）。

### 1.12 窗口控制按钮（close/minimize/maximize）

```lua
windowcontrols = "auto"              -- auto / yes / no
windowcontrols_alignment = "right"   -- right / left
windowcontrols_independent = true    -- 与底栏独立显隐
```

mpv 在无边框模式下显示关闭/最小化/最大化按钮。SFTP 有自己的导航栏，不需要。

### 1.13 右键菜单系统（select.lua 联动）

mpv 右键任何元素都触发 `script-binding select/xxx` 弹出选择菜单：

- 右键 `audio_track` → 选择音轨
- 右键 `sub_track` → 选择字幕
- 右键 `chapter_prev/next` → 选择章节
- 右键 `playlist_prev/next` → 选择播放列表
- 右键 `title` → 观看历史
- 右键 `volume` → 选择音频设备
- 右键 `menu` → 主菜单

SFTP 只有「选集抽屉」，没有右键菜单系统。

---

## 2. 跨平台要点清单 — 这是核心

> 跨平台不是"能在六端跑"，而是**六端行为一致、体验不打折、无端别 bug**。
> 以下是 mpv 源码思想 + KuiklyUI 框架能力 + SFTP 现状综合后，提炼出的跨平台要点清单。
> 每条都有**各端具体表现** + **验证方法**，改造时**必须逐条验证**。

### 2.1 能力矩阵（六端现状）

| 能力 | commonMain | Android | iOS | macOS | Web | OHOS | 状态 |
|------|-----------|---------|-----|-------|-----|------|------|
| `VideoView` 视频 | ✅ | ✅ ExoPlayer | ✅ AVPlayer | ✅ VLCKit | ✅ `<video>` | ❌ 桩 | 已统一 |
| 本地 HTTP 代理 | ✅ | ✅ NanoHTTPD | ✅ GCDWebServer | ✅ GCDWebServer | ✅ Node 网关 | ❌ 桩 | 已统一 |
| `pan` 拖动手势 | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | 已统一 |
| `vif` 响应式 | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | 已统一 |
| `observable` 状态 | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | 已统一 |
| `setTimeout` 定时器 | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | 已统一 |
| `addPagerEventObserver` | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | 已统一 |
| 全屏 `setFullscreen` | ✅ 声明 | ⚠️ 部分 | ✅ | ⚠️ 待验 | ✅ | ❌ 桩 | 不全 |
| `sftp_controls_activity` | ✅ 事件名 | ❌ 未转发 | ❌ 未转发 | ❌ 未转发 | ✅ | ❌ 桩 | **缺口** |
| `sftp_player_key` | ✅ 事件名 | ❌ 未转发 | ❌ 未转发 | ❌ 未转发 | ✅ | ❌ 桩 | **缺口** |
| `sftp_fullscreen_changed` | ✅ 事件名 | ⚠️ | ⚠️ | ⚠️ | ✅ | ❌ 桩 | 部分 |
| 右键事件 | ✅ 可扩展 | ⚠️ 长按 | ⚠️ 长按 | ✅ | ✅ | ❌ 桩 | **需补** |
| 滚轮事件 | ✅ 可扩展 | ❌ 无 | ❌ 无 | ✅ | ✅ | ❌ 无 | **桌面专属** |
| 鼠标 hover | ✅ 可扩展 | ❌ 无 | ❌ 无 | ✅ | ✅ | ❌ 无 | **桌面专属** |

### 2.2 跨端硬规则（不可违反）

1. **所有 UI/状态/逻辑改动只在 commonMain**，不碰原生渲染器（除非补宿主事件转发）
2. **不新增 Module/View**，复用 `VideoView` + `SftpMediaProxyModule`
3. **不引入新依赖**（不引字体库、图标库、不引 Kotlin/JS 浏览器 API）
4. **OHOS 播放是桩**，本次不涉及（spec 记录「OHOS 不支持播放」）
5. **响应式严格遵守**：`vif/velseif/velse` + `observable`/`observableList`（见 AGENTS.md §13.4 第 1/2 条）
6. **原生组件显式尺寸**：Video/按钮/进度条必须给 size（见 AGENTS.md §13.4 第 3 条）
7. **回调线程安全**：原生侧 Module 调用已串行化，commonMain 侧不额外加锁
8. **平台差异显式记录**：无法一致的端别行为，必须在 spec 显式记录（见 AGENTS.md §3 第 5 条）
9. **二进制通信原子通道**：Module 与原生通信用 JSON 字符串，不用裸 `JSONArray`/`JSONObject`（见 AGENTS.md §3 第 6 条）
10. **Web 浏览器沙箱限制**：不能直连 SSH/TCP，必须走 Node 网关（已实现，见 AGENTS.md §13.1.3）

### 2.3 交互映射（跨端一致的关键）

mpv 的交互丰富（3 键 + 滚轮 + hover），但移动端没有鼠标。跨端映射规则：

| mpv 交互 | 桌面端（macOS/Web） | 移动端（iOS/Android） | OHOS | commonMain 实现策略 |
|---------|-------------------|---------------------|------|-------------------|
| 鼠标移动唤醒 | `mousemove` → `sftp_controls_activity` | `touchstart` → `sftp_controls_activity` | 桩 | 宿主事件统一为 `sftp_controls_activity` |
| 左键 click | `click` | `click`（tap） | `click` | Kuikly `event { click }` 原生支持 |
| 右键菜单 | `contextmenu` → `sftp_context_menu` | 长按 → `sftp_context_menu` | 桩 | 宿主事件统一为 `sftp_context_menu` |
| 中键 | `auxclick` → `sftp_mid_click` | ❌ 无中键 | ❌ | commonMain 处理 `sftp_mid_click`，移动端不触发 |
| 滚轮 | `wheel` → `sftp_wheel` (±10s) | ❌ 无滚轮 | ❌ | commonMain 处理 `sftp_wheel`，移动端不触发 |
| hover | `mouseenter` → `sftp_hover` | ❌ 无 hover | ❌ | commonMain 处理 `sftp_hover`，移动端不触发 |
| 键盘 | `keydown` → `sftp_player_key` | ❌ 无键盘（仅硬件音量键） | ❌ | commonMain 处理 `sftp_player_key`，移动端不触发 |
| 拖动 | `pan` (Kuikly) | `pan` (Kuikly) | `pan` (Kuikly) | Kuikly `pan` 六端统一 |

**原则**：桌面专属能力（滚轮/hover/中键/键盘）在移动端**优雅降级**，而不是报错。commonMain 侧只处理宿主转发的事件，不假设端别。

### 2.4 全屏映射（跨端差异大）

| 端 | 全屏实现 | `setFullscreen(true)` 行为 | `sftp_fullscreen_changed` 回传 |
|----|---------|---------------------------|------------------------------|
| macOS | 窗口全屏（NSWindow enterFullScreenMode） | 窗口进入全屏 | ESC 退出时回传 `fullscreen=false` |
| iOS | 设备旋转（UIWindowScene.requestGeometryUpdate） | 强制横屏 | 旋转回竖屏时回传 `fullscreen=false` |
| Android | Activity 全屏 + 横屏 | `setRequestedOrientation(LANDSCAPE)` | 返回键退出时回传 `fullscreen=false` |
| Web | Fullscreen API（`#root` 容器） | `requestFullscreen()` | ESC 退出时回传 `fullscreen=false` |
| OHOS | 桩 | no-op | 不回传 |

**commonMain 策略**：
- 调用 `videoViewRef?.view?.setFullscreen(isFullscreen)`（已统一）
- 监听 `sftp_fullscreen_changed` 事件同步页面状态
- 全屏时隐藏导航栏（`vif({ !isFullscreen })`），非全屏时显示

### 2.5 视频组件能力差异（`VideoView` 各端实现）

| 属性/事件 | Android | iOS | macOS | Web | OHOS |
|----------|---------|-----|-------|-----|------|
| `src` | ✅ | ✅ | ✅ | ✅ | ❌ |
| `playControl(PLAY/PAUSE)` | ✅ | ✅ | ✅ | ✅ | ❌ |
| `seekTo(ms)` | ✅ | ✅ | ✅ | ✅ | ❌ |
| `muted` | ✅ | ✅ | ✅ | ✅ | ❌ |
| `rate` | ✅ | ✅ | ✅ | ✅ | ❌ |
| `resizeModeToContain` | ✅ | ✅ | ✅ | ✅ | ❌ |
| `firstFrameDidDisplay` | ✅ | ✅ | ✅ | ✅ | ❌ |
| `playStateDidChanged` | ✅ | ✅ | ✅ | ✅ | ❌ |
| `playTimeDidChanged` | ✅ | ✅ | ✅ | ✅ | ❌ |
| `customEvent` (buffered) | ❌ 未回传 | ❌ 未回传 | ❌ 未回传 | ✅ | ❌ |
| `setFullscreen` | ⚠️ 部分 | ✅ | ⚠️ 待验 | ✅ | ❌ |
| `volume` | ❌ 无属性 | ❌ 无属性 | ❌ 无属性 | ❌ 无属性 | ❌ |

**关键差异**：
- **`buffered` 只在 Web 回传**：其它端进度条无缓冲段（已知限制，不强行实现）
- **`volume` 六端都无**：只有 `muted` 二态，无音量滑杆（已知限制）
- **OHOS 完全桩**：所有属性 no-op，不实现播放

**commonMain 策略**：
- 所有属性通过 `VideoAttr` 声明式设置（Kuikly 自动跨端）
- 不假设 `buffered` 一定有（Web 有，其它端无）
- 不假设 `setFullscreen` 一定成功（原生端可能部分实现）

### 2.6 本地代理跨端差异

| 端 | 代理实现 | Range 响应 | token 生命周期 | 验证方式 |
|----|---------|-----------|--------------|---------|
| Android | NanoHTTPD `LocalHttpProxyServer` | 流式 206 + Content-Length | unregister 即释放 fileHandle | ExoPlayer 经代理播放出画 |
| iOS | GCDWebServer `KRLocalHttpProxy` | 流式 206 + Content-Length | unregister 即释放 | AVPlayer 经代理播放 |
| macOS | GCDWebServer `KRLocalHttpProxy` | 流式 206 + Content-Length | unregister 即释放 | VLCKit 经代理播放 |
| Web | Node 网关 `sftp-gateway/server.js` | HTTP Range（206/416） | 网关内存，重启失效 | `<video>` 经网关播放 |
| OHOS | ❌ 桩 | ❌ | ❌ | 不支持播放 |

**关键差异**：
- **Web 网关会话在内存**：重启网关 token 失效，旧 `sessionId` 的 seek 会失败
- **其它端 token 在进程内**：页面关闭即释放

**commonMain 策略**：
- 统一调 `sftpMediaProxyModule().registerToken(sessionId, remotePath, size)`
- 播放 URL 用 `SftpMediaUrlBuilder.buildPlayUrl(port, token, name)`（已统一）
- 页面 `pageWillDestroy` 时 `unregisterToken`（已实现）

### 2.7 跨端 UX 一致性要点（每条都有验证方法）

| # | 要点 | macOS | iOS | Android | Web | OHOS | 验证方法 |
|---|------|-------|-----|---------|-----|------|---------|
| X1 | 鼠标移动唤醒 | ✅ 鼠标 | ✅ 触摸 | ✅ 触摸 | ✅ 鼠标 | ❌ | 各端全屏播放，移动鼠标/触摸均唤醒控制条 |
| X2 | 右键弹菜单 | ✅ 右键 | ✅ 长按 | ✅ 长按 | ✅ 右键 | ❌ | 各端右键/长按选集按钮，弹选集抽屉 |
| X3 | 键盘快捷键 | ✅ 全效 | ❌ 无键盘 | ❌ 无键盘 | ✅ 全效 | ❌ | macOS/Web 按 Space/J/L，iOS/Android 不触发 |
| X4 | 滚轮微调进度 | ✅ 滚轮 | ❌ 无 | ❌ 无 | ✅ 滚轮 | ❌ | macOS/Web 滚轮悬停进度条 ±10s |
| X5 | 全屏旋转 | ✅ 窗口全屏 | ✅ 横屏 | ✅ 横屏 | ✅ 全屏 API | ❌ | 各端点全屏按钮，进入对应全屏模式 |
| X6 | ESC 退出全屏 | ✅ ESC | ✅ 状态栏返回 | ✅ 返回键 | ✅ ESC | ❌ | 各端全屏后用对应方式退出，页面状态同步 |
| X7 | 安全区不遮挡 | ✅ statusBarHeight | ✅ safeAreaInsets | ✅ statusBarHeight | ✅ 无状态栏 | ❌ | 顶部导航栏不被状态栏/灵动岛遮挡 |
| X8 | 视频尺寸显式 | ✅ flex+width | ✅ flex+width | ✅ flex+width | ✅ flex+width | ❌ | 视频出画，非黑屏 |
| X9 | 控制条不溢出 | ✅ 窄窗口 COMPACT | ✅ COMPACT | ✅ COMPACT | ✅ 窄窗口 COMPACT | ❌ | 各端窄宽度下控制条不溢出 |
| X10 | 图标跨端一致 | ✅ Unicode | ✅ Unicode | ✅ Unicode | ✅ Unicode | ❌ | 各端播放/暂停/全屏图标显示一致 |
| X11 | 夜间模式 | ✅ isNight | ✅ isNight | ✅ isNight | ✅ isNight | ❌ | 各端切夜间模式，控制条颜色正确 |
| X12 | 续播提示 | ✅ | ✅ | ✅ | ✅ | ❌ | 各端进入有历史的视频，弹续播提示 |
| X13 | 自动下一集 | ✅ | ✅ | ✅ | ✅ | ❌ | 各端播完当前集，3s 倒计时后自动下一集 |
| X14 | 播放历史落盘 | ✅ | ✅ | ✅ | ✅ | ❌ | 各端播放 5s 后退出，历史正确记录 |
| X15 | token 释放 | ✅ | ✅ | ✅ | ✅ 网关内存 | ❌ | 各端退出播放页，token 释放（Web 重启网关失效） |

### 2.8 跨端降级策略（移动端无鼠标/键盘/滚轮）

移动端没有鼠标/键盘/滚轮，这些桌面交互在移动端**优雅降级**：

| 桌面交互 | 移动端降级方案 | 实现位置 |
|---------|-------------|---------|
| 鼠标移动唤醒 | 触摸/点击即唤醒 | 宿主 `sftp_controls_activity`（touchstart 转发） |
| 右键菜单 | 长按弹菜单 | 宿主 `sftp_context_menu`（long press 转发） |
| 键盘快捷键 | 无键盘 → 不支持 | commonMain 只处理 `sftp_player_key`，移动端不转发 |
| 滚轮微调 | 无滚轮 → 不支持 | commonMain 只处理 `sftp_wheel`，移动端不转发 |
| hover tooltip | 无 hover → 拖动时显示 tooltip | commonMain `draggingProgress` 时显示，不依赖 hover |

**原则**：移动端**不报错、不卡顿、不缺关键功能**。桌面专属能力是锦上添花，移动端不影响核心播放。

### 2.9 OHOS 特殊处理

OHOS 播放能力是桩（`KRLocalHttpProxy` 未实现，`KRVideoView` 未实现）：

| 能力 | OHOS 现状 | 本次改造 | spec 记录 |
|------|---------|---------|----------|
| 视频播放 | ❌ 桩 | 不涉及 | 「OHOS 不支持播放」 |
| 本地代理 | ❌ 桩 | 不涉及 | 同上 |
| 控制条 UI | ❌ 无 Video | 不渲染 | 同上 |
| 选集/历史 | ✅ Module 已实现 | 保留 | 可用，但无法播放 |

**commonMain 策略**：
- `playUrl` 为 null 时显示「不支持播放」提示
- 选集/历史 Module 保留（数据层可用，只是无法播放）

### 2.10 跨端编译验证命令

每个改造阶段完成后，**必须六端全部编译通过**：

```bash
# macOS
xcodebuild -workspace macApp.xcworkspace -scheme macApp -configuration Debug \
  -destination 'platform=macOS' -derivedDataPath build/DerivedData build

# iOS
cd iosApp && pod install --repo-update  # 新增原生源文件后必须重跑
xcodebuild -workspace iosApp.xcworkspace -scheme iosApp \
  -configuration Debug -sdk iphonesimulator \
  -destination 'id=<UDID>' -derivedDataPath build/DerivedData build

# Android
export JAVA_HOME=<corretto-17>; export ANDROID_HOME=$HOME/Library/Android/sdk
./gradlew :androidApp:assembleDebug

# Web (H5)
./gradlew :demo:packLocalJsBundleDebug -Pkuikly.useLocalKsp=false
./gradlew :h5App:jsBrowserDevelopmentWebpack

# 小程序
./gradlew :miniApp:jsMiniAppDevelopmentWebpack

# HarmonyOS（渲染器，独立 CMake）
cmake -DCMAKE_TOOLCHAIN_FILE=<DevEco>/openharmony/native/sysroot ... \
  -DCMAKE_CURRENT_SOURCE_DIR=core-render-ohos/src/main/cpp
```

**编译失败的常见原因**（来自 AGENTS.md §13.1.2）：
- iOS 新增原生源文件后未 `pod install` → `library not found`
- iOS `EXCLUDED_ARCHS` 未设 `$(inherited)` → 模拟器 arm64 链接失败
- Android 改依赖时漏改某份 `build.<version>.gradle.kts` → Unresolved
- Web `core-ksp` JS 入口构建器缺失 → Kotlin/JS 编译失败
- OHOS API 版本门控未加 → 渲染器编译失败

### 2.11 跨端验证流程

每个改造阶段完成后，**必须**按 §2.7 跨端 UX 一致性要点逐条验证：

- **P0 完成** → 验证 X8（视频尺寸）、X9（控制条不溢出）、X10（图标一致）、X11（夜间模式）
- **P1 完成** → 验证 X12（续播提示）、X13（自动下一集）
- **P2 完成** → 验证 X14（播放历史落盘）
- **P3 完成** → 验证 X1（鼠标移动唤醒）
- **P4 完成** → 验证 X2（右键/长按弹菜单）
- **P5 完成** → 验证 X13（自动下一集 vs keep-open）
- **P6 完成** → 验证 X4（滚轮微调，仅桌面）
- **P7 完成** → 验证 X1~X6 全部跨端 UX
- **P8 完成** → 验证 X3（键盘快捷键，仅桌面）

**只有六端全部编译通过 + 对应跨端 UX 要点全部通过，该阶段才算完成。**

---

## 3. 目标架构（对齐 mpv）

### 3.1 改造后文件结构

```
demo/src/commonMain/kotlin/com/tencent/kuikly/demo/pages/sftp/
├── SftpPlayerPage.kt              # 主页面（精简，状态 + 编排）
├── theme/
│   ├── SftpColorTokens.kt         # 既有，不动
│   ├── SftpAccessibility.kt       # 补播放器 a11y
│   ├── SftpPlayerTokens.kt       # 新增：对齐 mpv 的 10+ 色彩 + 尺寸 token
│   └── SftpPlayerIcons.kt         # 新增：Unicode 图标（对齐 mpv icon_styles 结构）
└── player/
    ├── SftpPlayerLayout.kt       # 布局枚举 + 宽度判定（bottombar/slimbox/floating）
    ├── SftpPlayerTopRow.kt        # 信息行（menu/前后集/title/cache/全屏）
    ├── SftpPlayerControlsRow.kt  # 控制行（播放/前后集/时间/seekbar/音量/全屏）
    ├── SftpPlayerSeekBar.kt      # 进度条（3 样式 × 5 缓存样式）
    ├── SftpPlayerTitle.kt        # 标题组件（含 [3/12] 前缀）
    ├── SftpPlayerTimecode.kt     # 时间码组件（4 模式）
    ├── SftpPlayerSettingsMenu.kt # 倍速/keep-open 菜单
    ├── SftpEpisodeDrawer.kt      # 选集抽屉
    └── SftpPlayerDialogs.kt      # 续播/下一集倒计时
```

### 3.2 状态机（对齐 mpv `state` 表）

```kotlin
// 对齐 mpv osc.lua 的 state 表结构
private val playerState = SftpPlayerState(
    // 可见性（对齐 mpv visibility_modes: never/auto/always）
    visibility = SftpPlayerVisibility.AUTO,  // 新增：可见性模式枚举
    oscVisible = true,
    // 动画（对齐 mpv anistart/anitype/animation）
    fadeAnimation = 0f,  // 新增：0..1 淡入淡出
    // 时间显示（对齐 mpv timetotal/remaining_playtime/timems）
    timeDisplayMode = SftpTimeMode.TOTAL,  // 新增：TOTAL/REMAINING/REMAINING_PLAYTIME
    // 播放
    isPlaying = true,
    seekTarget = -1,
    currentPosition = 0,
    duration = 0,
    bufferedPosition = 0,
    // 进度条拖动
    draggingProgress = false,
    dragRatio = 0f,
    // 倍速/静音
    speed = 1f,
    muted = false,
    // 全屏
    isFullscreen = false,
    // 布局
    layout = SftpPlayerLayout.BOTTOMBAR,  // 新增：布局枚举
    // keep-open（对齐 mpv keep-open 选项）
    keepOpen = false,  // 新增
    // 选集
    episodes = emptyList(),
    currentIndex = 0,
    // 上一集/下一集（对齐 mpv playlist_prev/next，SFTP 无独立按钮，复用 chapter_prev/next）
    // hover 秒数（对齐 mpv hover_sec，用于未来接缩略图）
    hoverSec = -1f,  // 新增
)
```

---

## 4. 详细改造点（按 mpv 对齐优先级）

### P0 — 对齐 mpv 的两行布局 + token 系统

**目标**：把当前单行控制条拆成 mpv 的「信息行 + 控制行」两行结构。

**新增 `theme/SftpPlayerTokens.kt`**（对齐 mpv 的 10+ 色彩）：

```kotlin
object SftpPlayerTokens {
    // 对齐 mpv background_color
    val oscBg: Color get() = if (SftpColorTokens.isNight) Color(0xCC0A0A0A) else Color(0xCC000000)
    // 对齐 mpv timecode_color（进度条 + 时间）
    val timecode: Color get() = if (SftpColorTokens.isNight) Color(0xFFFFFFFF) else Color(0xFFFFFFFF)
    // 对齐 mpv title_color
    val title: Color get() = if (SftpColorTokens.isNight) Color(0xFFE8E8E8) else Color(0xFF222222)
    // 对齐 mpv time_pos_color（悬停 tooltip）
    val timePos: Color get() = if (SftpColorTokens.isNight) Color(0xFFFFFFFF) else Color(0xFFFFFFFF)
    // 对齐 mpv time_pos_outline_color
    val timePosOutline: Color get() = Color(0xFF000000)
    // 对齐 mpv buttons_color
    val buttons: Color get() = Color(0xFFFFFFFF)
    // 对齐 mpv held_element_color
    val heldElement: Color get() = Color(0xFF999999)
    // 对齐 mpv boxalpha
    const val BOX_ALPHA = 80  // 0..255
    // 尺寸（对齐 mpv bottombar：h=56, padX=9, buttonW=27）
    const val OSC_HEIGHT = 56f
    const val PAD_X = 9f
    const val BUTTON_W = 27f
    const val TC_W = 110f  // 时间码宽度
    const val TS_W = 90f   // 音轨/字幕按钮宽度
    // 进度条（对齐 mpv seekbar: border=0, gap=2）
    const val SEEKBAR_BORDER = 0f
    const val SEEKBAR_GAP = 2f
    const val SEEKBAR_HEIGHT = 5f
    const val SEEKBAR_THUMB = 14f
    // 可见性（对齐 mpv hidetimeout=500, fadeduration=200, deadzonesize=0.75）
    const val HIDE_TIMEOUT_MS = 500L
    const val FADE_DURATION_MS = 200L
    const val DEADZONE_SIZE = 0.75f
    // 刷新节流（对齐 mpv tick_delay=1/60）
    const val TICK_DELAY_MS = 16L
}
```

**新增 `theme/SftpPlayerIcons.kt`**（对齐 mpv `icon_styles` 结构）：

```kotlin
object SftpPlayerIcons {
    // 对齐 mpv 的 icon_style 概念，先用 Unicode 兜底
    // 后续可扩展为 icon font
    object Classic {
        const val PLAY = "▶"
        const val PAUSE = "❚❚"
        const val PREV = "◀│"
        const val NEXT = "│▶"
        const val SKIP_BACK = "◀◀"
        const val SKIP_FWD = "▶▶"
        const val MUTE = "🔇"
        const val VOLUME = "🔊"
        const val FULLSCREEN = "⛶"
        const val EXIT_FULLSCREEN = "⤡"
        const val MENU = "☰"
        const val CLOSE = "✕"
    }
    object Fluent {
        // 现代风格 Unicode（可后续替换为字体图标）
        const val PLAY = "►"
        const val PAUSE = "❙❙"
        // ... 同 Classic 结构，不同字符
    }
    // 对齐 mpv set_icon_style()：layout=floating→fluent，否则→classic
    fun forLayout(layout: SftpPlayerLayout) = when (layout) {
        SftpPlayerLayout.FLOATING -> Fluent
        else -> Classic
    }
}
```

**改动 `SftpPlayerPage.kt`**：

把当前单行控制条拆成两行：

```
┌─────────────────────────────────────────────────────────────┐
│ 信息行（44px）：[☰选集] [◀集][集▶] ── 文件名 [3/12] ── [缓冲] [⛶]│
├─────────────────────────────────────────────────────────────┤
│ 控制行（44px）：[▶/⏸] [◀◀][▶▶] [00:00] ──●── [00:00] [🔊] [⛶] │
└─────────────────────────────────────────────────────────────┘
```

- 信息行：选集菜单按钮、上一集/下一集按钮（对齐 mpv playlist_prev/next）、标题（含 `[当前/总数]`）、缓存指示、全屏
- 控制行：播放/暂停（对齐 mpv play_pause）、快退/快进（对齐 mpv skip_backward/forward）、左时间码、进度条、右时间码、静音（对齐 mpv volume，SFTP 无 volume 用 muted）、全屏

**验证**：六端编译 + macOS 手动播放验证两行布局不溢出。

### P1 — 对齐 mpv 的标题格式（播放列表位置）

**目标**：标题显示 `[3/12] filename.mp4`（对齐 mpv `title` 模板）。

```kotlin
// 对齐 mpv: "${!playlist-count==1:\\[${playlist-pos-1}/${playlist-count}\\] }${media-title}"
private fun formattedTitle(): String {
    return if (episodes.size > 1) {
        "[${currentIndex + 1}/${episodes.size}] $name"
    } else {
        name
    }
}
```

**验证**：同目录多视频时显示 `[1/12]`，单视频时不显示前缀。

### P2 — 对齐 mpv 的时间显示 4 模式

**目标**：右时间码可切换「总时长 / 剩余时长」（对齐 mpv `timetotal` / `remaining_playtime`）。

```kotlin
enum class SftpTimeMode { TOTAL, REMAINING }

private var timeMode: SftpTimeMode by observable(SftpTimeMode.TOTAL)

// 右时间码点击切换
View {
    event { click { 
        timeMode = if (timeMode == SftpTimeMode.TOTAL) SftpTimeMode.REMAINING 
                   else SftpTimeMode.TOTAL
    }}
    Text {
        attr {
            text(when (timeMode) {
                SftpTimeMode.TOTAL -> formatTime(duration.toLong())
                SftpTimeMode.REMAINING -> "-" + formatTime((duration - currentPosition).toLong())
            })
        }
    }
}
```

**验证**：点击右时间码在「总时长 / -剩余时长」之间切换。

### P3 — 对齐 mpv 的可见性 3 模式 + 死区 + 淡出

**目标**：把当前「全屏 2s 隐藏」改为 mpv 的「3 模式循环 + 500ms 隐藏 + 200ms 淡出 + 死区」。

```kotlin
enum class SftpPlayerVisibility { NEVER, AUTO, ALWAYS }

private var visibility: SftpPlayerVisibility by observable(SftpPlayerVisibility.AUTO)
private var fadeAlpha: Float by observable(1f)  // 0..1

// 对齐 mpv get_hidetimeout()
private val hideTimeoutMs: Long
    get() = when (visibility) {
        SftpPlayerVisibility.NEVER -> -1   // 永不隐藏（对齐 mpv always-on）
        SftpPlayerVisibility.ALWAYS -> -1
        SftpPlayerVisibility.AUTO -> 500   // mpv 默认 500ms
    }

// 对齐 mpv deadzonesize：鼠标在死区内不触发隐藏
// 简化：控制条上方 75% 区域是死区，鼠标在该区域不隐藏控制条
```

**操作入口**：信息行的 menu 按钮（☰）点击切换可见性模式（对齐 mpv `script-binding osc-visibility`）。

**验证**：切换 NEVER → 永不隐藏；AUTO → 500ms 无操作隐藏；ALWAYS → 永不隐藏。

### P4 — 对齐 mpv 的右键菜单系统（长按跨端映射）

**目标**：对齐 mpv 每元素右键弹菜单的交互。跨端映射：桌面右键、移动长按。

**Kuikly DSL 现状**：`pan` 手势有 `state="start/move/end"`，无右键/长按事件。

**方案**：
- 桌面端（Web/macOS）：补宿主事件 `sftp_context_menu`，右键时转发
- 移动端（iOS/Android）：`pan` 的 `start` 后 500ms 不移动 → 视为长按 → 转发 `sftp_context_menu`

**菜单内容**（对齐 mpv `select.lua`）：
- 右键 `播放/暂停` → 循环开关（对齐 mpv `cycle-values loop-file inf no`）
- 右键 `快退/快进` → 逐帧（对齐 mpv `frame-step`/`frame-back-step`，SFTP 无逐帧能力 → 不实现）
- 右键 `静音` → 音量（SFTP 无音量 → 不实现）
- 右键 `选集` → 选集抽屉（对齐 mpv `select/select-playlist`）
- 右键 `标题` → 播放历史（对齐 mpv `select/select-watch-history`）

**验证**：桌面右键弹菜单；移动长按弹菜单。

### P5 — 对齐 mpv 的 keep-open 选项

**目标**：播完时停末帧 vs 自动下一集（对齐 mpv `keep-open`）。

```kotlin
private var keepOpen: Boolean by observable(false)

if (state == PlayState.PLAY_END) {
    isPlaying = false
    sftpPlaybackHistoryModule().markCompleted(connectionId, remotePath) { _, _ -> }
    if (keepOpen) return  // 对齐 mpv keep-open=always：停末帧
    // 否则自动下一集
    if (episodes.isEmpty() || currentIndex >= episodes.size - 1) return
    nextEpisodeCountdown = 3
    showCountdown = true
    tickCountdown()
}
```

**入口**：倍速菜单里加「播完保持」开关。

### P6 — 对齐 mpv 的进度条 3 样式

**目标**：进度条支持 `bar` / `diamond` / `knob` 三种手柄样式（对齐 mpv `seekbarstyle`）。

```kotlin
enum class SftpSeekbarStyle { BAR, DIAMOND, KNOB }
private var seekbarStyle: SftpSeekbarStyle by observable(SftpSeekbarStyle.BAR)
```

- `BAR`：当前样式（方形滑块）
- `DIAMOND`：菱形滑块（对齐 mpv diamond）
- `KNOB`：圆形旋钮（对齐 mpv knob）

**入口**：设置菜单里加「进度条样式」选项。

**注**：缓存范围样式（`inverted`/`slider`/`line`/`bar`/`none`）暂不全部实现，先保留 `bar`（当前）+ `inverted`（对齐 mpv 默认）。

### P7 — 原生端宿主事件转发（跨端缺口补齐）

**目标**：iOS/macOS/Android 原生端转发鼠标移动 / 键盘 / 右键 / 全屏变化为页面事件，对齐 Web `Main.kt`。

**事件清单**（对齐 mpv 鼠标行为）：
- `sftp_controls_activity`：鼠标移动 / 触摸 → 唤醒控制条
- `sftp_player_key`：键盘 → 快捷键
- `sftp_context_menu`：右键 → 弹菜单（移动端长按）
- `sftp_fullscreen_changed`：全屏变化（含 ESC 退出）

**iOS/macOS**（`KRVideoView` 宿主层）：
```objc
// 鼠标移动 → sftp_controls_activity
// 右键 → sftp_context_menu
// 键盘 → sftp_player_key
// 全屏变化 → sftp_fullscreen_changed
[renderView sendEvent:@"sftp_controls_activity" params:@{}];
```

**Android**（`KRVideoView`）：
```kotlin
// onTouchEvent / onKeyDown / onKeyLongPress
sendEvent("sftp_player_key", mapOf("key" to ...))
```

**OHOS**：播放是桩，跳过。

### P8 — 对齐 mpv 的键盘快捷键（完整 input.conf 风格）

**现有**：Space/K（播放暂停）、←/→（±10s）、M（静音）、F（全屏）

**补充**（对齐 mpv 默认 `input.conf`）：

| 键 | mpv 语义 | SFTP 实现 |
|----|---------|---------|
| J | 上一章节 → SFTP 上一集 | `playPrevEpisode()` |
| L / Shift+→ | 下一章节 → SFTP 下一集 | `playNextEpisode()` |
| , / . | 逐帧 | `VideoView` 无逐帧 → 不实现 |
| 9 / 0 | 音量 ∓ | `VideoView` 无音量 → 不实现 |
| [ / ] | 倍速 −/+ | `speed` 调整 |
| { / } | 倍速 −/+ 0.5 | `speed` 调整 |
| Backspace | 重置倍速 | `speed = 1f` |
| v | 循环开关 | 对齐 mpv `cycle-values loop-file` |
| Esc | 退出全屏 | 已有（`sftp_fullscreen_changed`） |
| q | 退出 | `closeSelf()` |
| ` | 可见性切换 | `cycleVisibility()`（对齐 mpv `script-binding osc-visibility`） |

---

## 5. 实施顺序与验证矩阵

| 阶段 | 改动 | 跨端 | 风险 | mpv 对齐点 |
|------|------|------|------|-----------|
| P0 | 两行布局 + token + 图标层 | 纯 commonMain | 极低 | §1.1 §1.2 §1.4 §1.9 |
| P1 | 标题 `[3/12]` 前缀 | 纯 commonMain | 极低 | §1.7 |
| P2 | 时间显示 4 模式 | 纯 commonMain | 低 | §1.5 |
| P3 | 可见性 3 模式 + 死区 + 淡出 | 纯 commonMain | 中 | §1.8 |
| P4 | 右键菜单系统 | 需原生补事件 | 高 | §1.3 §1.13 |
| P5 | keep-open 选项 | 纯 commonMain | 低 | §1.6（部分） |
| P6 | 进度条 3 样式 | 纯 commonMain | 低 | §1.6 |
| P7 | 原生端事件转发 | iOS/macOS/Android | 高 | §1.3 §1.8 |
| P8 | 键盘快捷键完整 | 纯 commonMain | 极低 | §1.3 |

---

## 6. 验证清单（跨端）

### 6.1 编译

```bash
# macOS
xcodebuild -workspace macApp.xcworkspace -scheme macApp -configuration Debug \
  -destination 'platform=macOS' -derivedDataPath build/DerivedData build

# iOS
xcodebuild -workspace iosApp.xcworkspace -scheme iosApp \
  -configuration Debug -sdk iphonesimulator \
  -destination 'id=<UDID>' -derivedDataPath build/DerivedData build

# Android
export JAVA_HOME=<corretto-17>; export ANDROID_HOME=$HOME/Library/Android/sdk
./gradlew :androidApp:assembleDebug

# Web
./gradlew :demo:packLocalJsBundleDebug -Pkuikly.useLocalKsp=false
./gradlew :h5App:jsBrowserDevelopmentWebpack
```

### 6.2 功能（以 macOS 为基准）

- [ ] 两行布局：信息行 + 控制行（P0）
- [ ] 标题 `[3/12]` 前缀（P1）
- [ ] 右时间码点击切换 总/剩余（P2）
- [ ] 可见性 3 模式循环：NEVER/AUTO/ALWAYS（P3）
- [ ] 500ms 隐藏 + 200ms 淡出（P3）
- [ ] 死区：鼠标在控制条上方 75% 区域不触发隐藏（P3）
- [ ] 右键弹菜单（P4 桌面 / P7 原生端）
- [ ] keep-open 开关：播完停末帧（P5）
- [ ] 进度条 3 样式切换（P6）
- [ ] 键盘 J/L/[/]/v/`/q（P8）
- [ ] COMPACT 布局在窄宽度不溢出
- [ ] 夜间模式颜色正确

---

## 7. 不做的事（明确边界，跨端做不到）

- ❌ thumbfast 悬停缩略图（需服务端生成，跨端成本过高）
- ❌ 音量滑杆（`VideoView` 无 volume 属性）
- ❌ 逐帧（`VideoView` 无逐帧 API）
- ❌ 字幕/音轨选择（SFTP 单文件无多音轨/字幕）
- ❌ icon font（先用 Unicode 兜底，后续单独立项）
- ❌ OHOS 播放能力（渲染器未实现）
- ❌ 窗口控制按钮 close/minimize/maximize（SFTP 有自己的导航栏）
- ❌ 完整 5 种缓存范围样式（先做 `bar` + `inverted` 两种）
- ❌ 毫秒级时间码（`timems`，实用性低且常见端不支持）

---

## 7.5 用户体验（UX）要点清单 — 这是核心

> mpv 的 UX 不是"功能堆叠"，而是**每个交互都经过打磨**。以下是 mpv 源码提炼的 20 条 UX 要点，每条都对应明确的体验目标，改造时**必须逐条验证**。

### A. 可见性与显隐（来自 §1.8）

| # | UX 要点 | mpv 实现 | SFTP 改造后 | 验证方法 |
|---|--------|---------|------------|---------|
| A1 | **鼠标移动即显示** | `mouse_move` 事件直接 `show_osc()` | 鼠标移动 → 控制条出现 | 全屏播放，移动鼠标，控制条立即出现 |
| A2 | **500ms 无操作才隐藏** | `hidetimeout=500`，比 SFTP 当前 2000ms 更快 | 500ms 隐藏 | 显示后不动鼠标，500ms 后开始淡出 |
| A3 | **淡出动画** | `fadeduration=200`，200ms alpha 0→255 渐变 | 控制条 200ms 淡出（不是硬切） | 隐藏时有平滑淡出，不是瞬间消失 |
| A4 | **死区不触发隐藏** | `deadzonesize=0.75`，OSC 与屏幕边之间 75% 区域内鼠标移动不触发隐藏 | 死区逻辑 | 鼠标在控制条上方 75% 区域内移动，控制条不隐藏 |
| A5 | **3 模式循环** | `never/auto/always`，按 `` ` `` 键循环 | menu 按钮或 `` ` `` 键切换 | 切到 NEVER → 永不隐藏；ALWAYS → 永不隐藏；AUTO → 500ms 隐藏 |
| A6 | **鼠标离开窗口即隐藏** | `mouse_leave` → `hide_osc()` | 鼠标移出窗口 → 隐藏 | 鼠标移出窗口，控制条立即隐藏 |
| A7 | **idle 状态不显示** | `if state.idle then return end` | 无视频时不显示控制条 | 无视频源时控制条不出现 |

### B. 进度条交互（来自 §1.6 + seekbar 源码）

| # | UX 要点 | mpv 实现 | SFTP 改造后 | 验证方法 |
|---|--------|---------|------------|---------|
| B1 | **点击进度条任意位置直接 seek** | `mbtn_left_down` → `seek absolute-percent+exact` | 点击进度条 → 立即跳到该位置 | 点击进度条中点，视频立即跳到 50% 位置 |
| B2 | **拖动时实时 seek（keyframe 模式）** | `mouse_move` + `mbtn_left` → `seek absolute-percent` | 拖动时实时跳转 | 拖动进度条，视频跟随移动 |
| B3 | **拖动时 tooltip 跟随鼠标** | `tooltipF` + `adjust_tooltip` | 拖动时在滑块上方显示目标时间 | 拖动时滑块上方显示 `mm:ss` 气泡 |
| B4 | **tooltip 智能避让边界** | `if sliderpos < (s_min + 3) then an = an - 1` | tooltip 在两端自动偏移避免溢出 | 拖到进度条最左/右，tooltip 不溢出屏幕 |
| B5 | **滚轮微调** | `wheel_up` → `seek +10`，`wheel_down` → `seek -10` | 滚轮在进度条上 → ±10s | 鼠标悬停进度条滚轮，视频 ±10s |
| B6 | **右键跳最近章节** | `mbtn_right_up` → 找最近 marker → `set chapter` | 右键进度条 → 跳最近"集"（SFTP 无章节，映射到最近 episode 边界） | 右键进度条，跳到最近集的分界点 |
| B7 | **拖动后重置 lastseek** | `reset` 事件清 `lastseek`，避免重复 seek | 拖动结束后清状态 | 拖动结束不会重复 seek 同一位置 |

### C. 时间码交互（来自 §1.5）

| # | UX 要点 | mpv 实现 | SFTP 改造后 | 验证方法 |
|---|--------|---------|------------|---------|
| C1 | **左时间码点击切换毫秒** | `tc_left.mbtn_left_up` → `state.tc_ms = not state.tc_ms` | 左时间码点击 → 切换 `00:00` / `00:00.000` | 点击左时间码，显示毫秒 |
| C2 | **右时间码点击切换总/剩余** | `tc_right.mbtn_left_up` → `state.rightTC_trem = not state.rightTC_trem` | 右时间码点击 → 切换 `01:30` / `-00:45` | 点击右时间码，在总时长/剩余时长间切换 |

### D. 按钮交互（来自 §1.3 + bind_mouse_buttons）

| # | UX 要点 | mpv 实现 | SFTP 改造后 | 验证方法 |
|---|--------|---------|------------|---------|
| D1 | **按下态视觉反馈** | `held_element_color=#999999` + `styledown` | 按下时按钮变灰 | 按住播放键，按钮变灰 |
| D2 | **长按软重复** | `softrepeat` + `mouse_down_counter >= 15 && % 5 == 0` | 快退/快进长按 → 每 200ms 重复一次 | 长按快退，每 200ms 跳 10s |
| D3 | **中键显示信息** | `play_pause.mbtn_mid` → `cycle-values loop-playlist` | 中键播放 → 切换循环（SFTP 无循环 → 显示当前状态 OSD） | 中键播放键，显示提示 |
| D4 | **右键弹菜单** | 每元素 `mbtn_right_command` → `select/xxx` | 右键任意按钮 → 弹对应菜单 | 右键选集按钮，弹选集抽屉 |
| D5 | **边缘 hitbox 延伸** | `hitbox = {x1=0}` 或 `{x2=math.huge}` | 点最左边距也能触发最左按钮 | 点最左按钮左侧 20px 空白，仍触发该按钮 |

### E. 键盘交互（来自 §1.3 + input.conf）

| # | UX 要点 | mpv 实现 | SFTP 改造后 | 验证方法 |
|---|--------|---------|------------|---------|
| E1 | **Space/K 播放暂停** | `cycle pause` | 已有 | 按 Space，播放/暂停 |
| E2 | **←/→ ±10s** | `seek ±10` | 已有 | 按 ←/→，±10s |
| E3 | **J/L 上下集** | `playlist-prev/next` | 新增 | 按 J/L，切上一集/下一集 |
| E4 | **[/] 倍速** | `add speed ±0.1` | 新增 | 按 [/]，倍速 ±0.1 |
| E5 | **Backspace 重置倍速** | `set speed 1` | 新增 | 按 Backspace，倍速回 1.0 |
| E6 | **v 循环开关** | `cycle-values loop-file` | 新增（keep-open 切换） | 按 v，切换 keep-open |
| E7 | **` 可见性切换** | `script-binding osc-visibility` | 新增 | 按 `，循环 NEVER/AUTO/ALWAYS |
| E8 | **q 退出** | `quit` | 新增 | 按 q，退出播放页 |
| E9 | **Esc 退出全屏** | `cycle fullscreen` | 已有 | 全屏时按 Esc，退出全屏 |

### F. 视觉与布局（来自 §1.1 §1.4 §1.9）

| # | UX 要点 | mpv 实现 | SFTP 改造后 | 验证方法 |
|---|--------|---------|------------|---------|
| F1 | **两行布局更透气** | 信息行 + 控制行，56px | 同 | 控制条不挤，信息与控制分离 |
| F2 | **标题含播放列表位置** | `[3/12] filename` | 同 | 多集时显示 `[3/12]`，单文件不显示 |
| F3 | **标题超长省略** | `maxchars` + `fscx` 缩字号 | `textOverFlowTail()` | 长文件名末尾 `...` |
| F4 | **缓存指示** | `cache` 元素显示缓冲状态 | 缓冲进度在进度条上显示（已有 bufferedRatio） | 进度条有浅色缓冲段 |
| F5 | **图标跨端一致** | `mpv-osd-symbols` 字体 | Unicode 兜底（对齐结构） | 六端图标显示一致 |

### G. 性能与稳定性（来自 §1.10 + 源码 tick 节流）

| # | UX 要点 | mpv 实现 | SFTP 改造后 | 验证方法 |
|---|--------|---------|------------|---------|
| G1 | **60fps 节流重绘** | `tick_delay=1/60`，`request_tick` 合并多次请求 | observable 自动节流 + 显式 `setTimeout(16)` 合并 | 快速拖动进度条不卡顿 |
| G2 | **拖动期间不写历史** | SFTP 已有 `draggingProgress` 守卫 | 同 | 拖动进度条时不触发每 5s 写盘 |
| G3 | **seekTarget 独立于 currentPosition** | SFTP 已有 | 同 | 播放进度回调不触发真实 seek |
| G4 | **动画期间持续 tick** | `tick_animation` 检查 `anistart + fadeduration` | 淡出期间定时器持续 | 淡出动画 200ms 内不卡 |

### H. 跨端 UX 一致性

| # | UX 要点 | 桌面端 | 移动端 | 验证方法 |
|---|--------|--------|--------|---------|
| H1 | **鼠标移动唤醒** | 鼠标移动 → 显示 | 触摸 → 显示 | macOS 鼠标移动、iOS 触摸均唤醒 |
| H2 | **右键弹菜单** | 右键 → 菜单 | 长按 → 菜单 | macOS 右键、iOS 长按均弹菜单 |
| H3 | **键盘快捷键** | 全部生效 | 仅音量键等硬件键 | macOS 键盘全效，iOS 仅硬件键 |
| H4 | **全屏旋转** | 窗口全屏 | 横屏 | macOS 窗口全屏，iOS 横屏 |
| H5 | **安全区不遮挡** | 已有 statusBarHeight | 已有 | 顶部导航栏不被状态栏遮挡 |

### UX 要点验证流程

每个改造阶段完成后，**必须**按对应 UX 要点清单逐条验证：

- **P0 完成** → 验证 F1（两行布局）、F3（标题省略）、F5（图标一致）
- **P1 完成** → 验证 F2（`[3/12]` 前缀）
- **P2 完成** → 验证 C1（左时间码毫秒）、C2（右时间码总/剩余）
- **P3 完成** → 验证 A1~A7（全部可见性 UX）
- **P4 完成** → 验证 D4（右键菜单）、B6（右键进度条）
- **P5 完成** → 验证 E6（v 键 keep-open）
- **P6 完成** → 验证 B1~B7（全部进度条 UX）
- **P7 完成** → 验证 H1~H5（全部跨端 UX）
- **P8 完成** → 验证 E1~E9（全部键盘 UX）

**只有对应 UX 要点全部通过，该阶段才算完成。**

---

## 8. mpv 对齐度评估

| mpv 设计点 | SFTP 现状 | 改造后 | 对齐度 |
|-----------|----------|--------|--------|
| 两行布局 | ❌ 单行 | ✅ 两行 | 100% |
| hitbox 精准化 | ❌ 居中 | ⚠️ 部分（Kuikly DSL 限制） | 60% |
| 3 键 + 滚轮交互 | ❌ 仅左键 | ⚠️ 右键（长按跨端映射） | 50% |
| 10+ 色彩 token | 7 全局 | ✅ 10+ OSC 专属 | 100% |
| 时间显示 4 模式 | 1 模式 | ✅ 2 模式（总/剩余） | 50% |
| 进度条 3 样式 | 1 样式 | ✅ 3 样式 | 100% |
| 缓存范围 5 样式 | 1 样式 | ⚠️ 2 样式 | 40% |
| 标题 `[3/12]` | ❌ 无 | ✅ 有 | 100% |
| 可见性 3 模式 | 1 模式 | ✅ 3 模式 | 100% |
| 死区 | ❌ 无 | ✅ 有 | 100% |
| 淡出动画 | ❌ 无 | ✅ 有 | 100% |
| 图标系统 | Unicode | ⚠️ Unicode（对齐结构） | 70% |
| 60fps 节流 | observable 自动 | ⚠️ 无显式节流 | 60% |
| 右键菜单 | ❌ 无 | ✅ 有（长按跨端） | 80% |
| keep-open | ❌ 无 | ✅ 有 | 100% |
| 键盘快捷键 | 5 个 | ✅ 12+ 个 | 100% |
| thumbfast 缩略图 | ❌ | ❌（不做） | 0% |
| 窗口控制按钮 | ❌ 不需要 | ❌（不做） | N/A |
| 毫秒时间码 | ❌ | ❌（不做） | 0% |

**综合对齐度**：约 **80%**（排除不做的项后）。

---

## 9. 决策建议

**两大核心已就位**：

- **§2 跨平台要点清单**：11 小节，15 条跨端 UX 一致性要点（X1~X15），每条都有各端表现 + 验证方法
- **§7.5 用户体验要点清单**：8 大类 39 条 UX 要点（A~H），每条都有 mpv 出处 + 验证方法

这两个章节是**改造的硬约束**，每阶段完成必须逐条验证对应要点。

**推荐从 P0 开始**：

1. P0 是两行布局 + token + 图标层，纯重构、零行为变更、零原生改动
2. P0 是后续所有阶段的基础
3. P0 可立即六端编译验证（验证 X8/X9/X10/X11 四条跨端 UX）

**P4 + P7 一起做**（右键菜单 + 原生事件转发）：

1. P4 右键菜单需要 P7 原生事件转发支撑
2. 两者一起做，避免重复改原生渲染器
3. 一起验证 X1/X2/X6 三条跨端 UX

**P3 可见性系统单独做**：

1. 涉及动画 + 定时器，容易出 bug
2. 单独验证，不与其它阶段耦合
3. 验证 A1~A7 全部 7 条可见性 UX

**每阶段完成标准**（双重门禁）：

1. **跨平台门禁**：六端全部编译通过 + §2.7 对应跨端 UX 要点全部通过
2. **UX 门禁**：§7.5 对应 UX 要点全部通过

只有**双重门禁都通过**，该阶段才算完成。

如果你同意，我从 **P0** 开始落地。如果你想调整对齐度目标（比如 thumbfast 也要做、或某些 mpv 特性不要），告诉我。

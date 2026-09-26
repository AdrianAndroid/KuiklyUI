# Markdown 预览：参考项目功能分析与落地方案

> 目标：把 Markdown 预览做成「打开快、操作顺、能即时编辑、支持流程图」的阅读器，且**六端共用**。
> 参考实现：**MarkText**（MIT，渲染范围）、**Vditor**（MIT，IR 即时渲染 / 工具栏 / pin 工具条）、
> **VS Code / Monaco**（只读查看：行号 / 换行 / 字号）。

本文分三部分：① 现有实现与问题定位；② 参考项目功能对照与取舍；③ 本轮改动 + 后续路线。

---

## 1. 现有实现与问题定位（改造前）

| 层 | 文件 | 职责 |
|---|---|---|
| 装载 | `viewer/SftpTextLoader.kt` | SFTP 分块流式读（96KB/块，上限 2MB）+ 跨端解码（UTF-8 含 emoji / UTF-16 BOM） |
| 解析 | `viewer/md/MarkdownParser.kt` | 块级：标题/段落/代码/引用/列表(含任务)/表格/分隔线；行内：粗体/斜体/删除线/行内码/链接 |
| 渲染 | `viewer/SftpMarkdownViewer.kt` | `RichText + Span` 单文本流（正确折行）；块编辑高亮 |
| 外壳 | `viewer/SftpReaderScaffold.kt` | 单行置顶工具条（A−/A+/换行/源码/编辑/目录N/保存）+ `Scroller` |
| 宿主 | `viewer/SftpViewerDispatcherPage.kt` | 状态、装载、解析缓存、编辑/保存、目录跳转 |

**用户反馈的三个卡顿点，根因如下**（均已定位到具体代码）：

1. **拖动窗口卡**：`Scroller` 内**一次性建出全部块的视图**（最多 `MAX_MD_BLOCKS = 700`，
   每块 2~5 个 View）。窗口尺寸变化 → 整棵树重新布局，成本与块数线性相关。
2. **切换「换行/不换行」卡**：`wrapLines` 是页面级 observable，切换会**重跑整个 body**，
   于是又重建全部块视图；同时每块的行内样式都在渲染期重新 `MarkdownParser.parseInline(...)`，
   重复解析产生大量临时对象。
3. **没有「先缓存再操作」**：`reparse()` 每次都全量 `parse` + `split('\n')`；
   重新打开同一文件要重新走 SFTP 读取 → 解码 → 解析。

---

## 2. 参考项目功能对照（MarkText / Vditor）

`●` 已支持 ｜ `◐` 部分/降级 ｜ `○` 未支持（本轮或后续计划）

| 能力 | MarkText | Vditor | 本项目 | 说明 / 计划 |
|---|---|---|---|---|
| 标题/段落/引用/列表/任务/表格/分隔线 | ● | ● | ● | 已具备 |
| 行内 粗体/斜体/删除线/行内码/链接 | ● | ● | ● | 已具备 |
| 代码块 + 语言标记 | ● | ● | ● | 已具备（未做语法高亮） |
| 目录 TOC | ● | ● | ● | 二级弹窗（近似跳转） |
| 源码 ⇄ 预览 | ● | ● (sv) | ● | 已具备；**未取 sv 分屏**（窄屏体验差） |
| 换行开关 / 字号 / 字数统计 | ● | ● | ● | 已具备 |
| **IR 即时渲染编辑** | ● (source) | ● (IR) | ● | 点块改 → 实时预览（防抖）→ 应用即重排 |
| **工具栏（pin 单行）** | ● | ● | ● | 单行置顶紧凑 |
| 保存 | ● | ● | ● | 沙盒临时文件 → `upload` 覆盖远端 |
| **Mermaid 图表** | ● | ● | ◐→● | **本轮：纯 Kotlin `flowchart/graph` 子集**；Web/Electron 后续可挂 mermaid.js |
| 数学公式 KaTeX | ● | ● | ○ | 后续：纯 Kotlin 子集（上下标/分式/希腊字母）或宿主 KaTeX |
| 图片渲染 | ● | ● | ○ | 后续：走本地代理 URL（图片查看器已有代理能力） |
| 脚注 / 定义列表 / 前置元信息 | ● | ● | ○ | 后续（解析器小增量） |
| 代码语法高亮 | ● | ● | ○ | 后续：宿主 highlight.js（web）/ 轻量 tokenizer（native） |
| 导出 HTML/PDF | ● | ● | ○ | 超出预览范围，暂不做 |
| 主题（明/暗） | ● | ● | ◐ | 已有色板 `SftpColorTokens`，跟随暗色模式 |

**取舍原则**（与 `AGENTS.md §3.1` 一致）：**重型能力必须有一份 commonMain 的纯 Kotlin 降级实现**，
Web/Electron 可在宿主用现成 JS 库增强，但其它端不能空白。因此：
- Mermaid：先做共享降级渲染（本轮），宿主增强作为可选叠加。
- KaTeX / 语法高亮：同理，后续按同一模式补。

---

## 3. 本轮改动

### 3.1 性能：增量渲染 + 解析期预计算

| 改动 | 位置 | 效果 |
|---|---|---|
| 行内片段**解析期预计算**（`Paragraph.runs` / `Quote.runs` / `MdItem.runs`） | `MarkdownParser` | 渲染期不再 `parseInline`，消除重复解析与临时对象 |
| **增量渲染**：首屏 40 块，滚动到「已渲染底部 − 240px」再追加 40 | `SftpMarkdownViewer` + `SftpReaderScaffold.onScroll` + 宿主 `mdRenderLimit` | 存活视图数从最多 700 块降到 ~40 块 → 拖动窗口/切换行只重排这一批 |
| 目录跳转前**先把窗口撑到覆盖目标块** | `jumpToOutline` | 避免跳到未渲染的占位区 |

> 为什么不用 `ListView` 虚拟列表：正文块高不固定，且需要与 TOC 近似跳转、块编辑高亮共存；
> 增量渲染在保持现有布局语义的前提下即可把成本压到常数级，风险更低。

### 3.2 文档缓存：先缓存，再操作

新增 `viewer/SftpDocCache.kt`（纯 commonMain，LRU，最多 8 篇、单篇文本 ≤3MB）：

- 键：`connectionId::remotePath::size`；
- 值：文本 + 编码 + 截断标记 + **已解析的 blocks/outline**；
- 打开文档：命中缓存 → **不读 SFTP、不重新解析**，直接渲染；
- 编辑/保存后：`reparse()` 回写缓存，保证与远端一致。

### 3.3 Mermaid 流程图（共享降级实现）

- `md/MermaidFlowchart.kt`：纯 Kotlin 解析 + **最长路径分层布局**
  - 方向 `flowchart TD|TB|BT|LR|RL`、`graph ...`
  - 节点 `A[矩形] A(圆角) A([胶囊]) A{菱形} A((圆))`
  - 连线 `-->` `---` `-.->` `==>` `--x` `--o`，链式 `A --> B --> C`，标签 `-->|t|` / `-- t -->`
  - 暂不支持：`subgraph`、`style/classDef`、`click`
- `viewer/SftpDiagramView.kt`：**正交折线**（横/竖 1.5px View）+ 箭头字形 + 边标签，
  **不需要旋转/Canvas** → 各端渲染一致；解析失败回退为代码块并提示。
- 全文扫描仍不用正则（Kotlin/JS 正则字符类会抛 `Lone quantifier brackets`，见 `MarkdownParser` 注释）。

### 3.4 跨平台

- 解析、布局、缓存、渲染全部在 `commonMain`，六端共用；无新增原生方法。
- 宿主可选增强（后续）：Web/Electron 在 DOM 里调 mermaid.js / KaTeX / highlight.js，
  通过 `BridgeModule.supportsXxx()` 探测，native 端继续用共享降级实现。

---

## 4. 后续路线（按价值排序）

1. **宿主增强**：Electron/Web 挂 mermaid.js（完整图表）与 highlight.js（代码高亮），探测不到时回退共享实现。
2. **图片**：正文内 `![alt](url)` 走本地代理渲染。
3. **KaTeX 子集**：行内 `$...$` / 块 `$$...$$` 的常见语法。
4. **脚注/定义列表/前置元信息**：解析器小增量。
5. **磁盘缓存**：把 `SftpDocCache` 落到宿主文件（复用既有 CacheManager），冷启动也可秒开。
6. **编辑增强**：多块选择、撤销/重做、查找替换。

---

## 5. 测试

- 用例文件：`electron/test/text-viewer.mjs`（`npm run test:text`）。
- 本轮新增：**T11 Mermaid 流程图渲染**、**T12 增量渲染（首屏只渲染一批 + 提示，滚动后追加）**、
  **T13 文档缓存（二次打开无需重新装载即可渲染）**。
- 既有 T0–T10 必须全绿（含即时渲染 T7/T7b、保存 T8、工具条 T9、目录 T3d）。

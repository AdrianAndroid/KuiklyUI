/*
 * Tencent is pleased to support the open source community by making KuiklyUI
 * available.
 * Copyright (C) 2025 Tencent. All rights reserved.
 * Licensed under the License of KuiklyUI;
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://github.com/Tencent-TDS/KuiklyUI/blob/main/LICENSE
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.tencent.kuikly.demo.pages.sftp.viewer.md

/**
 * Mermaid 流程图（flowchart / graph）的**共享降级实现**：纯 Kotlin 解析 + 分层布局，
 * 不含平台依赖 → Android / iOS / HarmonyOS / Web / 小程序 / Electron 全端可用
 * （符合 AGENTS §3.1 规则 2：重型 UI 必须有 commonMain 降级实现）。
 *
 * 支持范围（Mermaid 常用子集）：
 * - 方向：`flowchart TD|TB|BT|LR|RL`、`graph TD|...`
 * - 节点形状：`A[矩形] A(圆角) A([胶囊]) A{菱形} A((圆))`
 * - 连线：`-->` `---` `-.->` `==>` `--x` `--o`，链式 `A --> B --> C`
 * - 连线标签：`A -->|文本| B` 与 `A -- 文本 --> B`
 * 暂不支持：subgraph、style/classDef、click 交互（遇到会忽略并保持可渲染）。
 *
 * 注意：全部使用字符串扫描而非正则 —— Kotlin/JS 的正则会导致 `Lone quantifier brackets`
 *（见 MarkdownParser.parseListItem 的同类踩坑记录）。
 */

internal enum class MdDirection { TD, BT, LR, RL }

internal enum class MdShape { RECT, ROUND, STADIUM, DIAMOND, CIRCLE }

internal data class MdNode(
    val id: String,
    val label: String,
    val shape: MdShape = MdShape.RECT
)

internal data class MdEdge(
    val from: String,
    val to: String,
    val label: String? = null,
    val dotted: Boolean = false,
    val thick: Boolean = false
)

internal data class MermaidGraph(
    val direction: MdDirection,
    val nodes: List<MdNode>,
    val edges: List<MdEdge>
)

/** 节点在分层布局中的位置（level = 主方向层号，order = 同层内次序） */
internal data class MdPlaced(val node: MdNode, val level: Int, val order: Int)

internal object MermaidParser {

    /** 解析失败或不是受支持的图表时返回 null（调用方回退为代码块展示）。 */
    fun parse(code: String): MermaidGraph? {
        val lines = code.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        var direction: MdDirection? = null
        val nodeMap = LinkedHashMap<String, MdNode>()
        val edges = ArrayList<MdEdge>()

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            // 注释 / 指令 / 暂不支持的结构
            if (line.startsWith("%%")) continue
            if (line.startsWith("subgraph") || line == "end") continue
            if (line.startsWith("style") || line.startsWith("classDef") ||
                line.startsWith("class ") || line.startsWith("linkStyle") ||
                line.startsWith("click") || line.startsWith("direction")
            ) continue

            val lower = line.lowercase()
            if (direction == null && (lower.startsWith("flowchart") || lower.startsWith("graph"))) {
                direction = parseDirection(line)
                continue
            }
            if (direction == null) {
                // 没有 header 时也允许解析（按 TD 处理）
                direction = MdDirection.TD
            }
            parseStatement(line, nodeMap, edges)
        }

        val dir = direction ?: return null
        if (nodeMap.isEmpty()) return null
        return MermaidGraph(dir, nodeMap.values.toList(), edges)
    }

    private fun parseDirection(line: String): MdDirection {
        // 取 header 之后的第一个词
        var i = 0
        while (i < line.length && !line[i].isWhitespace()) i++
        while (i < line.length && line[i].isWhitespace()) i++
        val token = line.substring(i).trim().uppercase().takeWhile { it.isLetter() }
        return when (token) {
            "TD", "TB" -> MdDirection.TD
            "BT" -> MdDirection.BT
            "LR" -> MdDirection.LR
            "RL" -> MdDirection.RL
            else -> MdDirection.TD
        }
    }

    /** 解析一条语句：可能是「纯节点声明」或「节点 (连线 节点)+」链式表达式 */
    private fun parseStatement(
        line: String,
        nodeMap: MutableMap<String, MdNode>,
        edges: MutableList<MdEdge>
    ) {
        var i = 0
        var prevId: String? = null
        var pendingEdge: MdEdge? = null

        while (i < line.length) {
            while (i < line.length && line[i].isWhitespace()) i++
            if (i >= line.length) break

            val ch = line[i]
            if (ch == '-' || ch == '=' || ch == '.' || ch == '~') {
                // 连线操作符
                val start = i
                var dotted = false
                var thick = false
                while (i < line.length && (line[i] == '-' || line[i] == '=' || line[i] == '.' || line[i] == '~')) {
                    when (line[i]) {
                        '.' -> dotted = true
                        '=' -> thick = true
                    }
                    i++
                }
                var arrow = false
                if (i < line.length && (line[i] == '>' || line[i] == 'x' || line[i] == 'o')) {
                    arrow = true
                    i++
                }
                var label: String? = null
                // 形式一：-->|标签|
                if (i < line.length && line[i] == '|') {
                    val close = line.indexOf('|', i + 1)
                    if (close > i) {
                        label = line.substring(i + 1, close).trim().ifEmpty { null }
                        i = close + 1
                    }
                }
                // 形式二：-- 标签 -->
                if (label == null && start > 0) {
                    val middle = line.substring(start, i).trim('-', '=', '.', '~', ' ', '>', 'x', 'o')
                    if (middle.isNotEmpty()) label = middle
                }
                pendingEdge = MdEdge(
                    from = prevId ?: "",
                    to = "",
                    label = label,
                    dotted = dotted,
                    thick = thick
                )
                if (!arrow && !dotted && !thick) {
                    // `---` 也是有效连线（无箭头）
                }
                continue
            }

            // 节点引用：id 后可选形状
            val idStart = i
            while (i < line.length && !line[i].isWhitespace() &&
                line[i] != '[' && line[i] != '(' && line[i] != '{' &&
                line[i] != '-' && line[i] != '=' && line[i] != '.' && line[i] != '~'
            ) i++
            val id = line.substring(idStart, i).trim()
            if (id.isEmpty()) {
                i++
                continue
            }

            var label: String? = null
            var shape = MdShape.RECT
            if (i < line.length) {
                val open = line[i]
                val close = when (open) {
                    '[' -> ']'
                    '(' -> ')'
                    '{' -> '}'
                    else -> null
                }
                if (close != null) {
                    var depth = 0
                    var j = i
                    while (j < line.length) {
                        if (line[j] == open) depth++
                        if (line[j] == close) {
                            depth--
                            if (depth == 0) break
                        }
                        j++
                    }
                    val innerRaw = if (j < line.length) line.substring(i, j + 1) else line.substring(i)
                    label = innerRaw.trim().trim(open, close).trim().trim('"')
                    shape = when {
                        innerRaw.startsWith("((") -> MdShape.CIRCLE
                        innerRaw.startsWith("([") -> MdShape.STADIUM
                        open == '{' -> MdShape.DIAMOND
                        open == '(' -> MdShape.ROUND
                        else -> MdShape.RECT
                    }
                    i = if (j < line.length) j + 1 else line.length
                }
            }

            val existing = nodeMap[id]
            val node = if (existing != null) {
                // 已有节点：补全标签/形状（后续声明可覆盖形状）
                if (label != null) existing.copy(label = label, shape = shape) else existing
            } else {
                MdNode(id = id, label = label ?: id, shape = shape)
            }
            nodeMap[id] = node

            val pe = pendingEdge
            if (pe != null && prevId != null) {
                edges.add(pe.copy(to = id))
            }
            pendingEdge = null
            prevId = id
        }
    }
}

internal object MermaidLayout {

    /**
     * 最长路径分层：level(v) = max(level(u) + 1)，u→v 为入边。
     * 用固定轮数迭代（而非拓扑排序）以容忍环，最多 [MAX_ROUNDS] 轮。
     */
    fun layout(graph: MermaidGraph): List<MdPlaced> {
        val ids = graph.nodes.map { it.id }
        val level = HashMap<String, Int>()
        ids.forEach { level[it] = 0 }
        val incoming = HashMap<String, MutableList<String>>()
        graph.edges.forEach { e ->
            if (e.from.isNotEmpty() && e.to.isNotEmpty()) {
                incoming.getOrPut(e.to) { ArrayList() }.add(e.from)
            }
        }
        val maxRounds = ids.size.coerceAtMost(200) + 1
        for (round in 0 until maxRounds) {
            var mutated = false
            for (v in ids) {
                val preds = incoming[v] ?: continue
                var best = level[v] ?: 0
                for (u in preds) {
                    val lu = (level[u] ?: 0) + 1
                    if (lu > best) best = lu
                }
                if (best != (level[v] ?: 0)) {
                    level[v] = best
                    mutated = true
                }
            }
            if (!mutated) break
        }
        // 同层按出现顺序编号（不用 getOrDefault：Kotlin/JS 上会解析到 Result.getOrDefault）
        val counter = HashMap<Int, Int>()
        return graph.nodes.map { n ->
            val lv = level[n.id] ?: 0
            val ord = counter[lv] ?: 0
            counter[lv] = ord + 1
            MdPlaced(n, lv, ord)
        }
    }
}

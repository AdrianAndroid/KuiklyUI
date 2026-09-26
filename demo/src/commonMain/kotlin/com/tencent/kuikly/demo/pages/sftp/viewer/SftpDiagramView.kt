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
package com.tencent.kuikly.demo.pages.sftp.viewer

import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import com.tencent.kuikly.demo.pages.sftp.theme.SftpColorTokens
import com.tencent.kuikly.demo.pages.sftp.viewer.md.MdBlock
import com.tencent.kuikly.demo.pages.sftp.viewer.md.MdDirection
import com.tencent.kuikly.demo.pages.sftp.viewer.md.MdPlaced
import com.tencent.kuikly.demo.pages.sftp.viewer.md.MdShape
import com.tencent.kuikly.demo.pages.sftp.viewer.md.MermaidGraph
import com.tencent.kuikly.demo.pages.sftp.viewer.md.MermaidLayout
import com.tencent.kuikly.demo.pages.sftp.viewer.md.MermaidParser

/**
 * Mermaid 流程图渲染（共享降级实现，六端共用）。
 *
 * 用「绝对定位 + 正交折线（横竖两段 1.5px View）」绘制，**不需要旋转/Canvas**，
 * 因此在 Kuikly 各端都能稳定渲染；Web/Electron 端后续可在宿主用 mermaid.js 覆盖以获得完整能力
 * （见 devDocs/markdown-viewer-plan.md）。
 *
 * 解析失败或不受支持时回退为代码块样式展示，保证「永远有内容可看」。
 */
internal fun ViewContainer<*, *>.MdDiagram(block: MdBlock.Diagram, fontScaleProvider: () -> Float) {
    val graph = MermaidParser.parse(block.code)
    if (graph == null || graph.nodes.isEmpty()) {
        DiagramFallback(block, fontScaleProvider)
        return
    }
    val placed = MermaidLayout.layout(graph)
    DiagramCanvas(graph, placed, fontScaleProvider)
}

private fun ViewContainer<*, *>.DiagramFallback(block: MdBlock.Diagram, fontScaleProvider: () -> Float) {
    View {
        attr {
            backgroundColor(Color(0xFF1E1E1E))
            borderRadius(6f)
            padding(10f, 10f, 10f, 10f)
            flexDirectionColumn()
        }
        Text {
            attr {
                text("${block.kind}（暂不支持的结构，按源码显示）")
                fontSize(10f * fontScaleProvider())
                color(Color(0xFF9CA3AF))
                marginBottom(4f)
            }
        }
        Text {
            attr {
                text(block.code)
                fontSize(11f * fontScaleProvider())
                color(Color(0xFFE6E6E6))
                lineHeight(16f * fontScaleProvider())
                fontFamily("monospace")
            }
        }
    }
}

private const val NODE_W = 132f
private const val NODE_H = 40f
private const val GAP_X = 26f
private const val GAP_Y = 34f
private const val ARROW = 1.6f

private fun ViewContainer<*, *>.DiagramCanvas(
    graph: MermaidGraph,
    placed: List<MdPlaced>,
    fontScaleProvider: () -> Float,
) {
    val vertical = graph.direction == MdDirection.TD || graph.direction == MdDirection.BT
    val maxLevel = placed.maxOf { it.level }
    val maxOrder = placed.maxOf { it.order } + 1

    // 单位尺寸（随字号缩放，保证与正文观感一致）
    val s = fontScaleProvider().coerceIn(0.7f, 2.2f)
    val nodeW = NODE_W * s
    val nodeH = NODE_H * s
    val gapX = GAP_X * s
    val gapY = GAP_Y * s

    val canvasW = if (vertical) (maxOrder * (nodeW + gapX) - gapX) else ((maxLevel + 1) * (nodeW + gapX) - gapX)
    val canvasH = if (vertical) ((maxLevel + 1) * (nodeH + gapY) - gapY) else (maxOrder * (nodeH + gapY) - gapY)

    // 每个节点的像素中心（TD/LR 为主方向，BT/RL 反向）
    val pos = HashMap<String, Pair<Float, Float>>()
    placed.forEach { p ->
        val mainIdx = when (graph.direction) {
            MdDirection.TD -> p.level
            MdDirection.BT -> maxLevel - p.level
            MdDirection.LR -> p.level
            MdDirection.RL -> maxLevel - p.level
        }
        val crossIdx = p.order
        val x: Float
        val y: Float
        if (vertical) {
            x = crossIdx * (nodeW + gapX)
            y = mainIdx * (nodeH + gapY)
        } else {
            x = mainIdx * (nodeW + gapX)
            y = crossIdx * (nodeH + gapY)
        }
        pos[p.node.id] = x to y
    }

    val lineColor = SftpColorTokens.divider

    View {
        attr {
            flexDirectionColumn()
            margin(8f, 0f, 8f, 0f)
            backgroundColor(SftpColorTokens.bg)
            borderRadius(6f)
            padding(10f, 10f, 10f, 10f)
        }
        View {
            attr { size(canvasW, canvasH) }

            // ---- 先画连线（节点后画，压在上面）----
            graph.edges.forEach { e ->
                val a = pos[e.from] ?: return@forEach
                val b = pos[e.to] ?: return@forEach
                val ax = a.first + nodeW / 2f
                val ay = a.second + nodeH / 2f
                val bx = b.first + nodeW / 2f
                val by = b.second + nodeH / 2f
                val thickness = if (e.thick) 2.6f * s else ARROW * s

                if (vertical) {
                    val startY = a.second + nodeH
                    val endY = b.second
                    val midY = (startY + endY) / 2f
                    // 竖 1：源底部 → 中线
                    Line(ax, startY, thickness, (midY - startY).coerceAtLeast(1f), thickness, lineColor)
                    // 横：源 x → 目标 x
                    Line(minOf(ax, bx), midY, (abs(bx - ax)).coerceAtLeast(1f), thickness, thickness, lineColor)
                    // 竖 2：中线 → 目标顶部
                    Line(bx, midY, thickness, (endY - midY).coerceAtLeast(1f), thickness, lineColor)
                    ArrowHead(bx - 5f * s, endY - 13f * s, "▼", 10f * s, lineColor)
                    if (!e.label.isNullOrEmpty()) {
                        EdgeLabel(minOf(ax, bx) + abs(bx - ax) / 2f + 4f * s, midY - 15f * s, e.label, s)
                    }
                } else {
                    val startX = a.first + nodeW
                    val endX = b.first
                    val midX = (startX + endX) / 2f
                    Line(startX, ay, (midX - startX).coerceAtLeast(1f), thickness, thickness, lineColor)
                    Line(midX, minOf(ay, by), thickness, abs(by - ay).coerceAtLeast(1f), thickness, lineColor)
                    Line(midX, by, (endX - midX).coerceAtLeast(1f), thickness, thickness, lineColor)
                    ArrowHead(endX - 13f * s, by - 5f * s, "▶", 10f * s, lineColor)
                    if (!e.label.isNullOrEmpty()) {
                        EdgeLabel(midX + 3f * s, minOf(ay, by) + abs(by - ay) / 2f - 8f * s, e.label, s)
                    }
                }
            }

            // ---- 节点 ----
            placed.forEach { p ->
                val xy = pos[p.node.id] ?: return@forEach
                val shapeRadius = when (p.node.shape) {
                    MdShape.ROUND, MdShape.STADIUM -> nodeH / 2f
                    MdShape.CIRCLE -> nodeH / 2f
                    MdShape.DIAMOND -> 8f * s
                    MdShape.RECT -> 6f * s
                }
                View {
                    attr {
                        positionAbsolute()
                        left(xy.first)
                        top(xy.second)
                        size(nodeW, nodeH)
                        backgroundColor(SftpColorTokens.cardBg)
                        borderRadius(shapeRadius)
                        flexDirectionColumn()
                        allCenter()
                    }
                    Text {
                        attr {
                            text(p.node.label)
                            fontSize(12f * s)
                            color(SftpColorTokens.textPrimary)
                            lines(2)
                            textAlignCenter()
                            margin(4f, 2f, 4f, 2f)
                        }
                    }
                }
            }
        }
    }
}

private fun ViewContainer<*, *>.Line(
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    thickness: Float,
    color: Color,
) {
    View {
        attr {
            positionAbsolute()
            this.left(left)
            this.top(top)
            if (width >= height) size(width, thickness) else size(thickness, height)
            backgroundColor(color)
        }
    }
}

private fun ViewContainer<*, *>.ArrowHead(
    left: Float,
    top: Float,
    glyph: String,
    sizePx: Float,
    color: Color,
) {
    View {
        attr { positionAbsolute(); this.left(left); this.top(top) }
        Text { attr { text(glyph); fontSize(sizePx); color(color) } }
    }
}

private fun ViewContainer<*, *>.EdgeLabel(left: Float, top: Float, label: String, s: Float) {
    View {
        attr {
            positionAbsolute()
            this.left(left)
            this.top(top)
            backgroundColor(SftpColorTokens.bg)
            borderRadius(3f)
            padding(3f, 1f, 3f, 1f)
        }
        Text { attr { text(label); fontSize(10f * s); color(SftpColorTokens.textSecondary); lines(1) } }
    }
}

private fun abs(v: Float): Float = if (v < 0f) -v else v

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
package com.tencent.kuikly.demo.pages.sftp.theme

/**
 * SFTP 播放器图标（对齐 mpv osc.lua 的 icon_styles 结构）
 *
 * mpv 有两套图标字体：
 * - classic：mpv-osd-symbols 字体，glyph codepoints E000~E2XX 区段
 * - fluent：现代风格，另一套 glyph
 *
 * 通过 `user_opts.icon_style = "layout" | "classic" | "fluent"` 切换。
 * `layout` 模式下：box/slimbox→classic，floating→fluent。
 *
 * SFTP 跨端实现策略：
 * - 不引字体库（跨端硬规则 §2.2 第 3 条）
 * - 用 Unicode 字符兜底，结构对齐 mpv 的 icon_style 概念
 * - `Classic` 对应经典几何符号，`Fluent` 对应现代实心符号
 * - 后续可扩展为 icon font（当前仅 Unicode）
 *
 * 所有图标跨端一致（UX 要点 F5 / 跨端 X10）。
 */
object SftpPlayerIcons {

    /**
     * 经典图标集（对齐 mpv `icon_styles.classic`）
     *
     * 几何线条风格，适配 box/slimbox/bottombar/topbar 布局。
     */
    object Classic {
        /** 播放（对齐 mpv `play` glyph） */
        const val PLAY = "▶"
        /** 暂停（对齐 mpv `pause` glyph） */
        const val PAUSE = "❚❚"
        /** 上一集（对齐 mpv `playlist_prev`，几何箭头） */
        const val PREV_EPISODE = "◀│"
        /** 下一集（对齐 mpv `playlist_next`，几何箭头） */
        const val NEXT_EPISODE = "│▶"
        /** 快退（对齐 mpv `skip_backward`） */
        const val SKIP_BACKWARD = "◀◀"
        /** 快进（对齐 mpv `skip_forward`） */
        const val SKIP_FORWARD = "▶▶"
        /** 静音（对齐 mpv `mute`） */
        const val MUTE = "🔇"
        /** 有声音（对齐 mpv `volume`） */
        const val VOLUME = "🔊"
        /** 进入全屏（对齐 mpv `fullscreen`） */
        const val FULLSCREEN = "⛶"
        /** 退出全屏（对齐 mpv `exit_fullscreen`） */
        const val EXIT_FULLSCREEN = "⤡"
        /** 菜单/选集（对齐 mpv `menu`） */
        const val MENU = "☰"
        /** 返回（SFTP 特有，非 mpv） */
        const val BACK = "<"
    }

    /**
     * 现代图标集（对齐 mpv `icon_styles.fluent`）
     *
     * 实心风格，适配 floating 布局。
     * 当前与 Classic 相同 Unicode（后续可替换为实心字符或字体图标）。
     */
    object Fluent {
        const val PLAY = "►"
        const val PAUSE = "❙❙"
        const val PREV_EPISODE = "◀│"
        const val NEXT_EPISODE = "│▶"
        const val SKIP_BACKWARD = "◁◁"
        const val SKIP_FORWARD = "▷▷"
        const val MUTE = "🔇"
        const val VOLUME = "🔊"
        const val FULLSCREEN = "⛶"
        const val EXIT_FULLSCREEN = "⤡"
        const val MENU = "☰"
        const val BACK = "<"
    }

    /**
     * 按布局选择图标集（对齐 mpv `set_icon_style()`）
     *
     * mpv 逻辑：`layout=floating` → fluent，否则 → classic。
     * SFTP 当前只有 bottombar 一种布局，返回 Classic。
     * P1 引入 COMPACT 布局后可扩展。
     */
    fun forLayout(layout: SftpPlayerLayout): Any = when (layout) {
        SftpPlayerLayout.FLOATING -> Fluent
        else -> Classic
    }
}

/**
 * 播放器布局枚举（对齐 mpv `layouts` 表）
 *
 * mpv 有 box / slimbox / bottombar / topbar / floating 五种布局。
 * SFTP 跨端简化为：
 * - BOTTOMBAR：默认两行底部布局（信息行 + 控制行）
 * - COMPACT：窄宽度下紧凑单行布局（P1 引入）
 * - FLOATING：浮动布局（后续扩展）
 *
 * 当前只实现 BOTTOMBAR，P1 引入 COMPACT 宽度判定。
 */
enum class SftpPlayerLayout {
    /** 默认两行底部布局（对齐 mpv bottombar） */
    BOTTOMBAR,
    /** 窄宽度紧凑单行布局（对齐 mpv slimbox，P1 引入） */
    COMPACT,
    /** 浮动布局（对齐 mpv floating，后续扩展） */
    FLOATING;

    companion object {
        /**
         * 宽度判定阈值（对齐 mpv minW 计算逻辑）
         *
         * mpv 的 minW = (buttonW + padX)*5 + (tcW + padX)*4 + (tsW + padX)*2
         * = (27+9)*5 + (110+9)*4 + (90+9)*2 = 180 + 476 + 198 = 854
         *
         * SFTP 按钮更大（40+12）*5 + (64+12)*2 = 260 + 152 = 412
         * 加上标题最小宽度 ~200，阈值约 600。
         */
        const val COMPACT_THRESHOLD = 600f

        /**
         * 根据页面宽度选择布局（对齐 mpv `osc_param.playresx < minW` 判定）
         */
        fun forWidth(width: Float): SftpPlayerLayout =
            if (width < COMPACT_THRESHOLD) COMPACT else BOTTOMBAR
    }
}

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

import com.tencent.kuikly.core.base.Color

/**
 * SFTP 播放器 Tokens（对齐 mpv osc.lua 的 10+ 色彩 + 尺寸 token）
 *
 * mpv OSC 颜色配置（osc.lua user_opts）：
 * - background_color     OSC 背景
 * - timecode_color       进度条 + 时间
 * - title_color          标题
 * - time_pos_color       悬停位置时间（tooltip）
 * - buttons_color        大按钮 + 边按钮
 * - small_buttonsL/R     左/右侧小按钮
 * - top_buttons_color    顶部按钮
 * - held_element_color   按下态
 * - time_pos_outline_color tooltip 描边
 * - boxalpha             OSC 背景透明度
 *
 * 所有 SFTP 播放器 UI 颜色/尺寸都从这里取，便于夜间模式切换与后续 mpv 对齐扩展。
 * 日夜切换由 [SftpColorTokens.isNight] 决定。
 */
object SftpPlayerTokens {

    // ── 颜色（对齐 mpv background_color / boxalpha） ──

    /** OSC 背景色（对齐 mpv background_color + boxalpha=80） */
    val oscBg: Color get() = if (SftpColorTokens.isNight) Color(0xCC0A0A0A.toInt()) else Color(0xCC101010.toInt())

    /** 进度条 + 时间码颜色（对齐 mpv timecode_color = #FFFFFF） */
    val timecode: Color get() = Color(0xFFFFFFFF)

    /** 已播段进度条颜色（对齐 mpv buttons_color，SFTP 用品牌蓝强调当前进度） */
    val seekbarPlayed: Color get() = if (SftpColorTokens.isNight) Color(0xFF4A9EFF.toInt()) else Color(0xFF3D7EFF.toInt())

    /** 缓冲段进度条颜色（对齐 mpv seekrangealpha=200，半透明白） */
    val seekbarBuffered: Color get() = Color(0x99FFFFFF)

    /** 进度条轨道颜色（未播放段） */
    val seekbarTrack: Color get() = Color(0x55FFFFFF)

    /** 进度条滑块颜色 */
    val seekbarThumb: Color get() = Color(0xFFFFFFFF)

    /** 进度条滑块外圈（拖动态） */
    val seekbarThumbHalo: Color get() = if (SftpColorTokens.isNight) Color(0x554A9EFF.toInt()) else Color(0x553D7EFF.toInt())

    /** 标题颜色（对齐 mpv title_color） */
    val title: Color get() = if (SftpColorTokens.isNight) Color(0xFFE8E8E8.toInt()) else Color(0xFFEEEEEE.toInt())

    /** 悬停时间 tooltip 颜色（对齐 mpv time_pos_color） */
    val timePos: Color get() = Color(0xFFFFFFFF)

    /** 悬停时间 tooltip 背景色 */
    val timePosBg: Color get() = Color(0xCC000000.toInt())

    /** 按钮（大按钮 + 顶部按钮）颜色（对齐 mpv buttons_color / top_buttons_color） */
    val buttons: Color get() = Color(0xFFFFFFFF)

    /** 按下态颜色（对齐 mpv held_element_color = #999999） */
    val heldElement: Color get() = Color(0xFF999999.toInt())

    /** 播放/暂停按钮背景色（SFTP 特有，强调主播放键） */
    val playButtonBg: Color get() = if (SftpColorTokens.isNight) Color(0xFF2C2C2C.toInt()) else Color(0xFF2A2A2A.toInt())

    /** 错误色（对齐 SftpColorTokens.danger，播放错误提示用） */
    val danger: Color get() = if (SftpColorTokens.isNight) Color(0xFFFF6B6B.toInt()) else Color(0xFFFF6B6B.toInt())

    /** 设置菜单背景色 */
    val settingsMenuBg: Color get() = Color(0xF0222222.toInt())

    /** 设置菜单选中态背景色 */
    val settingsMenuSelected: Color get() = if (SftpColorTokens.isNight) Color(0xFF4A9EFF.toInt()) else Color(0xFF3D7EFF.toInt())

    // ── 尺寸（对齐 mpv bottombar: h=56, padX=9, buttonW=27） ──

    /** OSC 总高度（对齐 mpv bottombar h=56，SFTP 两行各 40 + 44 = 84，触控更友好） */
    const val OSC_HEIGHT_TOTAL = 84f

    /** 信息行（line1）高度 */
    const val INFO_ROW_HEIGHT = 40f

    /** 控制行（line2）高度 */
    const val CONTROL_ROW_HEIGHT = 44f

    /** OSC 水平内边距（对齐 mpv padX=9，SFTP 用 12 适配触控） */
    const val PAD_X = 12f

    /** 按钮宽度（对齐 mpv buttonW=27，SFTP 用 40 适配触控） */
    const val BUTTON_W = 40f

    /** 播放/暂停按钮尺寸（比普通按钮大，强调主键） */
    const val PLAY_BUTTON_SIZE = 44f

    /** 时间码宽度（对齐 mpv tcW=110） */
    const val TC_W = 64f

    /** 设置菜单宽度 */
    const val SETTINGS_MENU_W = 132f

    /** 设置菜单项高度 */
    const val SETTINGS_MENU_ITEM_H = 36f

    // ── 进度条（对齐 mpv seekbar: border=0, gap=2, h=5, thumb=13） ──

    /** 进度条轨道高度（对齐 mpv seekbar h=5） */
    const val SEEKBAR_TRACK_H = 5f

    /** 进度条轨道圆角 */
    const val SEEKBAR_TRACK_RADIUS = 3f

    /** 进度条滑块直径（对齐 mpv thumb=13） */
    const val SEEKBAR_THUMB = 13f

    /** 进度条拖动态滑块直径（Plyr thumb-active 放大） */
    const val SEEKBAR_THUMB_DRAG = 20f

    /** 进度条触摸区高度（比视觉高度大，便于拖拽） */
    const val SEEKBAR_TOUCH_H = 28f

    /** 进度条手柄外圈半径（拖动态） */
    const val SEEKBAR_THUMB_HALO_DRAG = 10f

    /** 进度条手柄静止态偏移（视觉居中） */
    const val SEEKBAR_THUMB_OFFSET = 7f

    /** 进度条手柄拖动态偏移 */
    const val SEEKBAR_THUMB_OFFSET_DRAG = 10f

    /** 进度条手柄静止态 top 偏移（垂直居中于轨道） */
    const val SEEKBAR_THUMB_TOP = -4f

    /** 进度条手柄拖动态 top 偏移 */
    const val SEEKBAR_THUMB_TOP_DRAG = -8f

    // ── 可见性（对齐 mpv hidetimeout=500, fadeduration=200, deadzonesize=0.75） ──
    // P0 先保留现有 2000ms，P3 改为 500ms + 淡出 + 死区

    /** 控制条自动隐藏延迟（ms）。P0 保留 2000ms，P3 改为 500ms 对齐 mpv */
    const val HIDE_TIMEOUT_MS = 2000

    /** 淡出动画时长（ms）。P3 启用 */
    const val FADE_DURATION_MS = 200

    /** 死区占比（对齐 mpv deadzonesize=0.75）。P3 启用 */
    const val DEADZONE_SIZE = 0.75f

    // ── 刷新节流（对齐 mpv tick_delay=1/60） ──

    /** 最小重绘间隔（ms，对齐 mpv 60fps） */
    const val TICK_DELAY_MS = 16L

    // ── 字号 ──

    /** 信息行标题字号 */
    const val TITLE_FONT_SIZE = 14f

    /** 控制行时间码字号 */
    const val TIMECODE_FONT_SIZE = 12f

    /** 控制行按钮图标字号 */
    const val BUTTON_ICON_FONT_SIZE = 15f

    /** 播放键图标字号 */
    const val PLAY_ICON_FONT_SIZE = 18f

    /** 拖动 tooltip 字号 */
    const val TOOLTIP_FONT_SIZE = 11f

    /** 倍速标签字号 */
    const val SPEED_LABEL_FONT_SIZE = 12f

    /** 设置菜单项字号 */
    const val SETTINGS_MENU_ITEM_FONT_SIZE = 13f

    // ── 圆角 ──

    /** 播放键圆角 */
    const val PLAY_BUTTON_RADIUS = 22f

    /** 设置菜单圆角 */
    const val SETTINGS_MENU_RADIUS = 10f

    /** 设置菜单项圆角 */
    const val SETTINGS_MENU_ITEM_RADIUS = 6f

    /** tooltip 圆角 */
    const val TOOLTIP_RADIUS = 4f
}

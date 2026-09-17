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
 * SFTP 客户端 Color Tokens（§21.8.8）
 *
 * 7 个 token 日夜双色，所有 SFTP UI 颜色都从这里取，便于夜间模式切换。
 * 由 [SftpBasePager.isNightMode] 决定取 light 还是 dark。
 */
object SftpColorTokens {
    // Light mode
    val bg: Color get() = if (isNight) Color(0xFF1A1A1A.toInt()) else Color(0xFFF7F7F7.toInt())
    val cardBg: Color get() = if (isNight) Color(0xFF2C2C2C.toInt()) else Color(0xFFFFFFFF.toInt())
    val primary: Color get() = if (isNight) Color(0xFF4A9EFF.toInt()) else Color(0xFF1677FF.toInt())
    val textPrimary: Color get() = if (isNight) Color(0xFFE8E8E8.toInt()) else Color(0xFF222222.toInt())
    val textSecondary: Color get() = if (isNight) Color(0xFF999999.toInt()) else Color(0xFF666666.toInt())
    val divider: Color get() = if (isNight) Color(0xFF3A3A3A.toInt()) else Color(0xFFE5E5E5.toInt())
    val danger: Color get() = if (isNight) Color(0xFFFF6B6B.toInt()) else Color(0xFFFF4D4F.toInt())

    /** 由 [SftpBasePager] 在 `themeDidChanged` 时同步 */
    var isNight: Boolean = false
        private set

    fun setNightMode(night: Boolean) {
        isNight = night
    }
}

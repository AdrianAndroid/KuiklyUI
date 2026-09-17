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

package com.tencent.kuikly.core.module

object ModuleConst {
    const val NOTIFY = "KRNotifyModule"
    const val MEMORY = "KRMemoryCacheModule"
    const val SHARED_PREFERENCES = "KRSharedPreferencesModule"
    const val SNAPSHOT = "KRSnapshotModule"
    const val ROUTER = "KRRouterModule"
    const val NETWORK = "KRNetworkModule"
    const val CODEC = "KRCodecModule"
    const val TURBO_DISPLAY = "KRTurboDisplayModule"
    const val CALENDAR = "KRCalendarModule"
    const val REFLECTION = "KRReflectionModule"
    const val PERFORMANCE = "KRPerformanceModule"
    const val FONT = "KRFontModule"
    const val VSYNC = "KRVsyncModule"
    const val BACK_PRESS = "KRBackPressModule"
    const val FILE = "KRFileModule"

    // —— SFTP 客户端模块（§3.3） ——
    /** SFTP 主 Module，绑定 Page 生命周期（§3.3 / §15.1） */
    const val SFTP = "KRSftpModule"
    /** 收藏 Module，全局单例（§3.4 / §21.7.4） */
    const val SFTP_FAVORITES = "KRSftpFavoritesModule"
    /** 播放历史 Module，全局单例（§20.1 / §21.7.4） */
    const val SFTP_PLAYBACK_HISTORY = "KRSftpPlaybackHistoryModule"
    /** 连接列表 Module，全局单例（§17.3.1 / §21.7.4）—— 持久化用户保存的 SFTP 连接配置 */
    const val SFTP_CONNECTION = "KRSftpConnectionModule"
}

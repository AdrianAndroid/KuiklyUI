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
package com.tencent.kuikly.demo.pages.sftp

import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.module.Module
import com.tencent.kuikly.core.module.sftp.SftpConnectionModule
import com.tencent.kuikly.core.module.sftp.SftpFavoritesModule
import com.tencent.kuikly.core.module.sftp.SftpMediaProxyModule
import com.tencent.kuikly.core.module.sftp.SftpModule
import com.tencent.kuikly.core.module.sftp.SftpPlaybackHistoryModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.demo.pages.base.BasePager
import com.tencent.kuikly.demo.pages.sftp.theme.SftpColorTokens

/**
 * SFTP Pager 抽象基类
 *
 * - 注册 [SftpModule]（绑定 Page 生命周期，§15.1 / §21.7.4）
 * - 收藏/历史走全局单例，不在 `createExternalModules` 中创建
 * - 同步夜间模式到 [SftpColorTokens]
 * - 提供 Module accessor 便利方法
 */
internal abstract class SftpBasePager : BasePager() {

    override fun createExternalModules(): Map<String, Module>? {
        val map = hashMapOf<String, Module>()
        // 需要宿主能力（toast 等）：SftpBasePager 覆盖了父类实现，这里显式补上
        map[com.tencent.kuikly.demo.pages.base.BridgeModule.MODULE_NAME] =
            com.tencent.kuikly.demo.pages.base.BridgeModule()
        map[SftpModule.MODULE_NAME] = SftpModule()
        map[SftpFavoritesModule.MODULE_NAME] = SftpFavoritesModule()
        map[SftpPlaybackHistoryModule.MODULE_NAME] = SftpPlaybackHistoryModule()
        map[SftpConnectionModule.MODULE_NAME] = SftpConnectionModule()
        map[SftpMediaProxyModule.MODULE_NAME] = SftpMediaProxyModule()
        // 终端（shell 通道；web 转发到本地网关，native 待接入 libssh2 pty）
        map[com.tencent.kuikly.demo.pages.sftp.terminal.TerminalModule.MODULE_NAME] =
            com.tencent.kuikly.demo.pages.sftp.terminal.TerminalModule()
        return map
    }

    /** SFTP 主 Module（绑定本 Page 生命周期） */
    /**
     * 是否 Web/桌面（有宿主 `window.localFs` 提供本地栏）。
     * 双栏入口只在这些端提供；纯浏览器 H5 虽同属「非原生」，但页面会降级提示。
     */
    protected val isWebLike: Boolean
        get() = !com.tencent.kuikly.core.utils.PlatformUtils.isAndroid() &&
            !com.tencent.kuikly.core.utils.PlatformUtils.isIOS() &&
            !com.tencent.kuikly.core.utils.PlatformUtils.isMacOS() &&
            !com.tencent.kuikly.core.utils.PlatformUtils.isOhOs()

    protected fun sftpModule(): SftpModule = acquireModule(SftpModule.MODULE_NAME)

    /** 收藏 Module（全局单例） */
    protected fun sftpFavoritesModule(): SftpFavoritesModule = acquireModule(SftpFavoritesModule.MODULE_NAME)

    /** 播放历史 Module（全局单例） */
    protected fun sftpPlaybackHistoryModule(): SftpPlaybackHistoryModule = acquireModule(SftpPlaybackHistoryModule.MODULE_NAME)

    /** 连接列表 Module（全局单例） */
    protected fun sftpConnectionModule(): SftpConnectionModule = acquireModule(SftpConnectionModule.MODULE_NAME)

    /** 本地媒体代理 Module（§5 / §7.3） */
    protected fun sftpMediaProxyModule(): SftpMediaProxyModule = acquireModule(SftpMediaProxyModule.MODULE_NAME)

    override fun themeDidChanged(data: JSONObject) {
        super.themeDidChanged(data)
        val night = data.optBoolean(IS_NIGHT_MODE_KEY)
        SftpColorTokens.setNightMode(night)
    }

    override fun isNightMode(): Boolean {
        val night = super.isNightMode()
        SftpColorTokens.setNightMode(night)
        return night
    }
}

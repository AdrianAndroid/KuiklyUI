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

import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.log.KLog
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.demo.pages.base.BridgeModule

/**
 * 打开播放页的**唯一入口**（浏览页 / 收藏 / 历史 / 首页都走这里）。
 *
 * 桌面壳（Electron）支持把播放页开成**独立窗口**：可同时播放多个视频，
 * 且不影响主窗口继续浏览。其它端（H5 / Android / iOS / macOS / OHOS / 小程序）
 * 由宿主返回 supported=false，自动回退为页内路由（与旧行为一致）。
 */
internal fun Pager.openPlayerPage(playerParams: JSONObject) {
    val bridge = acquireModule<BridgeModule>(BridgeModule.MODULE_NAME)
    val supported = runCatching { bridge.supportsPlayerWindow() }.getOrElse {
        KLog.e("SftpPlayerLauncher", "supportsPlayerWindow 探测异常: ${it.message}")
        false
    }
    KLog.i("SftpPlayerLauncher", "supportsPlayerWindow=$supported")
    if (supported) {
        // 独立窗口：本次点击不再改当前页（主窗口留在原列表，可继续点开更多视频）。
        // 必须显式带上 page_name —— 页内路由是由 RouterModule 补的，宿主直接开窗口不会补，
        // 少了它新窗口会落到默认页（而不是播放页）。
        val hostParams = JSONObject(playerParams.toString())
        hostParams.put("page_name", SftpPlayerPage.PAGE_NAME)
        bridge.openPlayerWindow(hostParams)
        return
    }
    acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage(SftpPlayerPage.PAGE_NAME, playerParams)
}

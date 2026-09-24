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
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.demo.pages.base.BridgeModule
import com.tencent.kuikly.demo.pages.sftp.viewer.SftpViewerDispatcherPage

/**
 * 打开「查看器」页（文本 / Markdown / HTML 等）。
 *
 * 桌面壳（Electron）支持独立窗口 → 文本阅读时**另开窗口**（可与列表并存、可同时读多篇）；
 * 其它端由宿主返回 supported=false，自动回退为页内路由。
 *
 * 注：宿主能力方法名沿用 `supportsPlayerWindow/openPlayerWindow`（实现是通用的独立窗口），
 * 避免改动各端原生模块（iOS 未实现的方法会触发 NSAssert）。
 */
internal fun Pager.openViewerPage(viewerParams: JSONObject) {
    val bridge = acquireModule<BridgeModule>(BridgeModule.MODULE_NAME)
    val supported = runCatching { bridge.supportsPlayerWindow() }.getOrDefault(false)
    if (supported) {
        val hostParams = JSONObject(viewerParams.toString())
        // 宿主直接开窗口不会补 page_name（页内路由才由 RouterModule 补）
        hostParams.put("page_name", SftpViewerDispatcherPage.PAGE_NAME)
        bridge.openPlayerWindow(hostParams)
        return
    }
    acquireModule<RouterModule>(RouterModule.MODULE_NAME)
        .openPage(SftpViewerDispatcherPage.PAGE_NAME, viewerParams)
}

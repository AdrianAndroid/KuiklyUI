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

import com.tencent.kuikly.core.log.KLog
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.module.sftp.MimeExtMap
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.demo.pages.base.BridgeModule
import com.tencent.kuikly.demo.pages.sftp.viewer.SftpViewerDispatcherPage

/**
 * 打开「查看器」页（文本 / Markdown / HTML 等）的唯一入口。
 *
 * 打开方式分两种：
 * - **Markdown → 独立窗口**（仅桌面壳/Web 宿主支持时）：Markdown 阅读常伴随目录跳转、
 *   源码⇄预览切换、块编辑与保存，放在独立窗口里不占用主窗口的文件列表，可对照查看。
 *   与播放页同款机制（`BridgeModule.openPlayerWindow` + `standalone=1`，返回键只关该窗口）。
 * - **其它类型 / 宿主不支持 → 页内路由**：保持原行为（`RouterModule.openPage`）。
 */
internal fun Pager.openViewerPage(viewerParams: JSONObject) {
    val mime = MimeExtMap.mimeOfPath(viewerParams.optString("remotePath", ""))
    if (MimeExtMap.isMarkdown(mime) && supportsStandaloneWindow()) {
        // 独立窗口：宿主直接开窗口不会补 page_name（页内路由才由 RouterModule 补），必须显式带上
        val hostParams = JSONObject(viewerParams.toString())
        hostParams.put("page_name", SftpViewerDispatcherPage.PAGE_NAME)
        acquireModule<BridgeModule>(BridgeModule.MODULE_NAME).openPlayerWindow(hostParams)
        return
    }
    acquireModule<RouterModule>(RouterModule.MODULE_NAME)
        .openPage(SftpViewerDispatcherPage.PAGE_NAME, viewerParams)
}

private fun Pager.supportsStandaloneWindow(): Boolean =
    runCatching {
        acquireModule<BridgeModule>(BridgeModule.MODULE_NAME).supportsPlayerWindow()
    }.getOrElse {
        KLog.e("SftpViewerLauncher", "supportsPlayerWindow 探测异常: ${it.message}")
        false
    }

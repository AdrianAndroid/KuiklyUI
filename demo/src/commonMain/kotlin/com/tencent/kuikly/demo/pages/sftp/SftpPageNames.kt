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

/**
 * 页面名常量（commonMain）。
 *
 * 双栏文件管理器目前只有 JS/桌面实现（`demo/src/jsMain`），但首页入口在 commonMain，
 * 需要跨源集共享页面名；这里放常量避免字符串硬编码漂移。
 */
internal object SftpPageNames {
    const val FILES_DUAL_PANE = "FilesDualPanePage"

    /**
     * 双栏路由参数**唯一构造入口**：三处入口（首页默认/首页连接行/浏览页右上角）都用它，
     * 避免 key 名（connectionId/connectionLabel/remoteHome/remotePath）在多处手写后漂移。
     */
    fun dualPaneParams(
        connectionId: String,
        label: String,
        remoteHome: String = "/",
        remotePath: String = "",
    ): JSONObject {
        val p = JSONObject()
        if (connectionId.isNotEmpty()) p.put("connectionId", connectionId)
        if (label.isNotEmpty()) p.put("connectionLabel", label)
        p.put("remoteHome", remoteHome.ifEmpty { "/" })
        if (remotePath.isNotEmpty()) p.put("remotePath", remotePath)
        return p
    }

    fun openDualPane(
        router: RouterModule,
        connectionId: String,
        label: String,
        remoteHome: String = "/",
        remotePath: String = "",
    ) {
        router.openPage(FILES_DUAL_PANE, dualPaneParams(connectionId, label, remoteHome, remotePath))
    }
}

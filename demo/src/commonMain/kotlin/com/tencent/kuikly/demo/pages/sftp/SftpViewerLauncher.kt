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
import com.tencent.kuikly.demo.pages.sftp.viewer.SftpViewerDispatcherPage

/**
 * 打开「查看器」页（文本 / Markdown / HTML 等）。
 *
 * **统一页内路由**：查看与修改都在**同一个界面**完成，不再为文本查看另开独立窗口
 * （独立窗口会导致「编辑要新窗口、与列表割裂」的体验问题）。
 *
 * 注：`BridgeModule.supportsPlayerWindow` 仍被 `save()` 用作「是否 Web/桌面宿主」的探测，
 * 与本函数的打开方式无关（保存路径不受影响）。
 */
internal fun Pager.openViewerPage(viewerParams: JSONObject) {
    acquireModule<RouterModule>(RouterModule.MODULE_NAME)
        .openPage(SftpViewerDispatcherPage.PAGE_NAME, viewerParams)
}

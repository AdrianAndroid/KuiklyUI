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

package impl

import com.squareup.kotlinpoet.FileSpec

/**
 * JS / Web / MiniApp 入口生成器。
 *
 * 这些目标**没有**原生 `IKuiklyCoreEntry`（该类位于 core 的 androidMain），页面引导由
 * `core-render-web` / `core-wx` 自行完成，所以不能复用 Android 构建器——否则会把引用
 * androidMain-only 符号的代码写进 `jsMain`，导致 Kotlin/JS 编译必然失败。
 *
 * 但 Gradle 插件（`JSProcessor.getPageListFromEntryFile`）仍会读取生成文件的**第一行**
 * 来获取页面列表：取「最后一个 `/` 之后」的内容，再按 `|` 切分。因此这里直写
 * `//页面名1|页面名2`（`//` 后不加空格，保证切分出的第一项不带前导空格），
 * 且不生成任何平台相关代码。
 */
class JsTargetEntryBuilder : KuiklyCoreAbsEntryBuilder() {

    override fun build(builder: FileSpec.Builder, pagesAnnotations: List<PageInfo>) {
        // 不生成任何代码：JS 侧由 core-render-web / core-wx 引导。
    }

    override fun buildRawEntry(pagesAnnotations: List<PageInfo>): String {
        val names = pagesAnnotations.joinToString(separator = "|") { it.pageName }
        return "//$names\n"
    }

    override fun entryFileName(): String = "KuiklyCoreEntry"

    override fun packageName(): String = ""
}

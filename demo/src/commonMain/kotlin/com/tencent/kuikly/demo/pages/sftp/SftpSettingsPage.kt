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
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.module.SharedPreferencesModule
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import com.tencent.kuikly.demo.pages.base.Utils
import com.tencent.kuikly.demo.pages.sftp.theme.SftpColorTokens
import com.tencent.kuikly.demo.pages.sftp.terminal.SftpTerminalPage

/**
 * 设置页（跨端）：终端命令历史条数 / 清空缓存 / 清空播放历史 / 关于。
 * 入口：首页右下角「更多」抽屉（Web/桌面）。
 */
@Page(SftpPageNames.SETTINGS)
internal class SftpSettingsPage : SftpBasePager() {

    private var termHistoryMax: Int by observable(20)
    private var cacheHint: String by observable("")
    private var historyLimit: Int by observable(1000)
    private var historyHint: String by observable("")

    override fun created() {
        super.created()
        val prefs = acquireModule<SharedPreferencesModule>(SharedPreferencesModule.MODULE_NAME)
        termHistoryMax = prefs.getInt(SftpTerminalPage.KEY_TERM_HISTORY_MAX) ?: 20
        historyLimit = prefs.getInt(KEY_HISTORY_LIMIT) ?: 1000
    }

    private fun cycleHistoryMax() {
        val options = listOf(5, 10, 20, 50)
        val next = options[(options.indexOf(termHistoryMax).let { if (it < 0) 2 else it } + 1) % options.size]
        termHistoryMax = next
        acquireModule<SharedPreferencesModule>(SharedPreferencesModule.MODULE_NAME)
            .setInt(SftpTerminalPage.KEY_TERM_HISTORY_MAX, next)
        Utils.bridgeModule(this).toast("终端历史条数已设为 $next")
    }

    /** 播放历史容量：本地持久化 + Web 端同步到网关（追加语义，超限丢最旧） */
    private fun cycleHistoryLimit() {
        val options = listOf(5, 10, 20, 50, 200, 1000)
        val cur = options.indexOf(historyLimit).let { if (it < 0) 2 else it }
        val next = options[(cur + 1) % options.size]
        historyLimit = next
        acquireModule<SharedPreferencesModule>(SharedPreferencesModule.MODULE_NAME).setInt(KEY_HISTORY_LIMIT, next)
        // Web/桌面：同步到网关（native 端由各端 LRU 常量控制，待接入统一下发）
        val web = runCatching {
            acquireModule<com.tencent.kuikly.demo.pages.base.BridgeModule>(com.tencent.kuikly.demo.pages.base.BridgeModule.MODULE_NAME).cacheRoot().isNotEmpty()
        }.getOrDefault(false)
        if (web) {
            sftpPlaybackHistoryModule().setLimit(next) { }
        }
        Utils.bridgeModule(this).toast("播放历史条数已设为 $next")
    }

    /** 清空本地缓存目录（仅宿主提供本地文件能力时可用，其它端提示待支持） */
    private fun clearCache() {
        val host = js("(typeof window !== 'undefined' && window.localFs) || null") ?: run {
            cacheHint = "本端暂不支持清空缓存（待宿主提供文件删除能力）"
            Utils.bridgeModule(this).toast(cacheHint)
            return
        }
        host.home().then({ home: dynamic ->
            val dir = (home as String) + "/" + CACHE_DIR_NAME
            host.remove(dir, true).then({
                cacheHint = "已清空缓存"
                Utils.bridgeModule(this).toast(cacheHint)
                null
            }, { _: dynamic ->
                cacheHint = "已清空缓存（无缓存目录）"
                Utils.bridgeModule(this).toast(cacheHint)
                null
            })
            null
        }, { _: dynamic ->
            cacheHint = "清空缓存失败"
            Utils.bridgeModule(this).toast(cacheHint)
            null
        })
    }

    /** 清空播放历史：模块无「全局清空」API，故遍历连接逐个 clearByConnection（含内联凭据的空连接） */
    private fun clearPlaybackHistory() {
        sftpConnectionModule().list { conns, _ ->
            val ids = ArrayList<String>()
            ids.add("")
            conns.forEach { ids.add(it.id) }
            var left = ids.size
            ids.forEach { cid ->
                sftpPlaybackHistoryModule().clearByConnection(cid) { _, _ ->
                    left--
                    if (left <= 0) {
                        historyHint = "已清空播放历史"
                        Utils.bridgeModule(this).toast(historyHint)
                    }
                }
            }
        }
    }

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            attr { backgroundColor(SftpColorTokens.bg) }
            View {
                attr {
                    size(pagerData.pageViewWidth, 48f)
                    flexDirectionRow(); alignItemsCenter()
                    padding(12f, 6f, 12f, 6f)
                    backgroundColor(SftpColorTokens.cardBg)
                }
                Text {
                    attr { text("<"); fontSize(20f); color(SftpColorTokens.textPrimary); size(30f, 30f) }
                    event { click { ctx.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage() } }
                }
                Text { attr { text("设置"); fontSize(15f); fontWeightBold(); color(SftpColorTokens.textPrimary); marginLeft(6f) } }
            }

            SettingsRow("终端命令历史条数", { "${ctx.termHistoryMax} 条（点击切换 5/10/20/50）" }) { ctx.cycleHistoryMax() }
            SettingsRow("播放历史条数", { "${ctx.historyLimit} 条（点击切换 5/10/20/50/200/1000；追加，超限丢最旧）" }) { ctx.cycleHistoryLimit() }
            SettingsRow("清空缓存", { ctx.cacheHint.ifEmpty { "删除本地缓存目录（${CACHE_DIR_NAME}）" } }) { ctx.clearCache() }
            SettingsRow("清空播放历史", { ctx.historyHint.ifEmpty { "删除全部续播记录" } }) { ctx.clearPlaybackHistory() }
            SettingsRow("关于", { "Kuikly SFTP 0.1.0 · 六端共用 UI（本页为共享实现）" }) { Utils.bridgeModule(this).toast("Kuikly SFTP 0.1.0") }
        }
    }

    companion object {
        /** 本地缓存目录名（缓存整个目录功能使用） */
        const val CACHE_DIR_NAME = ".kuikly_cache"
        /** 播放历史容量偏好（SharedPreferences key） */
        const val KEY_HISTORY_LIMIT = "history_limit"
    }
}

private fun ViewContainer<*, *>.SettingsRow(title: String, subtitleProvider: () -> String, onClick: () -> Unit) {
    View {
        attr {
            width(pagerData.pageViewWidth - 8f)
            padding(16f, 12f, 16f, 12f)
            backgroundColor(SftpColorTokens.cardBg)
            flexDirectionColumn()
        }
        event { click { onClick() } }
        Text {
            attr { text(title); fontSize(15f); color(SftpColorTokens.textPrimary) }
            event { click { onClick() } }
        }
        Text {
            // 必须在 attr{} 内读取（provider），否则 observable 依赖不被收集，点击后文案不刷新
            attr { text(subtitleProvider()); fontSize(12f); color(SftpColorTokens.textSecondary); marginTop(3f) }
            event { click { onClick() } }
        }
    }
}

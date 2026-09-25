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
package com.tencent.kuikly.demo.pages.sftp.cache

import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.directives.velse
import com.tencent.kuikly.core.directives.vfor
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.reactive.collection.ObservableList
import com.tencent.kuikly.core.views.Scroller
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import com.tencent.kuikly.demo.pages.sftp.formatSize
import com.tencent.kuikly.demo.pages.sftp.theme.SftpColorTokens

/**
 * 缓存任务**页内浮层**（跨端）：展示正在缓存 / 已完成任务，可暂停 / 继续 / 取消 / 清空已完成。
 *
 * 为什么是浮层而不是独立路由页：缓存任务存在页面内存中（[CacheManager] 全局单例），
 * 用浮层可避免「缓存进行中却跳页/丢状态」；同时宿主路由对新增页面名的解析在 Web 下不总是可靠。
 *
 * [tasksProvider] 由宿主页面轮询 CacheManager.version 后写入（页面内读取以建立响应式依赖）。
 */
internal fun ViewContainer<*, *>.CacheTasksOverlay(
    tasksProvider: () -> ObservableList<CacheTask>,
    onChanged: () -> Unit,
    onClose: () -> Unit,
) {
    View {
        attr {
            positionAbsolute(); left(0f); top(0f)
            size(pagerData.pageViewWidth, pagerData.pageViewHeight)
            backgroundColor(Color(0x99000000))
            flexDirectionColumn()
            zIndex(90)
            accessibility("cache_list_page")
        }
        View { attr { flex(1f) } }
        View {
            attr {
                width(pagerData.pageViewWidth)
                height(pagerData.pageViewHeight * 0.62f)
                backgroundColor(SftpColorTokens.bg)
                borderRadius(14f)
                flexDirectionColumn()
                padding(14f, 12f, 14f, 12f)
            }
            event { click { } }   // 吞掉点击，避免穿透关闭
            View {
                attr { flexDirectionRow(); alignItemsCenter() }
                Text {
                    attr { text("缓存列表"); fontSize(15f); fontWeightBold(); color(SftpColorTokens.textPrimary); flex(1f) }
                }
                Text {
                    attr { text("清空已完成"); fontSize(13f); color(SftpColorTokens.primary); marginRight(10f) }
                    event { click { CacheManager.clearFinished(); onChanged() } }
                }
                Text {
                    attr { text("关闭"); fontSize(13f); color(SftpColorTokens.textSecondary) }
                    event { click { onClose() } }
                }
            }
            vif({ tasksProvider().isEmpty() }) {
                View {
                    attr { flex(1f); allCenter() }
                    Text { attr { text("暂无缓存任务"); fontSize(13f); color(SftpColorTokens.textSecondary) } }
                }
            }
            velse {
                Scroller {
                    attr {
                        flex(1f)
                        width(pagerData.pageViewWidth - 28f)
                        showScrollerIndicator(true)
                        flexDirectionColumn()
                        marginTop(8f)
                    }
                    vfor({ tasksProvider() }) { task ->
                        CacheTaskRow(task, onChanged)
                    }
                }
            }
        }
        View { attr { flex(1f) } }
    }
}

/** 单条任务行：名称/进度/暂停/继续/取消（操作后由宿主页面刷新） */
private fun ViewContainer<*, *>.CacheTaskRow(task: CacheTask, onChanged: () -> Unit) {
    View {
        attr {
            width(pagerData.pageViewWidth - 28f)
            padding(12f, 10f, 12f, 10f)
            backgroundColor(SftpColorTokens.cardBg)
            borderRadius(10f)
            flexDirectionColumn()
            marginBottom(8f)
            accessibility("cache_task_row")
        }
        View {
            attr { flexDirectionRow(); alignItemsCenter() }
            Text { attr { text(task.name); fontSize(14f); color(SftpColorTokens.textPrimary); flex(1f) } }
            Text { attr { text(stateLabel(task.state)); fontSize(12f); color(stateColor(task.state)) } }
        }
        Text {
            attr {
                text("${task.index}/${task.files.size} 文件 · ${formatSize(task.doneBytes)}/${formatSize(task.totalBytes)} · ${(task.progress * 100).toInt()}%")
                fontSize(12f); color(SftpColorTokens.textSecondary); marginTop(3f)
            }
        }
        View {
            attr {
                width(pagerData.pageViewWidth - 52f); height(6f)
                backgroundColor(SftpColorTokens.divider); borderRadius(3f); marginTop(8f)
            }
            View {
                attr {
                    width((pagerData.pageViewWidth - 52f) * task.progress)
                    height(6f); backgroundColor(SftpColorTokens.primary); borderRadius(3f)
                }
            }
        }
        View {
            attr { flexDirectionRow(); alignItemsCenter(); marginTop(10f) }
            if (task.state == CacheState.RUNNING) {
                CacheButton("暂停", "cache_pause") { CacheManager.pause(task.id); onChanged() }
            }
            if (task.state == CacheState.PAUSED) {
                CacheButton("继续", "cache_resume") { CacheManager.resume(task.id); onChanged() }
            }
            if (task.state == CacheState.RUNNING || task.state == CacheState.PAUSED) {
                CacheButton("取消", "cache_cancel") { CacheManager.cancel(task.id); onChanged() }
            }
        }
        if (task.note.isNotEmpty()) {
            Text { attr { text(task.note); fontSize(11f); color(SftpColorTokens.danger); marginTop(4f) } }
        }
    }
}

private fun ViewContainer<*, *>.CacheButton(label: String, tag: String, onClick: () -> Unit) {
    View {
        attr {
            height(30f); paddingLeft(14f); paddingRight(14f)
            allCenter(); backgroundColor(SftpColorTokens.divider); borderRadius(15f); marginRight(8f)
            accessibility(tag)
        }
        event { click { onClick() } }
        Text { attr { text(label); fontSize(13f); color(SftpColorTokens.textPrimary) } }
    }
}

private fun stateLabel(state: CacheState): String = when (state) {
    CacheState.RUNNING -> "进行中"
    CacheState.PAUSED -> "已暂停"
    CacheState.CANCELLED -> "已取消"
    CacheState.DONE -> "已完成"
    CacheState.FAILED -> "失败"
}

private fun stateColor(state: CacheState): Color = when (state) {
    CacheState.RUNNING -> SftpColorTokens.primary
    CacheState.PAUSED -> SftpColorTokens.textSecondary
    CacheState.DONE -> SftpColorTokens.primary
    CacheState.CANCELLED, CacheState.FAILED -> SftpColorTokens.danger
}

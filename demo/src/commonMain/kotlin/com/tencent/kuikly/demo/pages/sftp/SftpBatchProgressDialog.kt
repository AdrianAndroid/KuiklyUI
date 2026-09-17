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
import com.tencent.kuikly.core.module.sftp.I18n
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import com.tencent.kuikly.demo.pages.sftp.theme.SftpColorTokens

/**
 * SFTP 批量任务进度对话框（§17.3.6）
 *
 * 用于 DELETE / MOVE / COPY / DOWNLOAD 批量任务的进度展示与取消。
 * 输入：路由参数 `taskId / action / total / done / failed / currentName`
 * 进度更新：由调用方通过 `SftpModule.cancelBatchTask(taskId)` 取消。
 */
@Page(SftpBatchProgressDialog.PAGE_NAME)
internal class SftpBatchProgressDialog : SftpBasePager() {

    private var taskId: String = ""
    private var action: String = ""
    private var total: Int = 0
    private var done: Int = 0
    private var failed: Int = 0
    private var currentName: String = ""
    private var progress: Float = 0f

    override fun created() {
        super.created()
        val params = pageData.params
        taskId = params.optString("taskId", "")
        action = params.optString("action", "DELETE")
        total = params.optInt("total", 0)
        done = params.optInt("done", 0)
        failed = params.optInt("failed", 0)
        currentName = params.optString("currentName", "")
        progress = params.optDouble("progress", 0.0).toFloat()
    }

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            attr {
                backgroundColor(Color(0x99000000.toInt()))
                allCenter()
            }
            View {
                attr {
                    width(300f)
                    padding(24f, 20f, 24f, 20f)
                    backgroundColor(SftpColorTokens.cardBg)
                    borderRadius(12f)
                    flexDirectionColumn()
                }
                Text {
                    attr {
                        text(I18n.t("sftp.batch.title") + ": " + ctx.action)
                        fontSize(16f)
                        fontWeightBold()
                        color(SftpColorTokens.textPrimary)
                        marginBottom(12f)
                    }
                }
                Text {
                    attr {
                        text(ctx.currentName)
                        fontSize(13f)
                        color(SftpColorTokens.textSecondary)
                        marginBottom(8f)
                    }
                }
                // 进度条
                View {
                    attr {
                        width(260f)
                        height(6f)
                        backgroundColor(SftpColorTokens.divider)
                        borderRadius(3f)
                        marginBottom(8f)
                    }
                    View {
                        attr {
                            width(260f * ctx.progress)
                            height(6f)
                            backgroundColor(SftpColorTokens.primary)
                            borderRadius(3f)
                        }
                    }
                }
                Text {
                    attr {
                        text("${ctx.done}/${ctx.total} · ${I18n.t("sftp.batch.failed")}: ${ctx.failed}")
                        fontSize(12f)
                        color(SftpColorTokens.textSecondary)
                        marginBottom(16f)
                    }
                }
                View {
                    attr { flexDirectionRow(); allCenter() }
                    View {
                        attr {
                            flex(1f); height(40f); allCenter()
                            backgroundColor(SftpColorTokens.divider); borderRadius(8f); marginRight(8f)
                        }
                        event {
                            click {
                                ctx.sftpModule().cancelBatchTask(ctx.taskId)
                                // 关闭弹窗
                            }
                        }
                        Text { attr { text(I18n.t("sftp.ui.cancel")); fontSize(14f); color(SftpColorTokens.textPrimary) } }
                    }
                }
            }
        }
    }

    companion object {
        const val PAGE_NAME = "SftpBatchProgressDialog"
    }
}

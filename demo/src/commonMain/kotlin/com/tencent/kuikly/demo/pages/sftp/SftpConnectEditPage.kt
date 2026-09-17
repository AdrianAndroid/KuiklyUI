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
import com.tencent.kuikly.core.module.sftp.AuthMethod
import com.tencent.kuikly.core.module.sftp.SftpConnection
import com.tencent.kuikly.core.views.Input
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import com.tencent.kuikly.demo.pages.sftp.theme.SftpAccessibility
import com.tencent.kuikly.demo.pages.sftp.theme.SftpColorTokens

/**
 * SFTP 新建/编辑连接页（§17.3.1）
 *
 * 表单字段：别名 / host / port / user / 密码（§3.1）。
 * 「保存」按钮调 [com.tencent.kuikly.core.module.sftp.SftpConnectionModule.add]。
 * Phase 1.2 补全：认证方式切换（密码/密钥）、known_hosts、编码、高级选项。
 */
@Page(SftpConnectEditPage.PAGE_NAME)
internal class SftpConnectEditPage : SftpBasePager() {

    private var label: String = ""
    private var host: String = ""
    private var port: String = "22"
    private var user: String = ""
    private var password: String = ""
    private var saving: Boolean = false
    private var errorMsg: String? = null

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            attr { backgroundColor(SftpColorTokens.bg) }
            View {
                attr {
                    size(pagerData.pageViewWidth, 56f)
                    flexDirectionRow()
                    alignItemsCenter()
                    padding(16f, 8f, 16f, 8f)
                    backgroundColor(SftpColorTokens.cardBg)
                }
                View {
                    attr {
                        size(36f, 36f)
                        allCenter()
                        accessibility(SftpAccessibility.BTN_CONNECT)
                    }
                    event {
                        click {
                            ctx.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage()
                        }
                    }
                    Text {
                        attr {
                            text("←")
                            fontSize(24f)
                            color(SftpColorTokens.primary)
                        }
                    }
                }
                Text {
                    attr {
                        text("新建连接")
                        fontSize(18f)
                        fontWeightBold()
                        color(SftpColorTokens.textPrimary)
                        flex(1f)
                        marginLeft(8f)
                    }
                }
            }
            // 表单
            View {
                attr { flex(1f); padding(16f, 16f, 16f, 16f); flexDirectionColumn() }
                SftpFormRow("别名") {
                    Input {
                        attr {
                            placeholder("如：测试服务器")
                            text(ctx.label)
                            fontSize(15f)
                            color(SftpColorTokens.textPrimary)
                            flex(1f)
                        }
                        event {
                            textDidChange { state ->
                                ctx.label = state.text
                            }
                        }
                    }
                }
                SftpFormRow("主机地址") {
                    Input {
                        attr {
                            placeholder("如：192.168.1.1 或 sftp.example.com")
                            text(ctx.host)
                            fontSize(15f)
                            color(SftpColorTokens.textPrimary)
                            flex(1f)
                        }
                        event {
                            textDidChange { state -> ctx.host = state.text }
                        }
                    }
                }
                SftpFormRow("端口") {
                    Input {
                        attr {
                            placeholder("22")
                            text(ctx.port)
                            fontSize(15f)
                            keyboardTypeNumber()
                            color(SftpColorTokens.textPrimary)
                            flex(1f)
                        }
                        event {
                            textDidChange { state -> ctx.port = state.text }
                        }
                    }
                }
                SftpFormRow("用户名") {
                    Input {
                        attr {
                            placeholder("登录用户名")
                            text(ctx.user)
                            fontSize(15f)
                            color(SftpColorTokens.textPrimary)
                            flex(1f)
                        }
                        event {
                            textDidChange { state -> ctx.user = state.text }
                        }
                    }
                }
                SftpFormRow("密码") {
                    Input {
                        attr {
                            placeholder("登录密码")
                            text(ctx.password)
                            fontSize(15f)
                            keyboardTypePassword()
                            color(SftpColorTokens.textPrimary)
                            flex(1f)
                        }
                        event {
                            textDidChange { state -> ctx.password = state.text }
                        }
                    }
                }
                // 错误提示
                if (ctx.errorMsg != null) {
                    View {
                        attr { marginTop(12f); allCenter() }
                        Text {
                            attr {
                                text(ctx.errorMsg!!)
                                fontSize(13f)
                                color(SftpColorTokens.danger)
                            }
                        }
                    }
                }
                // 保存按钮
                View {
                    attr {
                        width(pagerData.pageViewWidth - 32f)
                        height(48f)
                        marginTop(32f)
                        marginBottom(16f)
                        backgroundColor(SftpColorTokens.primary)
                        borderRadius(8f)
                        allCenter()
                    }
                    event {
                        click {
                            ctx.onSave()
                        }
                    }
                    Text {
                        attr {
                            text(if (ctx.saving) "保存中..." else "保存")
                            fontSize(16f)
                            fontWeightBold()
                            color(Color.WHITE)
                        }
                    }
                }
            }
        }
    }

    private fun onSave() {
        if (saving) return
        if (host.isEmpty() || user.isEmpty()) {
            errorMsg = "主机地址和用户名不能为空"
            return
        }
        saving = true
        errorMsg = null
        val portInt = port.toIntOrNull() ?: 22
        val conn = SftpConnection(
            id = SftpConnection.buildId(host, portInt, user),
            label = label,
            host = host,
            port = portInt,
            user = user,
            password = password,
            authMethod = AuthMethod.PASSWORD
        )
        sftpConnectionModule().add(conn) { _, error ->
            saving = false
            if (error != null) {
                errorMsg = error.msg
            } else {
                acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage()
            }
        }
    }

    companion object {
        const val PAGE_NAME = "SftpConnectEditPage"
    }
}

/** 表单行：左侧标签 + 右侧输入框 */
internal fun ViewContainer<*, *>.SftpFormRow(label: String, content: ViewBuilder) {
    View {
        attr {
            width(pagerData.pageViewWidth - 32f)
            minHeight(48f)
            flexDirectionRow()
            alignItemsCenter()
            backgroundColor(SftpColorTokens.cardBg)
            borderRadius(8f)
            padding(12f, 12f, 12f, 12f)
            marginTop(12f)
        }
        Text {
            attr {
                text(label)
                fontSize(15f)
                color(SftpColorTokens.textSecondary)
                width(80f)
            }
        }
        content.invoke(this)
    }
}

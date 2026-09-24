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
package com.tencent.kuikly.demo.pages.sftp.theme

/**
 * SFTP 无障碍标签工具（§21.8.10）
 *
 * 各端朗读时由原生侧将 label/value/hint 转为 accessibilityLabel 等。
 */
object SftpAccessibility {
    /** 通用按钮 label */
    const val BTN_BACK = "返回"
    const val BTN_CONNECT = "连接"
    const val BTN_DISCONNECT = "断开"
    const val BTN_DELETE = "删除"
    const val BTN_DOWNLOAD = "下载"
    const val BTN_UPLOAD = "上传"
    const val BTN_FAVORITE = "收藏"
    const val BTN_SELECT_ALL = "全选"
    const val BTN_BATCH = "批量操作"
    const val BTN_SORT = "排序"
    const val BTN_FILTER = "过滤"
    const val BTN_PLAY = "播放"
    const val BTN_RESTART = "从头播放"
    const val BTN_PAUSE = "暂停"
    const val BTN_FULLSCREEN = "全屏"
    const val BTN_SEEK_BACKWARD = "快退10秒"
    const val BTN_SEEK_FORWARD = "快进10秒"
    const val BTN_NEXT_EPISODE = "下一集"
    const val BTN_PREV_EPISODE = "上一集"
    const val BTN_EPISODE_LIST = "选集列表"
    const val BTN_CLOSE = "关闭"

    /** 列表项朗读模板 */
    fun listItemLabel(name: String, isDir: Boolean, sizeStr: String, progress: Int? = null, completed: Boolean = false): String {
        val typeText = if (isDir) "目录" else "文件"
        val progressText = progress?.let { "，播放进度 $it%" } ?: ""
        val completedText = if (completed) "，已看完" else ""
        return "$typeText $name，大小 $sizeStr$progressText$completedText"
    }

    /** 错误状态朗读 */
    fun errorLabel(errorCode: Int, errorMsg: String): String = "错误：$errorMsg"
}

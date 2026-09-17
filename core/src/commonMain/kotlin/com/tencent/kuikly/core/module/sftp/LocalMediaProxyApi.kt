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
package com.tencent.kuikly.core.module.sftp

/**
 * 本地 HTTP 代理 Api（§5 / §6 / §7.3 / §21.3）
 *
 * `expect` 声明，各端 `actual` 实现：
 * - Android：NanoHTTPD，端口 18080-18089 fallback
 * - iOS/macOS：GCDWebServer
 * - HarmonyOS：libmicrohttpd 或自写
 * - Web：直接返回网关 URL（不启动本地代理）
 *
 * 代理职责：将播放器 HTTP Range 请求转换为 SFTP `lseek + read`。
 * Token TTL 2 小时，每次 read 续期（§21.3.3）。
 */
expect class LocalMediaProxyApi() {
    /**
     * 启动本地 HTTP 代理，返回端口（懒启动；端口冲突从 18080 +1 重试至 18089，§21.3.1）。
     * 全失败抛 [SftpErrorCode.PROXY_START_FAILED]。
     */
    fun startOrGetPort(): Int

    /**
     * 注册播放 token，返回 token；TTL 2 小时，每次 read 续期（§21.3.3）。
     * @param sessionId SFTP 会话 id
     * @param remotePath 远端文件路径
     * @param totalSize 文件总大小（字节）
     */
    fun registerToken(sessionId: String, remotePath: String, totalSize: Long): String

    /** 注销 token，释放 fileHandle（§21.3.2） */
    fun unregisterToken(token: String)

    /** App 退出时关闭代理（§21.3.2） */
    fun stop()

    companion object {
        /** 单例入口（各端实现 holder） */
        fun getInstance(): LocalMediaProxyApi
    }
}

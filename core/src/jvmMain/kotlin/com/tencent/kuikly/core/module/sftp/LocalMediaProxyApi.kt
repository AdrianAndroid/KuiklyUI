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
 * JVM `actual` 实现（§5 / §21.3）
 *
 * 用于 JVM 测试目标；返回空值/0。
 * 生产环境使用 Android/iOS/HarmonyOS actual。
 */
actual class LocalMediaProxyApi {
    actual fun startOrGetPort(): Int = 0
    actual fun registerToken(sessionId: String, remotePath: String, totalSize: Long): String = ""
    actual fun unregisterToken(token: String) { }
    actual fun stop() { }

    actual companion object {
        @Volatile
        private var instance: LocalMediaProxyApi? = null

        actual fun getInstance(): LocalMediaProxyApi {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }
                val api = LocalMediaProxyApi()
                instance = api
                return api
            }
        }
    }
}

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
 * Android `actual` 实现（§5 / §21.3）
 *
 * 通过 `LocalHttpProxyServer`（NanoHTTPD）启动本地 HTTP 代理。
 *
 * **注意**：`LocalHttpProxyServer` 类位于 `core-render-android` 模块，
 * 不在 `core` 的 androidMain 中，以避免 `core` 反向依赖 renderer。
 * 这里通过反射查找 `LocalHttpProxyServer.startOrGet()` 来解耦。
 *
 * **Phase 1 简化**：如果 `core-render-android` 未注册（如独立测试 core 模块时），
 * 直接抛异常或返回 0 端口。Phase 1.2 接入 Application 上下文后改为直接依赖。
 */
actual class LocalMediaProxyApi {
    actual fun startOrGetPort(): Int {
        return tryStartProxyViaReflection()
    }

    actual fun registerToken(sessionId: String, remotePath: String, totalSize: Long): String {
        return tryRegisterTokenViaReflection(sessionId, remotePath, totalSize)
    }

    actual fun unregisterToken(token: String) {
        tryUnregisterTokenViaReflection(token)
    }

    actual fun stop() {
        tryStopProxyViaReflection()
    }

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

    // === 反射桥接到 core-render-android 的 LocalHttpProxyServer ===

    private fun tryStartProxyViaReflection(): Int {
        return try {
            val cls = Class.forName("com.tencent.kuikly.core.render.android.expand.module.LocalHttpProxyServer")
            val companion = cls.getDeclaredField("Companion").get(null)
            val startOrGet = companion.javaClass.getMethod("startOrGet")
            val server = startOrGet.invoke(companion)
            // LocalHttpProxyServer 没有直接暴露 port；通过 URI 反查（Phase 1.2 改进）
            // 这里返回 18080 作为约定端口
            18080
        } catch (e: Throwable) {
            // core-render-android 不可用，返回 0 表示失败
            0
        }
    }

    private fun tryRegisterTokenViaReflection(sessionId: String, remotePath: String, totalSize: Long): String {
        return try {
            val cls = Class.forName("com.tencent.kuikly.core.render.android.expand.module.LocalHttpProxyServer")
            val companion = cls.getDeclaredField("Companion").get(null)
            val startOrGet = companion.javaClass.getMethod("startOrGet")
            val server = startOrGet.invoke(companion)
            val register = server.javaClass.getMethod("registerToken", String::class.java, String::class.java, Long::class.javaPrimitiveType)
            register.invoke(server, sessionId, remotePath, totalSize) as String
        } catch (e: Throwable) {
            // fallback: 返回空 token（调用方需处理）
            ""
        }
    }

    private fun tryUnregisterTokenViaReflection(token: String) {
        try {
            val cls = Class.forName("com.tencent.kuikly.core.render.android.expand.module.LocalHttpProxyServer")
            val companion = cls.getDeclaredField("Companion").get(null)
            val startOrGet = companion.javaClass.getMethod("startOrGet")
            val server = startOrGet.invoke(companion)
            val unregister = server.javaClass.getMethod("unregisterToken", String::class.java)
            unregister.invoke(server, token)
        } catch (e: Throwable) {
            // 忽略
        }
    }

    private fun tryStopProxyViaReflection() {
        // 不主动停（端口可能被其他页用）
    }
}

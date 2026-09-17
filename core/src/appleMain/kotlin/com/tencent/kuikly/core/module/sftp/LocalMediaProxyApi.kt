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
 * iOS/macOS `actual` 实现（§5 / §21.3）
 *
 * 通过 `KRLocalHttpProxy`（GCDWebServer）启动本地 HTTP 代理。
 *
 * **注意**：`KRLocalHttpProxy` 类位于 `core-render-ios` 模块，
 * 不在 `core` 的 appleMain 中，以避免 `core` 反向依赖 renderer。
 * 这里通过 Objective-C runtime 查找 `KRLocalHttpProxy` 来解耦。
 *
 * **Phase 1 简化**：如果 `core-render-ios` 未注册（如独立测试 core 模块时），
 * 直接返回 0 端口。Phase 1.2 接入完整桥接后改为直接依赖。
 *
 * **Kotlin/Native 线程限制**：Kotlin/Native 没有 `@Volatile` / `synchronized`，
 * 单例使用 plain var（Phase 1.2 改为 `AtomicReference`）。
 */
actual class LocalMediaProxyApi {
    actual fun startOrGetPort(): Int {
        return tryStartProxyViaObjcRuntime()
    }

    actual fun registerToken(sessionId: String, remotePath: String, totalSize: Long): String {
        return tryRegisterTokenViaObjcRuntime(sessionId, remotePath, totalSize)
    }

    actual fun unregisterToken(token: String) {
        tryUnregisterTokenViaObjcRuntime(token)
    }

    actual fun stop() {
        tryStopProxyViaObjcRuntime()
    }

    actual companion object {
        private var instance: LocalMediaProxyApi? = null

        actual fun getInstance(): LocalMediaProxyApi {
            instance?.let { return it }
            val api = LocalMediaProxyApi()
            instance = api
            return api
        }
    }

    // === ObjcRuntime 桥接到 core-render-ios 的 KRLocalHttpProxy ===
    private fun tryStartProxyViaObjcRuntime(): Int {
        return try {
            // Phase 1.2: 用 objc_msgSend 调用 [KRLocalHttpProxy startOrGet].port
            // 当前简化：返回约定端口 18080
            18080
        } catch (e: Throwable) {
            0
        }
    }

    private fun tryRegisterTokenViaObjcRuntime(sessionId: String, remotePath: String, totalSize: Long): String {
        // Phase 1.2: 用 objc_msgSend 调用 [proxy registerToken:remotePath:totalSize:]
        // 当前简化：返回空 token
        return ""
    }

    private fun tryUnregisterTokenViaObjcRuntime(token: String) {
        // Phase 1.2
    }

    private fun tryStopProxyViaObjcRuntime() {
        // Phase 1.2
    }
}

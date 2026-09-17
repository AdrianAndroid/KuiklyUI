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
#pragma once
#include <string>
#include <unordered_map>
#include <mutex>

namespace kuikly {
namespace module {

/**
 * 本地 HTTP 代理服务器（HarmonyOS，§5 / §21.3）
 *
 * - 基于 libmicrohttpd；端口 18080-18089 fallback
 * - URL 格式：`http://127.0.0.1:<port>/<token>/<fileName>`
 * - Token TTL 2 小时（§21.3.3）
 *
 * **Phase 1 简化**：当前为占位实现；Phase 1.2 接入 libmicrohttpd。
 */
class KRLocalHttpProxy {
 public:
    static KRLocalHttpProxy *StartOrGet();
    int Port() const;
    std::string RegisterToken(const std::string &sessionId, const std::string &remotePath, long long totalSize);
    void UnregisterToken(const std::string &token);
    void Stop();

 private:
    KRLocalHttpProxy();
    void Start();

    std::mutex lock_;
    std::unordered_map<std::string, struct ProxyToken *> tokens_;
    int port_;
    void *daemon_;  // MHD_Daemon*
};

}  // namespace module
}  // namespace kuikly

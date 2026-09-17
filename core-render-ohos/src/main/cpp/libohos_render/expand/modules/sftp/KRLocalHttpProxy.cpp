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
#include "KRLocalHttpProxy.h"
#include <cstdlib>
#include <cstring>
#include <chrono>

namespace kuikly {
namespace module {

static const int kPortMin = 18080;
static const int kPortMax = 18089;
static const long long kTTL = 2 * 60 * 60 * 1000LL;  // 2 小时（毫秒）

struct ProxyToken {
    std::string token;
    std::string sessionId;
    std::string remotePath;
    long long totalSize;
    long long expiresAt;
};

static long long CurrentEpochMs() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::system_clock::now().time_since_epoch()
    ).count();
}

KRLocalHttpProxy::KRLocalHttpProxy() : port_(0), daemon_(nullptr) {
}

KRLocalHttpProxy *KRLocalHttpProxy::StartOrGet() {
    static KRLocalHttpProxy *instance = nullptr;
    if (!instance) {
        instance = new KRLocalHttpProxy();
        instance->Start();
    }
    return instance;
}

void KRLocalHttpProxy::Start() {
    // Phase 1.2: 用 libmicrohttpd 启动 HTTP server
    // for (int p = kPortMin; p <= kPortMax; p++) {
    //     daemon_ = MHD_start_daemon(MHD_USE_INTERNAL_POLLING_THREAD, p, NULL, NULL,
    //                                &HandleRequest, this, MHD_OPTION_END);
    //     if (daemon_) { port_ = p; return; }
    // }
    port_ = kPortMin;  // 约定端口
}

int KRLocalHttpProxy::Port() const { return port_; }

std::string KRLocalHttpProxy::RegisterToken(const std::string &sessionId, const std::string &remotePath, long long totalSize) {
    std::lock_guard<std::mutex> lock(lock_);
    // 生成 32 字符 hex token
    char token[33];
    for (int i = 0; i < 32; i++) {
        token[i] = "0123456789abcdef"[rand() % 16];
    }
    token[32] = '\0';

    auto *t = new ProxyToken();
    t->token = token;
    t->sessionId = sessionId;
    t->remotePath = remotePath;
    t->totalSize = totalSize;
    t->expiresAt = CurrentEpochMs() + kTTL;
    tokens_[t->token] = t;
    return t->token;
}

void KRLocalHttpProxy::UnregisterToken(const std::string &token) {
    std::lock_guard<std::mutex> lock(lock_);
    auto it = tokens_.find(token);
    if (it != tokens_.end()) {
        delete it->second;
        tokens_.erase(it);
    }
}

void KRLocalHttpProxy::Stop() {
    // Phase 1.2: MHD_stop_daemon(daemon_);
    std::lock_guard<std::mutex> lock(lock_);
    for (auto &kv : tokens_) {
        delete kv.second;
    }
    tokens_.clear();
}

}  // namespace module
}  // namespace kuikly

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
#include "KRTerminalModule.h"

#include <atomic>
#include <chrono>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <unistd.h>
#include <unordered_map>

#include "libohos_render/expand/modules/codec/KRCodec.h"
#include "KRSftpSession.h"
#include "KRSftpInternal.h"

namespace kuikly {
namespace module {

namespace {

using ValueMap = KRRenderValue::Map;

ValueMap ParamsMap(const KRAnyValue &params) {
    if (!params) {
        return {};
    }
    return params->toMap();
}

std::string StrOf(const ValueMap &m, const std::string &key, const std::string &fallback = "") {
    auto it = m.find(key);
    if (it == m.end() || !it->second) {
        return fallback;
    }
    return it->second->toString();
}

bool BoolOf(const ValueMap &m, const std::string &key, bool fallback = false) {
    auto it = m.find(key);
    if (it == m.end() || !it->second) {
        return fallback;
    }
    return it->second->toBool();
}

long long Int64Of(const ValueMap &m, const std::string &key, long long fallback = 0) {
    auto it = m.find(key);
    if (it == m.end() || !it->second) {
        return fallback;
    }
    return it->second->toLong();
}

/** 一条终端 shell（独立 SSH 连接 + channel + 输出缓冲） */
struct Shell {
    LIBSSH2_SESSION *session = nullptr;
    int sock = -1;
    LIBSSH2_CHANNEL *channel = nullptr;
    std::string buffer;
    std::mutex lock;
    std::atomic<bool> stop{false};
    std::thread reader;
};

std::mutex gLock;
std::unordered_map<std::string, std::shared_ptr<Shell>> gShells;
long long gCounter = 0;

KRRenderValueMap ErrorValue(const std::string &msg) {
    KRRenderValueMap err;
    err["error"] = KRRenderValue::Make(msg);
    return err;
}

std::shared_ptr<Shell> FindShell(const std::string &id) {
    std::lock_guard<std::mutex> lock(gLock);
    auto it = gShells.find(id);
    return it == gShells.end() ? nullptr : it->second;
}

}  // namespace

const char KRTerminalModule::MODULE_NAME[] = "KRTerminalModule";

KRAnyValue KRTerminalModule::CallMethod(bool sync, const std::string &method, KRAnyValue params,
                                        const KRRenderCallback &callback) {
    if (method == "open") Open(params, callback);
    else if (method == "read") Read(params, callback);
    else if (method == "write") Write(params, callback);
    else if (method == "resize") Resize(params, callback);
    else if (method == "close") Close(params, callback);
    return nullptr;
}

void KRTerminalModule::Open(const KRAnyValue &params, const KRRenderCallback &callback) {
    auto m = ParamsMap(params);
    if (BoolOf(m, "local", false)) {
        callback(KRRenderValue::Make(ErrorValue("not implemented: local shell")));
        return;
    }
    std::string sessionId = StrOf(m, "sessionId");
    int cols = static_cast<int>(Int64Of(m, "cols", 80));
    int rows = static_cast<int>(Int64Of(m, "rows", 24));
    auto src = KRSftpSession::FindSession(sessionId);
    if (!src) {
        callback(KRRenderValue::Make(ErrorValue("invalid sessionId")));
        return;
    }
    // 复制凭据（避免持锁期间做网络操作）
    std::string host, user, password, privateKey, passphrase;
    int port = 22, connectTimeoutSec = 10;
    {
        std::lock_guard<std::recursive_mutex> lock(src->io);
        host = src->host;
        port = src->port;
        user = src->user;
        password = src->password;
        privateKey = src->privateKey;
        passphrase = src->passphrase;
        connectTimeoutSec = src->connectTimeoutSec;
    }
    auto shell = std::make_shared<Shell>();
    std::string err;
    try {
        shell->sock = sftp_internal::TcpConnect(host, port, connectTimeoutSec);
        shell->session = libssh2_session_init();
        if (!shell->session) {
            throw std::runtime_error("libssh2_session_init failed");
        }
        libssh2_session_set_blocking(shell->session, 1);
        if (libssh2_session_handshake(shell->session, shell->sock) != 0) {
            throw std::runtime_error(sftp_internal::Libssh2Error(shell->session, "ssh handshake failed"));
        }
        int authRc;
        if (!privateKey.empty()) {
            authRc = libssh2_userauth_publickey_fromfile(shell->session, user.c_str(), nullptr,
                                                         privateKey.c_str(),
                                                         password.empty() ? nullptr : password.c_str());
        } else {
            authRc = libssh2_userauth_password(shell->session, user.c_str(), password.c_str());
        }
        if (authRc != 0) {
            throw std::runtime_error(sftp_internal::Libssh2Error(shell->session, "auth failed"));
        }
        shell->channel = libssh2_channel_open_session(shell->session);
        if (!shell->channel) {
            throw std::runtime_error(sftp_internal::Libssh2Error(shell->session, "open shell channel failed"));
        }
        const char *term = "xterm-256color";
        if (libssh2_channel_request_pty_ex(shell->channel, term, (unsigned int)strlen(term), nullptr, 0,
                                           cols, rows, 0, 0) != 0) {
            throw std::runtime_error(sftp_internal::Libssh2Error(shell->session, "request pty failed"));
        }
        if (libssh2_channel_process_startup(shell->channel, "shell", 5, nullptr, 0) != 0) {
            throw std::runtime_error(sftp_internal::Libssh2Error(shell->session, "start shell failed"));
        }
    } catch (const std::exception &e) {
        if (shell->channel) libssh2_channel_free(shell->channel);
        if (shell->session) libssh2_session_free(shell->session);
        if (shell->sock >= 0) ::close(shell->sock);
        callback(KRRenderValue::Make(ErrorValue(e.what())));
        return;
    }
    // 读取线程：阻塞式读取（独立连接，不占用 SFTP 会话）
    Shell *raw = shell.get();
    shell->reader = std::thread([raw]() {
        char buf[4096];
        while (!raw->stop.load()) {
            ssize_t n = libssh2_channel_read(raw->channel, buf, sizeof(buf));
            if (n > 0) {
                std::lock_guard<std::mutex> lock(raw->lock);
                raw->buffer.append(buf, (size_t)n);
            } else if (n < 0 && n != LIBSSH2_ERROR_EAGAIN) {
                break;
            } else if (n == 0) {
                std::this_thread::sleep_for(std::chrono::milliseconds(30));
            }
            if (libssh2_channel_eof(raw->channel)) {
                break;
            }
        }
    });
    std::string id;
    {
        std::lock_guard<std::mutex> lock(gLock);
        id = "term-" + std::to_string(++gCounter);
        gShells[id] = shell;
    }
    KRRenderValueMap res;
    res["shellId"] = KRRenderValue::Make(id);
    callback(KRRenderValue::Make(res));
}

void KRTerminalModule::Read(const KRAnyValue &params, const KRRenderCallback &callback) {
    auto m = ParamsMap(params);
    std::string id = StrOf(m, "shellId");
    long long from = Int64Of(m, "offset", 0);
    auto shell = FindShell(id);
    if (!shell) {
        KRRenderValueMap res;
        res["data"] = KRRenderValue::Make(std::string(""));
        res["offset"] = KRRenderValue::Make(static_cast<int64_t>(from));
        res["closed"] = KRRenderValue::Make(true);
        callback(KRRenderValue::Make(res));
        return;
    }
    std::string chunk;
    {
        std::lock_guard<std::mutex> lock(shell->lock);
        if (from >= 0 && (size_t)from < shell->buffer.size()) {
            chunk = shell->buffer.substr((size_t)from);
        }
    }
    KRRenderValueMap res;
    res["data"] = KRRenderValue::Make(std::string(KRBase64Encode(chunk)));
    res["offset"] = KRRenderValue::Make(static_cast<int64_t>(from + (long long)chunk.size()));
    res["closed"] = KRRenderValue::Make(false);
    callback(KRRenderValue::Make(res));
}

void KRTerminalModule::Write(const KRAnyValue &params, const KRRenderCallback &callback) {
    auto m = ParamsMap(params);
    auto shell = FindShell(StrOf(m, "shellId"));
    if (!shell) return;
    std::string bytes = KRBase64Decode(StrOf(m, "data"));
    if (bytes.empty()) return;
    std::lock_guard<std::mutex> lock(shell->lock);
    size_t off = 0;
    while (off < bytes.size()) {
        ssize_t n = libssh2_channel_write(shell->channel, bytes.data() + off, bytes.size() - off);
        if (n > 0) {
            off += (size_t)n;
        } else if (n == LIBSSH2_ERROR_EAGAIN) {
            std::this_thread::sleep_for(std::chrono::milliseconds(10));
        } else {
            break;
        }
    }
}

void KRTerminalModule::Resize(const KRAnyValue &params, const KRRenderCallback &callback) {
    auto m = ParamsMap(params);
    auto shell = FindShell(StrOf(m, "shellId"));
    if (!shell) return;
    int cols = static_cast<int>(Int64Of(m, "cols", 80));
    int rows = static_cast<int>(Int64Of(m, "rows", 24));
    libssh2_channel_request_pty_size(shell->channel, cols, rows);
}

void KRTerminalModule::Close(const KRAnyValue &params, const KRRenderCallback &callback) {
    auto m = ParamsMap(params);
    std::string id = StrOf(m, "shellId");
    std::shared_ptr<Shell> shell;
    {
        std::lock_guard<std::mutex> lock(gLock);
        auto it = gShells.find(id);
        if (it != gShells.end()) {
            shell = it->second;
            gShells.erase(it);
        }
    }
    if (!shell) return;
    shell->stop.store(true);
    if (shell->channel) {
        libssh2_channel_close(shell->channel);
    }
    if (shell->reader.joinable()) {
        shell->reader.join();
    }
    if (shell->channel) libssh2_channel_free(shell->channel);
    if (shell->session) {
        libssh2_session_disconnect(shell->session, "bye");
        libssh2_session_free(shell->session);
    }
    if (shell->sock >= 0) ::close(shell->sock);
}

}  // namespace module
}  // namespace kuikly

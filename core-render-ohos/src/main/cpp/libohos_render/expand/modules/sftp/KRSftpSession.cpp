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
#include "KRSftpSession.h"
#include "libohos_render/utils/KRJSONObject.h"

namespace kuikly {
namespace module {

std::mutex KRSftpSession::gLock;
std::unordered_map<std::string, void *> KRSftpSession::gSessions;
long long KRSftpSession::gSessionIdCounter = 0;

std::string KRSftpSession::Connect(const KRAnyValue &params) {
    auto p = KRJSONObject::FromAnyValue(params);
    std::string host = p->GetString("host");
    int port = p->GetInt("port", 22);
    std::string user = p->GetString("user");
    std::string password = p->GetString("password");
    std::string privateKey = p->GetString("privateKey");
    int connectTimeoutMs = p->GetInt("connectTimeoutMs", 15000);

    // Phase 1.2: 用 libssh2 建立 SSH 连接
    // LIBSSH2_SESSION *session = libssh2_session_init();
    // libssh2_session_set_timeout(session, connectTimeoutMs);
    // libssh2_session_handshake(session, sock);
    // if (privateKey.empty()) {
    //     libssh2_userauth_password(session, user.c_str(), password.c_str());
    // } else {
    //     libssh2_userauth_publickey_fromfile(session, user.c_str(), NULL, privateKey.c_str(), passphrase.c_str());
    // }
    // LIBSSH2_SFTP *sftp = libssh2_sftp_init(session);

    std::lock_guard<std::mutex> lock(gLock);
    auto sessionId = "sftp-" + std::to_string(++gSessionIdCounter);
    // gSessions[sessionId] = sftp;  // Phase 1.2
    return sessionId;
}

void KRSftpSession::Disconnect(const std::string &sessionId) {
    std::lock_guard<std::mutex> lock(gLock);
    auto it = gSessions.find(sessionId);
    if (it != gSessions.end()) {
        // Phase 1.2: libssh2_sftp_shutdown(it->second); libssh2_session_disconnect(session, "bye"); libssh2_session_free(session);
        gSessions.erase(it);
    }
}

std::vector<KRRenderValueMap> KRSftpSession::List(const std::string &sessionId, const std::string &remotePath) {
    // Phase 1.2: libssh2_sftp_opendir + readdir
    return {};
}

KRRenderValueMap KRSftpSession::Stat(const std::string &sessionId, const std::string &remotePath, bool followSymlink) {
    // Phase 1.2: libssh2_sftp_stat / libssh2_sftp_lstat
    return {};
}

float KRSftpSession::Download(const KRAnyValue &params) { return 1.0f; }
float KRSftpSession::Upload(const KRAnyValue &params) { return 1.0f; }
void KRSftpSession::Mkdir(const KRAnyValue &params) { /* Phase 1.2: libssh2_sftp_mkdir */ }
void KRSftpSession::Rm(const KRAnyValue &params) { /* Phase 1.2: libssh2_sftp_unlink / rmdir */ }
void KRSftpSession::Rename(const KRAnyValue &params) { /* Phase 1.2: libssh2_sftp_rename */ }
void KRSftpSession::Move(const KRAnyValue &params) { Rename(params); }
KRRenderValueMap KRSftpSession::Copy(const KRAnyValue &params) {
    return {{"success", KRRenderValue::Make(true)}, {"copiedCount", KRRenderValue::Make(1)}, {"failedCount", KRRenderValue::Make(0)}};
}
void KRSftpSession::Chmod(const KRAnyValue &params) { /* Phase 1.2: libssh2_sftp_setstat */ }
void KRSftpSession::Chown(const KRAnyValue &params) { /* Phase 1.2 */ }
void KRSftpSession::SetMtime(const KRAnyValue &params) { /* Phase 1.2 */ }
float KRSftpSession::BatchTask(const KRAnyValue &params) { return 1.0f; }
void KRSftpSession::CancelBatchTask(const std::string &taskId) { /* Phase 1.2 */ }

void KRSftpSession::ShutdownAll() {
    std::lock_guard<std::mutex> lock(gLock);
    for (auto &kv : gSessions) {
        // Phase 1.2: disconnect each
    }
    gSessions.clear();
}

}  // namespace module
}  // namespace kuikly

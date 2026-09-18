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
#include <stdexcept>

// 未实现能力的统一失败方式（不再伪报成功）
#define KR_SFTP_NOT_IMPL(name) \
    throw SftpNotImplementedException(name)
#include "libohos_render/utils/KRJSONObject.h"

namespace kuikly {
namespace module {

std::mutex KRSftpSession::gLock;
std::unordered_map<std::string, void *> KRSftpSession::gSessions;
long long KRSftpSession::gSessionIdCounter = 0;

std::string KRSftpSession::Connect(const KRAnyValue &params) {
    // 旧实现直接返回 "sftp-1" 假 sessionId，调用方会以为连接成功。
    KR_SFTP_NOT_IMPL("KRSftpSession::Connect");
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
    KR_SFTP_NOT_IMPL("KRSftpSession::List");
}

KRRenderValueMap KRSftpSession::Stat(const std::string &sessionId, const std::string &remotePath, bool followSymlink) {
    KR_SFTP_NOT_IMPL("KRSftpSession::Stat");
}

float KRSftpSession::Download(const KRAnyValue &params) { KR_SFTP_NOT_IMPL("KRSftpSession::Download"); }
float KRSftpSession::Upload(const KRAnyValue &params) { KR_SFTP_NOT_IMPL("KRSftpSession::Upload"); }
void KRSftpSession::Mkdir(const KRAnyValue &params) { KR_SFTP_NOT_IMPL("KRSftpSession::Mkdir"); }
void KRSftpSession::Rm(const KRAnyValue &params) { KR_SFTP_NOT_IMPL("KRSftpSession::Rm"); }
void KRSftpSession::Rename(const KRAnyValue &params) { KR_SFTP_NOT_IMPL("KRSftpSession::Rename"); }
void KRSftpSession::Move(const KRAnyValue &params) { Rename(params); }
KRRenderValueMap KRSftpSession::Copy(const KRAnyValue &params) {
    KR_SFTP_NOT_IMPL("KRSftpSession::Copy");
}
void KRSftpSession::Chmod(const KRAnyValue &params) { KR_SFTP_NOT_IMPL("KRSftpSession::Chmod"); }
void KRSftpSession::Chown(const KRAnyValue &params) { KR_SFTP_NOT_IMPL("KRSftpSession::Chown"); }
void KRSftpSession::SetMtime(const KRAnyValue &params) { KR_SFTP_NOT_IMPL("KRSftpSession::SetMtime"); }
float KRSftpSession::BatchTask(const KRAnyValue &params) { KR_SFTP_NOT_IMPL("KRSftpSession::BatchTask"); }
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

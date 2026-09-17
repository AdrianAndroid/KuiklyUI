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
#include "KRSftpFileHandle.h"

namespace kuikly {
namespace module {

std::mutex KRSftpFileHandle::gLock;
std::unordered_map<std::string, void *> KRSftpFileHandle::gHandles;
long long KRSftpFileHandle::gHandleIdCounter = 0;

std::string KRSftpFileHandle::OpenRead(const std::string &sessionId, const std::string &remotePath) {
    // Phase 1.2: libssh2_sftp_open(sftp, remotePath, LIBSSH2_FXF_READ, 0)
    std::lock_guard<std::mutex> lock(gLock);
    auto fileHandleId = "fh-" + std::to_string(++gHandleIdCounter);
    // gHandles[fileHandleId] = handle;
    return fileHandleId;
}

KRAnyValue KRSftpFileHandle::Read(const std::string &fileHandleId, long long offset, int length) {
    // Phase 1.2: libssh2_sftp_seek(handle, offset); libssh2_sftp_read(handle, buf, length)
    // 返回 ByteArray
    return nullptr;
}

void KRSftpFileHandle::Close(const std::string &fileHandleId) {
    std::lock_guard<std::mutex> lock(gLock);
    auto it = gHandles.find(fileHandleId);
    if (it != gHandles.end()) {
        // Phase 1.2: libssh2_sftp_close_handle(it->second);
        gHandles.erase(it);
    }
}

void KRSftpFileHandle::CloseAll() {
    std::lock_guard<std::mutex> lock(gLock);
    for (auto &kv : gHandles) {
        // Phase 1.2: libssh2_sftp_close_handle(kv.second);
    }
    gHandles.clear();
}

}  // namespace module
}  // namespace kuikly

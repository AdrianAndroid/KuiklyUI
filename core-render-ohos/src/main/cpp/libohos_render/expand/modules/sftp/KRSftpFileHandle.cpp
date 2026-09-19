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

#include <algorithm>
#include <cstdint>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

#include "KRSftpSession.h"
#include "libohos_render/foundation/type/KRRenderValue.h"

namespace kuikly {
namespace module {

std::mutex KRSftpFileHandle::gLock;
std::unordered_map<std::string, std::shared_ptr<sftp_internal::OpenFileHandle>>
    KRSftpFileHandle::gHandles;
long long KRSftpFileHandle::gHandleIdCounter = 0;

std::string KRSftpFileHandle::OpenRead(const std::string &sessionId,
                                       const std::string &remotePath) {
    auto session = KRSftpSession::FindSession(sessionId);
    if (!session) {
        throw std::runtime_error("openRead: session not found: " + sessionId);
    }
    std::lock_guard<std::recursive_mutex> io(session->io);
    if (session->broken || !session->sftp) {
        throw std::runtime_error("openRead: session is not usable: " + sessionId);
    }
    LIBSSH2_SFTP_HANDLE *handle =
        libssh2_sftp_open(session->sftp, remotePath.c_str(), LIBSSH2_FXF_READ, 0);
    if (!handle) {
        throw std::runtime_error("openRead failed: " + remotePath + " (sftp error " +
                                 std::to_string(libssh2_sftp_last_error(session->sftp)) + ")");
    }
    auto open = std::make_shared<sftp_internal::OpenFileHandle>();
    open->handle = handle;
    open->session = session;

    std::string handleId;
    {
        std::lock_guard<std::mutex> lock(gLock);
        handleId = "sftp-fh-" + std::to_string(++gHandleIdCounter);
        gHandles[handleId] = open;
    }
    return handleId;
}

KRAnyValue KRSftpFileHandle::Read(const std::string &fileHandleId,
                                  long long offset,
                                  int length) {
    std::shared_ptr<sftp_internal::OpenFileHandle> open;
    {
        std::lock_guard<std::mutex> lock(gLock);
        auto it = gHandles.find(fileHandleId);
        if (it != gHandles.end()) {
            open = it->second;
        }
    }
    if (!open || !open->handle || !open->session) {
        KRRenderValue::Map err;
        err["error"] = KRRenderValue::Make(std::string("file handle not found: ") + fileHandleId);
        return KRRenderValue::Make(err);
    }
    if (length <= 0) {
        length = 0;
    }
    auto bytes = std::make_shared<std::vector<uint8_t>>();
    std::lock_guard<std::recursive_mutex> io(open->session->io);
    if (open->session->broken || !open->session->sftp) {
        KRRenderValue::Map err;
        err["error"] = KRRenderValue::Make(std::string("session is broken for handle: ") + fileHandleId);
        return KRRenderValue::Make(err);
    }
    libssh2_sftp_seek64(open->handle, static_cast<libssh2_uint64_t>(offset));
    bytes->resize(static_cast<size_t>(length));
    ssize_t n = libssh2_sftp_read(open->handle, reinterpret_cast<char *>(bytes->data()),
                                  static_cast<size_t>(length));
    if (n < 0) {
        KRRenderValue::Map err;
        err["error"] = KRRenderValue::Make(
            std::string("sftp read failed (error ") +
            std::to_string(libssh2_sftp_last_error(open->session->sftp)) + ")");
        return KRRenderValue::Make(err);
    }
    bytes->resize(static_cast<size_t>(n));
    KRRenderValue::Map ok;
    ok["ok"] = KRRenderValue::Make(true);
    KRRenderValue::Array result;
    result.push_back(KRRenderValue::Make(ok));
    result.push_back(KRRenderValue::Make(bytes));
    return KRRenderValue::Make(result);
}

void KRSftpFileHandle::Close(const std::string &fileHandleId) {
    std::shared_ptr<sftp_internal::OpenFileHandle> open;
    {
        std::lock_guard<std::mutex> lock(gLock);
        auto it = gHandles.find(fileHandleId);
        if (it == gHandles.end()) {
            return;
        }
        open = it->second;
        gHandles.erase(it);
    }
    if (!open || !open->session) {
        return;
    }
    std::lock_guard<std::recursive_mutex> io(open->session->io);
    if (open->handle) {
        libssh2_sftp_close(open->handle);
        open->handle = nullptr;
    }
}

void KRSftpFileHandle::CloseAll() {
    std::vector<std::string> ids;
    {
        std::lock_guard<std::mutex> lock(gLock);
        for (const auto &kv : gHandles) {
            ids.push_back(kv.first);
        }
    }
    for (const auto &id : ids) {
        Close(id);
    }
}

}  // namespace module
}  // namespace kuikly

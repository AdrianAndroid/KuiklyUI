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

#ifndef CORE_RENDER_OHOS_KRSFTPINTERNAL_H
#define CORE_RENDER_OHOS_KRSFTPINTERNAL_H

#include <libssh2.h>
#include <libssh2_sftp.h>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

namespace kuikly {
namespace module {
namespace sftp_internal {

/**
 * 一条 SFTP 连接的底层句柄。
 *
 * libssh2 的同一个 LIBSSH2_SESSION 不允许多线程并发调用，所有 SFTP 操作
 * 都必须持有 io 锁串行化；会话/打开的文件句柄通过 shared_ptr 共享生命周期，
 * 避免「会话已断开但读句柄仍在读」的释放后使用。
 */
struct SessionHandle {
    LIBSSH2_SESSION *session = nullptr;
    LIBSSH2_SFTP *sftp = nullptr;
    int sock = -1;
    std::string home;
    std::recursive_mutex io;
    bool broken = false;
};

using SessionPtr = std::shared_ptr<SessionHandle>;

struct OpenFileHandle {
    LIBSSH2_SFTP_HANDLE *handle = nullptr;
    SessionPtr session;
};

/**
 * 把任意 libssh2 错误码转成可读信息。
 */
std::string Libssh2Error(LIBSSH2_SESSION *session, const std::string &what);

/**
 * 阻塞连接 TCP（含超时），返回 fd；失败抛 std::runtime_error。
 */
int TcpConnect(const std::string &host, int port, int timeoutSec);

}  // namespace sftp_internal
}  // namespace module
}  // namespace kuikly

#endif  // CORE_RENDER_OHOS_KRSFTPINTERNAL_H

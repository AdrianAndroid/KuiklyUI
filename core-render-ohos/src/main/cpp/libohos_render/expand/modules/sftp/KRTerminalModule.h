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
#include "libohos_render/export/IKRRenderModuleExport.h"

namespace kuikly {
namespace module {

/**
 * 终端 shell（HarmonyOS，libssh2）。
 *
 * 复用 `KRSftpSession` 已保存的**连接凭据**，为终端**单开一条独立 SSH 连接**并打开 shell channel + pty，
 * 输出用「绝对偏移 + 拉取」（与 Web 网关 / Android / iOS 一致）。
 *
 * 为什么单开连接：libssh2 同一条 SESSION 上并发调用不是线程安全，且阻塞式 shell 读取会占住会话，
 * 影响同一会话上的 SFTP 操作。单开连接后 shell 读取在自己的线程里阻塞，互不干扰。
 *
 * 方法表与 commonMain `TerminalModule` 一致：open/read/write/resize/close。
 */
class KRTerminalModule : public IKRRenderModuleExport {
 public:
    static const char MODULE_NAME[];
    KRAnyValue CallMethod(bool sync, const std::string &method, KRAnyValue params,
                          const KRRenderCallback &callback) override;

 private:
    void Open(const KRAnyValue &params, const KRRenderCallback &callback);
    void Read(const KRAnyValue &params, const KRRenderCallback &callback);
    void Write(const KRAnyValue &params, const KRRenderCallback &callback);
    void Resize(const KRAnyValue &params, const KRRenderCallback &callback);
    void Close(const KRAnyValue &params, const KRRenderCallback &callback);
};

}  // namespace module
}  // namespace kuikly

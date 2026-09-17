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
 * SFTP 主 Module（HarmonyOS，§3.1 / §3.5 / §7.1 / §21.2）
 *
 * 通过 libssh2 实现 SSH/SFTP；方法表与 commonMain SftpModule 一一对应。
 *
 * **Phase 1 简化**：当前为占位实现；Phase 1.2 接入 libssh2 + libmicrohttpd。
 */
class KRSftpModule : public IKRRenderModuleExport {
 public:
    static const char MODULE_NAME[];
    KRAnyValue CallMethod(bool sync, const std::string &method, KRAnyValue params,
                          const KRRenderCallback &callback) override;
    void OnDestroy() override;

 private:
    static const char METHOD_CONNECT[];
    static const char METHOD_DISCONNECT[];
    static const char METHOD_LIST[];
    static const char METHOD_STAT[];
    static const char METHOD_OPEN_READ[];
    static const char METHOD_READ[];
    static const char METHOD_CLOSE[];
    static const char METHOD_DOWNLOAD[];
    static const char METHOD_UPLOAD[];
    static const char METHOD_MKDIR[];
    static const char METHOD_RM[];
    static const char METHOD_RENAME[];
    static const char METHOD_MOVE[];
    static const char METHOD_COPY[];
    static const char METHOD_CHMOD[];
    static const char METHOD_CHOWN[];
    static const char METHOD_SETMTIME[];
    static const char METHOD_BATCH_TASK[];
    static const char METHOD_CANCEL_BATCH_TASK[];

    void Connect(const KRAnyValue &params, const KRRenderCallback &callback);
    void Disconnect(const KRAnyValue &params, const KRRenderCallback &callback);
    void List(const KRAnyValue &params, const KRRenderCallback &callback);
    void Stat(const KRAnyValue &params, const KRRenderCallback &callback);
    void OpenRead(const KRAnyValue &params, const KRRenderCallback &callback);
    void Read(const KRAnyValue &params, const KRRenderCallback &callback);
    void Close(const KRAnyValue &params, const KRRenderCallback &callback);
    void Download(const KRAnyValue &params, const KRRenderCallback &callback);
    void Upload(const KRAnyValue &params, const KRRenderCallback &callback);
    void Mkdir(const KRAnyValue &params, const KRRenderCallback &callback);
    void Rm(const KRAnyValue &params, const KRRenderCallback &callback);
    void Rename(const KRAnyValue &params, const KRRenderCallback &callback);
    void Move(const KRAnyValue &params, const KRRenderCallback &callback);
    void Copy(const KRAnyValue &params, const KRRenderCallback &callback);
    void Chmod(const KRAnyValue &params, const KRRenderCallback &callback);
    void Chown(const KRAnyValue &params, const KRRenderCallback &callback);
    void SetMtime(const KRAnyValue &params, const KRRenderCallback &callback);
    void BatchTask(const KRAnyValue &params, const KRRenderCallback &callback);
    void CancelBatchTask(const KRAnyValue &params, const KRRenderCallback &callback);
};

}  // namespace module
}  // namespace kuikly

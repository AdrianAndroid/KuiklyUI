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
#include <vector>
#include <unordered_map>
#include <mutex>
#include "libohos_render/export/IKRRenderModuleExport.h"

namespace kuikly {
namespace module {

/**
 * 未实现能力统一抛这个异常。
 *
 * 设计原则（跨端一致）：**桩实现必须显式失败，绝不能伪报成功**。
 * 之前 OHOS 端 Connect 返回假 sessionId、Upload/Download 直接 return 1.0f、
 * Copy 返回 success=true，会让用户看到「已连接/上传完成」而实际什么都没做，
 * 属于静默数据丢失。错误码沿用 SftpErrorCode.NOT_IMPLEMENTED(9999)。
 */
class SftpNotImplementedException : public std::runtime_error {
 public:
    explicit SftpNotImplementedException(const std::string &what)
        : std::runtime_error("not implemented: " + what) {}
};

/**
 * SFTP 客户端（HarmonyOS，基于 libssh2，§3.5 / §21.1.1）
 *
 * **Phase 1 简化**：当前为占位实现；Phase 1.2 接入 libssh2。
 */
class KRSftpSession {
 public:
    static std::string Connect(const KRAnyValue &params);
    static void Disconnect(const std::string &sessionId);
    static std::vector<KRRenderValueMap> List(const std::string &sessionId, const std::string &remotePath);
    static KRRenderValueMap Stat(const std::string &sessionId, const std::string &remotePath, bool followSymlink);
    static float Download(const KRAnyValue &params);
    static float Upload(const KRAnyValue &params);
    static void Mkdir(const KRAnyValue &params);
    static void Rm(const KRAnyValue &params);
    static void Rename(const KRAnyValue &params);
    static void Move(const KRAnyValue &params);
    static KRRenderValueMap Copy(const KRAnyValue &params);
    static void Chmod(const KRAnyValue &params);
    static void Chown(const KRAnyValue &params);
    static void SetMtime(const KRAnyValue &params);
    static float BatchTask(const KRAnyValue &params);
    static void CancelBatchTask(const std::string &taskId);
    static void ShutdownAll();

 private:
    static std::mutex gLock;
    static std::unordered_map<std::string, void *> gSessions;  // sessionId → LIBSSH2_SESSION*
    static long long gSessionIdCounter;
};

}  // namespace module
}  // namespace kuikly

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
#include "libohos_render/export/IKRRenderModuleExport.h"

namespace kuikly {
namespace module {

/**
 * 流式读文件句柄（HarmonyOS，§7.1.3）
 *
 * **Phase 1 简化**：当前为占位实现；Phase 1.2 接入 libssh2 SFTP read。
 */
class KRSftpFileHandle {
 public:
    static std::string OpenRead(const std::string &sessionId, const std::string &remotePath);
    static KRAnyValue Read(const std::string &fileHandleId, long long offset, int length);
    static void Close(const std::string &fileHandleId);
    static void CloseAll();

 private:
    static std::mutex gLock;
    static std::unordered_map<std::string, void *> gHandles;  // fileHandleId → LIBSSH2_SFTP_HANDLE*
    static long long gHandleIdCounter;
};

}  // namespace module
}  // namespace kuikly

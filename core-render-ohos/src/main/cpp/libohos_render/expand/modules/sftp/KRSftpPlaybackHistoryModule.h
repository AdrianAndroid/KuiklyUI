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
 * SFTP 播放历史 Module（HarmonyOS，§20.1 / §21.5）
 *
 * 全局单例，持久化用 OHOS preferences key `sftp_playback_history`。
 * 容量 2000 条 LRU（§21.5.1）。
 *
 * **Phase 1 简化**：当前为占位实现；Phase 1.2 接入 OHOS preferences API。
 */
class KRSftpPlaybackHistoryModule : public IKRRenderModuleExport {
 public:
    static const char MODULE_NAME[];
    KRAnyValue CallMethod(bool sync, const std::string &method, KRAnyValue params,
                          const KRRenderCallback &callback) override;
    void OnDestroy() override {}

 private:
    static const char METHOD_UPSERT[];
    static const char METHOD_GET[];
    static const char METHOD_LIST_BY_DIR[];
    static const char METHOD_LIST_BY_CONN[];
    static const char METHOD_REMOVE[];
    static const char METHOD_CLEAR_BY_CONN[];
    static const char METHOD_MARK_COMPLETED[];
};

}  // namespace module
}  // namespace kuikly

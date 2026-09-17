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
 * SFTP 收藏 Module（HarmonyOS，§3.4 / §21.4）
 *
 * 全局单例，持久化用 OHOS preferences key `sftp_favorites`。
 *
 * **Phase 1 简化**：当前为占位实现；Phase 1.2 接入 OHOS preferences API。
 */
class KRSftpFavoritesModule : public IKRRenderModuleExport {
 public:
    static const char MODULE_NAME[];
    KRAnyValue CallMethod(bool sync, const std::string &method, KRAnyValue params,
                          const KRRenderCallback &callback) override;
    void OnDestroy() override {}

 private:
    static const char METHOD_ADD[];
    static const char METHOD_REMOVE[];
    static const char METHOD_REMOVE_BY_CONNECTION[];
    static const char METHOD_LIST[];
    static const char METHOD_IS_FAVORITED[];
    static const char METHOD_UPDATE[];
    static const char METHOD_SEARCH[];
};

}  // namespace module
}  // namespace kuikly

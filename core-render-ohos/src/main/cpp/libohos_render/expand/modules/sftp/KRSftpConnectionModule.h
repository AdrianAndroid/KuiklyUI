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
#ifndef KRSFTP_CONNECTION_MODULE_H
#define KRSFTP_CONNECTION_MODULE_H

#include "libohos_render/export/IKRRenderModuleExport.h"
#include <string>

namespace kuikly {
namespace module {

/**
 * SFTP 连接列表 Module（HarmonyOS，§17.3.1 / §21.4.5）
 *
 * Phase 1.2: 接入 @ohos.data.preferences 持久化
 */
class KRSftpConnectionModule : public IKRRenderModuleExport {
public:
    static constexpr const char *MODULE_NAME = "KRSftpConnectionModule";

    static const char METHOD_ADD[];
    static const char METHOD_UPDATE[];
    static const char METHOD_REMOVE[];
    static const char METHOD_LIST[];
    static const char METHOD_GET[];
    static const char METHOD_TOUCH_LAST_USED[];

    KRAnyValue CallMethod(bool sync, const std::string &method, KRAnyValue params,
                          const KRRenderCallback &callback) override;
    void OnDestroy() override;
};

}  // namespace module
}  // namespace kuikly

#endif  // KRSFTP_CONNECTION_MODULE_H

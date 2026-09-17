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
#include "KRSftpConnectionModule.h"
#include "SftpErrorFormatter.h"
#include "KRRenderValue.h"
#include <thread>

namespace kuikly {
namespace module {

const char KRSftpConnectionModule::METHOD_ADD[]             = "add";
const char KRSftpConnectionModule::METHOD_UPDATE[]         = "update";
const char KRSftpConnectionModule::METHOD_REMOVE[]          = "remove";
const char KRSftpConnectionModule::METHOD_LIST[]            = "list";
const char KRSftpConnectionModule::METHOD_GET[]             = "get";
const char KRSftpConnectionModule::METHOD_TOUCH_LAST_USED[] = "touchLastUsed";

KRAnyValue KRSftpConnectionModule::CallMethod(bool sync, const std::string &method, KRAnyValue params,
                                                const KRRenderCallback &callback) {
    std::thread([method, callback]() {
        // Phase 1.2: 接入 @ohos.data.preferences 持久化；当前 stub 返回 unsupported 错误
        KRRenderValueMap err;
        err["error"] = KRRenderValue::Make(
            SftpErrorFormatter::Format(9999, "KRSftpConnectionModule not yet implemented on OHOS: " + method));
        callback(KRRenderValue::Make(err));
    }).detach();
    return nullptr;
}

void KRSftpConnectionModule::OnDestroy() {
    // 无状态，无需清理
}

}  // namespace module
}  // namespace kuikly

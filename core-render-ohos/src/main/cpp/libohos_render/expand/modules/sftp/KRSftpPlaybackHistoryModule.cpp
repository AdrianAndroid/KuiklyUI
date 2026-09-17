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
#include "KRSftpPlaybackHistoryModule.h"
#include <thread>
#include "libohos_render/utils/KRJSONObject.h"
#include "SftpErrorFormatter.h"

namespace kuikly {
namespace module {

const char KRSftpPlaybackHistoryModule::MODULE_NAME[]                  = "KRSftpPlaybackHistoryModule";
const char KRSftpPlaybackHistoryModule::METHOD_UPSERT[]                 = "upsert";
const char KRSftpPlaybackHistoryModule::METHOD_GET[]                     = "get";
const char KRSftpPlaybackHistoryModule::METHOD_LIST_BY_DIR[]            = "listByDirectory";
const char KRSftpPlaybackHistoryModule::METHOD_LIST_BY_CONN[]            = "listByConnection";
const char KRSftpPlaybackHistoryModule::METHOD_REMOVE[]                  = "remove";
const char KRSftpPlaybackHistoryModule::METHOD_CLEAR_BY_CONN[]          = "clearByConnection";
const char KRSftpPlaybackHistoryModule::METHOD_MARK_COMPLETED[]         = "markCompleted";

KRAnyValue KRSftpPlaybackHistoryModule::CallMethod(bool sync, const std::string &method, KRAnyValue params,
                                                      const KRRenderCallback &callback) {
    std::thread([method, params, callback]() {
        try {
            // Phase 1.2: 接入 OHOS preferences API
            KRRenderValueMap result;
            result["ok"] = KRRenderValue::Make(true);
            callback(KRRenderValue::Make(result));
        } catch (const std::exception &e) {
            KRRenderValueMap err;
            err["error"] = KRRenderValue::Make(SftpErrorFormatter::Format(e));
            callback(KRRenderValue::Make(err));
        }
    }).detach();
    return nullptr;
}

}  // namespace module
}  // namespace kuikly

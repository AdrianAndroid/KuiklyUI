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
#include "KRSftpFavoritesModule.h"
#include <thread>
#include "libohos_render/utils/KRJSONObject.h"
#include "SftpErrorFormatter.h"

namespace kuikly {
namespace module {

const char KRSftpFavoritesModule::MODULE_NAME[]                   = "KRSftpFavoritesModule";
const char KRSftpFavoritesModule::METHOD_ADD[]                    = "add";
const char KRSftpFavoritesModule::METHOD_REMOVE[]                 = "remove";
const char KRSftpFavoritesModule::METHOD_REMOVE_BY_CONNECTION[]   = "removeByConnection";
const char KRSftpFavoritesModule::METHOD_LIST[]                   = "list";
const char KRSftpFavoritesModule::METHOD_IS_FAVORITED[]           = "isFavorited";
const char KRSftpFavoritesModule::METHOD_UPDATE[]                 = "update";
const char KRSftpFavoritesModule::METHOD_SEARCH[]                 = "search";

KRAnyValue KRSftpFavoritesModule::CallMethod(bool sync, const std::string &method, KRAnyValue params,
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

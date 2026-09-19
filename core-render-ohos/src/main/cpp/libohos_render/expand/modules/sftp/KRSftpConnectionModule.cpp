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

#include <algorithm>
#include <thread>
#include <vector>

#include "SftpErrorFormatter.h"
#include "SftpJsonStore.h"
#include "libohos_render/export/IKRRenderViewExport.h"
#include "libohos_render/foundation/type/KRRenderValue.h"

namespace kuikly {
namespace module {

namespace {

constexpr const char *kConnectionsFile = "sftp_connections.json";
constexpr const char *kFavoritesFile = "sftp_favorites.json";
constexpr const char *kHistoryFile = "sftp_playback_history.json";

std::string MakeConnectionId(const cJSON *item) {
    std::string host = sftp_store::CJsonString(item, "host");
    long long port = sftp_store::CJsonNumber(item, "port", 22);
    std::string user = sftp_store::CJsonString(item, "user");
    return host + ":" + std::to_string(port) + "/" + user + "#" +
           std::to_string(sftp_store::NowMs());
}

/** label 重复时追加 (2)/(3)…，与各端行为一致。 */
std::string UniqueLabel(const cJSON *array, const std::string &label) {
    bool dup = true;
    std::string candidate = label;
    int suffix = 2;
    while (dup) {
        dup = false;
        for (const cJSON *e = array ? array->child : nullptr; e != nullptr; e = e->next) {
            if (sftp_store::CJsonString(e, "label") == candidate) {
                dup = true;
                break;
            }
        }
        if (dup) {
            candidate = label + "(" + std::to_string(suffix++) + ")";
        }
    }
    return candidate;
}

}  // namespace

// MODULE_NAME 在头文件里已是 `static constexpr const char *`（C++17 隐式 inline），无需在此定义。
const char KRSftpConnectionModule::METHOD_ADD[]             = "add";
const char KRSftpConnectionModule::METHOD_UPDATE[]         = "update";
const char KRSftpConnectionModule::METHOD_REMOVE[]          = "remove";
const char KRSftpConnectionModule::METHOD_LIST[]            = "list";
const char KRSftpConnectionModule::METHOD_GET[]             = "get";
const char KRSftpConnectionModule::METHOD_TOUCH_LAST_USED[] = "touchLastUsed";

KRAnyValue KRSftpConnectionModule::CallMethod(bool sync, const std::string &method, KRAnyValue params,
                                                const KRRenderCallback &callback) {
    std::string filesDir;
    if (auto root = GetRootView().lock()) {
        filesDir = root->GetContext()->Config()->GetFilesDir();
    }
    std::thread([method, params, callback, filesDir]() {
        try {
            if (filesDir.empty()) {
                throw std::runtime_error("connection store: files dir unavailable");
            }
            sftp_store::JsonArrayFile store(
                sftp_store::PathIn(filesDir, kConnectionsFile), "id");

            if (method == METHOD_ADD) {
                std::string json = params ? params->toString() : "";
                cJSON *item = cJSON_Parse(json.c_str());
                if (!item) {
                    throw std::runtime_error("connection add: invalid json");
                }
                cJSON *array = store.Load();
                std::string id = sftp_store::CJsonString(item, "id");
                if (id.empty()) {
                    id = MakeConnectionId(item);
                    sftp_store::CJsonSetString(item, "id", id);
                }
                long long now = sftp_store::NowMs();
                if (sftp_store::CJsonNumber(item, "createdAt", 0) == 0) {
                    sftp_store::CJsonSetNumber(item, "createdAt", now);
                }
                sftp_store::CJsonSetString(
                    item, "label", UniqueLabel(array, sftp_store::CJsonString(item, "label")));
                store.Upsert(array, item);
                bool ok = store.SaveAndFree(array);
                cJSON_Delete(item);
                if (!ok) {
                    throw std::runtime_error("connection add: save failed");
                }
                KRRenderValueMap result;
                result["id"] = KRRenderValue::Make(id);
                callback(KRRenderValue::Make(result));
                return;
            }

            if (method == METHOD_UPDATE) {
                std::string json = params ? params->toString() : "";
                cJSON *item = cJSON_Parse(json.c_str());
                if (!item) {
                    throw std::runtime_error("connection update: invalid json");
                }
                cJSON *array = store.Load();
                std::string id = sftp_store::CJsonString(item, "id");
                cJSON *existing = store.FindById(array, id);
                if (existing) {
                    // 保留 createdAt，其余字段覆盖。
                    sftp_store::CJsonSetNumber(
                        item, "createdAt", sftp_store::CJsonNumber(existing, "createdAt", 0));
                    store.Upsert(array, item);
                } else {
                    store.Upsert(array, item);
                }
                bool ok = store.SaveAndFree(array);
                cJSON_Delete(item);
                if (!ok) {
                    throw std::runtime_error("connection update: save failed");
                }
                callback(KRRenderValue::Make(KRRenderValueMap{}));
                return;
            }

            if (method == METHOD_REMOVE) {
                auto map = params ? params->toMap() : KRRenderValue::Map{};
                auto it = map.find("id");
                std::string id = (it != map.end() && it->second) ? it->second->toString() : "";
                cJSON *array = store.Load();
                store.RemoveById(array, id);
                store.SaveAndFree(array);
                // 级联清理收藏与播放历史（§21.4.5）。
                if (!id.empty()) {
                    sftp_store::JsonArrayFile favorites(
                        sftp_store::PathIn(filesDir, kFavoritesFile), "id");
                    cJSON *favArray = favorites.Load();
                    if (favorites.RemoveByField(favArray, "connectionId", id) > 0) {
                        favorites.SaveAndFree(favArray);
                    } else {
                        cJSON_Delete(favArray);
                    }
                    sftp_store::JsonArrayFile history(
                        sftp_store::PathIn(filesDir, kHistoryFile), "id");
                    cJSON *hisArray = history.Load();
                    if (history.RemoveByField(hisArray, "connectionId", id) > 0) {
                        history.SaveAndFree(hisArray);
                    } else {
                        cJSON_Delete(hisArray);
                    }
                }
                callback(KRRenderValue::Make(KRRenderValueMap{}));
                return;
            }

            if (method == METHOD_LIST) {
                cJSON *array = store.Load();
                std::vector<cJSON *> items;
                for (cJSON *e = array->child; e != nullptr; e = e->next) {
                    items.push_back(e);
                }
                std::stable_sort(items.begin(), items.end(), [](const cJSON *a, const cJSON *b) {
                    return sftp_store::CJsonNumber(a, "lastUsedAt", 0) >
                           sftp_store::CJsonNumber(b, "lastUsedAt", 0);
                });
                KRRenderValue::Array out;
                for (const cJSON *e : items) {
                    out.push_back(sftp_store::FromCJson(e));
                }
                cJSON_Delete(array);
                KRRenderValueMap result;
                result["items"] = KRRenderValue::Make(out);
                callback(KRRenderValue::Make(result));
                return;
            }

            if (method == METHOD_GET) {
                auto map = params ? params->toMap() : KRRenderValue::Map{};
                auto it = map.find("id");
                std::string id = (it != map.end() && it->second) ? it->second->toString() : "";
                cJSON *array = store.Load();
                cJSON *found = store.FindById(array, id);
                KRRenderValueMap result;
                if (found) {
                    result["conn"] = sftp_store::FromCJson(found);
                }
                cJSON_Delete(array);
                callback(KRRenderValue::Make(result));
                return;
            }

            if (method == METHOD_TOUCH_LAST_USED) {
                auto map = params ? params->toMap() : KRRenderValue::Map{};
                auto it = map.find("id");
                std::string id = (it != map.end() && it->second) ? it->second->toString() : "";
                cJSON *array = store.Load();
                cJSON *found = store.FindById(array, id);
                if (found) {
                    sftp_store::CJsonSetNumber(found, "lastUsedAt", sftp_store::NowMs());
                    store.SaveAndFree(array);
                } else {
                    cJSON_Delete(array);
                }
                callback(KRRenderValue::Make(KRRenderValueMap{}));
                return;
            }

            KRRenderValueMap err;
            err["error"] = KRRenderValue::Make(SftpErrorFormatter::Format(
                9999, "KRSftpConnectionModule unknown method: " + method));
            callback(KRRenderValue::Make(err));
        } catch (const std::exception &e) {
            KRRenderValueMap err;
            err["error"] = KRRenderValue::Make(SftpErrorFormatter::Format(e));
            callback(KRRenderValue::Make(err));
        }
    }).detach();
    return nullptr;
}

void KRSftpConnectionModule::OnDestroy() {
    // 连接列表持久化在文件中，无内存状态。
}

}  // namespace module
}  // namespace kuikly

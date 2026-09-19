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

#include <algorithm>
#include <cctype>
#include <thread>
#include <vector>

#include "SftpErrorFormatter.h"
#include "SftpJsonStore.h"
#include "libohos_render/export/IKRRenderViewExport.h"
#include "libohos_render/foundation/type/KRRenderValue.h"

namespace kuikly {
namespace module {

namespace {

constexpr const char *kFavoritesFile = "sftp_favorites.json";

bool ContainsFold(const std::string &haystack, const std::string &needle) {
    if (needle.empty()) {
        return true;
    }
    auto lower = [](std::string s) {
        std::transform(s.begin(), s.end(), s.begin(),
                       [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
        return s;
    };
    return lower(haystack).find(lower(needle)) != std::string::npos;
}

/** STARRED_AT / NAME / CONNECTION_LABEL / MTIME（模型无 mtime，回退 starredAt）。 */
long long SortKeyOf(const cJSON *item, const std::string &sortBy) {
    if (sortBy == "NAME" || sortBy == "CONNECTION_LABEL") {
        return 0;
    }
    return sftp_store::CJsonNumber(item, "starredAt", 0);
}

void SortItems(std::vector<cJSON *> &items, const std::string &sortBy, bool ascending) {
    std::stable_sort(items.begin(), items.end(),
                     [&](const cJSON *a, const cJSON *b) {
                         if (sortBy == "NAME" || sortBy == "CONNECTION_LABEL") {
                             const char *field = (sortBy == "NAME") ? "name" : "connectionLabel";
                             std::string sa = sftp_store::CJsonString(a, field);
                             std::string sb = sftp_store::CJsonString(b, field);
                             return ascending ? (sa < sb) : (sa > sb);
                         }
                         long long ka = SortKeyOf(a, sortBy);
                         long long kb = SortKeyOf(b, sortBy);
                         return ascending ? (ka < kb) : (ka > kb);
                     });
}

}  // namespace

// 注册表（ModulesRegisterEntry.h）引用该常量，缺失会导致完整构建断链。
const char KRSftpFavoritesModule::MODULE_NAME[]            = "KRSftpFavoritesModule";
const char KRSftpFavoritesModule::METHOD_ADD[]             = "add";
const char KRSftpFavoritesModule::METHOD_REMOVE[]          = "remove";
const char KRSftpFavoritesModule::METHOD_REMOVE_BY_CONNECTION[] = "removeByConnection";
const char KRSftpFavoritesModule::METHOD_LIST[]            = "list";
const char KRSftpFavoritesModule::METHOD_IS_FAVORITED[]    = "isFavorited";
const char KRSftpFavoritesModule::METHOD_UPDATE[]          = "update";
const char KRSftpFavoritesModule::METHOD_SEARCH[]          = "search";

KRAnyValue KRSftpFavoritesModule::CallMethod(bool sync, const std::string &method, KRAnyValue params,
                                              const KRRenderCallback &callback) {
    std::string filesDir;
    if (auto root = GetRootView().lock()) {
        filesDir = root->GetContext()->Config()->GetFilesDir();
    }
    std::thread([method, params, callback, filesDir]() {
        try {
            if (filesDir.empty()) {
                throw std::runtime_error("favorites store: files dir unavailable");
            }
            sftp_store::JsonArrayFile store(sftp_store::PathIn(filesDir, kFavoritesFile), "id");
            auto map = params ? params->toMap() : KRRenderValue::Map{};
            auto strOf = [&map](const char *key) -> std::string {
                auto it = map.find(key);
                return (it != map.end() && it->second) ? it->second->toString() : std::string();
            };

            if (method == METHOD_ADD) {
                std::string json = params ? params->toString() : "";
                cJSON *item = cJSON_Parse(json.c_str());
                if (!item) {
                    throw std::runtime_error("favorite add: invalid json");
                }
                cJSON *array = store.Load();
                std::string id = sftp_store::CJsonString(item, "id");
                if (id.empty()) {
                    id = "fav-" + std::to_string(sftp_store::NowMs());
                    sftp_store::CJsonSetString(item, "id", id);
                }
                if (sftp_store::CJsonNumber(item, "starredAt", 0) == 0) {
                    sftp_store::CJsonSetNumber(item, "starredAt", sftp_store::NowMs());
                }
                store.Upsert(array, item);
                bool ok = store.SaveAndFree(array);
                cJSON_Delete(item);
                if (!ok) {
                    throw std::runtime_error("favorite add: save failed");
                }
                KRRenderValueMap result;
                result["id"] = KRRenderValue::Make(id);
                callback(KRRenderValue::Make(result));
                return;
            }

            if (method == METHOD_REMOVE) {
                cJSON *array = store.Load();
                int removed = store.RemoveById(array, strOf("id"));
                store.SaveAndFree(array);
                (void)removed;
                callback(KRRenderValue::Make(KRRenderValueMap{}));
                return;
            }

            if (method == METHOD_REMOVE_BY_CONNECTION) {
                cJSON *array = store.Load();
                int removed = store.RemoveByField(array, "connectionId", strOf("connectionId"));
                store.SaveAndFree(array);
                KRRenderValueMap result;
                result["removedCount"] = KRRenderValue::Make(removed);
                callback(KRRenderValue::Make(result));
                return;
            }

            if (method == METHOD_LIST) {
                std::string connectionId = strOf("connectionId");
                std::string sortBy = strOf("sortBy");
                bool ascending = strOf("sortOrder") == "ASC";
                cJSON *array = store.Load();
                std::vector<cJSON *> items;
                for (cJSON *e = array->child; e != nullptr; e = e->next) {
                    if (connectionId.empty() ||
                        sftp_store::CJsonString(e, "connectionId") == connectionId) {
                        items.push_back(e);
                    }
                }
                SortItems(items, sortBy, ascending);
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

            if (method == METHOD_IS_FAVORITED) {
                std::string connectionId = strOf("connectionId");
                std::string remotePath = strOf("remotePath");
                cJSON *array = store.Load();
                std::string foundId;
                for (cJSON *e = array->child; e != nullptr; e = e->next) {
                    if (sftp_store::CJsonString(e, "connectionId") == connectionId &&
                        sftp_store::CJsonString(e, "remotePath") == remotePath) {
                        foundId = sftp_store::CJsonString(e, "id");
                        break;
                    }
                }
                cJSON_Delete(array);
                KRRenderValueMap result;
                result["id"] = KRRenderValue::Make(foundId);
                callback(KRRenderValue::Make(result));
                return;
            }

            if (method == METHOD_UPDATE) {
                std::string id = strOf("id");
                cJSON *array = store.Load();
                cJSON *found = store.FindById(array, id);
                if (found) {
                    if (map.find("note") != map.end()) {
                        std::string note = strOf("note");
                        if (note.empty()) {
                            cJSON_DeleteItemFromObjectCaseSensitive(found, "note");
                        } else {
                            sftp_store::CJsonSetString(found, "note", note);
                        }
                    }
                    if (map.find("iconOverride") != map.end()) {
                        std::string icon = strOf("iconOverride");
                        if (icon.empty()) {
                            cJSON_DeleteItemFromObjectCaseSensitive(found, "iconOverride");
                        } else {
                            sftp_store::CJsonSetString(found, "iconOverride", icon);
                        }
                    }
                    store.SaveAndFree(array);
                } else {
                    cJSON_Delete(array);
                }
                callback(KRRenderValue::Make(KRRenderValueMap{}));
                return;
            }

            if (method == METHOD_SEARCH) {
                std::string keyword = strOf("keyword");
                cJSON *array = store.Load();
                KRRenderValue::Array out;
                for (cJSON *e = array->child; e != nullptr; e = e->next) {
                    if (ContainsFold(sftp_store::CJsonString(e, "name"), keyword) ||
                        ContainsFold(sftp_store::CJsonString(e, "remotePath"), keyword) ||
                        ContainsFold(sftp_store::CJsonString(e, "connectionLabel"), keyword)) {
                        out.push_back(sftp_store::FromCJson(e));
                    }
                }
                cJSON_Delete(array);
                KRRenderValueMap result;
                result["items"] = KRRenderValue::Make(out);
                callback(KRRenderValue::Make(result));
                return;
            }

            KRRenderValueMap err;
            err["error"] = KRRenderValue::Make(SftpErrorFormatter::Format(
                9999, "KRSftpFavoritesModule unknown method: " + method));
            callback(KRRenderValue::Make(err));
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

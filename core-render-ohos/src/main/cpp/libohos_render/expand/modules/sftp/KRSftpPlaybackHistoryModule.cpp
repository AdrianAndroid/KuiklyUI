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

constexpr const char *kHistoryFile = "sftp_playback_history.json";
constexpr int kMaxRecords = 2000;

std::string DirectoryOf(const std::string &remotePath) {
    auto pos = remotePath.find_last_of('/');
    if (pos == std::string::npos) {
        return "";
    }
    if (pos == 0) {
        return "/";
    }
    return remotePath.substr(0, pos);
}

/** 超出容量时按 lastPlayedAt 从旧到新淘汰。 */
void TrimToCapacity(cJSON *array) {
    if (!array || cJSON_GetArraySize(array) <= kMaxRecords) {
        return;
    }
    std::vector<cJSON *> items;
    for (cJSON *e = array->child; e != nullptr; e = e->next) {
        items.push_back(e);
    }
    std::stable_sort(items.begin(), items.end(), [](const cJSON *a, const cJSON *b) {
        return sftp_store::CJsonNumber(a, "lastPlayedAt", 0) <
               sftp_store::CJsonNumber(b, "lastPlayedAt", 0);
    });
    int toRemove = static_cast<int>(items.size()) - kMaxRecords;
    for (int i = 0; i < toRemove; ++i) {
        cJSON *item = items[static_cast<size_t>(i)];
        if (item->prev) {
            item->prev->next = item->next;
        } else {
            array->child = item->next;
        }
        if (item->next) {
            item->next->prev = item->prev;
        }
        item->prev = nullptr;
        item->next = nullptr;
        cJSON_Delete(item);
    }
}

}  // namespace

// 注册表（ModulesRegisterEntry.h）引用该常量，缺失会导致完整构建断链。
const char KRSftpPlaybackHistoryModule::MODULE_NAME[]           = "KRSftpPlaybackHistoryModule";
const char KRSftpPlaybackHistoryModule::METHOD_UPSERT[]          = "upsert";
const char KRSftpPlaybackHistoryModule::METHOD_GET[]             = "get";
const char KRSftpPlaybackHistoryModule::METHOD_LIST_BY_DIR[]     = "listByDirectory";
const char KRSftpPlaybackHistoryModule::METHOD_LIST_BY_CONN[]    = "listByConnection";
const char KRSftpPlaybackHistoryModule::METHOD_REMOVE[]          = "remove";
const char KRSftpPlaybackHistoryModule::METHOD_CLEAR_BY_CONN[]   = "clearByConnection";
const char KRSftpPlaybackHistoryModule::METHOD_MARK_COMPLETED[]  = "markCompleted";

KRAnyValue KRSftpPlaybackHistoryModule::CallMethod(bool sync, const std::string &method,
                                                    KRAnyValue params,
                                                    const KRRenderCallback &callback) {
    std::string filesDir;
    if (auto root = GetRootView().lock()) {
        filesDir = root->GetContext()->Config()->GetFilesDir();
    }
    std::thread([method, params, callback, filesDir]() {
        try {
            if (filesDir.empty()) {
                throw std::runtime_error("playback history store: files dir unavailable");
            }
            sftp_store::JsonArrayFile store(sftp_store::PathIn(filesDir, kHistoryFile), "id");
            auto map = params ? params->toMap() : KRRenderValue::Map{};
            auto strOf = [&map](const char *key) -> std::string {
                auto it = map.find(key);
                return (it != map.end() && it->second) ? it->second->toString() : std::string();
            };

            if (method == METHOD_UPSERT) {
                std::string json = params ? params->toString() : "";
                cJSON *item = cJSON_Parse(json.c_str());
                if (!item) {
                    throw std::runtime_error("history upsert: invalid json");
                }
                cJSON *array = store.Load();
                std::string id = sftp_store::CJsonString(item, "id");
                if (id.empty()) {
                    // 以 connectionId + remotePath 作为稳定主键，保证同一视频只有一条记录。
                    id = sftp_store::CJsonString(item, "connectionId") + "::" +
                         sftp_store::CJsonString(item, "remotePath");
                    sftp_store::CJsonSetString(item, "id", id);
                }
                if (sftp_store::CJsonNumber(item, "lastPlayedAt", 0) == 0) {
                    sftp_store::CJsonSetNumber(item, "lastPlayedAt", sftp_store::NowMs());
                }
                store.Upsert(array, item);
                TrimToCapacity(array);
                bool ok = store.SaveAndFree(array);
                cJSON_Delete(item);
                if (!ok) {
                    throw std::runtime_error("history upsert: save failed");
                }
                callback(KRRenderValue::Make(KRRenderValueMap{}));
                return;
            }

            if (method == METHOD_GET) {
                std::string connectionId = strOf("connectionId");
                std::string remotePath = strOf("remotePath");
                cJSON *array = store.Load();
                cJSON *found = nullptr;
                for (cJSON *e = array->child; e != nullptr; e = e->next) {
                    if (sftp_store::CJsonString(e, "connectionId") == connectionId &&
                        sftp_store::CJsonString(e, "remotePath") == remotePath) {
                        found = e;
                        break;
                    }
                }
                KRRenderValueMap result;
                if (found) {
                    result["record"] = sftp_store::FromCJson(found);
                }
                cJSON_Delete(array);
                callback(KRRenderValue::Make(result));
                return;
            }

            if (method == METHOD_LIST_BY_DIR) {
                std::string connectionId = strOf("connectionId");
                std::string directoryPath = strOf("directoryPath");
                cJSON *array = store.Load();
                std::vector<cJSON *> items;
                for (cJSON *e = array->child; e != nullptr; e = e->next) {
                    if (sftp_store::CJsonString(e, "connectionId") != connectionId) {
                        continue;
                    }
                    if (!directoryPath.empty() &&
                        DirectoryOf(sftp_store::CJsonString(e, "remotePath")) != directoryPath) {
                        continue;
                    }
                    items.push_back(e);
                }
                std::stable_sort(items.begin(), items.end(), [](const cJSON *a, const cJSON *b) {
                    return sftp_store::CJsonNumber(a, "lastPlayedAt", 0) >
                           sftp_store::CJsonNumber(b, "lastPlayedAt", 0);
                });
                KRRenderValue::Array out;
                for (const cJSON *e : items) {
                    out.push_back(sftp_store::FromCJson(e));
                }
                cJSON_Delete(array);
                KRRenderValueMap result;
                result["records"] = KRRenderValue::Make(out);
                callback(KRRenderValue::Make(result));
                return;
            }

            if (method == METHOD_LIST_BY_CONN) {
                std::string connectionId = strOf("connectionId");
                cJSON *array = store.Load();
                std::vector<cJSON *> items;
                for (cJSON *e = array->child; e != nullptr; e = e->next) {
                    if (sftp_store::CJsonString(e, "connectionId") == connectionId) {
                        items.push_back(e);
                    }
                }
                std::stable_sort(items.begin(), items.end(), [](const cJSON *a, const cJSON *b) {
                    return sftp_store::CJsonNumber(a, "lastPlayedAt", 0) >
                           sftp_store::CJsonNumber(b, "lastPlayedAt", 0);
                });
                KRRenderValue::Array out;
                for (const cJSON *e : items) {
                    out.push_back(sftp_store::FromCJson(e));
                }
                cJSON_Delete(array);
                KRRenderValueMap result;
                result["records"] = KRRenderValue::Make(out);
                callback(KRRenderValue::Make(result));
                return;
            }

            if (method == METHOD_REMOVE) {
                cJSON *array = store.Load();
                store.RemoveById(array, strOf("id"));
                store.SaveAndFree(array);
                callback(KRRenderValue::Make(KRRenderValueMap{}));
                return;
            }

            if (method == METHOD_CLEAR_BY_CONN) {
                cJSON *array = store.Load();
                store.RemoveByField(array, "connectionId", strOf("connectionId"));
                store.SaveAndFree(array);
                callback(KRRenderValue::Make(KRRenderValueMap{}));
                return;
            }

            if (method == METHOD_MARK_COMPLETED) {
                std::string connectionId = strOf("connectionId");
                std::string remotePath = strOf("remotePath");
                cJSON *array = store.Load();
                bool touched = false;
                for (cJSON *e = array->child; e != nullptr; e = e->next) {
                    if (sftp_store::CJsonString(e, "connectionId") == connectionId &&
                        sftp_store::CJsonString(e, "remotePath") == remotePath) {
                        sftp_store::CJsonSetBool(e, "completed", true);
                        sftp_store::CJsonSetNumber(e, "lastPlayedAt", sftp_store::NowMs());
                        touched = true;
                    }
                }
                if (touched) {
                    store.SaveAndFree(array);
                } else {
                    cJSON_Delete(array);
                }
                callback(KRRenderValue::Make(KRRenderValueMap{}));
                return;
            }

            KRRenderValueMap err;
            err["error"] = KRRenderValue::Make(SftpErrorFormatter::Format(
                9999, "KRSftpPlaybackHistoryModule unknown method: " + method));
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

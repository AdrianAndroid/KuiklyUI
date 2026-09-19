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
#include "SftpJsonStore.h"

#include <chrono>
#include <cstdio>
#include <fstream>
#include <sstream>

namespace kuikly {
namespace module {
namespace sftp_store {

namespace {

std::string ReadWholeFile(const std::string &path) {
    std::ifstream in(path, std::ios::binary);
    if (!in.good()) {
        return "";
    }
    std::ostringstream ss;
    ss << in.rdbuf();
    return ss.str();
}

bool WriteWholeFile(const std::string &path, const std::string &content) {
    // 先写临时文件再原子改名，避免写一半被读到导致 JSON 损坏。
    std::string tmp = path + ".tmp";
    {
        std::ofstream out(tmp, std::ios::binary | std::ios::trunc);
        if (!out.good()) {
            return false;
        }
        out.write(content.data(), static_cast<std::streamsize>(content.size()));
        out.flush();
        if (!out.good()) {
            return false;
        }
    }
    return std::rename(tmp.c_str(), path.c_str()) == 0;
}

bool FieldEquals(const cJSON *item, const char *field, const std::string &value) {
    if (!item || !field) {
        return false;
    }
    const cJSON *f = cJSON_GetObjectItemCaseSensitive(item, field);
    return f && cJSON_IsString(f) && f->valuestring && value == f->valuestring;
}

std::string StringField(const cJSON *item, const char *field) {
    if (!item || !field) {
        return "";
    }
    const cJSON *f = cJSON_GetObjectItemCaseSensitive(item, field);
    if (f && cJSON_IsString(f) && f->valuestring) {
        return f->valuestring;
    }
    return "";
}

}  // namespace

long long NowMs() {
    using namespace std::chrono;
    return duration_cast<milliseconds>(system_clock::now().time_since_epoch()).count();
}

KRAnyValue FromCJson(const cJSON *value) {
    if (!value) {
        return KRRenderValue::MakeNull();
    }
    switch (value->type) {
        case cJSON_String:
            return KRRenderValue::Make(std::string(value->valuestring ? value->valuestring : ""));
        case cJSON_Number: {
            double d = value->valuedouble;
            long long asLong = static_cast<long long>(d);
            if (static_cast<double>(asLong) == d) {
                return KRRenderValue::Make(static_cast<int64_t>(asLong));
            }
            return KRRenderValue::Make(d);
        }
        case cJSON_True:
            return KRRenderValue::Make(true);
        case cJSON_False:
            return KRRenderValue::Make(false);
        case cJSON_Array: {
            KRRenderValue::Array arr;
            for (const cJSON *item = value->child; item != nullptr; item = item->next) {
                arr.push_back(FromCJson(item));
            }
            return KRRenderValue::Make(arr);
        }
        case cJSON_Object: {
            KRRenderValue::Map map;
            for (const cJSON *item = value->child; item != nullptr; item = item->next) {
                map[item->string ? item->string : ""] = FromCJson(item);
            }
            return KRRenderValue::Make(map);
        }
        default:
            return KRRenderValue::MakeNull();
    }
}

cJSON *JsonArrayFile::Load() const {
    std::string content = ReadWholeFile(path_);
    if (content.empty()) {
        return cJSON_CreateArray();
    }
    cJSON *parsed = cJSON_Parse(content.c_str());
    if (!parsed || !cJSON_IsArray(parsed)) {
        if (parsed) {
            cJSON_Delete(parsed);
        }
        return cJSON_CreateArray();
    }
    return parsed;
}

bool JsonArrayFile::Save(const cJSON *array) const {
    if (!array) {
        return false;
    }
    char *printed = cJSON_PrintUnformatted(array);
    if (!printed) {
        return false;
    }
    std::string content(printed);
    cJSON_free(printed);
    return WriteWholeFile(path_, content);
}

cJSON *JsonArrayFile::FindById(cJSON *array, const std::string &id) const {
    if (!array || id.empty()) {
        return nullptr;
    }
    for (cJSON *item = array->child; item != nullptr; item = item->next) {
        if (FieldEquals(item, idKey_.c_str(), id)) {
            return item;
        }
    }
    return nullptr;
}

int JsonArrayFile::RemoveById(cJSON *array, const std::string &id) const {
    return RemoveByField(array, idKey_.c_str(), id);
}

int JsonArrayFile::RemoveByField(cJSON *array, const char *field, const std::string &value) const {
    if (!array) {
        return 0;
    }
    int removed = 0;
    for (cJSON *item = array->child; item != nullptr;) {
        cJSON *next = item->next;
        if (FieldEquals(item, field, value)) {
            // cJSON 子节点是双向链表，手动摘链后释放。
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
            ++removed;
        }
        item = next;
    }
    return removed;
}

int JsonArrayFile::CountByField(const cJSON *array, const char *field, const std::string &value) const {
    if (!array) {
        return 0;
    }
    int count = 0;
    for (const cJSON *item = array->child; item != nullptr; item = item->next) {
        if (FieldEquals(item, field, value)) {
            ++count;
        }
    }
    return count;
}

void JsonArrayFile::Upsert(cJSON *array, cJSON *item) const {
    if (!array || !item) {
        return;
    }
    std::string id = StringField(item, idKey_.c_str());
    if (!id.empty()) {
        for (cJSON *existing = array->child; existing != nullptr; existing = existing->next) {
            if (FieldEquals(existing, idKey_.c_str(), id)) {
                // 用新对象替换旧对象内容（保留旧对象在链表中的位置）。
                cJSON_Delete(existing->child);
                existing->child = nullptr;
                existing->type = item->type;
                if (cJSON_IsObject(item)) {
                    for (cJSON *field = item->child; field != nullptr; field = field->next) {
                        cJSON_AddItemToObject(existing, field->string ? field->string : "",
                                              cJSON_Duplicate(field, 1));
                    }
                }
                return;
            }
        }
    }
    cJSON_AddItemToArray(array, cJSON_Duplicate(item, 1));
}

bool JsonArrayFile::SaveAndFree(cJSON *array) const {
    if (!array) {
        return false;
    }
    bool ok = Save(array);
    cJSON_Delete(array);
    return ok;
}

std::string PathIn(const std::string &filesDir, const std::string &fileName) {
    if (filesDir.empty()) {
        return "";
    }
    if (filesDir.back() == '/') {
        return filesDir + fileName;
    }
    return filesDir + "/" + fileName;
}

std::string CJsonString(const cJSON *obj, const char *field, const std::string &fallback) {
    if (!obj || !field) {
        return fallback;
    }
    const cJSON *f = cJSON_GetObjectItemCaseSensitive(obj, field);
    if (f && cJSON_IsString(f) && f->valuestring) {
        return f->valuestring;
    }
    return fallback;
}

long long CJsonNumber(const cJSON *obj, const char *field, long long fallback) {
    if (!obj || !field) {
        return fallback;
    }
    const cJSON *f = cJSON_GetObjectItemCaseSensitive(obj, field);
    if (f && cJSON_IsNumber(f)) {
        return static_cast<long long>(f->valuedouble);
    }
    return fallback;
}

bool CJsonBool(const cJSON *obj, const char *field, bool fallback) {
    if (!obj || !field) {
        return fallback;
    }
    const cJSON *f = cJSON_GetObjectItemCaseSensitive(obj, field);
    if (f && cJSON_IsBool(f)) {
        return cJSON_IsTrue(f);
    }
    return fallback;
}

void CJsonSetString(cJSON *obj, const char *field, const std::string &value) {
    if (!obj || !field) {
        return;
    }
    cJSON_DeleteItemFromObjectCaseSensitive(obj, field);
    cJSON_AddStringToObject(obj, field, value.c_str());
}

void CJsonSetNumber(cJSON *obj, const char *field, long long value) {
    if (!obj || !field) {
        return;
    }
    cJSON_DeleteItemFromObjectCaseSensitive(obj, field);
    cJSON_AddNumberToObject(obj, field, static_cast<double>(value));
}

void CJsonSetBool(cJSON *obj, const char *field, bool value) {
    if (!obj || !field) {
        return;
    }
    cJSON_DeleteItemFromObjectCaseSensitive(obj, field);
    cJSON_AddBoolToObject(obj, field, value);
}

std::string ArrayToJson(const cJSON *array) {
    if (!array) {
        return "[]";
    }
    char *printed = cJSON_PrintUnformatted(array);
    if (!printed) {
        return "[]";
    }
    std::string result(printed);
    cJSON_free(printed);
    return result;
}

}  // namespace sftp_store
}  // namespace module
}  // namespace kuikly

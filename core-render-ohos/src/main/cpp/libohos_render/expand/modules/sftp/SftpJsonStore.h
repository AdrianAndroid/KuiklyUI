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

#ifndef CORE_RENDER_OHOS_SFTPJSONSTORE_H
#define CORE_RENDER_OHOS_SFTPJSONSTORE_H

#include <string>
#include <vector>

#include "libohos_render/foundation/type/KRRenderValue.h"
#include "thirdparty/cJSON/cJSON.h"

namespace kuikly {
namespace module {
namespace sftp_store {

/** 当前时间（epoch 毫秒）。 */
long long NowMs();

/** cJSON 值 → KRAnyValue（object→Map、array→Array、number→Int64/Double）。 */
KRAnyValue FromCJson(const cJSON *value);

/**
 * 以「JSON 数组文件」为存储单元，供收藏 / 播放历史 / 连接列表复用。
 *
 * 所有方法均为阻塞 IO，调用方必须在子线程使用（模块层已 detach 线程）。
 */
class JsonArrayFile {
 public:
    JsonArrayFile(std::string path, std::string idKey)
        : path_(std::move(path)), idKey_(std::move(idKey)) {}

    const std::string &path() const { return path_; }

    /** 读取数组；文件不存在或损坏时返回空数组（所有权归调用方，需 cJSON_Delete）。 */
    cJSON *Load() const;

    /** 写回数组（不接管所有权）。 */
    bool Save(const cJSON *array) const;

    /** 按 idKey 找元素；未命中返回 nullptr。 */
    cJSON *FindById(cJSON *array, const std::string &id) const;

    /** 删除所有 idKey == id 的元素，返回删除数量。 */
    int RemoveById(cJSON *array, const std::string &id) const;

    /** 删除所有 field == value 的元素，返回删除数量。 */
    int RemoveByField(cJSON *array, const char *field, const std::string &value) const;

    /** 统计 field == value 的元素数量。 */
    int CountByField(const cJSON *array, const char *field, const std::string &value) const;

    /** 新增/覆盖：按 idKey 命中则替换，否则追加。 */
    void Upsert(cJSON *array, cJSON *item) const;

    /** 把 array 序列化并写回文件。 */
    bool SaveAndFree(cJSON *array) const;

 private:
    std::string path_;
    std::string idKey_;
};

/** 拼接 filesDir 下的存储文件路径；filesDir 为空返回空串。 */
std::string PathIn(const std::string &filesDir, const std::string &fileName);

/** cJSON 对象字段读取（缺失或类型不符返回默认值）。 */
std::string CJsonString(const cJSON *obj, const char *field, const std::string &fallback = "");
long long CJsonNumber(const cJSON *obj, const char *field, long long fallback = 0);
bool CJsonBool(const cJSON *obj, const char *field, bool fallback = false);

/** cJSON 对象字段写入（已存在则覆盖）。 */
void CJsonSetString(cJSON *obj, const char *field, const std::string &value);
void CJsonSetNumber(cJSON *obj, const char *field, long long value);
void CJsonSetBool(cJSON *obj, const char *field, bool value);

/** JSON 数组序列化为字符串（便于作为字符串回包或调试）。 */
std::string ArrayToJson(const cJSON *array);

}  // namespace sftp_store
}  // namespace module
}  // namespace kuikly

#endif  // CORE_RENDER_OHOS_SFTPJSONSTORE_H

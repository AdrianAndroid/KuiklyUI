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
#include "SftpErrorFormatter.h"
#include <sstream>

namespace kuikly {
namespace module {

std::string SftpErrorFormatter::Format(const std::exception &e) {
    int code = 0;
    std::string msg = e.what();
    // Phase 1.2: 根据 exception 类型映射 code
    return Format(code, msg);
}

std::string SftpErrorFormatter::Format(int code, const std::string &msg, const std::string &detail) {
    std::ostringstream oss;
    oss << "{\"code\":" << code << ",\"msg\":\"" << msg << "\"";
    if (!detail.empty()) {
        oss << ",\"detail\":\"" << detail << "\"";
    }
    oss << "}";
    return oss.str();
}

}  // namespace module
}  // namespace kuikly

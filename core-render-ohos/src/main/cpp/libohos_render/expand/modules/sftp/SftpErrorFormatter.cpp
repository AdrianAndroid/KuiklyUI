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
    std::string msg = e.what();
    // 错误码约定与其它端保持一致（SftpErrorCode）：
    //   9999 NOT_IMPLEMENTED（桩实现必须显式失败，绝不伪报成功）
    //   2001 PERMISSION_DENIED / 2003 NO_SUCH_FILE / 1001-1003 连接类
    int code = 0;
    if (msg.find("not implemented") != std::string::npos) {
        code = 9999;
    } else if (msg.find("NoSuch") != std::string::npos || msg.find("no such") != std::string::npos) {
        code = 2003;
    } else if (msg.find("Permission") != std::string::npos || msg.find("permission") != std::string::npos) {
        code = 2001;
    } else if (msg.find("connect") != std::string::npos) {
        code = 1001;
    } else if (msg.find("auth") != std::string::npos) {
        code = 1003;
    }
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

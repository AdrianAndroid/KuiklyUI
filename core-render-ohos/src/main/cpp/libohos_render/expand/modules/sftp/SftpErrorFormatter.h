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
#pragma once
#include <string>
#include <exception>

namespace kuikly {
namespace module {

/**
 * SFTP 错误格式化器（HarmonyOS，§21.4.3）
 *
 * 把 std::exception 映射为 SftpError JSON 字符串。
 */
class SftpErrorFormatter {
 public:
    static std::string Format(const std::exception &e);
    static std::string Format(int code, const std::string &msg, const std::string &detail = "");
};

}  // namespace module
}  // namespace kuikly

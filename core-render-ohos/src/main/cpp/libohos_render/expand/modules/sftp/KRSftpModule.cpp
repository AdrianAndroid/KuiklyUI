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
#include "KRSftpModule.h"
#include <thread>
#include "libohos_render/export/IKRRenderViewExport.h"
#include "libohos_render/utils/KRJSONObject.h"
#include "KRSftpSession.h"
#include "KRSftpFileHandle.h"
#include "SftpErrorFormatter.h"

namespace kuikly {
namespace module {

namespace {

using ValueMap = KRRenderValue::Map;

ValueMap ParamsMap(const KRAnyValue &params) {
    if (!params) {
        return {};
    }
    return params->toMap();
}

std::string StrOf(const ValueMap &m, const std::string &key, const std::string &fallback = "") {
    auto it = m.find(key);
    if (it == m.end() || !it->second) {
        return fallback;
    }
    return it->second->toString();
}

bool BoolOf(const ValueMap &m, const std::string &key, bool fallback = false) {
    auto it = m.find(key);
    if (it == m.end() || !it->second) {
        return fallback;
    }
    return it->second->toBool();
}

long long Int64Of(const ValueMap &m, const std::string &key, long long fallback = 0) {
    auto it = m.find(key);
    if (it == m.end() || !it->second) {
        return fallback;
    }
    return it->second->toLong();
}

/** 把 SftpEntry 的 map 包成 callback 需要的 KRAnyValue。 */
KRAnyValue EntryValue(const KRRenderValueMap &entry) {
    ValueMap wrapped;
    wrapped["entry"] = KRRenderValue::Make(entry);
    return KRRenderValue::Make(wrapped);
}

KRRenderValueMap ErrorValue(const std::exception &e) {
    KRRenderValueMap err;
    err["error"] = KRRenderValue::Make(SftpErrorFormatter::Format(e));
    return err;
}

}  // namespace

const char KRSftpModule::MODULE_NAME[]                 = "KRSftpModule";
const char KRSftpModule::METHOD_CONNECT[]              = "connect";
const char KRSftpModule::METHOD_DISCONNECT[]           = "disconnect";
const char KRSftpModule::METHOD_LIST[]                 = "list";
const char KRSftpModule::METHOD_STAT[]                = "stat";
const char KRSftpModule::METHOD_OPEN_READ[]           = "openRead";
const char KRSftpModule::METHOD_READ[]                 = "read";
const char KRSftpModule::METHOD_CLOSE[]               = "close";
const char KRSftpModule::METHOD_DOWNLOAD[]            = "download";
const char KRSftpModule::METHOD_UPLOAD[]              = "upload";
const char KRSftpModule::METHOD_MKDIR[]               = "mkdir";
const char KRSftpModule::METHOD_RM[]                  = "rm";
const char KRSftpModule::METHOD_RENAME[]              = "rename";
const char KRSftpModule::METHOD_MOVE[]                = "move";
const char KRSftpModule::METHOD_COPY[]                 = "copy";
const char KRSftpModule::METHOD_CHMOD[]               = "chmod";
const char KRSftpModule::METHOD_CHOWN[]               = "chown";
const char KRSftpModule::METHOD_SETMTIME[]            = "setMtime";
const char KRSftpModule::METHOD_BATCH_TASK[]          = "batchTask";
const char KRSftpModule::METHOD_CANCEL_BATCH_TASK[]   = "cancelBatchTask";

KRAnyValue KRSftpModule::CallMethod(bool sync, const std::string &method, KRAnyValue params,
                                      const KRRenderCallback &callback) {
    if (method == METHOD_CONNECT) Connect(params, callback);
    else if (method == METHOD_DISCONNECT) Disconnect(params, callback);
    else if (method == METHOD_LIST) List(params, callback);
    else if (method == METHOD_STAT) Stat(params, callback);
    else if (method == METHOD_OPEN_READ) OpenRead(params, callback);
    else if (method == METHOD_READ) Read(params, callback);
    else if (method == METHOD_CLOSE) Close(params, callback);
    else if (method == METHOD_DOWNLOAD) Download(params, callback);
    else if (method == METHOD_UPLOAD) Upload(params, callback);
    else if (method == METHOD_MKDIR) Mkdir(params, callback);
    else if (method == METHOD_RM) Rm(params, callback);
    else if (method == METHOD_RENAME) Rename(params, callback);
    else if (method == METHOD_MOVE) Move(params, callback);
    else if (method == METHOD_COPY) Copy(params, callback);
    else if (method == METHOD_CHMOD) Chmod(params, callback);
    else if (method == METHOD_CHOWN) Chown(params, callback);
    else if (method == METHOD_SETMTIME) SetMtime(params, callback);
    else if (method == METHOD_BATCH_TASK) BatchTask(params, callback);
    else if (method == METHOD_CANCEL_BATCH_TASK) CancelBatchTask(params, callback);
    return nullptr;
}

void KRSftpModule::OnDestroy() {
    // 与 Android / iOS 保持一致：会话是显式生命周期，由调用方 disconnect 释放。
    // 本 Module 绑定在 Page 上，若在此做全局 ShutdownAll，会连带断开其它 Page
    // 仍持有的会话（如播放页、HOLD_SESSION_FOR_EXTERNAL_VERIFY 场景）。
    // 句柄随其会话一起释放（见 KRSftpSession::Disconnect）。
}

void KRSftpModule::Connect(const KRAnyValue &params, const KRRenderCallback &callback) {
    std::thread([params, callback]() {
        try {
            std::string sessionId = KRSftpSession::Connect(params);
            KRRenderValueMap result;
            result["sessionId"] = KRRenderValue::Make(sessionId);
            callback(KRRenderValue::Make(result));
        } catch (const std::exception &e) {
            KRRenderValueMap err;
            err["error"] = KRRenderValue::Make(SftpErrorFormatter::Format(e));
            callback(KRRenderValue::Make(err));
        }
    }).detach();
}

void KRSftpModule::Disconnect(const KRAnyValue &params, const KRRenderCallback &callback) {
    std::string sessionId = StrOf(ParamsMap(params), "sessionId");
    std::thread([sessionId, callback]() {
        KRSftpSession::Disconnect(sessionId);
        KRRenderValueMap result;
        result["ok"] = KRRenderValue::Make(true);
        callback(KRRenderValue::Make(result));
    }).detach();
}

void KRSftpModule::List(const KRAnyValue &params, const KRRenderCallback &callback) {
    auto p = ParamsMap(params);
    std::string sessionId = StrOf(p, "sessionId");
    std::string remotePath = StrOf(p, "remotePath");
    bool includeHidden = BoolOf(p, "includeHidden", true);
    long long offset = Int64Of(p, "offset", 0);
    long long limit = Int64Of(p, "limit", 10000);
    std::thread([sessionId, remotePath, includeHidden, offset, limit, callback]() {
        try {
            auto entries = KRSftpSession::List(sessionId, remotePath);
            KRRenderValue::Array arr;
            long long skipped = 0;
            long long emitted = 0;
            bool hasMore = false;
            for (const auto &entry : entries) {
                std::string name = StrOf(entry, "name");
                if (!includeHidden && !name.empty() && name[0] == '.') {
                    continue;
                }
                if (skipped < offset) {
                    ++skipped;
                    continue;
                }
                if (limit > 0 && emitted >= limit) {
                    hasMore = true;
                    break;
                }
                arr.push_back(KRRenderValue::Make(entry));
                ++emitted;
            }
            KRRenderValueMap result;
            result["entries"] = KRRenderValue::Make(arr);
            result["hasMore"] = KRRenderValue::Make(hasMore);
            callback(KRRenderValue::Make(result));
        } catch (const std::exception &e) {
            KRRenderValueMap err;
            err["error"] = KRRenderValue::Make(SftpErrorFormatter::Format(e));
            callback(KRRenderValue::Make(err));
        }
    }).detach();
}

void KRSftpModule::Stat(const KRAnyValue &params, const KRRenderCallback &callback) {
    auto p = ParamsMap(params);
    std::string sessionId = StrOf(p, "sessionId");
    std::string remotePath = StrOf(p, "remotePath");
    bool followSymlink = BoolOf(p, "followSymlink", true);
    std::thread([sessionId, remotePath, followSymlink, callback]() {
        try {
            auto entry = KRSftpSession::Stat(sessionId, remotePath, followSymlink);
            callback(EntryValue(entry));
        } catch (const std::exception &e) {
            KRRenderValueMap err;
            err["error"] = KRRenderValue::Make(SftpErrorFormatter::Format(e));
            callback(KRRenderValue::Make(err));
        }
    }).detach();
}

void KRSftpModule::OpenRead(const KRAnyValue &params, const KRRenderCallback &callback) {
    auto p = ParamsMap(params);
    std::string sessionId = StrOf(p, "sessionId");
    std::string remotePath = StrOf(p, "remotePath");
    std::thread([sessionId, remotePath, callback]() {
        try {
            std::string fileHandleId = KRSftpFileHandle::OpenRead(sessionId, remotePath);
            KRRenderValueMap result;
            result["fileHandleId"] = KRRenderValue::Make(fileHandleId);
            callback(KRRenderValue::Make(result));
        } catch (const std::exception &e) {
            KRRenderValueMap err;
            err["error"] = KRRenderValue::Make(SftpErrorFormatter::Format(e));
            callback(KRRenderValue::Make(err));
        }
    }).detach();
}

void KRSftpModule::Read(const KRAnyValue &params, const KRRenderCallback &callback) {
    // params 是 Array<Any?>：[fileHandleId, offset, length]
    std::string fileHandleId;
    long long offset = 0;
    int length = 0;
    if (params) {
        auto arr = params->toArray();
        if (arr.size() >= 3) {
            fileHandleId = arr[0] ? arr[0]->toString() : std::string();
            offset = arr[1] ? arr[1]->toLong() : 0;
            length = arr[2] ? arr[2]->toInt() : 0;
        }
    }
    std::thread([fileHandleId, offset, length, callback]() {
        try {
            // 原子通道回包：[meta(JSONObject), ByteArray]
            callback(KRSftpFileHandle::Read(fileHandleId, offset, length));
        } catch (const std::exception &e) {
            KRRenderValue::Array result;
            KRRenderValueMap meta;
            meta["error"] = KRRenderValue::Make(SftpErrorFormatter::Format(e));
            result.push_back(KRRenderValue::Make(meta));
            result.push_back(KRRenderValue::Make(std::make_shared<std::vector<uint8_t>>()));
            callback(KRRenderValue::Make(result));
        }
    }).detach();
}

void KRSftpModule::Close(const KRAnyValue &params, const KRRenderCallback &callback) {
    std::string fileHandleId = StrOf(ParamsMap(params), "fileHandleId");
    std::thread([fileHandleId, callback]() {
        KRSftpFileHandle::Close(fileHandleId);
        KRRenderValueMap result;
        result["ok"] = KRRenderValue::Make(true);
        callback(KRRenderValue::Make(result));
    }).detach();
}

void KRSftpModule::Download(const KRAnyValue &params, const KRRenderCallback &callback) {
    auto p = ParamsMap(params);
    std::string sessionId = StrOf(p, "sessionId");
    std::string remotePath = StrOf(p, "remotePath");
    std::string localName = StrOf(p, "localName");
    if (localName.empty()) {
        localName = remotePath.substr(remotePath.find_last_of('/') + 1);
    }
    long long offset = Int64Of(p, "offset", 0);
    std::string filesDir;
    if (auto root = GetRootView().lock()) {
        filesDir = root->GetContext()->Config()->GetFilesDir();
    }
    if (filesDir.empty()) {
        KRRenderValueMap err;
        err["error"] = KRRenderValue::Make(
            SftpErrorFormatter::Format(std::runtime_error("download: files dir unavailable")));
        callback(KRRenderValue::Make(err));
        return;
    }
    std::string localPath = filesDir + "/" + localName;
    std::thread([sessionId, remotePath, localPath, offset, callback]() {
        try {
            ValueMap sub;
            sub["sessionId"] = KRRenderValue::Make(sessionId);
            sub["remotePath"] = KRRenderValue::Make(remotePath);
            sub["localPath"] = KRRenderValue::Make(localPath);
            sub["offset"] = KRRenderValue::Make(static_cast<int64_t>(offset));
            float progress = KRSftpSession::Download(KRRenderValue::Make(sub));
            KRRenderValueMap result;
            result["progress"] = KRRenderValue::Make(progress);
            result["path"] = KRRenderValue::Make(localPath);
            callback(KRRenderValue::Make(result));
        } catch (const std::exception &e) {
            KRRenderValueMap err;
            err["error"] = KRRenderValue::Make(SftpErrorFormatter::Format(e));
            callback(KRRenderValue::Make(err));
        }
    }).detach();
}

void KRSftpModule::Upload(const KRAnyValue &params, const KRRenderCallback &callback) {
    std::thread([params, callback]() {
        try {
            float progress = KRSftpSession::Upload(params);
            KRRenderValueMap result;
            result["progress"] = KRRenderValue::Make(progress);
            result["success"] = KRRenderValue::Make(true);
            callback(KRRenderValue::Make(result));
        } catch (const std::exception &e) {
            KRRenderValueMap err;
            err["error"] = KRRenderValue::Make(SftpErrorFormatter::Format(e));
            callback(KRRenderValue::Make(err));
        }
    }).detach();
}

void KRSftpModule::Mkdir(const KRAnyValue &params, const KRRenderCallback &callback) {
    std::thread([params, callback]() {
        try {
            KRSftpSession::Mkdir(params);
            KRRenderValueMap result;
            result["ok"] = KRRenderValue::Make(true);
            callback(KRRenderValue::Make(result));
        } catch (const std::exception &e) {
            KRRenderValueMap err;
            err["error"] = KRRenderValue::Make(SftpErrorFormatter::Format(e));
            callback(KRRenderValue::Make(err));
        }
    }).detach();
}

void KRSftpModule::Rm(const KRAnyValue &params, const KRRenderCallback &callback) {
    std::thread([params, callback]() {
        try {
            KRSftpSession::Rm(params);
            KRRenderValueMap result;
            result["ok"] = KRRenderValue::Make(true);
            callback(KRRenderValue::Make(result));
        } catch (const std::exception &e) {
            KRRenderValueMap err;
            err["error"] = KRRenderValue::Make(SftpErrorFormatter::Format(e));
            callback(KRRenderValue::Make(err));
        }
    }).detach();
}

void KRSftpModule::Rename(const KRAnyValue &params, const KRRenderCallback &callback) {
    std::thread([params, callback]() {
        try {
            KRSftpSession::Rename(params);
            KRRenderValueMap result;
            result["ok"] = KRRenderValue::Make(true);
            callback(KRRenderValue::Make(result));
        } catch (const std::exception &e) {
            KRRenderValueMap err;
            err["error"] = KRRenderValue::Make(SftpErrorFormatter::Format(e));
            callback(KRRenderValue::Make(err));
        }
    }).detach();
}

void KRSftpModule::Move(const KRAnyValue &params, const KRRenderCallback &callback) {
    std::thread([params, callback]() {
        try {
            KRSftpSession::Move(params);
            KRRenderValueMap result;
            result["ok"] = KRRenderValue::Make(true);
            callback(KRRenderValue::Make(result));
        } catch (const std::exception &e) {
            KRRenderValueMap err;
            err["error"] = KRRenderValue::Make(SftpErrorFormatter::Format(e));
            callback(KRRenderValue::Make(err));
        }
    }).detach();
}

void KRSftpModule::Copy(const KRAnyValue &params, const KRRenderCallback &callback) {
    std::thread([params, callback]() {
        try {
            auto copyResult = KRSftpSession::Copy(params);
            KRRenderValueMap result;
            result["result"] = KRRenderValue::Make(copyResult);
            callback(KRRenderValue::Make(result));
        } catch (const std::exception &e) {
            KRRenderValueMap err;
            err["error"] = KRRenderValue::Make(SftpErrorFormatter::Format(e));
            callback(KRRenderValue::Make(err));
        }
    }).detach();
}

void KRSftpModule::Chmod(const KRAnyValue &params, const KRRenderCallback &callback) {
    std::thread([params, callback]() {
        try {
            KRSftpSession::Chmod(params);
            KRRenderValueMap result;
            result["ok"] = KRRenderValue::Make(true);
            callback(KRRenderValue::Make(result));
        } catch (const std::exception &e) {
            KRRenderValueMap err;
            err["error"] = KRRenderValue::Make(SftpErrorFormatter::Format(e));
            callback(KRRenderValue::Make(err));
        }
    }).detach();
}

void KRSftpModule::Chown(const KRAnyValue &params, const KRRenderCallback &callback) {
    std::thread([params, callback]() {
        try {
            KRSftpSession::Chown(params);
            KRRenderValueMap result;
            result["ok"] = KRRenderValue::Make(true);
            callback(KRRenderValue::Make(result));
        } catch (const std::exception &e) {
            KRRenderValueMap err;
            err["error"] = KRRenderValue::Make(SftpErrorFormatter::Format(e));
            callback(KRRenderValue::Make(err));
        }
    }).detach();
}

void KRSftpModule::SetMtime(const KRAnyValue &params, const KRRenderCallback &callback) {
    std::thread([params, callback]() {
        try {
            KRSftpSession::SetMtime(params);
            KRRenderValueMap result;
            result["ok"] = KRRenderValue::Make(true);
            callback(KRRenderValue::Make(result));
        } catch (const std::exception &e) {
            KRRenderValueMap err;
            err["error"] = KRRenderValue::Make(SftpErrorFormatter::Format(e));
            callback(KRRenderValue::Make(err));
        }
    }).detach();
}

void KRSftpModule::BatchTask(const KRAnyValue &params, const KRRenderCallback &callback) {
    auto p = ParamsMap(params);
    ValueMap sub = p;
    // 与 Android 一致：COPY/DOWNLOAD 需要本地落盘目录，Kotlin 未给时用沙盒 filesDir。
    if (StrOf(p, "localDir").empty()) {
        std::string filesDir;
        if (auto root = GetRootView().lock()) {
            filesDir = root->GetContext()->Config()->GetFilesDir();
        }
        if (!filesDir.empty()) {
            sub["localDir"] = KRRenderValue::Make(filesDir);
        }
    }
    KRAnyValue enriched = KRRenderValue::Make(sub);
    std::thread([enriched, callback]() {
        try {
            float progress = KRSftpSession::BatchTask(enriched);
            KRRenderValueMap result;
            result["progress"] = KRRenderValue::Make(progress);
            result["success"] = KRRenderValue::Make(true);
            callback(KRRenderValue::Make(result));
        } catch (const std::exception &e) {
            KRRenderValueMap err;
            err["error"] = KRRenderValue::Make(SftpErrorFormatter::Format(e));
            callback(KRRenderValue::Make(err));
        }
    }).detach();
}

void KRSftpModule::CancelBatchTask(const KRAnyValue &params, const KRRenderCallback &callback) {
    std::string taskId = StrOf(ParamsMap(params), "taskId");
    std::thread([taskId, callback]() {
        KRSftpSession::CancelBatchTask(taskId);
        KRRenderValueMap result;
        result["ok"] = KRRenderValue::Make(true);
        callback(KRRenderValue::Make(result));
    }).detach();
}

}  // namespace module
}  // namespace kuikly

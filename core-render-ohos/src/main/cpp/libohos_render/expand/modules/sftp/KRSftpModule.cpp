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
#include "libohos_render/utils/KRJSONObject.h"
#include "KRSftpSession.h"
#include "KRSftpFileHandle.h"
#include "SftpErrorFormatter.h"

namespace kuikly {
namespace module {

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
    KRSftpSession::ShutdownAll();
    KRSftpFileHandle::CloseAll();
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
    auto p = KRJSONObject::FromAnyValue(params);
    std::string sessionId = p->GetString("sessionId");
    std::thread([sessionId, callback]() {
        KRSftpSession::Disconnect(sessionId);
        KRRenderValueMap result;
        result["ok"] = KRRenderValue::Make(true);
        callback(KRRenderValue::Make(result));
    }).detach();
}

void KRSftpModule::List(const KRAnyValue &params, const KRRenderCallback &callback) {
    auto p = KRJSONObject::FromAnyValue(params);
    std::string sessionId = p->GetString("sessionId");
    std::string remotePath = p->GetString("remotePath");
    std::thread([sessionId, remotePath, callback]() {
        try {
            auto entries = KRSftpSession::List(sessionId, remotePath);
            // Phase 1.2: 转 KRAnyValue 数组
            KRRenderValueMap result;
            result["hasMore"] = KRRenderValue::Make(false);
            callback(KRRenderValue::Make(result));
        } catch (const std::exception &e) {
            KRRenderValueMap err;
            err["error"] = KRRenderValue::Make(SftpErrorFormatter::Format(e));
            callback(KRRenderValue::Make(err));
        }
    }).detach();
}

void KRSftpModule::Stat(const KRAnyValue &params, const KRRenderCallback &callback) {
    auto p = KRJSONObject::FromAnyValue(params);
    std::string sessionId = p->GetString("sessionId");
    std::string remotePath = p->GetString("remotePath");
    bool followSymlink = p->GetBool("followSymlink");
    std::thread([sessionId, remotePath, followSymlink, callback]() {
        try {
            auto entry = KRSftpSession::Stat(sessionId, remotePath, followSymlink);
            KRRenderValueMap result;
            // Phase 1.2: 填 entry 字段
            callback(KRRenderValue::Make(result));
        } catch (const std::exception &e) {
            KRRenderValueMap err;
            err["error"] = KRRenderValue::Make(SftpErrorFormatter::Format(e));
            callback(KRRenderValue::Make(err));
        }
    }).detach();
}

void KRSftpModule::OpenRead(const KRAnyValue &params, const KRRenderCallback &callback) {
    auto p = KRJSONObject::FromAnyValue(params);
    std::string sessionId = p->GetString("sessionId");
    std::string remotePath = p->GetString("remotePath");
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
    // params is Array<Any?>: [fileHandleId, offset, length]
    // Phase 1.2: parse array, call KRSftpFileHandle::Read
    std::thread([params, callback]() {
        try {
            // Phase 1.2: val bytes = KRSftpFileHandle::Read(fileHandleId, offset, length)
            // 原子通道回包：[meta, ByteArray]
            KRRenderValueMap meta;
            meta["ok"] = KRRenderValue::Make(true);
            // Phase 1.2: callback(KRRenderValue::MakeArray({meta, bytes}))
            callback(KRRenderValue::Make(meta));
        } catch (const std::exception &e) {
            KRRenderValueMap meta;
            meta["error"] = KRRenderValue::Make(SftpErrorFormatter::Format(e));
            callback(KRRenderValue::Make(meta));
        }
    }).detach();
}

void KRSftpModule::Close(const KRAnyValue &params, const KRRenderCallback &callback) {
    auto p = KRJSONObject::FromAnyValue(params);
    std::string fileHandleId = p->GetString("fileHandleId");
    std::thread([fileHandleId, callback]() {
        KRSftpFileHandle::Close(fileHandleId);
        KRRenderValueMap result;
        result["ok"] = KRRenderValue::Make(true);
        callback(KRRenderValue::Make(result));
    }).detach();
}

void KRSftpModule::Download(const KRAnyValue &params, const KRRenderCallback &callback) {
    std::thread([params, callback]() {
        try {
            float progress = KRSftpSession::Download(params);
            KRRenderValueMap result;
            result["progress"] = KRRenderValue::Make(progress);
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
            auto result_map = KRSftpSession::Copy(params);
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
    std::thread([params, callback]() {
        try {
            float progress = KRSftpSession::BatchTask(params);
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
    auto p = KRJSONObject::FromAnyValue(params);
    std::string taskId = p->GetString("taskId");
    std::thread([taskId, callback]() {
        KRSftpSession::CancelBatchTask(taskId);
        KRRenderValueMap result;
        result["ok"] = KRRenderValue::Make(true);
        callback(KRRenderValue::Make(result));
    }).detach();
}

}  // namespace module
}  // namespace kuikly

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
#import "KRSftpModule.h"
#import "NSObject+KR.h"
#import "KRSftpSession.h"
#import "KRSftpFileHandle.h"
#import "SftpErrorFormatter.h"
@implementation KRSftpModule

/// libssh2 的 session / SFTP 句柄不是线程安全的：并发调用会让请求在同一条 session 上交错，
/// 导致 list 读到过期结果、甚至握手偶发失败。因此所有 SFTP 操作统一串行在一条队列上执行。
static dispatch_queue_t KRSftpModuleSerialQueue(void) {
    static dispatch_queue_t q;
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        q = dispatch_queue_create("com.tencent.kuikly.sftp.module", DISPATCH_QUEUE_SERIAL);
    });
    return q;
}

- (void)connect:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] kr_stringToDictionary];
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    dispatch_async(KRSftpModuleSerialQueue(), ^{
        @try {
            NSString *sessionId = [KRSftpSession connect:params];
            if (callback) callback(@{@"sessionId": sessionId});
        } @catch (NSException *e) {
            if (callback) callback(@{@"error": [SftpErrorFormatter formatException:e]});
        }
    });
}

- (void)disconnect:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] kr_stringToDictionary];
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    NSString *sessionId = params[@"sessionId"];
    dispatch_async(KRSftpModuleSerialQueue(), ^{
        [KRSftpSession disconnect:sessionId];
        if (callback) callback(@{@"ok": @YES});
    });
}

- (void)list:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] kr_stringToDictionary];
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    NSString *sessionId = params[@"sessionId"];
    NSString *remotePath = params[@"remotePath"];
    dispatch_async(KRSftpModuleSerialQueue(), ^{
        @try {
            NSArray *entries = [KRSftpSession list:sessionId remotePath:remotePath];
            if (callback) callback(@{@"entries": entries, @"hasMore": @NO});
        } @catch (NSException *e) {
            if (callback) callback(@{@"error": [SftpErrorFormatter formatException:e]});
        }
    });
}

- (void)stat:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] kr_stringToDictionary];
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    NSString *sessionId = params[@"sessionId"];
    NSString *remotePath = params[@"remotePath"];
    BOOL followSymlink = [params[@"followSymlink"] boolValue];
    dispatch_async(KRSftpModuleSerialQueue(), ^{
        @try {
            NSDictionary *entry = [KRSftpSession stat:sessionId remotePath:remotePath followSymlink:followSymlink];
            if (callback) callback(@{@"entry": entry});
        } @catch (NSException *e) {
            if (callback) callback(@{@"error": [SftpErrorFormatter formatException:e]});
        }
    });
}

- (void)openRead:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] kr_stringToDictionary];
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    NSString *sessionId = params[@"sessionId"];
    NSString *remotePath = params[@"remotePath"];
    dispatch_async(KRSftpModuleSerialQueue(), ^{
        @try {
            NSString *fileHandleId = [KRSftpFileHandle openRead:sessionId remotePath:remotePath];
            if (callback) callback(@{@"fileHandleId": fileHandleId});
        } @catch (NSException *e) {
            if (callback) callback(@{@"error": [SftpErrorFormatter formatException:e]});
        }
    });
}

- (void)read:(NSDictionary *)args {
    NSArray *array = args[KR_PARAM_KEY];
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    NSString *fileHandleId = array[0];
    long long offset = [array[1] longLongValue];
    int length = [array[2] intValue];
    dispatch_async(KRSftpModuleSerialQueue(), ^{
        @try {
            NSData *bytes = [KRSftpFileHandle read:fileHandleId offset:offset length:length];
            NSDictionary *meta = @{@"ok": @YES};
            if (callback) callback(@[meta, bytes ?: [NSNull null]]);
        } @catch (NSException *e) {
            NSDictionary *meta = @{@"error": [SftpErrorFormatter formatException:e]};
            if (callback) callback(@[meta, [NSNull null]]);
        }
    });
}

- (void)close:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] kr_stringToDictionary];
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    NSString *fileHandleId = params[@"fileHandleId"];
    dispatch_async(KRSftpModuleSerialQueue(), ^{
        [KRSftpFileHandle close:fileHandleId];
        if (callback) callback(@{@"ok": @YES});
    });
}

- (void)download:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] kr_stringToDictionary];
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    dispatch_async(KRSftpModuleSerialQueue(), ^{
        @try {
            NSDictionary *result = [KRSftpSession download:params];
            if (callback) callback(@{@"progress": result[@"progress"] ?: @1.0f,
                                     @"path": result[@"path"] ?: @"",
                                     @"success": @YES});
        } @catch (NSException *e) {
            if (callback) callback(@{@"error": [SftpErrorFormatter formatException:e]});
        }
    });
}

- (void)upload:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] kr_stringToDictionary];
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    dispatch_async(KRSftpModuleSerialQueue(), ^{
        @try {
            float progress = [KRSftpSession upload:params];
            if (callback) callback(@{@"progress": @(progress), @"success": @YES});
        } @catch (NSException *e) {
            if (callback) callback(@{@"error": [SftpErrorFormatter formatException:e]});
        }
    });
}

- (void)mkdir:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] kr_stringToDictionary];
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    dispatch_async(KRSftpModuleSerialQueue(), ^{
        @try {
            BOOL ok = [KRSftpSession mkdir:params];
            if (callback) callback(ok ? @{@"ok": @YES} : @{@"error": [SftpErrorFormatter formatNSError:[NSError errorWithDomain:@"KuiklySftp" code:2001 userInfo:@{NSLocalizedDescriptionKey: @"mkdir failed"}]]});
        } @catch (NSException *e) {
            if (callback) callback(@{@"error": [SftpErrorFormatter formatException:e]});
        }
    });
}

- (void)rm:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] kr_stringToDictionary];
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    dispatch_async(KRSftpModuleSerialQueue(), ^{
        @try {
            BOOL ok = [KRSftpSession rm:params];
            if (callback) callback(ok ? @{@"ok": @YES} : @{@"error": [SftpErrorFormatter formatNSError:[NSError errorWithDomain:@"KuiklySftp" code:2001 userInfo:@{NSLocalizedDescriptionKey: @"rm failed"}]]});
        } @catch (NSException *e) {
            if (callback) callback(@{@"error": [SftpErrorFormatter formatException:e]});
        }
    });
}

- (void)rename:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] kr_stringToDictionary];
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    dispatch_async(KRSftpModuleSerialQueue(), ^{
        @try {
            BOOL ok = [KRSftpSession rename:params];
            if (callback) callback(ok ? @{@"ok": @YES} : @{@"error": [SftpErrorFormatter formatNSError:[NSError errorWithDomain:@"KuiklySftp" code:2001 userInfo:@{NSLocalizedDescriptionKey: @"rename failed"}]]});
        } @catch (NSException *e) {
            if (callback) callback(@{@"error": [SftpErrorFormatter formatException:e]});
        }
    });
}

- (void)move:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] kr_stringToDictionary];
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    dispatch_async(KRSftpModuleSerialQueue(), ^{
        @try {
            BOOL ok = [KRSftpSession move:params];
            if (callback) callback(ok ? @{@"ok": @YES} : @{@"error": [SftpErrorFormatter formatNSError:[NSError errorWithDomain:@"KuiklySftp" code:2001 userInfo:@{NSLocalizedDescriptionKey: @"move failed"}]]});
        } @catch (NSException *e) {
            if (callback) callback(@{@"error": [SftpErrorFormatter formatException:e]});
        }
    });
}

- (void)copy:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] kr_stringToDictionary];
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    dispatch_async(KRSftpModuleSerialQueue(), ^{
        @try {
            NSDictionary *result = [KRSftpSession copy:params];
            if (callback) callback(@{@"result": result, @"ok": @YES});
        } @catch (NSException *e) {
            if (callback) callback(@{@"error": [SftpErrorFormatter formatException:e]});
        }
    });
}

- (void)chmod:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] kr_stringToDictionary];
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    dispatch_async(KRSftpModuleSerialQueue(), ^{
        @try {
            BOOL ok = [KRSftpSession chmod:params];
            if (callback) callback(ok ? @{@"ok": @YES} : @{@"error": [SftpErrorFormatter formatNSError:[NSError errorWithDomain:@"KuiklySftp" code:2001 userInfo:@{NSLocalizedDescriptionKey: @"chmod failed"}]]});
        } @catch (NSException *e) {
            if (callback) callback(@{@"error": [SftpErrorFormatter formatException:e]});
        }
    });
}

- (void)chown:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] kr_stringToDictionary];
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    dispatch_async(KRSftpModuleSerialQueue(), ^{
        @try {
            BOOL ok = [KRSftpSession chown:params];
            if (callback) callback(ok ? @{@"ok": @YES} : @{@"error": [SftpErrorFormatter formatNSError:[NSError errorWithDomain:@"KuiklySftp" code:2001 userInfo:@{NSLocalizedDescriptionKey: @"chown failed"}]]});
        } @catch (NSException *e) {
            if (callback) callback(@{@"error": [SftpErrorFormatter formatException:e]});
        }
    });
}

- (void)setMtime:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] kr_stringToDictionary];
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    dispatch_async(KRSftpModuleSerialQueue(), ^{
        @try {
            BOOL ok = [KRSftpSession setMtime:params];
            if (callback) callback(ok ? @{@"ok": @YES} : @{@"error": [SftpErrorFormatter formatNSError:[NSError errorWithDomain:@"KuiklySftp" code:2001 userInfo:@{NSLocalizedDescriptionKey: @"setMtime failed"}]]});
        } @catch (NSException *e) {
            if (callback) callback(@{@"error": [SftpErrorFormatter formatException:e]});
        }
    });
}

- (void)batchTask:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] kr_stringToDictionary];
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    dispatch_async(KRSftpModuleSerialQueue(), ^{
        @try {
            float progress = [KRSftpSession batchTask:params];
            if (callback) callback(@{@"progress": @(progress), @"success": @YES});
        } @catch (NSException *e) {
            if (callback) callback(@{@"error": [SftpErrorFormatter formatException:e]});
        }
    });
}

- (void)cancelBatchTask:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] kr_stringToDictionary];
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    NSString *taskId = params[@"taskId"];
    dispatch_async(KRSftpModuleSerialQueue(), ^{
        [KRSftpSession cancelBatchTask:taskId];
        if (callback) callback(@{@"ok": @YES});
    });
}

@end

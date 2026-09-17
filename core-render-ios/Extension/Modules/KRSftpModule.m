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

- (void)connect:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] kr_stringToDictionary];
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{
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
    dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{
        [KRSftpSession disconnect:sessionId];
        if (callback) callback(@{@"ok": @YES});
    });
}

- (void)list:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] kr_stringToDictionary];
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    NSString *sessionId = params[@"sessionId"];
    NSString *remotePath = params[@"remotePath"];
    dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{
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
    dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{
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
    dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{
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
    dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{
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
    dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{
        [KRSftpFileHandle close:fileHandleId];
        if (callback) callback(@{@"ok": @YES});
    });
}

- (void)download:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] kr_stringToDictionary];
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{
        @try {
            float progress = [KRSftpSession download:params];
            if (callback) callback(@{@"progress": @(progress), @"path": params[@"localName"] ?: @""});
        } @catch (NSException *e) {
            if (callback) callback(@{@"error": [SftpErrorFormatter formatException:e]});
        }
    });
}

- (void)upload:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] kr_stringToDictionary];
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{
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
    dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{
        @try {
            [KRSftpSession mkdir:params];
            if (callback) callback(@{@"ok": @YES});
        } @catch (NSException *e) {
            if (callback) callback(@{@"error": [SftpErrorFormatter formatException:e]});
        }
    });
}

- (void)rm:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] kr_stringToDictionary];
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{
        @try {
            [KRSftpSession rm:params];
            if (callback) callback(@{@"ok": @YES});
        } @catch (NSException *e) {
            if (callback) callback(@{@"error": [SftpErrorFormatter formatException:e]});
        }
    });
}

- (void)rename:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] kr_stringToDictionary];
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{
        @try {
            [KRSftpSession rename:params];
            if (callback) callback(@{@"ok": @YES});
        } @catch (NSException *e) {
            if (callback) callback(@{@"error": [SftpErrorFormatter formatException:e]});
        }
    });
}

- (void)move:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] kr_stringToDictionary];
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{
        @try {
            [KRSftpSession move:params];
            if (callback) callback(@{@"ok": @YES});
        } @catch (NSException *e) {
            if (callback) callback(@{@"error": [SftpErrorFormatter formatException:e]});
        }
    });
}

- (void)copy:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] kr_stringToDictionary];
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{
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
    dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{
        @try {
            [KRSftpSession chmod:params];
            if (callback) callback(@{@"ok": @YES});
        } @catch (NSException *e) {
            if (callback) callback(@{@"error": [SftpErrorFormatter formatException:e]});
        }
    });
}

- (void)chown:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] kr_stringToDictionary];
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{
        @try {
            [KRSftpSession chown:params];
            if (callback) callback(@{@"ok": @YES});
        } @catch (NSException *e) {
            if (callback) callback(@{@"error": [SftpErrorFormatter formatException:e]});
        }
    });
}

- (void)setMtime:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] kr_stringToDictionary];
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{
        @try {
            [KRSftpSession setMtime:params];
            if (callback) callback(@{@"ok": @YES});
        } @catch (NSException *e) {
            if (callback) callback(@{@"error": [SftpErrorFormatter formatException:e]});
        }
    });
}

- (void)batchTask:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] kr_stringToDictionary];
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{
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
    dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{
        [KRSftpSession cancelBatchTask:taskId];
        if (callback) callback(@{@"ok": @YES});
    });
}

@end

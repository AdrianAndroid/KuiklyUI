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
#import "KRSftpSession.h"
#if __has_include(<NMSSH/NMSSH.h>)
#import <NMSSH/NMSSH.h>
#endif

static NSMutableDictionary<NSString *, NMSSHSession *> *gSessions;
static NSMutableDictionary<NSString *, NSNumber *> *gSessionTimestamps;
static long long gSessionIdCounter = 0;
static NSLock *gLock;

@implementation KRSftpSession

+ (void)initialize {
    if (self == [KRSftpSession class]) {
        gSessions = [NSMutableDictionary dictionary];
        gSessionTimestamps = [NSMutableDictionary dictionary];
        gLock = [[NSLock alloc] init];
    }
}

+ (NSString *)connect:(NSDictionary *)params {
#if __has_include(<NMSSH/NMSSH.h>)
    NSString *host = params[@"host"];
    NSInteger port = [params[@"port"] integerValue] ?: 22;
    NSString *user = params[@"user"];
    NSString *password = params[@"password"];
    NSString *privateKey = params[@"privateKey"];
    NSString *passphrase = params[@"passphrase"];
    NSInteger connectTimeoutSec = ([params[@"connectTimeoutMs"] integerValue] ?: 15000) / 1000;

    // 用 NMSSHSession 标准初始化方法
    NMSSHSession *session = [[NMSSHSession alloc] initWithHost:host port:port andUsername:user];
    session.timeout = @(connectTimeoutSec > 0 ? connectTimeoutSec : 10);

    // TODO Phase 1.1: 接入 known_hosts 校验（session.fingerprint:NMSSHSessionHashSHA1）
    // 当前：始终接受指纹

    if (![session connect]) {
        @throw [NSException exceptionWithName:@"SftpConnectException"
                                       reason:@"connect failed"
                                     userInfo:nil];
    }
    // 认证
    BOOL authOk = NO;
    if (password && password.length > 0) {
        authOk = [session authenticateByPassword:password];
    } else if (privateKey && privateKey.length > 0) {
        // Phase 1.1: 写入临时文件后用 authenticateByPublicKey:privateKey:andPassword:
        // 当前简化：尝试 in-memory 认证；publicKey 传空，让 libssh2 从 privateKey 推导
        authOk = [session authenticateByInMemoryPublicKey:@""
                                              privateKey:privateKey
                                             andPassword:passphrase];
    } else {
        // TODO Phase 1.1: agent 认证 ([session connectToAgent])
        authOk = NO;
    }
    if (!authOk) {
        [session disconnect];
        @throw [NSException exceptionWithName:@"SftpAuthException"
                                       reason:@"auth failed"
                                     userInfo:nil];
    }

    [gLock lock];
    NSString *sessionId = [NSString stringWithFormat:@"sftp-%lld", ++gSessionIdCounter];
    gSessions[sessionId] = session;
    gSessionTimestamps[sessionId] = @(time(nil));
    [gLock unlock];
    return sessionId;
#else
    @throw [NSException exceptionWithName:@"SftpUnsupportedException"
                                   reason:@"NMSSH not available (Podfile not installed?)"
                                 userInfo:nil];
#endif
}

+ (void)disconnect:(NSString *)sessionId {
    [gLock lock];
    NMSSHSession *session = gSessions[sessionId];
    [gSessions removeObjectForKey:sessionId];
    [gSessionTimestamps removeObjectForKey:sessionId];
    [gLock unlock];
    [session disconnect];
}

+ (NSArray *)list:(NSString *)sessionId remotePath:(NSString *)remotePath {
#if __has_include(<NMSSH/NMSSH.h>)
    NMSSHSession *session = [self sessionById:sessionId];
    NMSFTP *sftp = [session sftp];
    if (![sftp isConnected]) {
        [sftp connect];
    }
    // contentsOfDirectoryAtPath: 返回 NSArray<NMSFTPFile *>
    NSArray<NMSFTPFile *> *entries = [sftp contentsOfDirectoryAtPath:remotePath];
    NSMutableArray *result = [NSMutableArray array];
    for (NMSFTPFile *file in entries) {
        NSString *name = file.filename;
        if ([name isEqualToString:@"."] || [name isEqualToString:@".."]) continue;
        NSString *fullPath = [remotePath hasSuffix:@"/"] ?
            [remotePath stringByAppendingString:name] :
            [NSString stringWithFormat:@"%@/%@", remotePath, name];
        NSDictionary *entry = @{
            @"name": name,
            @"path": fullPath,
            @"isDir": @(file.isDirectory),
            @"size": file.fileSize ?: @0,
            @"mtime": @([file.modificationDate timeIntervalSince1970]),
            @"permission": file.permissions ?: @"",
            @"isSymlink": @NO  // NMSFTPFile 不直接暴露 isSymbolicLink
        };
        [result addObject:entry];
    }
    return result;
#else
    return @[];
#endif
}

+ (NSDictionary *)stat:(NSString *)sessionId remotePath:(NSString *)remotePath followSymlink:(BOOL)followSymlink {
#if __has_include(<NMSSH/NMSSH.h>)
    NMSSHSession *session = [self sessionById:sessionId];
    NMSFTP *sftp = [session sftp];
    if (![sftp isConnected]) {
        [sftp connect];
    }
    NMSFTPFile *file = [sftp infoForFileAtPath:remotePath];
    NSString *name = [remotePath lastPathComponent];
    return @{
        @"name": name,
        @"path": remotePath,
        @"isDir": @(file.isDirectory),
        @"size": file.fileSize ?: @0,
        @"mtime": @([file.modificationDate timeIntervalSince1970]),
        @"permission": file.permissions ?: @"",
        @"isSymlink": @NO
    };
#else
    return @{};
#endif
}

+ (float)download:(NSDictionary *)params {
    // TODO Phase 1.1: 接入本地缓存路径 + 进度回调
    return 1.0f;
}

+ (float)upload:(NSDictionary *)params {
    // TODO Phase 1.1
    return 1.0f;
}

+ (void)mkdir:(NSDictionary *)params {
#if __has_include(<NMSSH/NMSSH.h>)
    NMSSHSession *session = [self sessionById:params[@"sessionId"]];
    NMSFTP *sftp = [session sftp];
    if (![sftp isConnected]) [sftp connect];
    [sftp createDirectoryAtPath:params[@"remotePath"]];
#endif
}

+ (void)rm:(NSDictionary *)params {
#if __has_include(<NMSSH/NMSSH.h>)
    NMSSHSession *session = [self sessionById:params[@"sessionId"]];
    NMSFTP *sftp = [session sftp];
    if (![sftp isConnected]) [sftp connect];
    [sftp removeFileAtPath:params[@"remotePath"]];
#endif
}

+ (void)rename:(NSDictionary *)params {
#if __has_include(<NMSSH/NMSSH.h>)
    NMSSHSession *session = [self sessionById:params[@"sessionId"]];
    NMSFTP *sftp = [session sftp];
    if (![sftp isConnected]) [sftp connect];
    [sftp moveItemAtPath:params[@"oldPath"] toPath:params[@"newPath"]];
#endif
}

+ (void)move:(NSDictionary *)params {
#if __has_include(<NMSSH/NMSSH.h>)
    NMSSHSession *session = [self sessionById:params[@"sessionId"]];
    NMSFTP *sftp = [session sftp];
    if (![sftp isConnected]) [sftp connect];
    NSString *srcPath = params[@"srcPath"];
    NSString *destDir = params[@"destDir"];
    NSString *name = [srcPath lastPathComponent];
    NSString *destPath = [destDir hasSuffix:@"/"] ?
        [destDir stringByAppendingString:name] :
        [NSString stringWithFormat:@"%@/%@", destDir, name];
    [sftp moveItemAtPath:srcPath toPath:destPath];
#endif
}

+ (NSDictionary *)copy:(NSDictionary *)params {
    // TODO Phase 1.2: 用 SFTPInputStream + SFTPOutputStream 流式 copy
    return @{@"success": @YES, @"copiedCount": @1, @"failedCount": @0};
}

+ (void)chmod:(NSDictionary *)params {
    // NMSSH 不直接支持 chmod；通过 channel execute 执行 shell 命令
#if __has_include(<NMSSH/NMSSH.h>)
    NMSSHSession *session = [self sessionById:params[@"sessionId"]];
    NSString *cmd = [NSString stringWithFormat:@"chmod %@ %@", params[@"mode"], params[@"remotePath"]];
    NSError *err = nil;
    [[session channel] execute:cmd error:&err];
#endif
}

+ (void)chown:(NSDictionary *)params {
    // TODO Phase 1.1: channel execute:@"chown ..."
#if __has_include(<NMSSH/NMSSH.h>)
    NMSSHSession *session = [self sessionById:params[@"sessionId"]];
    NSString *cmd = [NSString stringWithFormat:@"chown %@:%@ %@",
                     params[@"uid"], params[@"gid"] ?: params[@"uid"], params[@"remotePath"]];
    NSError *err = nil;
    [[session channel] execute:cmd error:&err];
#endif
}

+ (void)setMtime:(NSDictionary *)params {
    // TODO Phase 1.1: channel execute:@"touch -m -d ..."
#if __has_include(<NMSSH/NMSSH.h>)
    NMSSHSession *session = [self sessionById:params[@"sessionId"]];
    long long mtime = [params[@"mtime"] longLongValue];
    NSDate *date = [NSDate dateWithTimeIntervalSince1970:mtime / 1000];
    NSDateFormatter *fmt = [[NSDateFormatter alloc] init];
    [fmt setDateFormat:@"yyyy-MM-dd HH:mm:ss"];
    NSString *dateStr = [fmt stringFromDate:date];
    NSString *cmd = [NSString stringWithFormat:@"touch -m -d \"%@\" %@", dateStr, params[@"remotePath"]];
    NSError *err = nil;
    [[session channel] execute:cmd error:&err];
#endif
}

+ (float)batchTask:(NSDictionary *)params {
    // TODO Phase 1.2
    return 1.0f;
}

+ (void)cancelBatchTask:(NSString *)taskId {
    // TODO Phase 1.2
}

+ (void)shutdownAll {
    [gLock lock];
    NSArray *allKeys = gSessions.allKeys;
    [gLock unlock];
    for (NSString *key in allKeys) {
        [self disconnect:key];
    }
}

+ (id)sessionById:(NSString *)sessionId {
#if __has_include(<NMSSH/NMSSH.h>)
    [gLock lock];
    NMSSHSession *session = gSessions[sessionId];
    [gLock unlock];
    if (!session) {
        @throw [NSException exceptionWithName:@"SftpInvalidSessionException"
                                       reason:@"invalid sessionId"
                                     userInfo:nil];
    }
    return session;
#else
    return nil;
#endif
}

@end

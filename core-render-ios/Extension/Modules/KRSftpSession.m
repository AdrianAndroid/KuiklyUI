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
#import "KRLogModule.h"
#if __has_include(<NMSSH/NMSSH.h>)
#import <NMSSH/NMSSH.h>
#endif

static NSMutableDictionary<NSString *, NMSSHSession *> *gSessions;
static NSMutableDictionary<NSString *, NSNumber *> *gSessionTimestamps;
static long long gSessionIdCounter = 0;
static NSLock *gLock;

@interface KRSftpSession ()
+ (BOOL)removeEntry:(NSString *)path sftp:(NMSFTP *)sftp recursive:(BOOL)recursive depth:(NSInteger)depth;
+ (NSString *)localDownloadPathForName:(NSString *)name;
+ (BOOL)ensureDirectory:(NSString *)path sftp:(NMSFTP *)sftp;
+ (void)copyEntry:(NSString *)src to:(NSString *)dest sftp:(NMSFTP *)sftp
           copied:(NSInteger *)copied failed:(NSInteger *)failed depth:(NSInteger)depth;
@end

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
        // Surface the underlying libssh2/NMSSH reason — without it a failed handshake
        // (e.g. the server offering only algorithms this libssh2 cannot negotiate) is
        // indistinguishable from a network error.
        NSString *detail = [NSString stringWithFormat:@"host=%@ port=%ld lastError=%@",
                            host ?: @"<nil>", (long)port,
                            session.lastError.localizedDescription ?: @"<none>"];
        [KRLogModule logInfo:[NSString stringWithFormat:@"[sftp] connect failed: %@", detail]];
        @throw [NSException exceptionWithName:@"SftpConnectException"
                                       reason:@"connect failed"
                                     userInfo:@{@"detail": detail}];
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
        NSString *detail = [NSString stringWithFormat:@"user=%@ lastError=%@",
                            user ?: @"<nil>",
                            session.lastError.localizedDescription ?: @"<none>"];
        [KRLogModule logInfo:[NSString stringWithFormat:@"[sftp] auth failed: %@", detail]];
        [session disconnect];
        @throw [NSException exceptionWithName:@"SftpAuthException"
                                       reason:@"auth failed"
                                     userInfo:@{@"detail": detail}];
    }
    [KRLogModule logInfo:[NSString stringWithFormat:@"[sftp] connected %@@%@:%ld", user, host, (long)port]];

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
        // NMSFTP 会给目录名追加 "/"（NMSFTP.m: "Append a \"/\" at the end of all directories"）。
        // 这里必须归一化，否则 UI 会显示 "Documents/"，且按名匹配（收藏/历史/路径拼接）全部失效。
        NSString *rawName = file.filename;
        BOOL isDir = file.isDirectory || [rawName hasSuffix:@"/"];
        NSString *name = [rawName hasSuffix:@"/"] ? [rawName substringToIndex:rawName.length - 1] : rawName;
        if ([name isEqualToString:@"."] || [name isEqualToString:@".."] || name.length == 0) continue;
        NSString *fullPath = [remotePath hasSuffix:@"/"] ?
            [remotePath stringByAppendingString:name] :
            [NSString stringWithFormat:@"%@/%@", remotePath, name];
        NSDictionary *entry = @{
            @"name": name,
            @"path": fullPath,
            @"isDir": @(isDir),
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
    if (!file) {
        // `infoForFileAtPath:` opens the path with LIBSSH2_FXF_READ, which always fails for
        // directories. Resolve those (and re-check existence) through the parent listing.
        NSString *parent = [remotePath stringByDeletingLastPathComponent];
        NSString *leaf = [remotePath lastPathComponent];
        if (parent.length == 0) parent = @"/";
        for (NMSFTPFile *candidate in [sftp contentsOfDirectoryAtPath:parent]) {
            NSString *candidateName = candidate.filename;
            if ([candidateName hasSuffix:@"/"]) {
                candidateName = [candidateName substringToIndex:candidateName.length - 1];
            }
            if ([candidateName isEqualToString:leaf]) {
                file = candidate;
                break;
            }
        }
    }
    if (!file) {
        @throw [NSException exceptionWithName:@"SftpNoSuchFileException"
                                       reason:[NSString stringWithFormat:@"no such file: %@", remotePath]
                                     userInfo:nil];
    }
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

+ (NSDictionary *)download:(NSDictionary *)params {
#if __has_include(<NMSSH/NMSSH.h>)
    NMSSHSession *session = [self sessionById:params[@"sessionId"]];
    NMSFTP *sftp = [session sftp];
    if (![sftp isConnected]) [sftp connect];
    NSString *remotePath = params[@"remotePath"];
    NSString *localName = params[@"localName"] ?: remotePath.lastPathComponent;
    NSString *destPath = [self localDownloadPathForName:localName];
    if (remotePath.length == 0 || destPath.length == 0) {
        @throw [NSException exceptionWithName:@"SftpInvalidParamException"
                                       reason:@"remotePath required" userInfo:nil];
    }
    NSOutputStream *out = [NSOutputStream outputStreamToFileAtPath:destPath append:NO];
    [out open];
    BOOL ok = [sftp contentsAtPath:remotePath toStream:out progress:nil];
    [out close];
    if (!ok) {
        [[NSFileManager defaultManager] removeItemAtPath:destPath error:nil];
        [KRLogModule logInfo:[NSString stringWithFormat:@"[sftp] download failed: %@", remotePath]];
        @throw [NSException exceptionWithName:@"SftpNoSuchFileException"
                                       reason:[NSString stringWithFormat:@"download failed: %@", remotePath]
                                     userInfo:nil];
    }
    // 回包带本地落地绝对路径（params 可能是不可变字典，不能就地写回）
    return @{@"progress": @1.0f, @"path": destPath};
#else
    @throw [NSException exceptionWithName:@"SftpNotImplementedException" reason:@"NMSSH not available" userInfo:nil];
#endif
}

/** 下载落地目录：Caches/KRSftpDownloads（沙箱内可写） */
+ (NSString *)localDownloadPathForName:(NSString *)name {
    NSArray<NSString *> *caches = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, YES);
    NSString *dir = [caches.firstObject stringByAppendingPathComponent:@"KRSftpDownloads"];
    [[NSFileManager defaultManager] createDirectoryAtPath:dir withIntermediateDirectories:YES attributes:nil error:nil];
    NSString *safe = name.lastPathComponent;
    if (safe.length == 0) safe = @"download";
    return [dir stringByAppendingPathComponent:safe];
}

+ (float)upload:(NSDictionary *)params {
#if __has_include(<NMSSH/NMSSH.h>)
    NMSSHSession *session = [self sessionById:params[@"sessionId"]];
    NMSFTP *sftp = [session sftp];
    if (![sftp isConnected]) [sftp connect];
    NSString *localPath = params[@"localPath"];
    NSString *remotePath = params[@"remotePath"];
    if (localPath.length == 0 || remotePath.length == 0) {
        @throw [NSException exceptionWithName:@"SftpInvalidParamException"
                                       reason:@"localPath/remotePath required" userInfo:nil];
    }
    if (![[NSFileManager defaultManager] fileExistsAtPath:localPath]) {
        @throw [NSException exceptionWithName:@"SftpNoSuchFileException"
                                       reason:[NSString stringWithFormat:@"local file missing: %@", localPath]
                                     userInfo:nil];
    }
    BOOL ok = [sftp writeFileAtPath:localPath toFileAtPath:remotePath progress:nil];
    if (!ok) {
        [KRLogModule logInfo:[NSString stringWithFormat:@"[sftp] upload failed: %@ -> %@",
                              localPath, remotePath]];
        @throw [NSException exceptionWithName:@"SftpPermissionException"
                                       reason:[NSString stringWithFormat:@"upload failed: %@", remotePath]
                                     userInfo:nil];
    }
    return 1.0f;
#else
    @throw [NSException exceptionWithName:@"SftpNotImplementedException" reason:@"NMSSH not available" userInfo:nil];
#endif
}

+ (BOOL)mkdir:(NSDictionary *)params {
#if __has_include(<NMSSH/NMSSH.h>)
    NMSSHSession *session = [self sessionById:params[@"sessionId"]];
    NMSFTP *sftp = [session sftp];
    if (![sftp isConnected]) [sftp connect];
    NSString *remotePath = params[@"remotePath"];
    BOOL recursive = [params[@"recursive"] boolValue];
    if (!recursive) {
        return [sftp createDirectoryAtPath:remotePath];
    }
    // NMSFTP has no recursive create; walk the path and create each missing segment.
    BOOL ok = YES;
    NSMutableArray<NSString *> *segments = [NSMutableArray array];
    for (NSString *seg in [remotePath componentsSeparatedByString:@"/"]) {
        if (seg.length > 0) [segments addObject:seg];
    }
    NSMutableString *cur = [NSMutableString string];
    if ([remotePath hasPrefix:@"/"]) [cur appendString:@"/"];
    for (NSString *seg in segments) {
        if (cur.length > 0 && ![cur hasSuffix:@"/"]) [cur appendString:@"/"];
        [cur appendString:seg];
        if ([sftp directoryExistsAtPath:cur]) continue;
        if (![sftp createDirectoryAtPath:cur]) {
            // Tolerate "already exists" races; verify instead of trusting the return value.
            if (![sftp directoryExistsAtPath:cur]) { ok = NO; break; }
        }
    }
    if (!ok) {
        [KRLogModule logInfo:[NSString stringWithFormat:@"[sftp] mkdir failed: %@", remotePath]];
    }
    return ok;
#else
    return NO;
#endif
}

+ (BOOL)rm:(NSDictionary *)params {
#if __has_include(<NMSSH/NMSSH.h>)
    NMSSHSession *session = [self sessionById:params[@"sessionId"]];
    NMSFTP *sftp = [session sftp];
    if (![sftp isConnected]) [sftp connect];
    NSString *remotePath = params[@"remotePath"];
    BOOL recursive = [params[@"recursive"] boolValue];
    BOOL ok = [self removeEntry:remotePath sftp:sftp recursive:recursive depth:0];
    if (!ok) {
        [KRLogModule logInfo:[NSString stringWithFormat:@"[sftp] rm failed: %@ recursive=%d", remotePath, recursive]];
    }
    return ok;
#else
    return NO;
#endif
}

/// unlink for files, rmdir for directories; `recursive` drains children first.
+ (BOOL)removeEntry:(NSString *)path sftp:(NMSFTP *)sftp recursive:(BOOL)recursive depth:(NSInteger)depth {
    if (depth > 64) return NO; // guard against symlink loops / pathological trees
    if ([sftp directoryExistsAtPath:path]) {
        if (recursive) {
            for (NMSFTPFile *child in [sftp contentsOfDirectoryAtPath:path]) {
                NSString *childName = child.filename;
                if ([childName hasSuffix:@"/"]) {
                    childName = [childName substringToIndex:childName.length - 1];
                }
                if ([childName isEqualToString:@"."] || [childName isEqualToString:@".."] || childName.length == 0) continue;
                NSString *childPath = [path hasSuffix:@"/"]
                    ? [path stringByAppendingString:childName]
                    : [NSString stringWithFormat:@"%@/%@", path, childName];
                if (![self removeEntry:childPath sftp:sftp recursive:YES depth:depth + 1]) return NO;
            }
        }
        return [sftp removeDirectoryAtPath:path];
    }
    return [sftp removeFileAtPath:path];
}

+ (BOOL)rename:(NSDictionary *)params {
#if __has_include(<NMSSH/NMSSH.h>)
    NMSSHSession *session = [self sessionById:params[@"sessionId"]];
    NMSFTP *sftp = [session sftp];
    if (![sftp isConnected]) [sftp connect];
    BOOL ok = [sftp moveItemAtPath:params[@"oldPath"] toPath:params[@"newPath"]];
    if (!ok) {
        [KRLogModule logInfo:[NSString stringWithFormat:@"[sftp] rename failed: %@", params]];
    }
    return ok;
#else
    return NO;
#endif
}

+ (BOOL)move:(NSDictionary *)params {
#if __has_include(<NMSSH/NMSSH.h>)
    NMSSHSession *session = [self sessionById:params[@"sessionId"]];
    NMSFTP *sftp = [session sftp];
    if (![sftp isConnected]) [sftp connect];
    NSString *srcPath = params[@"srcPath"];
    NSString *destDir = params[@"destDir"];
    NSString *name = [srcPath lastPathComponent];
    [self ensureDirectory:destDir sftp:sftp];
    NSString *destPath = [destDir hasSuffix:@"/"] ?
        [destDir stringByAppendingString:name] :
        [NSString stringWithFormat:@"%@/%@", destDir, name];
    BOOL ok = [sftp moveItemAtPath:srcPath toPath:destPath];
    if (!ok) {
        [KRLogModule logInfo:[NSString stringWithFormat:@"[sftp] move failed: %@ -> %@", srcPath, destPath]];
    }
    return ok;
#else
    return NO;
#endif
}

+ (NSDictionary *)copy:(NSDictionary *)params {
#if __has_include(<NMSSH/NMSSH.h>)
    NMSSHSession *session = [self sessionById:params[@"sessionId"]];
    NMSFTP *sftp = [session sftp];
    if (![sftp isConnected]) [sftp connect];
    NSString *srcPath = params[@"srcPath"];
    NSString *destPath = params[@"destPath"];
    if (srcPath.length == 0 || destPath.length == 0) {
        @throw [NSException exceptionWithName:@"SftpInvalidParamException"
                                       reason:@"srcPath/destPath required" userInfo:nil];
    }
    NSInteger copied = 0, failed = 0;
    [self copyEntry:srcPath to:destPath sftp:sftp copied:&copied failed:&failed depth:0];
    return @{@"success": @(failed == 0), @"copiedCount": @(copied), @"failedCount": @(failed)};
#else
    @throw [NSException exceptionWithName:@"SftpNotImplementedException" reason:@"NMSSH not available" userInfo:nil];
#endif
}


/** 递归确保远端目录存在（SFTP 无 mkdir -p） */
+ (BOOL)ensureDirectory:(NSString *)path sftp:(NMSFTP *)sftp {
    if (path.length == 0 || [path isEqualToString:@"/"] || [path isEqualToString:@"."]) return YES;
    if ([sftp directoryExistsAtPath:path]) return YES;
    NSMutableArray<NSString *> *segments = [NSMutableArray array];
    for (NSString *seg in [path componentsSeparatedByString:@"/"]) {
        if (seg.length > 0) [segments addObject:seg];
    }
    NSMutableString *cur = [NSMutableString string];
    if ([path hasPrefix:@"/"]) [cur appendString:@"/"];
    for (NSString *seg in segments) {
        if (cur.length > 0 && ![cur hasSuffix:@"/"]) [cur appendString:@"/"];
        [cur appendString:seg];
        if ([sftp directoryExistsAtPath:cur]) continue;
        if (![sftp createDirectoryAtPath:cur] && ![sftp directoryExistsAtPath:cur]) return NO;
    }
    return YES;
}

/** 远端→远端复制：文件走「下载到临时文件 → 上传」流式搬运，目录递归。
 *  之所以借道本地临时文件，是因为 SFTP 协议没有服务端拷贝原语，而一次性把
 *  整个文件读进内存会在大文件上爆内存。 */
+ (void)copyEntry:(NSString *)src to:(NSString *)dest sftp:(NMSFTP *)sftp
           copied:(NSInteger *)copied failed:(NSInteger *)failed depth:(NSInteger)depth {
    if (depth > 64) { (*failed)++; return; }
    BOOL isDir = [sftp directoryExistsAtPath:src];
    if (isDir) {
        if (![sftp directoryExistsAtPath:dest]) {
            if (![sftp createDirectoryAtPath:dest]) { (*failed)++; return; }
        }
        for (NMSFTPFile *child in [sftp contentsOfDirectoryAtPath:src]) {
            NSString *childName = child.filename;
            if ([childName hasSuffix:@"/"]) childName = [childName substringToIndex:childName.length - 1];
            if ([childName isEqualToString:@"."] || [childName isEqualToString:@".."] || childName.length == 0) continue;
            NSString *childSrc = [NSString stringWithFormat:@"%@/%@", src, childName];
            NSString *childDest = [NSString stringWithFormat:@"%@/%@", dest, childName];
            [self copyEntry:childSrc to:childDest sftp:sftp copied:copied failed:failed depth:depth + 1];
        }
        return;
    }

    // 复制文件前先确保目标父目录存在，否则写入会失败
    [self ensureDirectory:[dest stringByDeletingLastPathComponent] sftp:sftp];

    NSString *tmp = [self localDownloadPathForName:[NSString stringWithFormat:@"copy-%@", NSUUID.UUID.UUIDString]];
    NSOutputStream *out = [NSOutputStream outputStreamToFileAtPath:tmp append:NO];
    [out open];
    BOOL readOk = [sftp contentsAtPath:src toStream:out progress:nil];
    [out close];
    if (!readOk) {
        [[NSFileManager defaultManager] removeItemAtPath:tmp error:nil];
        (*failed)++;
        return;
    }
    BOOL writeOk = [sftp writeFileAtPath:tmp toFileAtPath:dest progress:nil];
    [[NSFileManager defaultManager] removeItemAtPath:tmp error:nil];
    if (writeOk) (*copied)++; else (*failed)++;
}

+ (BOOL)chmod:(NSDictionary *)params {
    // NMSSH 不直接支持 chmod；通过 channel execute 执行 shell 命令
#if __has_include(<NMSSH/NMSSH.h>)
    NMSSHSession *session = [self sessionById:params[@"sessionId"]];
    NSString *cmd = [NSString stringWithFormat:@"chmod %@ '%@'", params[@"mode"], params[@"remotePath"]];
    NSError *err = nil;
    [[session channel] execute:cmd error:&err];
    if (err) {
        [KRLogModule logInfo:[NSString stringWithFormat:@"[sftp] chmod failed: %@ (%@)", cmd, err.localizedDescription]];
    }
    return err == nil;
#else
    return NO;
#endif
}

+ (BOOL)chown:(NSDictionary *)params {
#if __has_include(<NMSSH/NMSSH.h>)
    NMSSHSession *session = [self sessionById:params[@"sessionId"]];
    NSString *remotePath = params[@"remotePath"];
    int uid = [params[@"uid"] intValue];
    int gid = [params[@"gid"] intValue];
    NSString *owner = nil;
    if (uid >= 0) {
        owner = (gid >= 0) ? [NSString stringWithFormat:@"%d:%d", uid, gid]
                           : [NSString stringWithFormat:@"%d", uid];
    } else {
        // NMSFTPFile 不暴露 uid/gid；未显式给出时回落到当前会话用户（等价于 chown 给自己）
        owner = session.username;
    }
    if (owner.length == 0) {
        @throw [NSException exceptionWithName:@"SftpInvalidParamException"
                                       reason:@"uid/gid or session username required" userInfo:nil];
    }
    NSString *cmd = [NSString stringWithFormat:@"chown %@ '%@'", owner, remotePath];
    NSError *err = nil;
    [[session channel] execute:cmd error:&err];
    if (err) {
        [KRLogModule logInfo:[NSString stringWithFormat:@"[sftp] chown failed: %@ (%@)",
                              cmd, err.localizedDescription]];
    }
    return err == nil;
#else
    return NO;
#endif
}

+ (BOOL)setMtime:(NSDictionary *)params {
    // TODO Phase 1.1: channel execute:@"touch -m -d ..."
#if __has_include(<NMSSH/NMSSH.h>)
    NMSSHSession *session = [self sessionById:params[@"sessionId"]];
    long long mtime = [params[@"mtime"] longLongValue];
    NSDate *date = [NSDate dateWithTimeIntervalSince1970:mtime / 1000];
    NSDateFormatter *fmt = [[NSDateFormatter alloc] init];
    [fmt setDateFormat:@"yyyy-MM-dd HH:mm:ss"];
    NSString *dateStr = [fmt stringFromDate:date];
    NSString *cmd = [NSString stringWithFormat:@"touch -m -d \"%@\" '%@'", dateStr, params[@"remotePath"]];
    NSError *err = nil;
    [[session channel] execute:cmd error:&err];
    if (err) {
        [KRLogModule logInfo:[NSString stringWithFormat:@"[sftp] setMtime failed: %@ (%@)", cmd, err.localizedDescription]];
    }
    return err == nil;
#else
    return NO;
#endif
}

+ (float)batchTask:(NSDictionary *)params {
#if __has_include(<NMSSH/NMSSH.h>)
    NMSSHSession *session = [self sessionById:params[@"sessionId"]];
    NMSFTP *sftp = [session sftp];
    if (![sftp isConnected]) [sftp connect];
    NSString *action = params[@"action"] ?: @"DELETE";
    NSArray *items = params[@"items"] ?: @[];
    NSString *targetDir = params[@"targetDir"];
    if (items.count == 0) {
        @throw [NSException exceptionWithName:@"SftpInvalidParamException"
                                       reason:@"items required" userInfo:nil];
    }
    // 批量写入类操作先确保目标目录存在（SFTP 不会自动建父目录）
    if (([action isEqualToString:@"COPY"] || [action isEqualToString:@"MOVE"]) && targetDir.length > 0) {
        if (![self ensureDirectory:targetDir sftp:sftp]) {
            @throw [NSException exceptionWithName:@"SftpPermissionException"
                                           reason:[NSString stringWithFormat:@"cannot create targetDir: %@", targetDir]
                                         userInfo:nil];
        }
    }
    NSInteger done = 0, failed = 0;
    for (NSString *item in items) {
        @try {
            if ([action isEqualToString:@"DELETE"]) {
                if ([self removeEntry:item sftp:sftp recursive:YES depth:0]) done++; else failed++;
            } else if ([action isEqualToString:@"MOVE"]) {
                NSString *dest = [self destPathFor:item targetDir:targetDir];
                if ([sftp moveItemAtPath:item toPath:dest]) done++; else failed++;
            } else if ([action isEqualToString:@"COPY"]) {
                NSInteger c = 0, f = 0;
                [self copyEntry:item to:[self destPathFor:item targetDir:targetDir]
                           sftp:sftp copied:&c failed:&f depth:0];
                done += c; failed += f;
            } else if ([action isEqualToString:@"DOWNLOAD"]) {
                NSString *dest = [self localDownloadPathForName:item.lastPathComponent];
                NSOutputStream *out = [NSOutputStream outputStreamToFileAtPath:dest append:NO];
                [out open];
                BOOL ok = [sftp contentsAtPath:item toStream:out progress:nil];
                [out close];
                if (ok) done++; else failed++;
            } else {
                failed++;
            }
        } @catch (NSException *e) {
            failed++;
        }
    }
    if (failed > 0) {
        [KRLogModule logInfo:[NSString stringWithFormat:@"[sftp] batch %@ done=%ld failed=%ld",
                              action, (long)done, (long)failed]];
        @throw [NSException exceptionWithName:@"SftpPermissionException"
                                       reason:[NSString stringWithFormat:@"batch %@ partially failed (%ld/%ld)",
                                               action, (long)failed, (long)(done + failed)]
                                     userInfo:nil];
    }
    return 1.0f;
#else
    @throw [NSException exceptionWithName:@"SftpNotImplementedException" reason:@"NMSSH not available" userInfo:nil];
#endif
}

/** 批量操作的目标路径：targetDir + 原文件名 */
+ (NSString *)destPathFor:(NSString *)srcPath targetDir:(NSString *)targetDir {
    NSString *name = srcPath.lastPathComponent;
    if (targetDir.length == 0) return srcPath;
    return [targetDir hasSuffix:@"/"] ? [targetDir stringByAppendingString:name]
                                     : [NSString stringWithFormat:@"%@/%@", targetDir, name];
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

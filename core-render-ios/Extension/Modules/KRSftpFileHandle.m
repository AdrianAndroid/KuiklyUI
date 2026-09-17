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
#import "KRSftpFileHandle.h"
#import "KRSftpSession.h"
#if __has_include(<NMSSH/NMSSH.h>)
#import <NMSSH/NMSSH.h>
#endif

static NSMutableDictionary<NSString *, NSDictionary *> *gHandles;
static long long gHandleIdCounter = 0;
static NSLock *gHandleLock;

@interface KRSftpFileHandle ()
@end

@implementation KRSftpFileHandle

+ (void)initialize {
    if (self == [KRSftpFileHandle class]) {
        gHandles = [NSMutableDictionary dictionary];
        gHandleLock = [[NSLock alloc] init];
    }
}

+ (NSString *)openRead:(NSString *)sessionId remotePath:(NSString *)remotePath {
#if __has_include(<NMSSH/NMSSH.h>)
    @autoreleasepool {
        // 校验 sessionId 有效（会抛异常若无效）
        NMSSHSession *session = [KRSftpSession sessionById:sessionId];
        if (!session) {
            @throw [NSException exceptionWithName:@"SftpNoSuchFileException"
                                           reason:[NSString stringWithFormat:@"invalid sessionId: %@", sessionId]
                                         userInfo:nil];
        }
        // 校验目标可读，避免返回一个永远读不出内容的假句柄
        NMSFTP *sftp = [session sftp];
        if (![sftp isConnected]) [sftp connect];
        if (![sftp fileExistsAtPath:remotePath]) {
            @throw [NSException exceptionWithName:@"SftpNoSuchFileException"
                                           reason:[NSString stringWithFormat:@"no such file: %@", remotePath]
                                         userInfo:nil];
        }
        // Phase 1.2: 用 NMSSH SFTPInputStream 打开随机读取
        [gHandleLock lock];
        NSString *fileHandleId = [NSString stringWithFormat:@"fh-%lld", ++gHandleIdCounter];
        gHandles[fileHandleId] = @{
            @"sessionId": sessionId,
            @"remotePath": remotePath,
            @"offset": @0
        };
        [gHandleLock unlock];
        return fileHandleId;
    }
#else
    return @"";
#endif
}

+ (NSData *)read:(NSString *)fileHandleId offset:(long long)offset length:(int)length {
    // TODO Phase 1.2: 用 NMSSH SFTPInputStream 流式读
    @throw [NSException exceptionWithName:@"SftpNotImplementedException"
                                   reason:@"read not implemented yet"
                                 userInfo:nil];
}

+ (void)close:(NSString *)fileHandleId {
    [gHandleLock lock];
    [gHandles removeObjectForKey:fileHandleId];
    [gHandleLock unlock];
}

+ (void)closeAll {
    [gHandleLock lock];
    [gHandles removeAllObjects];
    [gHandleLock unlock];
}

@end

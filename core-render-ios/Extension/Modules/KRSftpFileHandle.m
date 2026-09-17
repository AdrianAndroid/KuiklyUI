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
#import "KRLogModule.h"
#if __has_include(<NMSSH/NMSSH.h>)
#import <NMSSH/NMSSH.h>
#endif

/**
 * 一个打开中的远端文件（§7.1.3 / §5.2）。
 *
 * 持有自己的一条 libssh2 SFTP 会话与文件句柄，因此可以 `seek64 + read` 随机读取——
 * 这正是「HTTP Range → SFTP lseek+read」流式播放（§5.2）所依赖的底座。
 * NMSFTP 只提供整体读写，拿不到内部句柄，所以这里直接用 libssh2。
 */
@interface KRSftpOpenFile : NSObject
@property (nonatomic, copy) NSString *sessionId;
@property (nonatomic, copy) NSString *remotePath;
@property (nonatomic, assign) LIBSSH2_SFTP *sftp;          // 本句柄独占
@property (nonatomic, assign) LIBSSH2_SFTP_HANDLE *handle; // 只读打开
@property (nonatomic, assign) long long size;
@end

@implementation KRSftpOpenFile
@end

static NSMutableDictionary<NSString *, KRSftpOpenFile *> *gHandles;
static long long gHandleIdCounter = 0;
static NSLock *gHandleLock;

@implementation KRSftpFileHandle

+ (void)initialize {
    if (self == [KRSftpFileHandle class]) {
        gHandles = [NSMutableDictionary dictionary];
        gHandleLock = [[NSLock alloc] init];
    }
}

+ (NSString *)openRead:(NSString *)sessionId remotePath:(NSString *)remotePath {
#if __has_include(<NMSSH/NMSSH.h>)
    NMSSHSession *session = [KRSftpSession sessionById:sessionId];
    if (!session) {
        @throw [NSException exceptionWithName:@"SftpNoSuchFileException"
                                       reason:[NSString stringWithFormat:@"invalid sessionId: %@", sessionId]
                                     userInfo:nil];
    }
    LIBSSH2_SESSION *raw = [session rawSession];
    if (!raw) {
        @throw [NSException exceptionWithName:@"SftpConnectException"
                                       reason:@"ssh session is not ready"
                                     userInfo:nil];
    }

    // 每次打开用独立的 SFTP 会话，避免与 NMSFTP 的句柄互相干扰，也让 seek 语义干净。
    LIBSSH2_SFTP *sftp = libssh2_sftp_init(raw);
    if (!sftp) {
        @throw [NSException exceptionWithName:@"SftpConnectException"
                                       reason:[NSString stringWithFormat:@"libssh2_sftp_init failed (lastError=%ld)",
                                               (long)libssh2_session_last_errno(raw)]
                                     userInfo:nil];
    }

    LIBSSH2_SFTP_HANDLE *handle = libssh2_sftp_open(sftp, remotePath.UTF8String, LIBSSH2_FXF_READ, 0);
    if (!handle) {
        unsigned long sftpErr = libssh2_sftp_last_error(sftp);
        libssh2_sftp_shutdown(sftp);
        NSString *name = (sftpErr == LIBSSH2_FX_NO_SUCH_FILE || sftpErr == LIBSSH2_FX_NO_SUCH_PATH)
            ? @"SftpNoSuchFileException" : @"SftpPermissionException";
        [KRLogModule logInfo:[NSString stringWithFormat:@"[sftp] openRead failed: %@ sftpErr=%lu",
                              remotePath, sftpErr]];
        @throw [NSException exceptionWithName:name
                                       reason:[NSString stringWithFormat:@"open failed: %@", remotePath]
                                     userInfo:nil];
    }

    // 取文件大小，供 HTTP Content-Length / Range 计算使用
    long long size = 0;
    LIBSSH2_SFTP_ATTRIBUTES attrs;
    if (libssh2_sftp_fstat(handle, &attrs) == 0) {
        size = (long long)attrs.filesize;
    }

    KRSftpOpenFile *open = [KRSftpOpenFile new];
    open.sessionId = sessionId;
    open.remotePath = remotePath;
    open.sftp = sftp;
    open.handle = handle;
    open.size = size;

    [gHandleLock lock];
    NSString *fileHandleId = [NSString stringWithFormat:@"fh-%lld", ++gHandleIdCounter];
    gHandles[fileHandleId] = open;
    [gHandleLock unlock];

    [KRLogModule logInfo:[NSString stringWithFormat:@"[sftp] openRead %@ size=%lld id=%@",
                          remotePath, size, fileHandleId]];
    return fileHandleId;
#else
    @throw [NSException exceptionWithName:@"SftpNotImplementedException"
                                   reason:@"NMSSH not available"
                                 userInfo:nil];
#endif
}

+ (NSData *)read:(NSString *)fileHandleId offset:(long long)offset length:(int)length {
#if __has_include(<NMSSH/NMSSH.h>)
    if (length <= 0) return [NSData data];

    [gHandleLock lock];
    KRSftpOpenFile *open = gHandles[fileHandleId];
    [gHandleLock unlock];
    if (!open) {
        @throw [NSException exceptionWithName:@"SftpNoSuchFileException"
                                       reason:[NSString stringWithFormat:@"invalid fileHandleId: %@", fileHandleId]
                                     userInfo:nil];
    }
    if (offset < 0) offset = 0;
    if (open.size > 0 && offset >= open.size) {
        return [NSData data]; // 读到文件尾
    }

    NSMutableData *out = [NSMutableData dataWithCapacity:(NSUInteger)length];
    libssh2_sftp_seek64(open.handle, (uint64_t)offset);

    while ((int)out.length < length) {
        char buf[64 * 1024];
        size_t want = MIN(sizeof(buf), (size_t)(length - (int)out.length));
        ssize_t n = libssh2_sftp_read(open.handle, buf, want);
        if (n > 0) {
            [out appendBytes:buf length:(NSUInteger)n];
            continue;
        }
        if (n == 0) break;                    // EOF
        if (n == LIBSSH2_ERROR_EAGAIN) continue; // 阻塞模式下通常会重试
        [KRLogModule logInfo:[NSString stringWithFormat:@"[sftp] read error %ld at offset=%lld (id=%@)",
                              (long)n, offset, fileHandleId]];
        break;
    }
    return out;
#else
    @throw [NSException exceptionWithName:@"SftpNotImplementedException"
                                   reason:@"NMSSH not available"
                                 userInfo:nil];
#endif
}

+ (long long)sizeOf:(NSString *)fileHandleId {
    [gHandleLock lock];
    KRSftpOpenFile *open = gHandles[fileHandleId];
    [gHandleLock unlock];
    return open ? open.size : -1;
}

+ (void)close:(NSString *)fileHandleId {
#if __has_include(<NMSSH/NMSSH.h>)
    [gHandleLock lock];
    KRSftpOpenFile *open = gHandles[fileHandleId];
    [gHandles removeObjectForKey:fileHandleId];
    [gHandleLock unlock];
    if (!open) return;
    if (open.handle) libssh2_sftp_close(open.handle);
    if (open.sftp) libssh2_sftp_shutdown(open.sftp);
    [KRLogModule logInfo:[NSString stringWithFormat:@"[sftp] close %@ (id=%@)",
                          open.remotePath, fileHandleId]];
#endif
}

+ (void)closeAll {
#if __has_include(<NMSSH/NMSSH.h>)
    [gHandleLock lock];
    NSArray<KRSftpOpenFile *> *all = gHandles.allValues;
    [gHandles removeAllObjects];
    [gHandleLock unlock];
    for (KRSftpOpenFile *open in all) {
        if (open.handle) libssh2_sftp_close(open.handle);
        if (open.sftp) libssh2_sftp_shutdown(open.sftp);
    }
#endif
}

@end

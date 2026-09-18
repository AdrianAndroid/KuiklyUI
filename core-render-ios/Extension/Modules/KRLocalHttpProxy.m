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
#import "KRLocalHttpProxy.h"
#import "KRSftpFileHandle.h"
#import "KRLogModule.h"
#import <objc/runtime.h>
#if __has_include(<GCDWebServer/GCDWebServer.h>)
#import <GCDWebServer/GCDWebServer.h>
#import <GCDWebServer/GCDWebServerDataResponse.h>
#import <GCDWebServer/GCDWebServerStreamedResponse.h>
#endif

static const int kPortMin = 18080;
static const int kPortMax = 18089;
static const NSTimeInterval kTTL = 2 * 60 * 60;            // token TTL 2 小时（§21.3.3）
static const NSInteger kMaxRangeBytes = 2 * 1024 * 1024;   // 单次 Range 最多 8MB，避免大文件整体进内存
static const NSInteger kStreamChunkBytes = 256 * 1024;
/** 无 Range 请求时，小于该值直接整包返回（图片/文本预览依赖 Content-Length） */
static const long long kNoRangeInlineMaxBytes = 8 * 1024 * 1024;

/** 一个播放 token 对应一次远端文件的流式读取（§21.3.3） */
@interface KRLocalHttpProxyToken : NSObject
@property (nonatomic, copy) NSString *sessionId;
@property (nonatomic, copy) NSString *remotePath;
@property (nonatomic, assign) long long totalSize;
@property (nonatomic, assign) NSTimeInterval expiresAt;
@property (nonatomic, copy, nullable) NSString *fileHandleId;  // 懒打开
@end

@implementation KRLocalHttpProxyToken
@end

@interface KRLocalHttpProxy ()
@property (nonatomic, strong) NSMutableDictionary<NSString *, KRLocalHttpProxyToken *> *tokens;
@property (nonatomic, strong) NSLock *lock;
@property (nonatomic, assign) int port;
#if __has_include(<GCDWebServer/GCDWebServer.h>)
@property (nonatomic, strong) GCDWebServer *server;
#endif
@end

@implementation KRLocalHttpProxy

+ (KRLocalHttpProxy *)startOrGet {
    static KRLocalHttpProxy *instance;
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        instance = [[KRLocalHttpProxy alloc] init];
        [instance start];
    });
    return instance;
}

+ (instancetype)sharedInstance {
    return [self startOrGet];
}

- (instancetype)init {
    self = [super init];
    if (self) {
        _tokens = [NSMutableDictionary dictionary];
        _lock = [[NSLock alloc] init];
        _port = 0;
    }
    return self;
}

- (void)start {
#if __has_include(<GCDWebServer/GCDWebServer.h>)
    for (int p = kPortMin; p <= kPortMax; p++) {
        @try {
            GCDWebServer *server = [[GCDWebServer alloc] init];
            __weak typeof(self) weakSelf = self;
            // 兜底：不匹配上面 pattern 的请求（含非法/过期 token）返回 404，
            // 而不是 GCDWebServer 默认的 501，便于播放器/排查时区分。
            [server addDefaultHandlerForMethod:@"GET"
                                  requestClass:[GCDWebServerRequest class]
                                  processBlock:^GCDWebServerResponse *(GCDWebServerRequest *request) {
                return [[GCDWebServerDataResponse alloc] initWithHTML:
                        @"<html><body>404 not found</body></html>"];
            }];
            [server addHandlerWithMatchBlock:^GCDWebServerRequest *(NSString *requestMethod, NSURL *requestURL, NSDictionary<NSString *, NSString *> *requestHeaders, NSString *urlPath, NSDictionary<NSString *, NSString *> *urlQuery) {
                if (![requestMethod isEqualToString:@"GET"] && ![requestMethod isEqualToString:@"HEAD"]) return nil;
                NSArray *parts = [urlPath componentsSeparatedByString:@"/"];
                if (parts.count < 3) return nil;
                __strong typeof(weakSelf) strong = weakSelf;
                if ([strong tokenForId:parts[1]] == nil) return nil;  // 不存在/过期 → 不匹配
                return [[GCDWebServerRequest alloc] initWithMethod:requestMethod url:requestURL headers:requestHeaders path:urlPath query:urlQuery];
            } processBlock:^GCDWebServerResponse *(GCDWebServerRequest *request) {
                __strong typeof(weakSelf) strong = weakSelf;
                return [strong responseForRequest:request];
            }];
            if ([server startWithPort:p bonjourName:nil]) {
                self.server = server;
                self.port = p;
                [KRLogModule logInfo:[NSString stringWithFormat:@"[sftp.proxy] started on 127.0.0.1:%d", p]];
                return;
            }
        } @catch (NSException *e) {
            // 端口被占用，继续下一个（§21.3.1）
        }
    }
    [KRLogModule logInfo:@"[sftp.proxy] failed to bind any port in 18080-18089"];
    self.port = 0;
#else
    self.port = 0;
    [KRLogModule logInfo:@"[sftp.proxy] GCDWebServer unavailable"];
#endif
}

- (int)port { return _port; }

#pragma mark - Token management

- (nullable KRLocalHttpProxyToken *)tokenForId:(NSString *)token {
    if (token.length == 0) return nil;
    [self.lock lock];
    KRLocalHttpProxyToken *entry = self.tokens[token];
    if (entry && entry.expiresAt < [NSDate date].timeIntervalSince1970) {
        [self.tokens removeObjectForKey:token];
        entry = nil;
    }
    if (entry) {
        entry.expiresAt = [NSDate date].timeIntervalSince1970 + kTTL;  // 每次访问续期
    }
    [self.lock unlock];
    return entry;
}

- (NSString *)registerToken:(NSString *)sessionId remotePath:(NSString *)remotePath totalSize:(long long)totalSize {
    NSString *token = [self generateToken];
    KRLocalHttpProxyToken *entry = [KRLocalHttpProxyToken new];
    entry.sessionId = sessionId;
    entry.remotePath = remotePath;
    entry.totalSize = totalSize;
    entry.expiresAt = [NSDate date].timeIntervalSince1970 + kTTL;
    [self.lock lock];
    self.tokens[token] = entry;
    [self.lock unlock];
    [KRLogModule logInfo:[NSString stringWithFormat:@"[sftp.proxy] token for %@ (size=%lld)", remotePath, totalSize]];
    return token;
}

- (void)unregisterToken:(NSString *)token {
    [self.lock lock];
    KRLocalHttpProxyToken *entry = self.tokens[token];
    [self.tokens removeObjectForKey:token];
    [self.lock unlock];
    if (entry.fileHandleId) [KRSftpFileHandle close:entry.fileHandleId];
}

- (void)stop {
#if __has_include(<GCDWebServer/GCDWebServer.h>)
    [self.server stop];
#endif
    [self.lock lock];
    NSArray<KRLocalHttpProxyToken *> *all = self.tokens.allValues;
    [self.tokens removeAllObjects];
    [self.lock unlock];
    for (KRLocalHttpProxyToken *entry in all) {
        if (entry.fileHandleId) [KRSftpFileHandle close:entry.fileHandleId];
    }
}

- (NSString *)generateToken {
    NSMutableString *token = [NSMutableString string];
    for (int i = 0; i < 32; i++) {
        [token appendFormat:@"%02x", arc4random_uniform(256)];
    }
    return token;
}

#pragma mark - Serving

- (nullable NSString *)fileHandleForToken:(KRLocalHttpProxyToken *)entry error:(NSError **)error {
    if (entry.fileHandleId) return entry.fileHandleId;
    @try {
        NSString *fh = [KRSftpFileHandle openRead:entry.sessionId remotePath:entry.remotePath];
        entry.fileHandleId = fh;
        long long sz = [KRSftpFileHandle sizeOf:fh];
        if (sz >= 0) entry.totalSize = sz;
        return fh;
    } @catch (NSException *e) {
        if (error) {
            *error = [NSError errorWithDomain:@"KuiklySftpProxy" code:500
                                     userInfo:@{NSLocalizedDescriptionKey: e.reason ?: @"open failed"}];
        }
        return nil;
    }
}

- (GCDWebServerResponse *)responseForRequest:(GCDWebServerRequest *)request {
#if __has_include(<GCDWebServer/GCDWebServer.h>)
    NSArray *parts = [request.path componentsSeparatedByString:@"/"];
    NSString *token = parts.count >= 3 ? parts[1] : @"";
    KRLocalHttpProxyToken *entry = [self tokenForId:token];
    if (!entry) {
        return [GCDWebServerDataResponse responseWithHTML:@"<html><body>token expired</body></html>"];
    }

    NSError *openError = nil;
    NSString *fh = [self fileHandleForToken:entry error:&openError];
    if (!fh) {
        return [[GCDWebServerDataResponse alloc] initWithHTML:[NSString stringWithFormat:
            @"<html><body>open failed: %@</body></html>", openError.localizedDescription ?: @""]];
    }

    long long total = entry.totalSize;
    NSString *contentType = [KRLocalHttpProxy mimeForPath:entry.remotePath];
    NSRange range = [KRLocalHttpProxy parseRange:request.headers[@"Range"] total:total];
    [KRLogModule logInfo:[NSString stringWithFormat:@"[sftp.proxy] req Range=%@ -> loc=%lu len=%lu total=%lld",
                          request.headers[@"Range"] ?: @"<none>",
                          (unsigned long)range.location, (unsigned long)range.length, total]];

    // 越界（如 VLC 探测用的 bytes=<size>-）必须回 416，否则会被当成有效请求，
    // 客户端会拿到错误的数据而反复重试
    if ([KRLocalHttpProxy isRangeUnsatisfiable:request.headers[@"Range"] total:total]) {
        GCDWebServerDataResponse *resp = [[GCDWebServerDataResponse alloc] initWithData:[NSData data]
                                                                          contentType:contentType];
        resp.statusCode = 416;
        [resp setValue:[NSString stringWithFormat:@"bytes */%lld", total]
forAdditionalHeader:@"Content-Range"];
        return resp;
    }

    if (range.location != NSNotFound) {
        long long start = (long long)range.location;
        long long end = start + (long long)range.length - 1;
        if (end - start + 1 > kMaxRangeBytes) {
            end = start + kMaxRangeBytes - 1;   // 分片返回；Content-Range 反映真实区间
        }
        NSData *data = [KRSftpFileHandle read:fh offset:start length:(int)(end - start + 1)];
        [KRLogModule logInfo:[NSString stringWithFormat:@"[sftp.proxy] served %lu bytes for %lld-%lld/%lld",
                              (unsigned long)data.length, start, end, total]];
        GCDWebServerDataResponse *resp = [[GCDWebServerDataResponse alloc] initWithData:data
                                                                          contentType:contentType];
        resp.statusCode = 206;
        [resp setValue:@"bytes" forAdditionalHeader:@"Accept-Ranges"];
        [resp setValue:[NSString stringWithFormat:@"bytes %lld-%lld/%lld", start, end, total]
forAdditionalHeader:@"Content-Range"];
        [resp setValue:@"no-store" forAdditionalHeader:@"Cache-Control"];
        return resp;
    }

    // 无 Range：
    //   - 小文件（图片/文本等）直接整包返回，带 Content-Length。图片加载器通常不认
    //     没有 Content-Length 的流式响应，之前图片预览因此始终空白。
    //   - 大文件才走流式，避免整文件进内存。
    if (total >= 0 && total <= kNoRangeInlineMaxBytes) {
        NSData *data = [KRSftpFileHandle read:fh offset:0 length:(int)total];
        GCDWebServerDataResponse *resp = [[GCDWebServerDataResponse alloc] initWithData:data contentType:contentType];
        [resp setValue:@"bytes" forAdditionalHeader:@"Accept-Ranges"];
        [resp setValue:@"no-store" forAdditionalHeader:@"Cache-Control"];
        return resp;
    }

    __block long long offset = 0;
    GCDWebServerStreamedResponse *resp =
        [GCDWebServerStreamedResponse responseWithContentType:contentType
                                            asyncStreamBlock:^(GCDWebServerBodyReaderCompletionBlock completionBlock) {
            if (total > 0 && offset >= total) {
                completionBlock([NSData data], nil);
                return;
            }
            NSData *chunk = [KRSftpFileHandle read:fh offset:offset length:(int)kStreamChunkBytes];
            offset += chunk.length;
            completionBlock(chunk.length > 0 ? chunk : [NSData data], nil);
        }];
    [resp setValue:@"bytes" forAdditionalHeader:@"Accept-Ranges"];
    [resp setValue:@"no-store" forAdditionalHeader:@"Cache-Control"];
    return resp;
#else
    return [GCDWebServerDataResponse responseWithText:@"proxy unavailable"];
#endif
}

#pragma mark - Helpers

/** 解析 `bytes=start-end` / `bytes=start-` / `bytes=-suffix`；无有效 Range 返回 location=NSNotFound */
+ (NSRange)parseRange:(NSString *)header total:(long long)total {
    if (header.length == 0 || ![header hasPrefix:@"bytes="]) return NSMakeRange(NSNotFound, 0);
    NSString *spec = [[header substringFromIndex:6] stringByTrimmingCharactersInSet:[NSCharacterSet whitespaceCharacterSet]];
    if ([spec containsString:@","]) return NSMakeRange(NSNotFound, 0);   // 多段 Range 不支持
    NSRange dash = [spec rangeOfString:@"-"];
    if (dash.location == NSNotFound) return NSMakeRange(NSNotFound, 0);
    NSString *startStr = [spec substringToIndex:dash.location];
    NSString *endStr = [spec substringFromIndex:dash.location + 1];
    long long start = 0, end = 0;
    if (startStr.length == 0) {
        long long suffix = endStr.longLongValue;
        if (suffix <= 0 || total <= 0) return NSMakeRange(NSNotFound, 0);
        start = MAX(0, total - suffix);
        end = total - 1;
    } else {
        start = startStr.longLongValue;
        end = endStr.length > 0 ? endStr.longLongValue : (total > 0 ? total - 1 : 0);
    }
    if (total > 0 && end >= total) end = total - 1;
    if (start < 0 || end < start) return NSMakeRange(NSNotFound, 0);
    return NSMakeRange((NSUInteger)start, (NSUInteger)(end - start + 1));
}

/** Range 起点 >= 文件大小 时为不可满足（HTTP 416） */
+ (BOOL)isRangeUnsatisfiable:(NSString *)header total:(long long)total {
    if (header.length == 0 || total <= 0) return NO;
    if (![header hasPrefix:@"bytes="]) return NO;
    NSString *spec = [header substringFromIndex:6];
    if ([spec hasPrefix:@"-"]) return NO;   // 后缀 Range 总可满足
    NSRange dash = [spec rangeOfString:@"-"];
    if (dash.location == NSNotFound) return NO;
    long long start = [[spec substringToIndex:dash.location] longLongValue];
    return start >= total;
}

+ (NSString *)mimeForPath:(NSString *)path {
    NSString *ext = path.pathExtension.lowercaseString;
    static NSDictionary<NSString *, NSString *> *map;
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        map = @{
            @"mp4": @"video/mp4", @"m4v": @"video/x-m4v", @"mov": @"video/quicktime",
            @"mkv": @"video/x-matroska", @"avi": @"video/x-msvideo", @"webm": @"video/webm",
            @"ts": @"video/mp2t", @"flv": @"video/x-flv", @"wmv": @"video/x-ms-wmv",
            @"mp3": @"audio/mpeg", @"m4a": @"audio/mp4", @"aac": @"audio/aac",
            @"wav": @"audio/wav", @"flac": @"audio/flac", @"ogg": @"audio/ogg",
            @"jpg": @"image/jpeg", @"jpeg": @"image/jpeg", @"png": @"image/png",
            @"gif": @"image/gif", @"webp": @"image/webp", @"bmp": @"image/bmp",
            @"pdf": @"application/pdf", @"txt": @"text/plain; charset=utf-8",
            @"md": @"text/markdown; charset=utf-8", @"html": @"text/html; charset=utf-8",
            @"json": @"application/json", @"xml": @"application/xml",
        };
    });
    return map[ext] ?: @"application/octet-stream";
}

#pragma mark - Kotlin/Native bridge

// core 模块不能反向依赖 renderer，因此 core 侧通过 NSClassFromString + performSelector
// 调用下面这些方法。performSelector 只支持「0~1 个对象入参 / 对象出参」，所以这里刻意
// 使用单对象入参 + 对象返回的选择子。

- (NSNumber *)startOrGetPortNumber {
    (void)[KRLocalHttpProxy startOrGet];
    return @(self.port);
}

/** json: {"sessionId","remotePath","totalSize"} → token(NSString) */
- (NSString *)registerTokenWithJson:(NSString *)json {
    NSData *data = [json dataUsingEncoding:NSUTF8StringEncoding];
    id dict = data ? [NSJSONSerialization JSONObjectWithData:data options:0 error:NULL] : nil;
    if (![dict isKindOfClass:[NSDictionary class]]) return @"";
    return [self registerToken:dict[@"sessionId"]
                    remotePath:dict[@"remotePath"]
                     totalSize:[dict[@"totalSize"] longLongValue]];
}

/** json: {"token"} */
- (void)unregisterTokenWithJson:(NSString *)json {
    NSData *data = [json dataUsingEncoding:NSUTF8StringEncoding];
    id dict = data ? [NSJSONSerialization JSONObjectWithData:data options:0 error:NULL] : nil;
    NSString *token = [dict isKindOfClass:[NSDictionary class]] ? dict[@"token"] : nil;
    if (token) [self unregisterToken:token];
}

- (void)stopProxy {
    [self stop];
}

@end

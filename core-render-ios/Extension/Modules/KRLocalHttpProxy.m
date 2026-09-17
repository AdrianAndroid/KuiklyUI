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
#if __has_include(<GCDWebServer/GCDWebServer.h>)
#import <GCDWebServer/GCDWebServer.h>
#import <GCDWebServer/GCDWebServerDataResponse.h>
#endif

static const int kPortMin = 18080;
static const int kPortMax = 18089;
static const NSTimeInterval kTTL = 2 * 60 * 60;  // 2 小时

@interface KRLocalHttpProxy ()
@property (nonatomic, strong) NSMutableDictionary<NSString *, NSDictionary *> *tokens;
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
            // GCDWebServer API: matchBlock 返回 GCDWebServerRequest（非 nil 表示匹配），processBlock 返回响应
            [server addHandlerWithMatchBlock:^GCDWebServerRequest *(NSString *requestMethod, NSURL *requestURL, NSDictionary<NSString *, NSString *> *requestHeaders, NSString *urlPath, NSDictionary<NSString *, NSString *> *urlQuery) {
                // 只匹配 GET 请求
                if (![requestMethod isEqualToString:@"GET"]) return nil;
                NSArray *parts = [urlPath componentsSeparatedByString:@"/"];
                if (parts.count < 3) return nil;
                NSString *token = parts[1];
                __strong typeof(weakSelf) strong = weakSelf;
                NSDictionary *tokenInfo = strong.tokens[token];
                if (!tokenInfo) return nil;  // token 不存在/过期，不匹配
                return [[GCDWebServerRequest alloc] initWithMethod:requestMethod url:requestURL headers:requestHeaders path:urlPath query:urlQuery];
            } processBlock:^GCDWebServerResponse *(GCDWebServerRequest *request) {
                NSString *uri = request.path;
                NSArray *parts = [uri componentsSeparatedByString:@"/"];
                if (parts.count < 3) {
                    return [GCDWebServerDataResponse responseWithText:@"bad request"];
                }
                NSString *token = parts[1];
                __strong typeof(weakSelf) strong = weakSelf;
                NSDictionary *tokenInfo = strong.tokens[token];
                if (!tokenInfo) {
                    return [GCDWebServerDataResponse responseWithText:@"token expired"];
                }
                // Phase 1.2: 这里应该通过 sessionId 调用 [KRSftpFileHandle read:...]
                // 当前简化：返回空响应
                return [GCDWebServerDataResponse responseWithText:@""];
            }];
            BOOL ok = [server startWithPort:p bonjourName:nil];
            if (ok) {
                self.server = server;
                self.port = p;
                return;
            }
        } @catch (NSException *e) {
            // 端口被占用，继续下一个
        }
    }
    // 所有端口都失败，使用约定端口
    self.port = kPortMin;
#else
    // GCDWebServer 不可用（Podfile 未配置），使用约定端口
    self.port = kPortMin;
#endif
}

- (int)port { return _port; }

- (NSString *)registerToken:(NSString *)sessionId remotePath:(NSString *)remotePath totalSize:(long long)totalSize {
    [self.lock lock];
    NSString *token = [self generateToken];
    self.tokens[token] = @{
        @"sessionId": sessionId,
        @"remotePath": remotePath,
        @"totalSize": @(totalSize),
        @"expiresAt": @([[NSDate date] timeIntervalSince1970] + kTTL)
    };
    [self.lock unlock];
    return token;
}

- (void)unregisterToken:(NSString *)token {
    [self.lock lock];
    [self.tokens removeObjectForKey:token];
    [self.lock unlock];
}

- (void)stop {
#if __has_include(<GCDWebServer/GCDWebServer.h>)
    [self.server stop];
#endif
}

- (NSString *)generateToken {
    NSMutableString *token = [NSMutableString string];
    for (int i = 0; i < 32; i++) {
        [token appendFormat:@"%02x", arc4random_uniform(256)];
    }
    return token;
}

@end

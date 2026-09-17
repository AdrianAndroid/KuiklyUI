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
#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/**
 * 本地 HTTP 代理服务器（iOS，§5 / §21.3）
 *
 * - 基于 GCDWebServer；端口 18080-18089 fallback
 * - URL 格式：`http://127.0.0.1:<port>/<token>/<fileName>`
 * - Token TTL 2 小时，每次 read 续期（§21.3.3）
 * - 支持 HTTP Range 请求（§21.3.4）
 *
 * 代理把播放器的 HTTP Range 请求转换为 SFTP `lseek + read`（§5.2），因此不需要把整个文件下载到本地。
 */
@interface KRLocalHttpProxy : NSObject

+ (KRLocalHttpProxy *)startOrGet;
+ (instancetype)sharedInstance;
- (int)port;
- (NSString *)registerToken:(NSString *)sessionId remotePath:(NSString *)remotePath totalSize:(long long)totalSize;
- (void)unregisterToken:(NSString *)token;
- (void)stop;

#pragma mark - Kotlin/Native bridge
// core 通过 NSClassFromString + performSelector 调用；选择子保持「0~1 个对象入参 / 对象出参」。
- (NSNumber *)startOrGetPortNumber;
- (NSString *)registerTokenWithJson:(NSString *)json;
- (void)unregisterTokenWithJson:(NSString *)json;
- (void)stopProxy;

@end

NS_ASSUME_NONNULL_END

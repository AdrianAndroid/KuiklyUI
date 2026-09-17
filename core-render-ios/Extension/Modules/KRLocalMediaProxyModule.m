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
#import "KRLocalMediaProxyModule.h"
#import "KRLocalHttpProxy.h"
#import "KRSftpFileHandle.h"
#import "SftpErrorFormatter.h"

/**
 * 本地媒体代理 Module（§5 / §7.3）
 *
 * 为什么用 Module 而不是 core 的 `LocalMediaProxyApi` expect/actual：
 * `core` 不能反向依赖 `core-render-ios`，Kotlin/Native 无法直接调用 renderer 的类。
 * 走 Kuikly Module 体系（模块名即类名，`TDFBaseModule` 用 NSClassFromString 解析）
 * 与本项目其它 SFTP 能力保持一致，也不需要 cinterop 或 objc_msgSend。
 */
@implementation KRLocalMediaProxyModule

- (void)startOrGetPort:(NSDictionary *)args {
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    int port = [[KRLocalHttpProxy startOrGet] port];
    if (callback) callback(@{@"port": @(port)});
}

- (void)registerToken:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] kr_stringToDictionary];
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    NSString *sessionId = params[@"sessionId"];
    NSString *remotePath = params[@"remotePath"];
    long long totalSize = [params[@"totalSize"] longLongValue];
    if (sessionId.length == 0 || remotePath.length == 0) {
        if (callback) callback(@{@"error": [SftpErrorFormatter formatException:
            [NSException exceptionWithName:@"SftpInvalidParamException"
                                   reason:@"sessionId/remotePath required" userInfo:nil]]});
        return;
    }
    NSString *token = [[KRLocalHttpProxy startOrGet] registerToken:sessionId
                                                        remotePath:remotePath
                                                         totalSize:totalSize];
    if (callback) callback(@{@"token": token ?: @""});
}

- (void)unregisterToken:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] kr_stringToDictionary];
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    NSString *token = params[@"token"];
    if (token.length > 0) [[KRLocalHttpProxy startOrGet] unregisterToken:token];
    if (callback) callback(@{@"ok": @YES});
}

- (void)stop:(NSDictionary *)args {
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    [[KRLocalHttpProxy startOrGet] stop];
    if (callback) callback(@{@"ok": @YES});
}

@end

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

#import "KRBaseModule.h"
#import "KuiklyRenderView.h"
#import "NSObject+KR.h"
#import "KRLogModule.h"
NSString *const KR_PARAM_KEY = @"param";
NSString *const KR_CALLBACK_KEY = @"callback";


@implementation KRBaseModule

@synthesize hr_rootView;
@synthesize hr_contextParam;
#pragma mark - KuiklyRenderModuleExportProtocol

- (id _Nullable)hrv_callWithMethod:(NSString *)method params:(id _Nullable)params callback:(KuiklyRenderCallback)callback {
    SEL selector = NSSelectorFromString( [NSString stringWithFormat:@"%@:", method] );
    // Diagnostic: every module dispatch is recorded so a silent no-op (module missing,
    // selector typo, callback never invoked) is visible in the diagnostic log.
    [KRLogModule logInfo:[NSString stringWithFormat:@"[module] %@.%@%@ hasParams=%@",
                          NSStringFromClass([self class]), method,
                          ([self respondsToSelector:selector] ? @"" : @" (NO HANDLER)"),
                          (params ? @"YES" : @"NO")]];
    if ([self respondsToSelector:selector]) {
        NSMutableDictionary *args = [@{
             KR_PARAM_KEY: params ?: @"",
        } mutableCopy];
        if (callback){
            // Wrap the callback so the diagnostic log records whether (and with what)
            // the native module answered. Without this, a module that never calls back
            // is indistinguishable from one that returned an empty result.
            NSString *moduleTag = [NSString stringWithFormat:@"%@.%@",
                                   NSStringFromClass([self class]), method];
            KuiklyRenderCallback loggedCallback = ^(id _Nullable result) {
                NSString *desc = @"<nil>";
                if (result) {
                    NSData *json = [NSJSONSerialization isValidJSONObject:result]
                        ? [NSJSONSerialization dataWithJSONObject:result options:0 error:NULL]
                        : nil;
                    if (json) {
                        NSString *s = [[NSString alloc] initWithData:json encoding:NSUTF8StringEncoding];
                        desc = (s.length > 4000) ? [[s substringToIndex:4000] stringByAppendingString:@"…"] : s;
                    } else {
                        desc = [result description] ?: @"<desc-nil>";
                    }
                }
                [KRLogModule logInfo:[NSString stringWithFormat:@"[module-cb] %@ -> %@",
                                      moduleTag, desc]];
                callback(result);
            };
            args[KR_CALLBACK_KEY] = loggedCallback;
        }
        id result = [self kr_invokeWithSelector:selector args:args];
        return result;
    } else {
        NSString *reason = [NSString stringWithFormat:@"module方法不存在: %@:(NSDictionary *)args）在Module中未实现，请补充该方法", method];
        [KRLogModule logError:reason];
        NSAssert(false, reason);
        callback( @{
            @"code":@(-1),
            @"message": @"method does not exist",
        } );
    }
    return nil;
}


/*
 * @brief 获取tag对应的View实例（仅支持在主线程调用）.
 * @param tag view对应的索引
 * @return view实例
 */
- (UIView * _Nullable)viewWithTag:(NSNumber *)tag {
    if ( [self.hr_rootView isKindOfClass:[KuiklyRenderView class]]) {
        id<KuiklyRenderViewExportProtocol> viewHandler = [((KuiklyRenderView *)self.hr_rootView) viewWithRefTag:tag];
        if ([viewHandler isKindOfClass:[UIView class]]) {
            return (UIView *)viewHandler;
        }
    }
    return nil;
}

@end

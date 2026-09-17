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

#import "KRLogHandler.h"
#import "KRDiagnosticLog.h"

@implementation KRLogHandler

+ (void)load {
    // Register both: `KRLogModule` is the iOS-renderer-side funnel; `KuiklyRenderBridge`
    // is the new (preferred for future code) funnel. Both write to the same sink.
    [KRLogModule registerLogHandler:[self new]];
    @try {
        [KuiklyRenderBridge registerLogHandler:[self new]];
    } @catch (NSException *e) {
        // Older SDK may not export registerLogHandler — fail quietly so +load never crashes.
        KR_DIAG_WARN(@"diag.handshake", @"KuiklyRenderBridge.registerLogHandler unavailable: %@", e.reason ?: @"");
    }
    KR_DIAG_INFO(@"diag.handshake", @"KRLogHandler registered, log file at: %@",
                 [KRDiagnosticLog logFilePath] ?: @"<unavailable>");
}

+ (void)bootstrap {
    // No-op; +load does the work. This exists so Swift / ObjC callers can ensure init from any thread.
    (void)[KRDiagnosticLog class];
}

#pragma mark - KuiklyLogProtocol

- (BOOL)asyncLogEnable { return YES; }

- (void)logInfo:(NSString *)message {
    // The renderer may emit messages with the form "|HH:mm.ss.SSS|msg"; strip the leading timestamp
    // since the diagnostic sink already provides microsecond timestamps.
    NSString *cleaned = [KRLogHandler stripRendererTimestamp:message];
    KR_DIAG_INFO(@"kuikly", @"%@", cleaned);
}

- (void)logDebug:(NSString *)message {
    NSString *cleaned = [KRLogHandler stripRendererTimestamp:message];
    KR_DIAG_DEBUG(@"kuikly", @"%@", cleaned);
}

- (void)logError:(NSString *)message {
    NSString *cleaned = [KRLogHandler stripRendererTimestamp:message];
    KR_DIAG_ERROR(@"kuikly", @"%@", cleaned);
}

+ (NSString *)stripRendererTimestamp:(NSString *)raw {
    if (raw.length < 13) return raw;
    if (![raw hasPrefix:@"|"]) return raw;
    NSRange pipe = [raw rangeOfString:@"|" options:0 range:NSMakeRange(1, raw.length - 1)];
    if (pipe.location == NSNotFound) return raw;
    if (pipe.location > 16) return raw; // too long to be the renderer timestamp
    NSString *head = [raw substringWithRange:NSMakeRange(pipe.location + 1, raw.length - pipe.location - 1)];
    return head;
}

@end

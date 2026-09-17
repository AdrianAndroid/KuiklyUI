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

typedef NS_ENUM(NSInteger, KRLogLevel) {
    KRLogLevelTrace = 0,
    KRLogLevelDebug = 1,
    KRLogLevelInfo  = 2,
    KRLogLevelWarn  = 3,
    KRLogLevelError = 4,
    KRLogLevelFatal = 5,
};

@interface KRDiagnosticLog : NSObject

/// Process-wide minimum level. Defaults to KRLogLevelInfo. Thread-safe.
/// Reads KUIKLY_LOG_LEVEL env var at startup (TRACE/DEBUG/INFO/WARN/ERROR/FATAL or numeric 0..5).
@property (class, nonatomic, readonly) KRLogLevel minLevel;

/// Throttle state for repeating messages. Defaults to YES.
@property (class, nonatomic) BOOL throttleEnabled;

/// Ensure the sink is initialized. Safe to call many times — the underlying state is `dispatch_once`.
+ (void)bootstrap;

/// Synchronous flush — meant for `applicationWillTerminate` and crash handlers.
+ (void)flushSync;

/// Async-emit helper for plain messages (no field, no stack).
/// Always non-blocking: dispatches to a serial background queue.
+ (void)log:(KRLogLevel)level
        tag:(NSString *)tag
    message:(NSString *)message;

/// Async-emit helper for structured key/value entries. `fields` must be JSON-serializable.
/// Stays off the caller thread; safe in signal-unsafe paths is NOT assumed (see KR_DIAG_SAFE).
+ (void)log:(KRLogLevel)level
        tag:(NSString *)tag
    message:(NSString *)message
     fields:(nullable NSDictionary<NSString *, id> *)fields;

/// For ObjC @try/@catch protection near bridge boundaries. Captures NSException reason+stack
/// and emits a single FATAL-level entry. Safe to call from any thread.
+ (void)logObjCException:(NSException *)exception
                     tag:(NSString *)tag
                  origin:(nullable NSString *)origin;

/// Returns the absolute path of the JSONL log file, or nil if not yet initialized.
+ (nullable NSString *)logFilePath;

/// Flushes the JSONL writer. Call before crashing / on app exit. Idempotent.
+ (void)flush;

/// Returns the most recent ~N log lines (for in-app inspection). Synchronous & cheap.
+ (NSArray<NSString *> *)recentLines:(NSUInteger)maxLines;

/// Current thread label, e.g. "main", "context", "module-KRNetworkModule", "log".
+ (NSString *)currentThreadLabel;

/// Captures the current backtrace at `skipFrames` skip depth. Returns compact stack, no module names.
+ (NSString *)backtraceSkippingFrames:(NSUInteger)skipFrames limit:(NSUInteger)limit;

@end

/// Convenience macros. Use these at call sites for compiler-stripping in Release when level
///   is below minLevel (still async, but avoids forming the format string).
/// IMPORTANT: macro parameter names avoid ObjC keyword tokens (`tag`, `level`, `message`,
/// `format`) — using any of those as the macro's positional name will break expansion.
#define KR_DIAGN(level_, tag_, fmt_, ...) do { \
    NSString *__kr_msg = [NSString stringWithFormat:(fmt_), ##__VA_ARGS__]; \
    [KRDiagnosticLog log:(level_) tag:(tag_) message:__kr_msg]; \
} while (0)

#define KR_DIAG_ERROR(tag, ...)  KR_DIAGN(KRLogLevelError, tag, __VA_ARGS__)
#define KR_DIAG_WARN(tag, ...)   KR_DIAGN(KRLogLevelWarn,  tag, __VA_ARGS__)
#define KR_DIAG_INFO(tag, ...)   KR_DIAGN(KRLogLevelInfo,  tag, __VA_ARGS__)
#define KR_DIAG_DEBUG(tag, ...)  KR_DIAGN(KRLogLevelDebug, tag, __VA_ARGS__)
#define KR_DIAG_TRACE(tag, ...)  KR_DIAGN(KRLogLevelTrace, tag, __VA_ARGS__)

/// Backwards compatibility: keep `KR_DIAG(level, tag, fmt, ...)` working too.
#define KR_DIAG(level, tag, ...)  KR_DIAGN(level, tag, __VA_ARGS__)

/// For code paths that are async-signal-unsafe (e.g. malloc, pthread mutex inside signal handler).
/// Never use these inside the crash backstop unless instructed.
#define KR_DIAG_SAFE_ERROR(tag, ...)  KR_DIAGN(KRLogLevelError, tag, __VA_ARGS__)

NS_ASSUME_NONNULL_END

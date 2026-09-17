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

#import "KRDiagnosticLog.h"
#import <os/log.h>
#import <execinfo.h>
#import <signal.h>
#import <pthread.h>
#import <sys/stat.h>
#import <sys/sysctl.h>
#import <sys/uio.h>
#import <unistd.h>
#import <fcntl.h>
#import <mach/mach_time.h>
#import <mach-o/dyld.h>

static NSString * const kKRDiagSubsystem = @"com.tencent.kuikly.macApp";
static NSString * const kKRDiagCategory  = @"diagnostic";
static NSString * const kKRDiagFileName  = @"diagnostics.log";
static NSString * const kKRDiagFileArchiveName = @"diagnostics.archive.log";
static const NSUInteger kKRDiagMaxFileBytes   = 5 * 1024 * 1024;   // 5 MB
static const NSUInteger kKRDiagKeepArchiveBytes = 5 * 1024 * 1024; // 5 MB
static const NSUInteger kKRDiagMaxRecentLines = 2000;
static const NSUInteger kKRDiagThrottleWindowSec = 5;

@interface _KRDiagThrottleKey : NSObject
@property (nonatomic, copy) NSString *tag;
@property (nonatomic, copy) NSString *message;
@property (nonatomic, assign) NSUInteger count;
@property (nonatomic, assign) NSTimeInterval firstAt;
@end
@implementation _KRDiagThrottleKey @end

/// Stored plain `int` file descriptor for the signal handler.
/// Async-signal-safe code paths MUST NOT use Objective-C, NSString, NSFileHandle, locks,
/// or stdio buffering — `write(2)` on a pre-opened fd is the only safe primitive.
static int sLogFileDescriptor = -1;
static mach_timebase_info_data_t sKRDiagTimebase = {0, 0};

@interface KRDiagnosticLog ()
@property (nonatomic, strong) dispatch_queue_t serialQueue;
@property (nonatomic, strong) NSFileHandle *fileHandle;
@property (nonatomic, copy)   NSString *filePath;
@property (nonatomic, strong) NSMutableArray<NSString *> *recentBuffer;
@property (nonatomic, strong) NSMutableDictionary<NSString *, _KRDiagThrottleKey *> *throttleMap;
@property (nonatomic, assign) BOOL throttleEnabled;
@property (nonatomic, assign) KRLogLevel minLevel;
@property (nonatomic, assign) BOOL signaled;
@end

@implementation KRDiagnosticLog

#pragma mark - Singleton

/// Backing storage for the singleton. Assigned once during `-init` (which itself is
/// triggered by `+[shared]`); read by `+[sharedNoOnce]` from contexts where re-entering
/// `dispatch_once` would deadlock (e.g. during init / from inside other +log helpers).
static KRDiagnosticLog *sKRDiagSingleton = nil;

+ (instancetype)shared {
    static dispatch_once_t once;
    dispatch_once(&once, ^{ sKRDiagSingleton = [KRDiagnosticLog new]; });
    return sKRDiagSingleton;
}

/// Non-recursive accessor — returns the singleton if already constructed, otherwise nil.
/// Used during `+init` because `dispatch_once` cannot be re-entered from inside the
/// block it was started from.
+ (instancetype)sharedNoOnce {
    return sKRDiagSingleton;
}

+ (KRLogLevel)minLevel { return [KRDiagnosticLog shared].minLevel; }

+ (BOOL)throttleEnabled {
    return [KRDiagnosticLog shared].throttleEnabled;
}
+ (void)setThrottleEnabled:(BOOL)enabled {
    [KRDiagnosticLog shared].throttleEnabled = enabled;
}

+ (void)bootstrap { (void)[KRDiagnosticLog shared]; }

+ (void)flushSync {
    KRDiagnosticLog *self_ = [KRDiagnosticLog shared];
    if (!self_.fileHandle) return;
    dispatch_sync(self_.serialQueue, ^{
        @try { [self_.fileHandle synchronizeFile]; }
        @catch (NSException *e) { NSLog(@"[KRDiagnosticLog] flushSync failed: %@", e); }
    });
}

#pragma mark - Init

- (instancetype)init {
    self = [super init];
    if (!self) return nil;
    // Assign the backing singleton FIRST so anything called during init can read it back
    // via `sharedNoOnce` without re-entering `dispatch_once`.
    sKRDiagSingleton = self;

    _serialQueue   = dispatch_queue_create("com.tencent.kuikly.macApp.diag", DISPATCH_QUEUE_SERIAL);
    _throttleMap   = [NSMutableDictionary new];
    _recentBuffer  = [NSMutableArray new];
    _throttleEnabled = YES;
    _minLevel  = [KRDiagnosticLog readMinLevelFromEnvironment];
    [KRDiagnosticLog installCrashBackstop];
    [KRDiagnosticLog openLogFile];
    if (self.fileHandle) {
        // Synchronous first write so AI can see startup even if the app crashes immediately.
        NSDictionary *env = [KRDiagnosticLog makeEnvelope:KRLogLevelInfo
                                                      tag:@"diag.init"
                                                  message:[NSString stringWithFormat:@"KRDiagnosticLog ready, minLevel=%ld, pid=%d, file=%@",
                                                           (long)_minLevel, getpid(), self.filePath]
                                                   fields:nil];
        NSString *line = [KRDiagnosticLog jsonLineForEnvelope:env];
        [self writeLine:line];
        [self appendToRecent:line];
    } else {
        NSLog(@"[KRDiagnosticLog] init: file handle is nil, path=%@", self.filePath ?: @"<nil>");
    }
    return self;
}

+ (KRLogLevel)readMinLevelFromEnvironment {
    NSString *raw = [[[NSProcessInfo processInfo] environment] objectForKey:@"KUIKLY_LOG_LEVEL"];
    if (raw.length == 0) return KRLogLevelInfo;
    NSString *upper = [raw uppercaseString];
    if ([upper isEqualToString:@"TRACE"]) return KRLogLevelTrace;
    if ([upper isEqualToString:@"DEBUG"]) return KRLogLevelDebug;
    if ([upper isEqualToString:@"INFO"])  return KRLogLevelInfo;
    if ([upper isEqualToString:@"WARN"])  return KRLogLevelWarn;
    if ([upper isEqualToString:@"WARNING"]) return KRLogLevelWarn;
    if ([upper isEqualToString:@"ERROR"]) return KRLogLevelError;
    if ([upper isEqualToString:@"ERR"])   return KRLogLevelError;
    if ([upper isEqualToString:@"FATAL"]) return KRLogLevelFatal;
    NSInteger n = [upper integerValue];
    if (n >= KRLogLevelTrace && n <= KRLogLevelFatal) return (KRLogLevel)n;
    return KRLogLevelInfo;
}

#pragma mark - File

+ (void)openLogFile {
    NSString *dir = [KRDiagnosticLog logDirectoryCreateIfNeeded];
    if (!dir) {
        sLogFileDescriptor = -1;
        return;
    }
    NSString *path = [dir stringByAppendingPathComponent:kKRDiagFileName];
    // Use the singleton via a lock-protected instance variable accessor that does NOT
    // call `dispatch_once` recursively from inside the same `+[shared]` initializer.
    KRDiagnosticLog *self_ = [self sharedNoOnce]; // see helper below
    if (self_ == nil) return;
    self_.filePath = path;
    if (![[NSFileManager defaultManager] fileExistsAtPath:path]) {
        [[NSFileManager defaultManager] createFileAtPath:path contents:nil attributes:nil];
    }
    NSFileHandle *fh = [NSFileHandle fileHandleForWritingAtPath:path];
    if (fh) {
        [fh seekToEndOfFile];
        self_.fileHandle = fh;
        int fd = open(path.UTF8String, O_WRONLY | O_APPEND | O_CREAT, 0644);
        if (fd >= 0) sLogFileDescriptor = fd;
    }
}

+ (NSString *)logDirectoryCreateIfNeeded {
    NSFileManager *fm = [NSFileManager defaultManager];
    NSError *err = nil;
    NSArray<NSString *> *roots = nil;
    roots = [fm URLsForDirectory:NSLibraryDirectory inDomains:NSUserDomainMask];
    NSURL *libURL = roots.firstObject;
    if (libURL) {
        NSURL *logsDir = [libURL URLByAppendingPathComponent:@"Logs" isDirectory:YES];
        NSURL *appLogs = [logsDir URLByAppendingPathComponent:@"KuiklyMacApp" isDirectory:YES];
        if ([fm createDirectoryAtURL:appLogs withIntermediateDirectories:YES
                          attributes:@{NSFilePosixPermissions: @0755} error:&err]) {
            return appLogs.path;
        }
        NSLog(@"[KRDiagnosticLog] cannot create ~/Library/Logs/KuiklyMacApp: %@", err);
    }
    NSString *tmp = NSTemporaryDirectory();
    NSString *fallback = [tmp stringByAppendingPathComponent:@"KuiklyMacApp-logs"];
    if ([fm createDirectoryAtPath:fallback withIntermediateDirectories:YES
                       attributes:@{NSFilePosixPermissions: @0755} error:&err]) {
        return fallback;
    }
    NSLog(@"[KRDiagnosticLog] cannot create fallback log dir %@: %@", fallback, err);
    return nil;
}

+ (nullable NSString *)logFilePath { return [KRDiagnosticLog shared].filePath; }

+ (void)flush {
    KRDiagnosticLog *self_ = [KRDiagnosticLog shared];
    if (!self_.fileHandle) return;
    dispatch_sync(self_.serialQueue, ^{
        @try { [self_.fileHandle synchronizeFile]; }
        @catch (NSException *e) { NSLog(@"[KRDiagnosticLog] flush failed: %@", e); }
    });
}

#pragma mark - Public API

+ (void)log:(KRLogLevel)level tag:(NSString *)tag message:(NSString *)message {
    [KRDiagnosticLog log:level tag:tag message:message fields:nil];
}

+ (void)log:(KRLogLevel)level
        tag:(NSString *)tag
    message:(NSString *)message
     fields:(nullable NSDictionary<NSString *, id> *)fields {
    KRDiagnosticLog *self_ = [KRDiagnosticLog shared];
    if (level < self_.minLevel) return;
    if (tag.length == 0) tag = @"app";
    if (message == nil) message = @"";

    __block _KRDiagThrottleKey *tk = nil;
    if (self_.throttleEnabled) {
        NSString *key = [NSString stringWithFormat:@"%@|%@", tag, message];
        dispatch_sync(self_.serialQueue, ^{
            tk = self_.throttleMap[key];
            NSTimeInterval now = [NSDate timeIntervalSinceReferenceDate];
            if (!tk) {
                tk = [_KRDiagThrottleKey new];
                tk.tag = tag;
                tk.message = message;
                tk.count = 1;
                tk.firstAt = now;
                self_.throttleMap[key] = tk;
            } else {
                tk.count += 1;
            }
        });
        if (tk.count > 5 && ([NSDate timeIntervalSinceReferenceDate] - tk.firstAt) < kKRDiagThrottleWindowSec) {
            return;
        }
        if (tk.count > 5 && ([NSDate timeIntervalSinceReferenceDate] - tk.firstAt) >= kKRDiagThrottleWindowSec) {
            dispatch_sync(self_.serialQueue, ^{ tk.count = 1; tk.firstAt = [NSDate timeIntervalSinceReferenceDate]; });
        }
    }

    dispatch_async(self_.serialQueue, ^{
        [self_ emitInline:level tag:tag message:message fields:fields];
    });
}

+ (void)logObjCException:(NSException *)exception
                     tag:(NSString *)tag
                  origin:(nullable NSString *)origin {
    if (!exception) return;
    NSString *reason = exception.reason ?: @"<no reason>";
    NSArray<NSString *> *symbols = exception.callStackSymbols ?: @[];
    NSMutableDictionary *fields = [NSMutableDictionary new];
    fields[@"name"]      = exception.name ?: @"NSException";
    fields[@"origin"]    = origin ?: @"unknown";
    fields[@"userInfo"]  = exception.userInfo ?: @{};
    fields[@"symbols"]   = symbols;
    [KRDiagnosticLog log:KRLogLevelFatal
                      tag:(tag.length ? tag : @"diag.exception")
                  message:reason
                   fields:fields];
    [self flush];
}

#pragma mark - Emit

- (void)emitInline:(KRLogLevel)level
               tag:(NSString *)tag
           message:(NSString *)message
            fields:(nullable NSDictionary *)fields {
    NSDictionary *envelope = [KRDiagnosticLog makeEnvelope:level tag:tag message:message fields:fields];
    NSString *line = [KRDiagnosticLog jsonLineForEnvelope:envelope];
    if (line) {
        [self writeLine:line];
        [self appendToRecent:line];
    }
    [self writeToOsLog:envelope];
}

+ (NSDictionary *)makeEnvelope:(KRLogLevel)level
                            tag:(NSString *)tag
                        message:(NSString *)message
                         fields:(nullable NSDictionary *)fields {
    NSTimeInterval ts = [NSDate timeIntervalSinceReferenceDate];
    uint64_t epoch_us = (uint64_t)(ts * 1000000.0);
    NSString *thr = [KRDiagnosticLog currentThreadLabel];
    NSMutableDictionary *env = [NSMutableDictionary new];
    env[@"ts_us"]   = @(epoch_us);
    env[@"level"]   = [KRDiagnosticLog levelName:level];
    env[@"tag"]     = tag ?: @"app";
    env[@"thread"]  = thr ?: @"unknown";
    env[@"isMain"]  = @([NSThread isMainThread]);
    env[@"pid"]     = @([NSProcessInfo processInfo].processIdentifier);
    if (message.length) env[@"msg"] = message;
    if (fields.count)  env[@"fields"] = fields;
    return env;
}

+ (NSString *)jsonLineForEnvelope:(NSDictionary *)env {
    if (![NSJSONSerialization isValidJSONObject:env]) {
        NSMutableDictionary *safe = [NSMutableDictionary dictionaryWithDictionary:env];
        [safe removeObjectForKey:@"fields"];
        safe[@"fields"] = @{@"_unserializable": @"true",
                            @"_orig_keys": [env[@"fields"] isKindOfClass:[NSDictionary class]]
                                ? [env[@"fields"] allKeys] : @[]};
        env = safe;
    }
    NSError *err = nil;
    NSData *data = [NSJSONSerialization dataWithJSONObject:env options:0 error:&err];
    if (!data) return nil;
    NSString *str = [[NSString alloc] initWithData:data encoding:NSUTF8StringEncoding];
    return str ? [str stringByAppendingString:@"\n"] : nil;
}

- (void)writeLine:(NSString *)line {
    if (line.length == 0) return;
    NSFileHandle *fh = self.fileHandle;
    if (!fh) return;
    @try {
        [fh writeData:[line dataUsingEncoding:NSUTF8StringEncoding]];
        unsigned long long size = [fh offsetInFile];
        if (size > kKRDiagMaxFileBytes) {
            [fh synchronizeFile];
            [fh closeFile];
            self.fileHandle = nil;
            sLogFileDescriptor = -1;
            [KRDiagnosticLog archiveAndRotate];
            [KRDiagnosticLog openLogFile];
        }
    } @catch (NSException *e) {
        NSLog(@"[KRDiagnosticLog] writeLine crashed: %@ reason=%@", e.name, e.reason);
    }
}

+ (void)archiveAndRotate {
    NSFileManager *fm = [NSFileManager defaultManager];
    NSString *dir = [KRDiagnosticLog logDirectoryCreateIfNeeded];
    if (!dir) return;
    NSString *cur = [dir stringByAppendingPathComponent:kKRDiagFileName];
    NSString *arc = [dir stringByAppendingPathComponent:kKRDiagFileArchiveName];
    if ([fm fileExistsAtPath:arc]) {
        NSDictionary *attrs = [fm attributesOfItemAtPath:arc error:nil];
        if (attrs) {
            unsigned long long sz = [attrs fileSize];
            if (sz > kKRDiagKeepArchiveBytes) {
                [fm removeItemAtPath:arc error:nil];
            }
        }
    }
    [fm moveItemAtPath:cur toPath:arc error:nil];
}

- (void)appendToRecent:(NSString *)line {
    NSString *trimmed = [line stringByTrimmingCharactersInSet:[NSCharacterSet newlineCharacterSet]];
    [self.recentBuffer addObject:trimmed];
    if (self.recentBuffer.count > kKRDiagMaxRecentLines) {
        [self.recentBuffer removeObjectsInRange:NSMakeRange(0, self.recentBuffer.count - kKRDiagMaxRecentLines)];
    }
}

+ (NSArray<NSString *> *)recentLines:(NSUInteger)maxLines {
    KRDiagnosticLog *self_ = [KRDiagnosticLog shared];
    __block NSArray *snapshot = nil;
    dispatch_sync(self_.serialQueue, ^{
        NSUInteger n = MIN(maxLines, self_.recentBuffer.count);
        NSRange r = NSMakeRange(self_.recentBuffer.count - n, n);
        snapshot = [self_.recentBuffer subarrayWithRange:r];
    });
    return snapshot ?: @[];
}

- (void)writeToOsLog:(NSDictionary *)env {
    os_log_t logger = os_log_create([kKRDiagSubsystem UTF8String], [kKRDiagCategory UTF8String]);
    NSString *line = [NSString stringWithFormat:@"%@",
                      [env[@"msg"] isKindOfClass:[NSString class]] ? env[@"msg"] : @"<event>"];
    NSString *prefix = [NSString stringWithFormat:@"[%@][%@][%@] ",
                        env[@"tag"] ?: @"app",
                        env[@"thread"] ?: @"unknown",
                        env[@"level"] ?: @"info"];
    NSString *combined = [prefix stringByAppendingString:line];
    switch ([KRDiagnosticLog levelFromName:env[@"level"]]) {
        case KRLogLevelTrace: os_log_debug(logger, "%{public}@", combined); break;
        case KRLogLevelDebug: os_log_debug(logger, "%{public}@", combined); break;
        case KRLogLevelInfo:  os_log_info (logger, "%{public}@", combined); break;
        case KRLogLevelWarn:  os_log_info (logger, "%{public}@ (warn)", combined); break;
        case KRLogLevelError: os_log_error(logger, "%{public}@", combined); break;
        case KRLogLevelFatal: os_log_fault(logger, "%{public}@", combined); break;
    }
}

+ (KRLogLevel)levelFromName:(NSString *)name {
    if ([name isEqualToString:@"TRACE"]) return KRLogLevelTrace;
    if ([name isEqualToString:@"DEBUG"]) return KRLogLevelDebug;
    if ([name isEqualToString:@"INFO"])  return KRLogLevelInfo;
    if ([name isEqualToString:@"WARN"])  return KRLogLevelWarn;
    if ([name isEqualToString:@"ERROR"]) return KRLogLevelError;
    if ([name isEqualToString:@"FATAL"]) return KRLogLevelFatal;
    return KRLogLevelInfo;
}

+ (NSString *)levelName:(KRLogLevel)l {
    switch (l) {
        case KRLogLevelTrace: return @"TRACE";
        case KRLogLevelDebug: return @"DEBUG";
        case KRLogLevelInfo:  return @"INFO";
        case KRLogLevelWarn:  return @"WARN";
        case KRLogLevelError: return @"ERROR";
        case KRLogLevelFatal: return @"FATAL";
    }
    return @"INFO";
}

#pragma mark - Thread id

+ (NSString *)currentThreadLabel {
    if ([NSThread isMainThread]) return @"main";
    NSString *name = [NSThread currentThread].name;
    if (name.length) return name;
    pthread_t tid = pthread_self();
    uint64_t tid64 = 0;
    pthread_threadid_np(tid, &tid64);
    return [NSString stringWithFormat:@"tid-%llx", (unsigned long long)tid64];
}

#pragma mark - Backtrace

+ (NSString *)backtraceSkippingFrames:(NSUInteger)skip limit:(NSUInteger)limit {
    void *stack[kKRDiagMaxRecentLines];
    int depth = backtrace(stack, MIN((int)sizeof(stack)/sizeof(stack[0]), (int)limit + (int)skip + 1));
    if (depth <= (int)skip) return @"";
    int n = depth - (int)skip;
    char **syms = backtrace_symbols(stack + skip, n);
    NSMutableString *out = [NSMutableString new];
    for (int i = 0; i < n; i++) {
        if (syms && syms[i]) [out appendFormat:@"%s\n", syms[i]];
    }
    if (syms) free(syms);
    return [out stringByTrimmingCharactersInSet:[NSCharacterSet whitespaceAndNewlineCharacterSet]];
}

#pragma mark - Crash backstop

static void KRDiagnosticLog_OnObjCException(NSException *e) {
    [KRDiagnosticLog handleObjCException:e];
}

+ (void)handleObjCException:(NSException *)e {
    KRDiagnosticLog *self_ = [KRDiagnosticLog shared];
    self_.signaled = YES;
    // Write synchronously (we are on the crashing thread).
    NSDictionary *env = [KRDiagnosticLog makeEnvelope:KRLogLevelFatal
                                                  tag:@"diag.exception"
                                              message:(e.reason ?: @"uncaught NSException")
                                               fields:@{@"name": e.name ?: @"NSException",
                                                        @"stack": e.callStackSymbols ?: @[]}];
    NSString *line = [KRDiagnosticLog jsonLineForEnvelope:env];
    [self_ writeLine:line];
    [self_ appendToRecent:line];
    [self_ writeToOsLog:env];
    [KRDiagnosticLog flushSync];
}

/// C-style minimal signal handler. ASYNC-SIGNAL-UNSAFE — call only from `write(2)` and
/// `mach_absolute_time`. No Objective-C, no Foundation, no locks, no stdio buffering.
static void KRDiag_SignalHandlerImpl(int sig, siginfo_t *info, void *ucontext) {
    (void)ucontext;
    int code = info ? info->si_code : 0;
    void *addr = info ? info->si_addr : NULL;
    if (sKRDiagTimebase.denom == 0) {
        mach_timebase_info(&sKRDiagTimebase);
    }
    uint64_t ns = mach_absolute_time() * (uint64_t)sKRDiagTimebase.numer / (uint64_t)sKRDiagTimebase.denom;
    char buf[512];
    int len = snprintf(buf, sizeof(buf),
                       "{\"ts_us\":%llu,\"level\":\"FATAL\",\"tag\":\"diag.signal\","
                       "\"thread\":\"signal\",\"isMain\":false,\"pid\":%d,"
                       "\"msg\":\"signal=%d code=%d\","
                       "\"fields\":{\"signo\":%d,\"code\":%d,\"fault_addr\":\"%p\"}}\n",
                       (unsigned long long)(ns / 1000ULL),
                       getpid(), sig, code, sig, code, addr);
    int fd = sLogFileDescriptor;
    if (len > 0 && fd >= 0) {
        (void)write(fd, buf, (size_t)len);
    }
    // Re-raise with default handler so the OS records a real crash.
    struct sigaction sa; memset(&sa, 0, sizeof(sa));
    sa.sa_handler = SIG_DFL;
    sigaction(sig, &sa, NULL);
    raise(sig);
}

+ (void)installCrashBackstop {
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        NSSetUncaughtExceptionHandler(&KRDiagnosticLog_OnObjCException);
        struct sigaction sa;
        memset(&sa, 0, sizeof(sa));
        sa.sa_sigaction = KRDiag_SignalHandlerImpl;
        sa.sa_flags = SA_SIGINFO | SA_RESETHAND;
        sigemptyset(&sa.sa_mask);
        sigaction(SIGSEGV, &sa, NULL);
        sigaction(SIGBUS,  &sa, NULL);
        sigaction(SIGILL,  &sa, NULL);
        sigaction(SIGABRT, &sa, NULL);
        sigaction(SIGFPE,  &sa, NULL);
        sigaction(SIGTRAP, &sa, NULL);
    });
}

@end

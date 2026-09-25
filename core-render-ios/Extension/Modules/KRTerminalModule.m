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
#import "KRTerminalModule.h"
#import "NSObject+KR.h"
#import "KRSftpSession.h"
#import <NMSSH/NMSSH.h>

/// 一个 shell：持有 channel 引用（delegate 是 weak，必须由本对象保活）与输出缓冲
@interface KRTerminalShell : NSObject <NMSSHChannelDelegate>
@property (nonatomic, strong) NMSSHChannel *channel;
@property (nonatomic, strong) NSMutableData *buffer;
@property (nonatomic, strong) NSLock *lock;
@end

@implementation KRTerminalShell

- (instancetype)initWithChannel:(NMSSHChannel *)channel {
    self = [super init];
    if (self) {
        _channel = channel;
        _buffer = [NSMutableData data];
        _lock = [NSLock new];
    }
    return self;
}

- (void)append:(NSData *)data {
    if (data.length == 0) return;
    [self.lock lock];
    [self.buffer appendData:data];
    [self.lock unlock];
}

- (void)channel:(NMSSHChannel *)channel didReadData:(NSString *)message {
    [self append:[message dataUsingEncoding:NSUTF8StringEncoding]];
}

- (void)channel:(NMSSHChannel *)channel didReadRawData:(NSData *)data {
    [self append:data];
}

- (void)channel:(NMSSHChannel *)channel didReadError:(NSString *)error {
    [self append:[error dataUsingEncoding:NSUTF8StringEncoding]];
}

- (void)channel:(NMSSHChannel *)channel didReadRawError:(NSData *)data {
    [self append:data];
}

@end

@implementation KRTerminalModule {
    NSMutableDictionary<NSString *, KRTerminalShell *> *_shells;
    NSUInteger _counter;
}

- (instancetype)init {
    self = [super init];
    if (self) {
        _shells = [NSMutableDictionary new];
    }
    return self;
}

- (void)open:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] kr_stringToDictionary];
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    if ([params[@"local"] boolValue]) {
        if (callback) callback(@{@"error": @"not implemented: local shell"});
        return;
    }
    NSString *sessionId = params[@"sessionId"] ?: @"";
    @try {
        NMSSHSession *session = (NMSSHSession *)[KRSftpSession sessionById:sessionId];
        NMSSHChannel *channel = session.channel;
        channel.requestPty = YES;
        channel.ptyTerminalType = NMSSHChannelPtyTerminalXterm;
        KRTerminalShell *shell = [[KRTerminalShell alloc] initWithChannel:channel];
        channel.delegate = shell;
        NSError *err = nil;
        if (![channel startShell:&err]) {
            if (callback) callback(@{@"error": err.localizedDescription ?: @"startShell failed"});
            return;
        }
        NSString *sid = [NSString stringWithFormat:@"term-%lu", (unsigned long)(++_counter)];
        _shells[sid] = shell;
        if (callback) callback(@{@"shellId": sid});
    } @catch (NSException *e) {
        if (callback) callback(@{@"error": e.reason ?: @"open shell failed"});
    }
}

- (void)read:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] kr_stringToDictionary];
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    NSString *sid = params[@"shellId"] ?: @"";
    long long from = [params[@"offset"] longLongValue];
    KRTerminalShell *shell = _shells[sid];
    if (!shell) {
        if (callback) callback(@{@"data": @"", @"offset": @(from), @"closed": @YES});
        return;
    }
    NSData *chunk = nil;
    [shell.lock lock];
    NSUInteger total = shell.buffer.length;
    if (from >= 0 && (NSUInteger)from < total) {
        chunk = [shell.buffer subdataWithRange:NSMakeRange((NSUInteger)from, total - (NSUInteger)from)];
    }
    [shell.lock unlock];
    if (!chunk) chunk = [NSData data];
    long long next = from + (long long)chunk.length;
    if (callback) callback(@{
        @"data": [chunk base64EncodedStringWithOptions:0] ?: @"",
        @"offset": @(next),
        @"closed": @NO,
    });
}

- (void)write:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] kr_stringToDictionary];
    KRTerminalShell *shell = _shells[params[@"shellId"] ?: @""];
    if (!shell) return;
    NSData *data = [[NSData alloc] initWithBase64EncodedString:(params[@"data"] ?: @"") options:0];
    NSString *text = data ? [[NSString alloc] initWithData:data encoding:NSUTF8StringEncoding] : nil;
    if (text.length == 0) return;
    NSError *e = nil;
    [shell.channel write:text error:&e];
}

- (void)resize:(NSDictionary *)args {
    // NMSSH 未公开 pty resize API：保持默认尺寸（no-op）
}

- (void)close:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] kr_stringToDictionary];
    NSString *sid = params[@"shellId"] ?: @"";
    KRTerminalShell *shell = _shells[sid];
    if (!shell) return;
    [_shells removeObjectForKey:sid];
    @try {
        [shell.channel closeShell];
    } @catch (NSException *e) {
    }
}

@end

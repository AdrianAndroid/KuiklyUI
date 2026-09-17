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

#import "KRLogModule.h"
#import "KuiklyRenderThreadManager.h"
#import "KRConvertUtil.h"
#import "KuiklyRenderThreadManager.h"

static id<KuiklyLogProtocol> gLogHandler;
static id<KuiklyLogProtocol> gLogUserSuppliedHandler;


@interface KuiklyLogHandler : NSObject<KuiklyLogProtocol>

@end

@implementation KuiklyLogHandler

- (BOOL)asyncLogEnable {
    return NO;
}

- (void)logInfo:(NSString *)message {
    NSLog(@"%@", message);
}

- (void)logDebug:(NSString *)message {
#if DEBUG
    NSLog(@"%@", message);
#endif
}

- (void)logError:(NSString *)message {
    NSLog(@"%@", message);
}

@end


@interface KRLogModule()


@property (nonatomic, assign) BOOL needSyncLogTasks;
@property (nonatomic, strong) NSMutableArray<dispatch_block_t> *logTasks;
@property (nonatomic, assign) BOOL asyncLogEnable;
@end

@implementation KRLogModule

- (instancetype)init {
    if (self = [super init]) {
        _logTasks = [NSMutableArray new];
        if ([[[self class] logHandler] respondsToSelector:@selector(asyncLogEnable)]) {
            _asyncLogEnable = [[[self class] logHandler] asyncLogEnable];
        }
    }
    return self;
}

/*
 * @brief 注册自定义log实现
 */
+ (void)registerLogHandler:(id<KuiklyLogProtocol>)logHandler {
    gLogUserSuppliedHandler = logHandler;
}

+ (id<KuiklyLogProtocol>)logHandler {
    // 优先使用用户赋值的 handler
    if (gLogUserSuppliedHandler) {
        return gLogUserSuppliedHandler;
    }
    
    static dispatch_once_t onceToken;
    // 避免多线程同时访问，确保只创建一次
    dispatch_once(&onceToken, ^{
        if (!gLogHandler) {
            gLogHandler = [KuiklyLogHandler new];
        }
    });
    return gLogHandler;
}

- (void)logInfo:(NSDictionary *)args {
    NSString *message = args[KR_PARAM_KEY];
    if (_asyncLogEnable) {
        NSString *logTime = [self logTimeDate];
        [self addLogTask:^{
            [[KRLogModule logHandler] logInfo:[NSString stringWithFormat:@"|%@|%@", logTime, message]];
        }];
    } else {
        [[KRLogModule logHandler] logInfo:message];
    }
    
   
}

- (void)logDebug:(NSDictionary *)args {
    NSString *message = args[KR_PARAM_KEY];
    if (_asyncLogEnable) {
        NSString *logTime = [self logTimeDate];
        [self addLogTask:^{
            [[KRLogModule logHandler] logDebug:[NSString stringWithFormat:@"|%@|%@", logTime, message]];
        }];
    } else {
        [[KRLogModule logHandler] logDebug:message];
    }
}

- (void)logError:(NSDictionary *)args {
    NSString *message = args[KR_PARAM_KEY];
    if (_asyncLogEnable) {
        NSString *logTime = [self logTimeDate];
        [self addLogTask:^{
            [[KRLogModule logHandler] logError:[NSString stringWithFormat:@"|%@|%@", logTime, message]];
        }];
    } else {
        [[KRLogModule logHandler] logError:message];
    }
}

#pragma mark - public

+ (void)logError:(NSString *)errorLog {
    NSString *message = [NSString stringWithFormat:@"[kuikly error]%@", errorLog];
    NSLog(@"%@", message);
    [[KRLogModule logHandler] logError:message]; // 日志落入接入层体系中
#if DEBUG
    // 本地开发可视化提醒。
    // 自动化/无人值守运行（集成测试、CI、批量回归）里模态弹窗会阻塞流程，
    // 可用环境变量 KUIKLY_SUPPRESS_ERROR_ALERT=1 关闭；同时限制最多弹 3 次，
    // 避免错误风暴时把主线程埋在一堆弹窗里。
    static NSInteger sAlertCount = 0;
    static BOOL sAlertEnabled = YES;
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        NSString *v = [[[NSProcessInfo processInfo] environment] objectForKey:@"KUIKLY_SUPPRESS_ERROR_ALERT"];
        sAlertEnabled = !(v.length > 0 && ![v isEqualToString:@"0"] && ![v.lowercaseString isEqualToString:@"false"]);
    });
    if (sAlertEnabled && sAlertCount < 3) {
        sAlertCount++;
        [KRConvertUtil hr_alertWithTitle:@"kuikly error" message:message];
    }
#endif
}

+ (void)logInfo:(NSString *)infoLog {
    [[KRLogModule logHandler] logInfo:infoLog]; // 日志落入接入层体系中
}


#pragma mark - private
  
- (void)addLogTask:(dispatch_block_t)task {
    assert([KuiklyRenderThreadManager isContextQueue]);
    if (!task) {
        return ;
    }
    [_logTasks addObject:task];
    [self setNeedSyncLogTasks];
}

- (void)setNeedSyncLogTasks {
    if (!_needSyncLogTasks) {
        _needSyncLogTasks = YES;
        [KuiklyRenderThreadManager performOnContextQueueWithBlock:^{
            self.needSyncLogTasks = NO;
            NSArray *tasks = [self.logTasks copy];
            self.logTasks = [NSMutableArray new];
            [KuiklyRenderThreadManager performOnLogQueueWithBlock:^{
                for (dispatch_block_t task in tasks) {
                    task();
                }
            }];
        }];
    }
}

- (NSString *)logTimeDate {
    NSDate *date = [NSDate date];
    NSDateFormatter *formatter = [[NSDateFormatter alloc] init];
    [formatter setDateFormat:@"HH:mm.ss.SSS"];
    return [formatter stringFromDate:date];
}

@end



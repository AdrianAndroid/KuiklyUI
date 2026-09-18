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

#import "KRVideoViewHandler.h"

@interface KRVideoViewHandler()<WMPlayerDelegate>
/** 进度轮询定时器：WMPlayer 没有周期回调，只能轮询 currentTime/duration */
@property (nonatomic, strong) NSTimer *krv_progressTimer;
/** 首帧是否已上报（用于隐藏加载态） */
@property (nonatomic, assign) BOOL krv_firstFrameReported;
/** 是否处于播放态（WMPlayer 未暴露可靠的 isPlaying，自己记录） */
@property (nonatomic, assign) BOOL krv_isPlaying;
@end

@implementation KRVideoViewHandler

+ (void)load {
    [KRVideoView registerVideoViewCreator:^id<KRVideoViewProtocol> _Nonnull(NSString * _Nonnull src, CGRect frame) {
        WMPlayerModel *playerModel = [WMPlayerModel new];
        playerModel.videoURL = [NSURL URLWithString:src];
        KRVideoViewHandler *videoView = [[KRVideoViewHandler alloc] initPlayerModel:playerModel];
        videoView.delegate = videoView;
        return videoView;
    }];
}

#pragma mark - KRVideoViewProtocol

@synthesize krv_delegate;

- (void)krv_play {
    self.krv_isPlaying = YES;
    [self play];
    [self.krv_delegate videoPlayStateDidChangedWithState:KRVideoPlayStatePlaying extInfo:@{}];
    [self krv_startProgressTimer];
}

- (void)krv_preplay {
    //
}

- (void)krv_pause {
    self.krv_isPlaying = NO;
    [self pause];
    [self.krv_delegate videoPlayStateDidChangedWithState:KRVideoPlayStatePaused extInfo:@{}];
}

- (void)krv_stop {
    self.krv_isPlaying = NO;
    [self krv_stopProgressTimer];
    [self krv_teardownSafely];
}

/**
 * 安全拆除播放器。
 *
 * WMPlayer 的 `resetWMPlayer` 只把 currentItem/player 置 nil，**不会摘除**它用
 * `addPeriodicTimeObserverForInterval:` 注册的周期观察者；观察者随后再触发一次
 * `syncScrubber` 时 currentItem 已为 nil，内部 `currentTime.timescale` 为 0 →
 * **整数除零崩溃（SIGFPE）**。
 * 这些属性在 WMPlayer.m 的类扩展里（对外不可见），只能用 KVC 访问。
 */
- (void)krv_teardownSafely {
    @try {
        id observer = [self valueForKey:@"playbackTimeObserver"];
        id player = [self valueForKey:@"player"];
        if (observer && player) {
            [player removeTimeObserver:observer];
            [self setValue:nil forKey:@"playbackTimeObserver"];
        }
    } @catch (__unused NSException *e) {
        // KVC 取不到时忽略：WMPlayer 的 dealloc 仍会兜底移除观察者
    }
    [self resetWMPlayer];
}

- (void)krv_setVideoContentMode:(KRVideoViewContentMode)videoViewContentMode {
    if (videoViewContentMode == KRVideoViewContentModeScaleToFill) {
        [self setPlayerLayerGravity:(WMPlayerLayerGravityResize)];
    }  else if (videoViewContentMode == KRVideoViewContentModeScaleAspectFit) {
        [self setPlayerLayerGravity:(WMPlayerLayerGravityResizeAspect)];
    } else {
        [self setPlayerLayerGravity:(WMPlayerLayerGravityResizeAspectFill)];
    }
}

- (void)krv_setRate:(CGFloat)rate {
    self.rate = rate;
}

- (void)krv_setMuted:(BOOL)muted {
    self.muted = muted;
}
/*
 * kuikly侧设置的属性，一般用于业务扩展使用
 */
- (void)krv_setPropWithKey:(NSString *)propKey propValue:(id)propValue {
    
}
/*
 * kuikly侧调用方法，一般用于业务扩展使用
 */
- (void)krv_callWithMethod:(NSString *)method params:(NSString *)params {
    
}

- (void)krv_seekToTime:(NSUInteger)seekTotime { 
    // ...
}


#pragma mark - 进度轮询（WMPlayer 无周期回调）

- (void)krv_startProgressTimer {
    if (self.krv_progressTimer) {
        return;
    }
    // 必须用 block 版并捕获 weak self：target:self 的写法会让 timer 强引用 handler，
    // 而 handler 又强引用 timer（krv_progressTimer），形成保留环 → handler/播放器永不释放，
    // 表现为「退出播放页后仍在播放、多次进入会叠播多个」。
    __weak typeof(self) weakSelf = self;
    self.krv_progressTimer = [NSTimer scheduledTimerWithTimeInterval:0.5
                                                            repeats:YES
                                                              block:^(NSTimer *timer) {
        [weakSelf krv_onProgressTick];
    }];
    [[NSRunLoop mainRunLoop] addTimer:self.krv_progressTimer forMode:NSRunLoopCommonModes];
}

- (void)krv_stopProgressTimer {
    [self.krv_progressTimer invalidate];
    self.krv_progressTimer = nil;
}

- (void)krv_onProgressTick {
    if (!self.krv_isPlaying) {
        return;
    }
    // WMPlayer 的 currentTime/duration 单位为秒，协议要求毫秒
    NSTimeInterval cur = [self currentTime];
    NSTimeInterval total = [self duration];
    NSUInteger curMs = (NSUInteger)(MAX(0.0, cur) * 1000.0);
    NSUInteger totalMs = (NSUInteger)(MAX(0.0, total) * 1000.0);

    // 有进度即说明画面已在解码，上报首帧，供 kotlin 侧隐藏「加载中」
    if (!self.krv_firstFrameReported && curMs > 0) {
        self.krv_firstFrameReported = YES;
        [self.krv_delegate videoFirstFrameDidDisplay];
    }
    [self.krv_delegate playTimeDidChangedWithCurrentTime:curMs totalTime:totalMs];
}

- (void)dealloc {
    [self krv_stopProgressTimer];
    [self pause];
}

/**
 * 视图被移出窗口层级（关闭播放页 / 页面销毁）时必须停止播放并释放。
 * 否则退出后音频继续、再进入会叠加多个播放器同时出声。
 */
- (void)didMoveToWindow {
    [super didMoveToWindow];
    if (self.window == nil) {
        // 只暂停、不要在这里 resetWMPlayer。
        // WMPlayer 的 resetWMPlayer 会先把 currentItem/player 置 nil，却**不摘除**其
        // addPeriodicTimeObserver 注册的观察者；观察者随后再触发一次 syncScrubber 时
        // currentItem 已为 nil → 内部 timescale = 0 → 整数除零崩溃（SIGFPE，实测栈在
        // -[WMPlayer syncScrubber] WMPlayer.m:1072）。
        // 视图释放后 WMPlayer 的 dealloc 会按正确顺序（先 removeTimeObserver 再拆 item）收尾。
        self.krv_isPlaying = NO;
        [self krv_stopProgressTimer];
        [self pause];
    }
}

/// 被从父视图移除时同样兜底（部分场景不走 didMoveToWindow）
- (void)removeFromSuperview {
    self.krv_isPlaying = NO;
    [self krv_stopProgressTimer];
    [self pause];
    [super removeFromSuperview];
}

#pragma mark - WMPlayerDelegate

//准备播放的代理方法
-(void)wmplayerReadyToPlay:(WMPlayer *)wmplayer WMPlayerStatus:(WMPlayerState)state {
    [self.krv_delegate videoPlayStateDidChangedWithState:(KRVideoPlayStatePlaying) extInfo:@{}];
}
//播放失败的代理方法
-(void)wmplayerFailedPlay:(WMPlayer *)wmplayer WMPlayerStatus:(WMPlayerState)state {
    [self.krv_delegate videoPlayStateDidChangedWithState:(KRVideoPlayStateFaild) extInfo:@{}];
}

//播放器已经拿到视频的尺寸大小
-(void)wmplayerGotVideoSize:(WMPlayer *)wmplayer videoSize:(CGSize )presentationSize {
    
}

//播放完毕的代理方法
-(void)wmplayerFinishedPlay:(WMPlayer *)wmplayer {
    [self krv_stopProgressTimer];
    [self.krv_delegate videoPlayStateDidChangedWithState:(KRVideoPlayStatePlayEnd) extInfo:@{}];
}

// 说明：这里曾用 +IsiPhoneX 覆盖来规避 WMPlayer 崩溃，但 WMPlayer 内部是
// `[WMPlayer IsiPhoneX]` 的类级调用，不会走子类覆盖，因此无效。
// 真正的修复是在 iOSApp.swift 里通过 UIApplicationDelegateAdaptor 提供带 window 属性的
// AppDelegate，使 `UIApplication.sharedApplication.delegate.window` 可安全访问。

@end

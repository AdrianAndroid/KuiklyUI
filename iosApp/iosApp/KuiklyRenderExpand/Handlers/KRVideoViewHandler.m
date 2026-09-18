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
    self.krv_progressTimer = [NSTimer scheduledTimerWithTimeInterval:0.5
                                                             target:self
                                                           selector:@selector(krv_onProgressTick)
                                                           userInfo:nil
                                                            repeats:YES];
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

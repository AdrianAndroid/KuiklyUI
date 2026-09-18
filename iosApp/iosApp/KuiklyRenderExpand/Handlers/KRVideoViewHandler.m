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
#import <AVFoundation/AVFoundation.h>

@interface KRVideoViewHandler ()
@property (nonatomic, strong, nullable) AVPlayer *player;
@property (nonatomic, strong, nullable) AVPlayerLayer *playerLayer;
@property (nonatomic, copy, nullable) NSString *source;
@property (nonatomic, strong, nullable) id timeObserver;
/** 用户意图：是否希望处于播放态（rate 设置等会受它约束） */
@property (nonatomic, assign) BOOL userWantsPlay;
/** 期望倍速，起播时应用（AVPlayer 设置 rate 会直接起播，暂停时只记录） */
@property (nonatomic, assign) CGFloat pendingRate;
/** 首帧是否已上报（用于隐藏加载态） */
@property (nonatomic, assign) BOOL firstFrameReported;
/** 当前上报给上层的播放状态，避免重复回调 */
@property (nonatomic, assign) KRVideoPlayState reportedState;
@end

@implementation KRVideoViewHandler

// 协议里声明的属性不会自动合成，必须显式 @synthesize，
// 否则 KRVideoView 设置 krv_delegate 时会 doesNotRecognizeSelector 崩溃。
@synthesize krv_delegate;

#pragma mark - 注册

+ (void)load {
    [KRVideoView registerVideoViewCreator:^id<KRVideoViewProtocol> _Nonnull(NSString * _Nonnull src, CGRect frame) {
        return [[KRVideoViewHandler alloc] initWithFrame:frame source:src];
    }];
}

- (instancetype)initWithFrame:(CGRect)frame source:(NSString *)source {
    self = [super initWithFrame:frame];
    if (self) {
        _source = [source copy];
        _pendingRate = 1.0;
        _reportedState = KRVideoPlayStateUnknown;
        self.backgroundColor = UIColor.blackColor;
        [self p_setupPlayer];
    }
    return self;
}

- (void)p_setupPlayer {
    NSURL *url = [NSURL URLWithString:self.source ?: @""];
    if (!url) {
        return;
    }
    AVPlayerItem *item = [AVPlayerItem playerItemWithURL:url];
    self.player = [AVPlayer playerWithPlayerItem:item];
    self.player.actionAtItemEnd = AVPlayerActionAtItemEndPause;

    self.playerLayer = [AVPlayerLayer playerLayerWithPlayer:self.player];
    self.playerLayer.frame = self.bounds;
    self.playerLayer.videoGravity = AVLayerVideoGravityResizeAspect;
    [self.layer addSublayer:self.playerLayer];

    // 首帧上屏：AVPlayerLayer 的 readyForDisplay 是标准信号
    [self.playerLayer addObserver:self forKeyPath:@"readyForDisplay"
                          options:NSKeyValueObservingOptionNew context:NULL];
    [item addObserver:self forKeyPath:@"status" options:NSKeyValueObservingOptionNew context:NULL];
    [[NSNotificationCenter defaultCenter] addObserver:self
                                             selector:@selector(p_didPlayToEnd:)
                                                 name:AVPlayerItemDidPlayToEndTimeNotification
                                               object:item];

    __weak typeof(self) weakSelf = self;
    self.timeObserver = [self.player addPeriodicTimeObserverForInterval:CMTimeMakeWithSeconds(0.5, NSEC_PER_SEC)
                                                                  queue:dispatch_get_main_queue()
                                                             usingBlock:^(CMTime time) {
        [weakSelf p_onProgressTick];
    }];
}

- (void)layoutSubviews {
    [super layoutSubviews];
    // 关闭隐式动画，避免旋转/布局变化时画面跳动
    [CATransaction begin];
    [CATransaction setDisableActions:YES];
    self.playerLayer.frame = self.bounds;
    [CATransaction commit];
}

- (void)dealloc {
    if (self.timeObserver && self.player) {
        [self.player removeTimeObserver:self.timeObserver];
        self.timeObserver = nil;
    }
    @try {
        [self.playerLayer removeObserver:self forKeyPath:@"readyForDisplay"];
        [self.player.currentItem removeObserver:self forKeyPath:@"status"];
    } @catch (__unused NSException *e) {
        // 观察者可能已随 item 释放，忽略
    }
    [[NSNotificationCenter defaultCenter] removeObserver:self];
    [self.player pause];
}

#pragma mark - 进度 / 状态

- (void)p_onProgressTick {
    if (!self.player.currentItem) {
        return;
    }
    NSTimeInterval cur = CMTimeGetSeconds(self.player.currentTime);
    NSTimeInterval total = CMTimeGetSeconds(self.player.currentItem.duration);
    if (isnan(cur) || isinf(cur) || cur < 0) cur = 0;
    if (isnan(total) || isinf(total) || total < 0) total = 0;
    NSUInteger curMs = (NSUInteger)(cur * 1000.0);
    NSUInteger totalMs = (NSUInteger)(total * 1000.0);

    // 有播放进度即说明首帧已经在解码，上报首帧供上层隐藏「加载中」
    if (!self.firstFrameReported && curMs > 0) {
        self.firstFrameReported = YES;
        [self.krv_delegate videoFirstFrameDidDisplay];
    }
    [self.krv_delegate playTimeDidChangedWithCurrentTime:curMs totalTime:totalMs];
}

- (void)p_reportState:(KRVideoPlayState)state {
    if (self.reportedState == state) {
        return;
    }
    self.reportedState = state;
    [self.krv_delegate videoPlayStateDidChangedWithState:state extInfo:@{}];
}

- (void)observeValueForKeyPath:(NSString *)keyPath
                      ofObject:(id)object
                        change:(NSDictionary<NSKeyValueChangeKey, id> *)change
                       context:(void *)context {
    if ([keyPath isEqualToString:@"readyForDisplay"]) {
        if (self.playerLayer.readyForDisplay) {
            // 协议约定：回调 Playing 时应有画面
            [self p_reportState:KRVideoPlayStatePlaying];
        }
        return;
    }
    if ([keyPath isEqualToString:@"status"]) {
        if (self.player.currentItem.status == AVPlayerItemStatusFailed) {
            [self p_reportState:KRVideoPlayStateFaild];
        }
        return;
    }
}

- (void)p_didPlayToEnd:(NSNotification *)note {
    [self p_reportState:KRVideoPlayStatePlayEnd];
}

#pragma mark - KRVideoViewProtocol

- (void)krv_preplay {
    // 预加载到首帧但不自动播放
    self.userWantsPlay = NO;
    [self.player pause];
}

- (void)krv_play {
    self.userWantsPlay = YES;
    [self.player play];
    if (self.pendingRate > 0 && self.pendingRate != 1.0) {
        self.player.rate = self.pendingRate;
    }
    [self p_reportState:KRVideoPlayStatePlaying];
}

- (void)krv_pause {
    self.userWantsPlay = NO;
    [self.player pause];
    [self p_reportState:KRVideoPlayStatePaused];
}

- (void)krv_stop {
    self.userWantsPlay = NO;
    [self.player pause];
    [self.player seekToTime:kCMTimeZero];
    [self p_reportState:KRVideoPlayStatePaused];
}

- (void)krv_setVideoContentMode:(KRVideoViewContentMode)contentMode {
    switch (contentMode) {
        case KRVideoViewContentModeScaleToFill:
            self.playerLayer.videoGravity = AVLayerVideoGravityResize;
            break;
        case KRVideoViewContentModeScaleAspectFill:
            self.playerLayer.videoGravity = AVLayerVideoGravityResizeAspectFill;
            break;
        case KRVideoViewContentModeScaleAspectFit:
        default:
            self.playerLayer.videoGravity = AVLayerVideoGravityResizeAspect;
            break;
    }
}

- (void)krv_setMuted:(BOOL)muted {
    self.player.muted = muted;
}

- (void)krv_setRate:(CGFloat)rate {
    self.pendingRate = (rate > 0 ? rate : 1.0);
    // AVPlayer 直接设置 rate 会起播，暂停态下只记录，等 play 时应用
    if (self.userWantsPlay) {
        self.player.rate = self.pendingRate;
    }
}

- (void)krv_seekToTime:(NSUInteger)seekToTime {
    CMTime target = CMTimeMakeWithSeconds(seekToTime / 1000.0, NSEC_PER_SEC);
    [self.player seekToTime:target toleranceBefore:kCMTimeZero toleranceAfter:kCMTimeZero];
}

- (NSInteger)krv_currentTimeMs {
    if (!self.player.currentItem) {
        return 0;
    }
    NSTimeInterval cur = CMTimeGetSeconds(self.player.currentTime);
    if (isnan(cur) || isinf(cur) || cur < 0) {
        return 0;
    }
    return (NSInteger)(cur * 1000.0);
}

- (NSInteger)krv_playState {
    // 与 macOS(VLC) 侧对齐：0=Stopped 3=Ended 5=Playing 6=Paused
    if (!self.player.currentItem) {
        return 0;
    }
    if (self.reportedState == KRVideoPlayStatePlayEnd) {
        return 3;
    }
    return self.userWantsPlay ? 5 : 6;
}

- (void)krv_setPropWithKey:(NSString *)propKey propValue:(id)propValue {
    // 预留业务扩展
}

- (void)krv_callWithMethod:(NSString *)method params:(NSString *)params {
    if ([method isEqualToString:@"seekToTime"]) {
        [self krv_seekToTime:(NSUInteger)[params integerValue]];
        return;
    }
    if ([method isEqualToString:@"setFullscreen"]) {
        [self krv_setFullscreen:[params boolValue]];
        return;
    }
}

#pragma mark - 全屏（旋转屏幕）

/// 请求横屏 / 竖屏。iOS 16+ 用 requestGeometryUpdate，低版本回落到 UIDevice 设置方向。
- (void)krv_setFullscreen:(BOOL)fullscreen {
    UIInterfaceOrientationMask mask = fullscreen
        ? UIInterfaceOrientationMaskLandscapeRight
        : UIInterfaceOrientationMaskPortrait;
    [self krv_requestOrientationMask:mask];
}

- (void)krv_requestOrientationMask:(UIInterfaceOrientationMask)mask {
    UIWindowScene *scene = (UIWindowScene *)self.window.windowScene;
    if (!scene) {
        for (UIScene *s in UIApplication.sharedApplication.connectedScenes) {
            if ([s isKindOfClass:[UIWindowScene class]]) {
                scene = (UIWindowScene *)s;
                break;
            }
        }
    }
    if (!scene) {
        return;
    }
    if (@available(iOS 16.0, *)) {
        UIWindowSceneGeometryPreferencesIOS *prefs =
            [[UIWindowSceneGeometryPreferencesIOS alloc] initWithInterfaceOrientations:mask];
        [scene requestGeometryUpdateWithPreferences:prefs errorHandler:^(NSError *error) {
            NSLog(@"[VideoView] requestGeometryUpdate failed: %@", error);
        }];
        // 让当前 VC 的 supportedInterfaceOrientations 生效
        [scene.keyWindow.rootViewController setNeedsUpdateOfSupportedInterfaceOrientations];
    } else {
        [UIDevice.currentDevice setValue:@(mask == UIInterfaceOrientationMaskPortrait
                                           ? UIInterfaceOrientationPortrait
                                           : UIInterfaceOrientationLandscapeRight)
                                  forKey:@"orientation"];
    }
}

@end

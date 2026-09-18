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

#import "KRVideoView.h"
#import "KRComponentDefine.h"
#import "KRHttpRequestTool.h"
#import "KRConvertUtil.h"
/// 播控操作状态化维护
typedef NS_ENUM(NSInteger, KRVideoViewPlayControl) {
    KRVideoViewPlayControlNone = 0,
    KRVideoViewPlayControlPreplay = 1, //操作预播放视频
    KRVideoViewPlayControlPlay = 2, // 操作播放视频
    KRVideoViewPlayControlPause = 3, // 操作暂停视频
    KRVideoViewPlayControlStop = 4   // 操作停止视频
};

static VideoViewCreator gVideoViewCreator;

@interface KRVideoView()<KRVideoViewDelegate>

@property (nonatomic, strong) id<KRVideoViewProtocol> videoView;
/// 播放源属性
@property (nonatomic, strong) NSString *css_src;
/** 上次 seek 到的位置，用于对重复下发的属性去重 */
@property (nonatomic, assign) NSInteger lastSeekMs;
@property (nonatomic, strong) NSNumber *p_appliedSeekMs;
/// 播控操作属性
@property (nonatomic, strong) NSNumber *css_playControl;
/** 真正已下发给播放器的值；视图重建时清空以强制重放 */
@property (nonatomic, strong) NSNumber *p_appliedPlayControl;
/** 播放器尚未进入可播放态时收到的 seek，先存起来，起播后再补发 */
@property (nonatomic, assign) NSInteger p_pendingSeekMs;
/// 画面拉伸模式
@property (nonatomic, strong) NSString *css_resizeMode;
/// 静音属性
@property (nonatomic, strong) NSNumber *css_muted;
@property (nonatomic, strong) NSNumber *p_appliedMuted;
/// 倍速属性
@property (nonatomic, strong) NSNumber *css_rate;
/// 首帧事件
@property (nonatomic, strong) KuiklyRenderCallback css_firstFrame;
/// 播放状态变化事件
@property (nonatomic, strong) KuiklyRenderCallback css_stateChange;
/// 播放时间变化事件
@property (nonatomic, strong) KuiklyRenderCallback css_playTimeChange;
/// 通用扩展事件
@property (nonatomic, strong) KuiklyRenderCallback css_customEvent;

@end

@implementation KRVideoView
@synthesize hr_rootView;

+ (void)registerVideoViewCreator:(VideoViewCreator)creator {
    gVideoViewCreator = creator;
    NSAssert(gVideoViewCreator, @"creator 不能为空");
}

- (instancetype)initWithFrame:(CGRect)frame {
    if (self = [super initWithFrame:frame]) {
        NSAssert(gVideoViewCreator, @"should registerVideoViewCreato");
    }
    return self;
}

#pragma mark - KuiklyRenderViewExportProtocol

- (void)hrv_setPropWithKey:(NSString *)propKey propValue:(id)propValue {
    KUIKLY_SET_CSS_COMMON_PROP;
    if ([_videoView respondsToSelector:@selector(krv_setPropWithKey:propValue:)]) {
        [_videoView krv_setPropWithKey:propKey propValue:propValue];
    }
    
}

- (void)hrv_callWithMethod:(NSString *)method params:(NSString *)params callback:(KuiklyRenderCallback)callback {
    KUIKLY_CALL_CSS_METHOD;
    if ([_videoView respondsToSelector:@selector(krv_callWithMethod:params:)]) {
        [_videoView krv_callWithMethod:method params:params];
    }
}

#pragma mark - css 属性

- (void)setCss_src:(NSString *)css_src {
    if (!_css_src && css_src.length) { // 因为播放器不复用，所以就一次绑定src即可
        _css_src = css_src;
        [self p_createVideoViewIfNeed];
    }
}

- (void)setCss_seekTo:(NSNumber *)css_seekTo {
    NSInteger targetMs = css_seekTo.integerValue;
    // 约定：负值表示「未请求 seek」，直接忽略（避免首帧前下发 seekTo(0) 打断起播）
    if (targetMs < 0) {
        return;
    }
    // 属性可能因其他状态变化而被重复下发，这里去重，避免每帧都 seek
    if (_p_appliedSeekMs && _p_appliedSeekMs.intValue == targetMs) {
        return;
    }

    NSInteger currentMs = 0;
    if ([_videoView respondsToSelector:@selector(krv_currentTimeMs)]) {
        currentMs = [_videoView krv_currentTimeMs];
    }
    // 兜底：若业务把「播放进度」误绑到 seekTo，会出现每秒一次进度的 seek 风暴，
    // 每次都让 VLC flush + 重缓冲（表现为播放卡顿）。这里丢弃与播放器当前时间
    // 接近（<1.5s）的 seek 请求；真正的手动跳转差值通常远大于该阈值。
    if (currentMs > 0 && labs((long)(targetMs - currentMs)) < 1500) {
        _p_appliedSeekMs = css_seekTo;
        _lastSeekMs = targetMs;
        return;
    }

    // 播放器还没起播（Opening/Buffering/ESAdded）时 seek 会打断 VLC 的启动序列，
    // 甚至让状态停在 Paused。先暂存，等进入 Playing 再补发。
    if (![self p_isPlayerPlayable]) {
        _p_pendingSeekMs = targetMs;
        _p_appliedSeekMs = css_seekTo;
        _lastSeekMs = targetMs;
        return;
    }

    _lastSeekMs = targetMs;
    _p_appliedSeekMs = css_seekTo;
    if ([_videoView respondsToSelector:@selector(krv_seekToTime:)]) {
        [_videoView krv_seekToTime:(NSUInteger)targetMs];
    }
}

/** 播放器是否已进入可接受 seek 的状态 */
- (BOOL)p_isPlayerPlayable {
    if (!_videoView) {
        return NO;
    }
    if ([_videoView respondsToSelector:@selector(krv_currentTimeMs)] &&
        [_videoView krv_currentTimeMs] > 0) {
        return YES;
    }
    if ([_videoView respondsToSelector:@selector(krv_playState)]) {
        NSInteger st = [_videoView krv_playState];
        // VLC: 5=Playing 6=Paused 3=Ended 0=Stopped
        return (st == 5 || st == 6 || st == 3 || st == 0);
    }
    return NO;
}

- (void)setCss_resizeMode:(NSString *)css_resizeMode {
    _css_resizeMode = css_resizeMode;
    if ([css_resizeMode isEqualToString:@"cover"]) {
        [_videoView krv_setVideoContentMode:(KRVideoViewContentModeScaleAspectFill)];
    } else if ([css_resizeMode isEqualToString:@"stretch"]) {
        [_videoView krv_setVideoContentMode:(KRVideoViewContentModeScaleToFill)];
    } else {
        [_videoView krv_setVideoContentMode:(KRVideoViewContentModeScaleAspectFit)];
    }
}

- (void)setCss_playControl:(NSNumber *)css_playControl {
    // attr 块只要有任一被依赖的状态变化就会整体重算，playControl 往往被一同重复下发。
    // 不去重的话会在播放中反复调用 krv_play（并可能打断/重启解码），表现为卡顿。
    // 注意：去重看的是「已真正下发给播放器」的值（p_appliedPlayControl），
    // 而不是 css_playControl —— 视图尚未创建时下发会被丢弃，创建后必须能重放。
    if (_p_appliedPlayControl && _p_appliedPlayControl.intValue == css_playControl.intValue) {
        _css_playControl = css_playControl;
        return;
    }
    _p_appliedPlayControl = css_playControl;
    _css_playControl = css_playControl;
    switch ([css_playControl intValue]) {
        case KRVideoViewPlayControlPreplay:
            [_videoView krv_preplay];
            break;
        case KRVideoViewPlayControlPlay:
            [_videoView krv_play];
            break;
        case KRVideoViewPlayControlPause:
            [_videoView krv_pause];
            break;
        case KRVideoViewPlayControlStop:
            [_videoView krv_stop];
            break;
        default:
            break;
    }
}

- (void)setCss_muted:(NSNumber *)css_muted {
    if (_p_appliedMuted && _p_appliedMuted.boolValue == css_muted.boolValue) {
        _css_muted = css_muted;
        return;
    }
    _p_appliedMuted = css_muted;
    _css_muted = css_muted;
    [_videoView krv_setMuted:[_css_muted boolValue]];
}

- (void)setCss_rate:(NSNumber *)css_rate {
    _css_rate = css_rate;
    [_videoView krv_setRate:[css_rate floatValue]];
}



- (void)setFrame:(CGRect)frame {
    [super setFrame:frame];
    [self p_createVideoViewIfNeed];
    ((UIView *)_videoView).frame = self.bounds;
}

#pragma mark - KRVideoViewDelegate

- (void)videoPlayStateDidChangedWithState:(KRVideoPlayState)playState extInfo:(NSDictionary<NSString *, NSString *> *)extInfo {
    if (_css_stateChange) {
        _css_stateChange(@{@"state" : @(playState), @"extInfo": extInfo ?: @{}});
    }
    
}

- (void)playTimeDidChangedWithCurrentTime:(NSUInteger)currentTime totalTime:(NSUInteger)totalTime {
    if (_css_playTimeChange) {
        _css_playTimeChange(@{@"currentTime" : @(currentTime), @"totalTime": @(totalTime)});
    }
}

- (void)videoFirstFrameDidDisplay {
    if (_css_firstFrame) {
        _css_firstFrame(@{});
    }
}

- (void)customEventWithInfo:(NSDictionary<NSString *,NSString *> *)eventInfo {
    if (_css_customEvent) {
        _css_customEvent(eventInfo);
    }
}

#pragma mark - private

- (void)p_createVideoViewIfNeed {
    if (_videoView) {
        return ;
    }
    NSAssert(gVideoViewCreator, @"宿主未注册VideoView实现，却使用了VideoView");
    if (_css_src.length && !CGSizeEqualToSize(self.bounds.size, CGSizeZero) && gVideoViewCreator) {
        _videoView = gVideoViewCreator(_css_src, self.bounds);
        _videoView.krv_delegate = self;
        NSAssert([_videoView isKindOfClass:[UIView class]], @"videoView需要为UIView的子类");
        [self addSubview:(UIView *)_videoView];
        ((UIView *)_videoView).frame = self.bounds;
        // 新播放器还没有收到过任何属性，清空标记以强制重放
        _p_appliedPlayControl = nil;
        _p_appliedMuted = nil;
        _p_appliedSeekMs = nil;
        _p_pendingSeekMs = 0;
        [self setCss_resizeMode:_css_resizeMode];
        [self setCss_muted:_css_muted];
        if (_css_rate) {
            [self setCss_rate:_css_rate];
        }
        [self setCss_playControl:_css_playControl];
       
    }
}

@end

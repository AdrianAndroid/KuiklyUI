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
#import "KRDiagnosticLog.h"
#if __has_include(<VLCKit/VLCKit.h>)
#import <VLCKit/VLCKit.h>
#endif

@interface KRVideoViewHandler () <VLCMediaPlayerDelegate>

@property (nonatomic, strong) VLCMediaPlayer *mediaPlayer;
@property (nonatomic, copy) NSString *krv_source;
@property (nonatomic, strong) VLCVideoView *vlcVideoView;
@property (nonatomic, assign) BOOL krv_hasFirstFrameDisplayed;

@end

@implementation KRVideoViewHandler

@synthesize krv_delegate;

+ (void)load {
    [KRVideoView registerVideoViewCreator:^id<KRVideoViewProtocol> _Nonnull(NSString * _Nonnull src, CGRect frame) {
        KRVideoViewHandler *videoView = [[KRVideoViewHandler alloc] initWithFrame:frame source:src];
        return videoView;
    }];
}

- (instancetype)initWithFrame:(NSRect)frame source:(NSString *)source {
    self = [super initWithFrame:frame];
    if (self) {
        _krv_source = [source copy];
        self.backColor = [NSColor blackColor];
        [self p_setupMediaPlayerIfNeeded];
    }
    return self;
}

- (instancetype)initWithFrame:(NSRect)frame {
    self = [super initWithFrame:frame];
    if (self) {
        self.backColor = [NSColor blackColor];
    }
    return self;
}

- (void)dealloc {
    self.mediaPlayer.delegate = nil;
    [self.mediaPlayer stop];
    self.mediaPlayer.drawable = nil;
}

#pragma mark - Private

- (void)p_setupMediaPlayerIfNeeded {
    if (self.mediaPlayer || self.krv_source.length == 0) {
        return;
    }

    NSURL *url = [NSURL URLWithString:self.krv_source];
    if (url == nil) {
        return;
    }

    // 打开 VLC 内部日志：排查「有解码状态但无画面」这类问题必须看 vout 的报错
    if ([[[NSProcessInfo processInfo] environment][@"KUIKLY_VLC_DEBUG"] isEqualToString:@"1"]) {
        [VLCLibrary sharedLibrary].debugLogging = YES;
        [VLCLibrary sharedLibrary].debugLoggingLevel = 2;
    }
    VLCMedia *media = [VLCMedia mediaWithURL:url];
    // 用 VLCKit 自带的 VLCVideoView 作为渲染目标：
    // 直接把普通 NSView 传给 drawable/initWithVideoView: 时，VLC 会创建视频输出
    // 但画面挂不上（表现为解码在走、state 到 Playing，屏幕却全黑）。
    VLCVideoView *videoView = [[VLCVideoView alloc] initWithFrame:self.bounds];
    videoView.autoresizingMask = NSViewWidthSizable | NSViewHeightSizable;
    videoView.backColor = [NSColor blackColor];
    [self addSubview:videoView];
    self.vlcVideoView = videoView;

    VLCMediaPlayer *player = [[VLCMediaPlayer alloc] initWithVideoView:videoView];
    player.drawable = videoView;
    player.media = media;
    player.delegate = self;
    self.mediaPlayer = player;
    KR_DIAG_INFO(@"video", @"setup media url=%@", self.krv_source);
}

#pragma mark - KRVideoViewProtocol

- (void)krv_preplay {
    [self p_setupMediaPlayerIfNeeded];
}

- (void)krv_play {
    [self p_setupMediaPlayerIfNeeded];
    [self.mediaPlayer play];
}

- (void)krv_pause {
    [self.mediaPlayer pause];
}

- (void)krv_stop {
    [self.mediaPlayer stop];
}

- (void)krv_setVideoContentMode:(KRVideoViewContentMode)videoViewContentMode {
    VLCVideoView *vv = self.vlcVideoView;
    switch (videoViewContentMode) {
        case KRVideoViewContentModeScaleAspectFit:
            self.fillScreen = NO;
            if (vv) vv.fillScreen = NO;
            break;
        case KRVideoViewContentModeScaleAspectFill:
            self.fillScreen = YES;
            if (vv) vv.fillScreen = YES;
            break;
        case KRVideoViewContentModeScaleToFill:
        default:
            self.fillScreen = YES;
            if (vv) vv.fillScreen = YES;
            break;
    }
}

- (void)krv_setMuted:(BOOL)muted {
    if (self.mediaPlayer.audio != nil) {
        self.mediaPlayer.audio.muted = muted;
    }
}

- (void)krv_setRate:(CGFloat)rate {
    self.mediaPlayer.rate = rate;
}

- (NSInteger)krv_currentTimeMs {
    if (!self.mediaPlayer) return 0;
    return self.mediaPlayer.time.intValue;  // VLCKit 的 VLCTime 以毫秒为单位
}

- (NSInteger)krv_playState {
    if (!self.mediaPlayer) return -1;
    return (NSInteger)self.mediaPlayer.state;
}

- (void)krv_seekToTime:(NSUInteger)seekTotime {
    if (!self.mediaPlayer) {
        return;
    }
    VLCTime *time = [VLCTime timeWithInt:(int)seekTotime];
    self.mediaPlayer.time = time;
}

- (void)krv_setPropWithKey:(NSString *)propKey propValue:(id)propValue {
    // 预留给业务扩展使用，目前 mac 端默认不处理
}

- (void)krv_callWithMethod:(NSString *)method params:(NSString *)params {
    if ([method isEqualToString:@"seekToTime"]) {
        // 参数为毫秒字符串
        NSInteger ms = [params integerValue];
        [self krv_seekToTime:(NSUInteger)ms];
        return;
    }
}

#pragma mark - VLCMediaPlayerDelegate

- (void)mediaPlayerStateChanged:(NSNotification *)aNotification {
    VLCMediaPlayer *player = aNotification.object;
    if (player != self.mediaPlayer) {
        return;
    }
    KR_DIAG_INFO(@"video", @"vlc state=%ld hasVideo=%d drawableFrame=%@ selfFrame=%@",
                 (long)player.state, self.vlcVideoView.hasVideo ? 1 : 0,
                 NSStringFromRect(self.vlcVideoView.frame), NSStringFromRect(self.frame));

    KRVideoPlayState playState = KRVideoPlayStateUnknown;
    switch (player.state) {
        case VLCMediaPlayerStatePlaying:
            playState = KRVideoPlayStatePlaying;
            break;
        case VLCMediaPlayerStatePaused:
            playState = KRVideoPlayStatePaused;
            break;
        case VLCMediaPlayerStateOpening:
        case VLCMediaPlayerStateBuffering:
            playState = KRVideoPlayStateCaching;
            break;
        case VLCMediaPlayerStateStopped:
        case VLCMediaPlayerStateEnded:
            playState = KRVideoPlayStatePlayEnd;
            break;
        case VLCMediaPlayerStateError:
            playState = KRVideoPlayStateFaild;
            break;
        default:
            playState = KRVideoPlayStateUnknown;
            break;
    }

    if ([self.krv_delegate respondsToSelector:@selector(videoPlayStateDidChangedWithState:extInfo:)]) {
        [self.krv_delegate videoPlayStateDidChangedWithState:playState extInfo:@{}];
    }
}

- (void)mediaPlayerTimeChanged:(NSNotification *)aNotification {
    VLCMediaPlayer *player = aNotification.object;
    if (player != self.mediaPlayer) {
        return;
    }

    VLCTime *currentTime = player.time;
    VLCTime *totalTime = player.media.length;
    NSUInteger currentMs = (NSUInteger)currentTime.intValue;
    NSUInteger totalMs = totalTime ? (NSUInteger)totalTime.intValue : 0;

    if ([self.krv_delegate respondsToSelector:@selector(playTimeDidChangedWithCurrentTime:totalTime:)]) {
        [self.krv_delegate playTimeDidChangedWithCurrentTime:currentMs totalTime:totalMs];
    }

    if (!self.krv_hasFirstFrameDisplayed && player.hasVideoOut && currentMs > 0) {
        self.krv_hasFirstFrameDisplayed = YES;
        if ([self.krv_delegate respondsToSelector:@selector(videoFirstFrameDidDisplay)]) {
            [self.krv_delegate videoFirstFrameDidDisplay];
        }
    }
}

@end

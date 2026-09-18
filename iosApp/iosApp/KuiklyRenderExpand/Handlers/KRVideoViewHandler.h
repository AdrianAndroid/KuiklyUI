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

#import <UIKit/UIKit.h>
#import "KRVideoView.h"

NS_ASSUME_NONNULL_BEGIN

/**
 * iOS 端 VideoView 实现（基于系统 AVPlayer + AVPlayerLayer）。
 *
 * 为什么不用 WMPlayer：WMPlayer 5.0 会**无条件创建自己的一整套控件**
 * （左上角关闭、播放/暂停、进度条、全屏按钮），无法关闭，叠加在 Kuikly 自绘控件之上
 * 形成「多余的按钮」；且它还有 `+IsiPhoneX` 访问 delegate.window 崩溃、
 * `resetWMPlayer` 不摘周期观察者导致 `syncScrubber` 整数除零（SIGFPE）等问题。
 * 直接用 AVPlayerLayer 既没有自带 UI，也避免上述隐患。
 */
@interface KRVideoViewHandler : UIView <KRVideoViewProtocol>

- (instancetype)initWithFrame:(CGRect)frame source:(NSString *)source;

@end

NS_ASSUME_NONNULL_END

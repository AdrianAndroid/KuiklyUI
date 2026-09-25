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

#import "KRBridgeModule.h"
#import "NSObject+RIJCategory.h"

#import "KuiklyRenderViewController.h"
#import "KuiklyContextParam.h"
#import "KuiklyRenderView.h"
#import <SDWebImageManager.h>
#import <SDWebImageDownloader.h>
#import <SDImageCache.h>

#define REQ_PARAM_KEY @"reqParam"
#define CMD_KEY @"cmd"
// 扩展桥接接口
/*
 * @brief Native暴露接口到kotlin侧，提供kotlin侧调用native能力
 */

@implementation KRBridgeModule

@synthesize hr_rootView;


// 页面退出
- (void)closePage:(NSDictionary *)args {
    [self.hr_rootView.rij_viewController.navigationController popViewControllerAnimated:YES];
}

// 打开页面
- (void)openPage:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] rij_stringToDictionary];
   // KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];//
    NSString *pageName = params[@"pageName"] ?: params[@"url"]; // == pageName
    NSMutableDictionary *pageData = [params[@"pageData"] mutableCopy] ?: [NSMutableDictionary new];
    NSDictionary *urlParams = [pageName rij_getURLParameters];
    if (urlParams.count) {
        [pageData addEntriesFromDictionary:urlParams];
    }
    KuiklyRenderViewController *renderViewController = [[KuiklyRenderViewController alloc] initWithPageName:pageName pageData:pageData];
    [[self.hr_rootView.rij_viewController navigationController] pushViewController:renderViewController animated:YES];
}

// 轻量非模态提示（与 macOS 端行为对齐）：
// SFTP 页面保存成功后会调用 toast，iOS 侧此前没有该实现，
// 会命中 KRBaseModule 里「module方法不存在」的 NSAssert → DEBUG 直接崩溃。
- (void)toast:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] rij_stringToDictionary];
    NSString *content = params[@"content"];
    if (content.length == 0) {
        return;
    }
    dispatch_async(dispatch_get_main_queue(), ^{
        UIWindow *window = [self p_keyWindow];
        if (!window) {
            return;
        }
        UILabel *label = [[UILabel alloc] init];
        label.text = content;
        label.font = [UIFont systemFontOfSize:14];
        label.textColor = UIColor.whiteColor;
        label.textAlignment = NSTextAlignmentCenter;
        label.numberOfLines = 0;
        [label sizeToFit];

        CGFloat padX = 20, padY = 12;
        label.frame = CGRectMake(padX, padY, label.frame.size.width, label.frame.size.height);
        UIView *hud = [[UIView alloc] initWithFrame:CGRectMake(0, 0,
                                                              label.frame.size.width + padX * 2,
                                                              label.frame.size.height + padY * 2)];
        hud.backgroundColor = [UIColor colorWithWhite:0.0 alpha:0.82];
        hud.layer.cornerRadius = 8.0;
        [hud addSubview:label];
        hud.center = CGPointMake(window.bounds.size.width / 2.0, window.bounds.size.height / 2.0);
        hud.autoresizingMask = UIViewAutoresizingFlexibleLeftMargin | UIViewAutoresizingFlexibleRightMargin |
                               UIViewAutoresizingFlexibleTopMargin | UIViewAutoresizingFlexibleBottomMargin;
        [window addSubview:hud];

        hud.alpha = 0.0;
        [UIView animateWithDuration:0.15 animations:^{ hud.alpha = 1.0; }];
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(1.5 * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
            [UIView animateWithDuration:0.25 animations:^{ hud.alpha = 0.0; }
                            completion:^(BOOL finished) { [hud removeFromSuperview]; }];
        });
    });
}

- (nullable UIWindow *)p_keyWindow {
    for (UIWindow *w in UIApplication.sharedApplication.windows) {
        if (w.isKeyWindow) return w;
    }
    return UIApplication.sharedApplication.windows.firstObject;
}

- (void)copyToPasteboard:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] rij_stringToDictionary];
    NSString *content = params[@"content"];
    UIPasteboard *pasteboard = [UIPasteboard generalPasteboard];
    pasteboard.string = content;
}


- (void)log:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] rij_stringToDictionary];
    NSString *content = params[@"content"];
    NSLog(@"KuiklyRender:%@", content);
}

- (id)testArray:(NSDictionary *)args {
    
    NSMutableArray *aaray =  args[KR_PARAM_KEY];
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    if ([aaray isKindOfClass:[NSArray class]]) {
        id dd = aaray[1];
        if ([dd isKindOfClass:[NSData class]]) {
            callback(@[@"343434", dd, @"33434"]);
            return [NSMutableArray arrayWithObjects:@"224343",dd, nil];
        }
    }
    
    return nil;
    
    
}

- (void)getLocalImagePath:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] rij_stringToDictionary];
    NSString *urlStr = params[@"imageUrl"];
    NSURL *url = [NSURL URLWithString:urlStr];

    [[SDWebImageDownloader sharedDownloader] downloadImageWithURL:url
                                                          options:0 progress:nil
                                                        completed:^(UIImage * _Nullable image, NSData * _Nullable data, NSError * _Nullable error, BOOL finished) {
        if (image) {
            NSString *key = [[SDWebImageManager sharedManager] cacheKeyForURL:url];
            [[SDImageCache sharedImageCache] storeImage:image imageData:data forKey:key toDisk:YES completion:^{
                NSString *path = [[SDImageCache sharedImageCache] cachePathForKey:key];
                KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
                callback(@{@"localPath": path ?: @""});

            }];
        }
    }];
}

- (void)readAssetFile:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] rij_stringToDictionary];
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    NSString *path = params[@"assetPath"];
    KuiklyContextParam *contextParam = ((KuiklyRenderView *)self.hr_rootView).contextParam;
    NSURL *pathUrl = nil;
    pathUrl = [contextParam urlForFileName:[path stringByDeletingPathExtension] extension:[path pathExtension]];
    dispatch_async(dispatch_get_global_queue(0, 0), ^{
        NSError *error;
        NSString *jsonStr = [NSString stringWithContentsOfURL:pathUrl encoding:NSUTF8StringEncoding error:&error];
        NSDictionary *result = @{
            @"result": jsonStr?:@"",
            @"error": error.description?:@""
        };
        callback(result);
    });
}


#pragma mark - 独立窗口播放（桌面壳能力）

/// 独立窗口播放仅桌面壳（Electron）支持；原生端返回 supported=false，
/// Kotlin 侧（SftpPlayerLauncher）据此回退为页内路由。
- (NSDictionary *)supportsPlayerWindow:(NSDictionary *)args {
    return @{@"supported": @NO};
}

- (void)openPlayerWindow:(NSDictionary *)args {
    // 原生端不提供独立窗口播放（业务侧不会走到这里）
}


#pragma mark - 终端能力（待接入 libssh2 pty）

/// 原生端终端能力：接入 libssh2 channel + pty 后改为返回 YES，页面即显示终端入口。
- (NSDictionary *)supportsTerminal:(NSDictionary *)args {
    return @{@"supported": @NO};
}

#pragma mark - 剪贴板 / 缓存 / xterm（跨端统一能力）

- (NSDictionary *)supportsXterm:(NSDictionary *)args {
    return @{@"supported": @NO};
}

- (NSDictionary *)clipboardSupported:(NSDictionary *)args {
    return @{@"supported": @YES};
}

- (void)copyToClipboard:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] rij_stringToDictionary];
    NSString *text = params[@"text"] ?: @"";
    [UIPasteboard generalPasteboard].string = text;
}

/// 缓存根目录：Caches/.kuikly_cache（缓存下载落盘根）
- (NSDictionary *)cacheRoot:(NSDictionary *)args {
    NSString *base = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, YES).firstObject ?: NSTemporaryDirectory();
    NSString *dir = [base stringByAppendingPathComponent:@".kuikly_cache"];
    [[NSFileManager defaultManager] createDirectoryAtPath:dir withIntermediateDirectories:YES attributes:nil error:nil];
    return @{@"path": dir ?: @""};
}

- (void)clearCache:(NSDictionary *)args {
    NSString *base = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, YES).firstObject ?: NSTemporaryDirectory();
    NSString *dir = [base stringByAppendingPathComponent:@".kuikly_cache"];
    [[NSFileManager defaultManager] removeItemAtPath:dir error:nil];
}

@end

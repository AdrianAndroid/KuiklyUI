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
#import "KuiklyRenderViewController.h"
#import "KuiklyContextParam.h"
#import "KuiklyRenderView.h"
#import "NSObject+KR.h"
#import "KRNavigationController.h"
#import "KRDiagnosticLog.h"
#import <SDWebImageManager.h>
#import <SDWebImageDownloader.h>
#import <SDImageCache.h>
#import <objc/runtime.h>

#define REQ_PARAM_KEY @"reqParam"
#define CMD_KEY @"cmd"

// Associated object key for window self-retention
static char kBridgeWindowSelfRetentionKey;

// macOS Helper: 解析URL参数
static NSDictionary *ParseURLParameters(NSString *urlString) {
    if (urlString.length == 0) {
        return nil;
    }
    
    NSURLComponents *components = [NSURLComponents componentsWithString:urlString];
    if (!components || !components.queryItems || components.queryItems.count == 0) {
        return nil;
    }
    
    NSMutableDictionary *params = [NSMutableDictionary dictionary];
    for (NSURLQueryItem *item in components.queryItems) {
        if (item.name && item.value) {
            params[item.name] = item.value;
        }
    }
    
    return params.count > 0 ? [params copy] : nil;
}

// macOS Helper: 从view获取window
static NSWindow *GetWindowFromView(NSView *view) {
    return view.window;
}

// macOS Helper: 从view获取viewController
static NSViewController *GetViewControllerFromView(NSView *view) {
    return [view kr_viewController];
}


/*
 * @brief 扩展桥接接口，Native暴露接口到kotlin侧，提供kotlin侧调用native能力
 */
@implementation KRBridgeModule

@synthesize hr_rootView;


// 页面退出
- (void)closePage:(NSDictionary *)args {
    NSViewController *viewController = GetViewControllerFromView((NSView *)self.hr_rootView);
    KR_DIAG_INFO(@"bridge", @"closePage fromVC=%@", viewController.title ?: @"<nil>");
    
    // 方案1：NavigationController 堆栈管理（自定义，与iOS一致）
    KRNavigationController *navController = viewController.kr_navigationController;
    if (navController) {
        [navController popViewControllerAnimated:YES];
        return;
    }
    
    // 方案2：关闭 Sheet（macOS 原生方式）
    if (viewController.presentingViewController) {
        [viewController.presentingViewController dismissViewController:viewController];
        return;
    }
    
    // 方案3：关闭窗口（后备方案）
    NSWindow *window = GetWindowFromView((NSView *)self.hr_rootView);
    if (window && window.sheetParent) {
        [window.sheetParent endSheet:window];
    } else if (window) {
        [window close];
    }
}

// 打开页面
- (void)openPage:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] hr_stringToDictionary];
    NSString *pageName = params[@"pageName"] ?: params[@"url"];
    NSMutableDictionary *pageData = [params[@"pageData"] mutableCopy] ?: [NSMutableDictionary new];
    
    // 解析URL参数
    NSDictionary *urlParams = ParseURLParameters(pageName);
    if (urlParams.count) {
        [pageData addEntriesFromDictionary:urlParams];
    }
    
    [KRDiagnosticLog log:KRLogLevelInfo tag:@"bridge" message:@"openPage" fields:(@{
        @"page": pageName ?: @"<nil>",
        @"pageData_keys": pageData.allKeys ?: @[],
        @"useSheet": @([params[@"presentAsSheet"] boolValue]),
    })];;
    
    KuiklyRenderViewController *renderViewController = [[KuiklyRenderViewController alloc] 
        initWithPageName:pageName pageData:pageData];
    renderViewController.title = pageName;
    
    NSViewController *currentViewController = GetViewControllerFromView((NSView *)self.hr_rootView);
    
    // 方案1：NavigationController 堆栈管理（自定义，与iOS一致）
    KRNavigationController *navController = currentViewController.kr_navigationController;
    if (navController) {
        [navController pushViewController:renderViewController animated:YES];
        return;
    }
    
    // 方案2：Sheet 方式（macOS 原生推荐）
    // 适用于设置、详情等临时页面
    if (currentViewController && currentViewController.presentedViewControllers.count == 0) {
        // 检查是否应该使用 Sheet（可以根据 params 中的标记判断）
        BOOL useSheet = [params[@"presentAsSheet"] boolValue];
        if (useSheet) {
            [currentViewController presentViewControllerAsSheet:renderViewController];
            return;
        }
    }
    
    // 方案3：新窗口（适用于独立功能模块）
    NSWindow *window = GetWindowFromView((NSView *)self.hr_rootView);
    if (window) {
        
        // 获取视图尺寸，使用较大值确保窗口不会太小
        CGSize viewSize = renderViewController.view.frame.size;
        CGFloat windowWidth = MAX(viewSize.width, 900.0);
        CGFloat windowHeight = MAX(viewSize.height, 650.0);
        
        NSWindow *newWindow = [[NSWindow alloc] initWithContentRect:NSMakeRect(0, 0, windowWidth, windowHeight)
                                                          styleMask:NSWindowStyleMaskTitled | 
                                                                   NSWindowStyleMaskClosable | 
                                                                   NSWindowStyleMaskResizable | 
                                                                   NSWindowStyleMaskMiniaturizable
                                                            backing:NSBackingStoreBuffered
                                                              defer:NO];
        
        // 防止窗口在关闭动画期间被释放（会导致crash）
        newWindow.releasedWhenClosed = NO;
        
        newWindow.contentViewController = renderViewController;
        newWindow.title = pageName ?: @"Kuikly Page";
        newWindow.minSize = NSMakeSize(400, 300);
        
        // 设置自动调整大小
        renderViewController.view.autoresizingMask = NSViewWidthSizable | NSViewHeightSizable;
        
        // 让窗口持有自己的强引用，防止被提前释放
        objc_setAssociatedObject(newWindow, &kBridgeWindowSelfRetentionKey, newWindow, OBJC_ASSOCIATION_RETAIN);
        
        // 监听窗口关闭通知，关闭后移除强引用让窗口可以被释放
        // 重要：不要在block中捕获newWindow，而是从notification.object获取，避免循环引用
        [[NSNotificationCenter defaultCenter] addObserverForName:NSWindowWillCloseNotification
                                                           object:newWindow
                                                            queue:[NSOperationQueue mainQueue]
                                                       usingBlock:^(NSNotification *note) {
            NSWindow *closingWindow = note.object; // 从通知获取窗口，不捕获外部引用
            NSLog(@"[KRBridgeModule] Window closing: %@", closingWindow.title);
            // 立即移除强引用，让窗口在关闭动画完成后可以被释放
            objc_setAssociatedObject(closingWindow, &kBridgeWindowSelfRetentionKey, nil, OBJC_ASSOCIATION_RETAIN);
        }];
        
        [newWindow center];
        [newWindow makeKeyAndOrderFront:nil];
        
        NSLog(@"[KRBridgeModule] Created window with size: %.0fx%.0f", windowWidth, windowHeight);
    }
}

- (void)copyToPasteboard:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] hr_stringToDictionary];
    NSString *content = params[@"content"];
    // macOS: 使用NSPasteboard替代UIPasteboard
    NSPasteboard *pasteboard = [NSPasteboard generalPasteboard];
    [pasteboard clearContents];
    [pasteboard setString:content ?: @"" forType:NSPasteboardTypeString];
}


- (void)log:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] hr_stringToDictionary];
    NSString *content = params[@"content"];
    KR_DIAG_INFO(@"kuikly.bridge", @"%@", content ?: @"<empty>");
}

- (void)toast:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] hr_stringToDictionary];
    NSString *content = params[@"content"];
    KR_DIAG_INFO(@"bridge.toast", @"content=%@", content ?: @"<empty>");
    if (content.length == 0) return;
    dispatch_async(dispatch_get_main_queue(), ^{
        NSWindow *window = [NSApplication sharedApplication].keyWindow ?: NSApp.windows.firstObject;
        NSView *host = window.contentView;
        if (!host) return;

        // 轻量非模态 HUD：不抢焦点、无按钮、无遮挡弹窗，1.5s 后淡出。
        // 之前的 NSAlert sheet 是模态的，只为提示「已保存」会盖住整窗并打断操作。
        NSTextField *label = [NSTextField labelWithString:content ?: @""];
        label.font = [NSFont systemFontOfSize:14];
        label.textColor = NSColor.whiteColor;
        label.alignment = NSTextAlignmentCenter;
        label.maximumNumberOfLines = 0;
        label.lineBreakMode = NSLineBreakByWordWrapping;
        [label sizeToFit];

        const CGFloat padX = 20, padY = 12;
        NSRect labelFrame = NSMakeRect(padX, padY, label.frame.size.width, label.frame.size.height);
        NSRect hudFrame = NSMakeRect(0, 0, labelFrame.size.width + padX * 2, labelFrame.size.height + padY * 2);

        NSView *hud = [[NSView alloc] initWithFrame:hudFrame];
        hud.wantsLayer = YES;
        hud.layer.backgroundColor = [NSColor colorWithWhite:0.0 alpha:0.82].CGColor;
        hud.layer.cornerRadius = 8.0;
        label.frame = labelFrame;
        [hud addSubview:label];

        // 居中显示，并随窗口尺寸变化保持居中
        hudFrame.origin.x = NSMidX(host.bounds) - hudFrame.size.width / 2.0;
        hudFrame.origin.y = NSMidY(host.bounds) - hudFrame.size.height / 2.0;
        hud.frame = hudFrame;
        hud.autoresizingMask = NSViewMinXMargin | NSViewMaxXMargin | NSViewMinYMargin | NSViewMaxYMargin;
        hud.alphaValue = 0.0;
        [host addSubview:hud];

        [NSAnimationContext runAnimationGroup:^(NSAnimationContext *ctx) {
            ctx.duration = 0.15;
            hud.animator.alphaValue = 1.0;
        } completionHandler:nil];

        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(1.5 * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
            [NSAnimationContext runAnimationGroup:^(NSAnimationContext *ctx) {
                ctx.duration = 0.25;
                hud.animator.alphaValue = 0.0;
            } completionHandler:^{
                [hud removeFromSuperview];
            }];
        });
    });
}

- (id)testArray:(NSDictionary *)args {
    
    NSMutableArray *array =  args[KR_PARAM_KEY];
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    if ([array isKindOfClass:[NSArray class]]) {
        id dd = array[1];
        if ([dd isKindOfClass:[NSData class]]) {
            callback(@[@"343434", dd, @"33434"]);
            return [NSMutableArray arrayWithObjects:@"224343",dd, nil];
        }
    }
    
    return nil;
}

- (void)getLocalImagePath:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] hr_stringToDictionary];
    NSString *urlStr = params[@"imageUrl"];
    NSURL *url = [NSURL URLWithString:urlStr];
    KR_DIAG_DEBUG(@"bridge.image", @"download: %@", urlStr ?: @"<nil>");

    [[SDWebImageDownloader sharedDownloader] downloadImageWithURL:url
                                                          options:0 
                                                         progress:nil
                                                        completed:^(UIImage * _Nullable image, NSData * _Nullable data, NSError * _Nullable error, BOOL finished) {
        if (image) {
            NSString *key = [[SDWebImageManager sharedManager] cacheKeyForURL:url];
            [[SDImageCache sharedImageCache] storeImage:image 
                                              imageData:data 
                                                 forKey:key 
                                                 toDisk:YES 
                                             completion:^{
                NSString *path = [[SDImageCache sharedImageCache] cachePathForKey:key];
                KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
                callback(@{@"localPath": path ?: @""});
            }];
        } else if (error) {
            KR_DIAG_ERROR(@"bridge.image", @"download failed: %@ code=%ld domain=%@",
                          urlStr ?: @"<nil>", (long)error.code, error.domain ?: @"");
        }
    }];
}

- (void)readAssetFile:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] hr_stringToDictionary];
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    NSString *path = params[@"assetPath"];
    KuiklyContextParam *contextParam = ((KuiklyRenderView *)self.hr_rootView).contextParam;
    NSURL *pathUrl = nil;
    pathUrl = [contextParam urlForFileName:[path stringByDeletingPathExtension] extension:[path pathExtension]];
    dispatch_async(dispatch_get_global_queue(0, 0), ^{
        NSError *error;
        NSString *jsonStr = [NSString stringWithContentsOfURL:pathUrl encoding:NSUTF8StringEncoding error:&error];
        NSDictionary *result = @{
            @"result": jsonStr ?: @"",
            @"error": error.description ?: @""
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

/// 远程终端已接入（KRTerminalModule，复用 NMSSH 会话 shell）；本地终端不支持
- (NSDictionary *)supportsTerminal:(NSDictionary *)args {
    return @{@"supported": @YES};
}

#pragma mark - 剪贴板 / 缓存 / xterm（跨端统一能力）

- (NSDictionary *)supportsXterm:(NSDictionary *)args {
    return @{@"supported": @NO};
}

- (NSDictionary *)clipboardSupported:(NSDictionary *)args {
    return @{@"supported": @YES};
}

- (void)copyToClipboard:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] hr_stringToDictionary];
    NSString *text = params[@"text"] ?: @"";
    NSPasteboard *pb = [NSPasteboard generalPasteboard];
    [pb clearContents];
    [pb setString:text forType:NSPasteboardTypeString];
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

#pragma mark - 本地文件系统（双栏本地栏，沙盒 Documents/local）

static NSString *KRLocalRoot(void) {
    NSString *docs = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, YES).firstObject ?: NSTemporaryDirectory();
    NSString *dir = [docs stringByAppendingPathComponent:@"local"];
    [[NSFileManager defaultManager] createDirectoryAtPath:dir withIntermediateDirectories:YES attributes:nil error:nil];
    return dir;
}

static NSString *KRLocalResolve(NSString *path) {
    NSString *root = KRLocalRoot();
    NSString *target = (path.length == 0) ? root : path;
    NSString *std = [target stringByStandardizingPath];
    if ([std isEqualToString:root] || [std hasPrefix:[root stringByAppendingString:@"/"]]) {
        return std;
    }
    return nil;
}

- (NSDictionary *)supportsLocalFs:(NSDictionary *)args {
    return @{@"supported": @YES};
}

- (void)lfHome:(NSDictionary *)args {
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    if (callback) callback(@{@"path": KRLocalRoot()});
}

- (void)lfList:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] hr_stringToDictionary];
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    NSString *dir = KRLocalResolve(params[@"path"]);
    if (!dir) { if (callback) callback(@{@"error": @"路径越界"}); return; }
    NSFileManager *fm = [NSFileManager defaultManager];
    BOOL isDir = NO;
    if (![fm fileExistsAtPath:dir isDirectory:&isDir] || !isDir) {
        if (callback) callback(@{@"error": @"不是目录"});
        return;
    }
    NSMutableArray *arr = [NSMutableArray array];
    for (NSString *name in [fm contentsOfDirectoryAtPath:dir error:nil]) {
        NSString *full = [dir stringByAppendingPathComponent:name];
        NSDictionary *st = [fm attributesOfItemAtPath:full error:nil];
        BOOL childDir = [st[NSFileType] isEqual:NSFileTypeDirectory];
        [arr addObject:@{
            @"name": name,
            @"type": childDir ? @"dir" : @"file",
            @"size": @([st[NSFileSize] longLongValue]),
            @"mtime": @((long long)([st[NSFileModificationDate] timeIntervalSince1970] * 1000.0)),
        }];
    }
    NSData *data = [NSJSONSerialization dataWithJSONObject:arr options:0 error:nil];
    NSString *json = data ? [[NSString alloc] initWithData:data encoding:NSUTF8StringEncoding] : @"[]";
    if (callback) callback(@{@"entries": json ?: @"[]"});
}

- (void)lfMkdir:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] hr_stringToDictionary];
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    NSString *path = KRLocalResolve(params[@"path"]);
    if (!path) { if (callback) callback(@{@"error": @"路径越界"}); return; }
    NSError *e = nil;
    if (![[NSFileManager defaultManager] createDirectoryAtPath:path withIntermediateDirectories:YES attributes:nil error:&e]) {
        if (callback) callback(@{@"error": e.localizedDescription ?: @"创建失败"});
        return;
    }
    if (callback) callback(@{});
}

- (void)lfRename:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] hr_stringToDictionary];
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    NSString *from = KRLocalResolve(params[@"from"]);
    NSString *to = KRLocalResolve(params[@"to"]);
    if (!from || !to) { if (callback) callback(@{@"error": @"路径越界"}); return; }
    NSError *e = nil;
    if (![[NSFileManager defaultManager] moveItemAtPath:from toPath:to error:&e]) {
        if (callback) callback(@{@"error": e.localizedDescription ?: @"重命名失败"});
        return;
    }
    if (callback) callback(@{});
}

- (void)lfRemove:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] hr_stringToDictionary];
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    NSString *path = KRLocalResolve(params[@"path"]);
    if (!path) { if (callback) callback(@{@"error": @"路径越界"}); return; }
    [[NSFileManager defaultManager] removeItemAtPath:path error:nil];
    if (callback) callback(@{});
}

@end

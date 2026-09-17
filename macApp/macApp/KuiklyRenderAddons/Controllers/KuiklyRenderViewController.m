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

#import "KuiklyRenderViewController.h"
#import "KuiklyRenderViewControllerBaseDelegator.h"
#import "KuiklyRenderContextProtocol.h"
#import "KuiklyRenderCore.h"
#import "KuiklyRenderView.h"
#import "KRPerformanceDataProtocol.h"
#import "KRPerformanceManager.h"
#import "KRConvertUtil.h"
#import "KRDiagnosticLog.h"


#pragma mark - Constants

/// 默认的framework名称
static NSString * const kDefaultFrameworkName = @"shared";
/// 默认应用ID
static NSString * const kDefaultAppId = @"1";
/// 平台标识
static NSString * const kPlatformIdentifier = @"macOS";
/// 异常信息键
static NSString * const kExceptionUserInfoKey = @"exception";
/// UI元素尺寸
static const CGFloat kLoadingIndicatorSize = 32.0;
static const CGFloat kErrorLabelFontSize = 16.0;
/// 单位换算
static const NSInteger kBytesToMegabytes = 1024 * 1024;


#pragma mark - KuiklyPageLifeCycleObserver

/**
 * @brief Kuikly页面生命周期观察者
 *
 * 负责监听和响应KuiklyRenderViewControllerBaseDelegator的生命周期事件
 */
@interface KuiklyPageLifeCycleObserver : NSObject <KRControllerDelegatorLifeCycleProtocol>
- (instancetype)initWithPageName:(NSString *)pageName;
@property (nonatomic, copy, readonly) NSString *pageName;
@property (nonatomic, assign) CFTimeInterval t0;
@end

@implementation KuiklyPageLifeCycleObserver

@synthesize delegator = _delegator;

- (instancetype)initWithPageName:(NSString *)pageName {
    self = [super init];
    if (self) {
        _pageName = [pageName copy];
        _t0 = CFAbsoluteTimeGetCurrent();
    }
    return self;
}

#pragma mark - KRControllerDelegatorLifeCycleProtocol

- (void)viewDidLoad {
    KR_DIAG_INFO(@"page.life", @"%@ viewDidLoad", self.pageName);
}

- (void)willInitRenderView {
    KR_DIAG_INFO(@"page.life", @"%@ willInitRenderView", self.pageName);
}

- (void)didInitRenderView {
    KR_DIAG_INFO(@"page.life", @"%@ didInitRenderView (%.1fms)", self.pageName,
                 (CFAbsoluteTimeGetCurrent() - self.t0) * 1000.0);
}

- (void)didSendEvent:(NSString *)event {
    KR_DIAG_INFO(@"page.event", @"%@ didSendEvent=%@", self.pageName, event ?: @"<nil>");
}

- (void)viewWillAppear {
    KR_DIAG_INFO(@"page.life", @"%@ viewWillAppear", self.pageName);
}

- (void)viewDidAppear {
    KR_DIAG_INFO(@"page.life", @"%@ viewDidAppear (%.1fms since init)", self.pageName,
                 (CFAbsoluteTimeGetCurrent() - self.t0) * 1000.0);
}

- (void)viewWillDisappear {
    KR_DIAG_INFO(@"page.life", @"%@ viewWillDisappear", self.pageName);
}

- (void)viewDidDisappear {
    KR_DIAG_INFO(@"page.life", @"%@ viewDidDisappear", self.pageName);
}

- (void)willFetchContextCode {
    KR_DIAG_INFO(@"page.life", @"%@ willFetchContextCode", self.pageName);
}

- (void)didFetchContextCode {
    KR_DIAG_INFO(@"page.life", @"%@ didFetchContextCode", self.pageName);
}

- (void)contentViewDidLoad {
    KR_DIAG_INFO(@"page.life", @"%@ contentViewDidLoad (%.1fms since init)", self.pageName,
                 (CFAbsoluteTimeGetCurrent() - self.t0) * 1000.0);
}

- (void)delegatorDealloc {
    KR_DIAG_INFO(@"page.life", @"%@ delegatorDealloc", self.pageName);
}

@end


#pragma mark - KuiklyPageViewController

@interface KuiklyRenderViewController () <KuiklyRenderViewControllerBaseDelegatorDelegate>

/// Kuikly渲染代理器
@property (nonatomic, strong, readonly) KuiklyRenderViewControllerBaseDelegator *delegator;

/// 生命周期观察者
@property (nonatomic, strong, readonly) KuiklyPageLifeCycleObserver *lifeCycleObserver;

/// 当前页面名称
@property (nonatomic, copy, readonly) NSString *pageName;

/// Creation time — used to compute lifecycle latencies.
@property (nonatomic, assign) CFTimeInterval creationTime;

/// Current page data deep-hash (for change detection).
@property (nonatomic, copy, readonly) NSString *pageDataFingerprint;

/// 当前页面数据
@property (nonatomic, copy, readonly) NSDictionary<NSString *, id> *pageData;

/// 页面开始加载时间
@property (nonatomic, assign) CFTimeInterval beginTime;

/// 视图是否可见
@property (nonatomic, assign, getter=isViewVisible) BOOL viewVisible;

@end

@implementation KuiklyRenderViewController

#pragma mark - Lifecycle

- (instancetype)initWithPageName:(NSString *)pageName 
                        pageData:(nullable NSDictionary<NSString *, id> *)data {
    NSParameterAssert(pageName.length > 0);
    
    self = [super initWithNibName:nil bundle:nil];
    if (self) {
        _pageName = [pageName copy];
        _pageData = [self mergeExtendedParametersWithOriginalParameters:data];
        _pageDataFingerprint = [KuiklyRenderViewController fingerprintForDict:_pageData];
        _lifeCycleObserver = [[KuiklyPageLifeCycleObserver alloc] initWithPageName:pageName];
        _viewVisible = NO;
        _creationTime = CFAbsoluteTimeGetCurrent();
        
        [self setupDelegatorWithPageName:pageName data:_pageData];
        [self registerNotifications];
        [KRDiagnosticLog log:KRLogLevelInfo tag:@"page.life" message:@"vc.init" fields:(@{
            @"page": pageName ?: @"<nil>",
            @"data_fp": _pageDataFingerprint ?: @"<nil>",
            @"data_keys": [_pageData allKeys] ?: @[],
        })];;
    }
    return self;
}

- (void)dealloc {
    [self unregisterNotifications];
    KR_DIAG_INFO(@"page.life", @"dealloc page=%@ vc=%p", self.pageName, (void *)self);
}

+ (NSString *)fingerprintForDict:(NSDictionary *)d {
    if (!d) return @"nil";
    NSError *err = nil;
    NSData *data = [NSJSONSerialization dataWithJSONObject:d
                                                   options:NSJSONWritingSortedKeys
                                                     error:&err];
    if (!data) return @"<unserializable>";
    return [[NSString alloc] initWithData:data encoding:NSUTF8StringEncoding] ?: @"<unserializable>";
}

#pragma mark - View Lifecycle

- (void)loadView {
    // 设置合理的初始尺寸，避免窗口过小
    // 注意：实际窗口尺寸由包含它的窗口或导航控制器决定
    self.view = [[NSView alloc] initWithFrame:NSMakeRect(0, 0, 800, 600)];
}

- (void)viewDidLoad {
    [super viewDidLoad];
    [self setupView];
    KR_DIAG_INFO(@"page.life", @"vc.viewDidLoad page=%@ vc=%p since_init=%.1fms",
                 self.pageName, (void *)self,
                 (CFAbsoluteTimeGetCurrent() - self.creationTime) * 1000.0);
    [self.delegator viewDidLoadWithView:(id)self.view];
}

- (void)viewDidLayout {
    [super viewDidLayout];
    [self.delegator viewDidLayoutSubviews];
}

- (void)viewWillAppear {
    [super viewWillAppear];
    self.viewVisible = YES;
    KR_DIAG_INFO(@"page.life", @"vc.viewWillAppear page=%@", self.pageName);
    [self.delegator viewWillAppear];
}

- (void)viewDidAppear {
    [super viewDidAppear];
    KR_DIAG_INFO(@"page.life", @"vc.viewDidAppear page=%@ since_init=%.1fms",
                 self.pageName, (CFAbsoluteTimeGetCurrent() - self.creationTime) * 1000.0);
    [self.delegator viewDidAppear];
    [self scheduleViewTreeDump];
}

#pragma mark - View tree diagnostics

/// Dumps the native Kuikly view tree (class / frame / visibility / text-field state) shortly
/// after first paint. This is the fastest way for an automated agent to tell "the view was
/// never created" apart from "the view exists but is 0-sized / hidden / unfocusable".
- (void)scheduleViewTreeDump {
    __weak typeof(self) weakSelf = self;
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(1.5 * NSEC_PER_SEC)),
                   dispatch_get_main_queue(), ^{
        [weakSelf dumpViewTree];
    });
}

- (void)dumpViewTree {
    NSView *root = self.delegator.renderView;
    if (!root) {
        KR_DIAG_WARN(@"uilayout", @"view tree dump skipped: renderView is nil (page=%@)", self.pageName);
        return;
    }
    NSMutableArray<NSString *> *rows = [NSMutableArray array];
    [KuiklyRenderViewController p_collectView:root depth:0 into:rows];
    [KRDiagnosticLog log:KRLogLevelInfo tag:@"uilayout" message:@"viewTree" fields:(@{
        @"page": self.pageName ?: @"<nil>",
        @"render_view_frame": NSStringFromRect(root.frame),
        @"view_frame": NSStringFromRect(self.view.frame),
        @"rows": rows,
    })];
    // The render view is often *larger* than the window content area; log the mismatch loudly
    // because that is what makes a page look "shifted" / clipped on macOS.
    NSRect contentRect = self.view.bounds;
    if (!NSEqualRects(root.frame, contentRect)) {
        KR_DIAG_WARN(@"uilayout", @"renderView frame %@ != host bounds %@ (page=%@)",
                     NSStringFromRect(root.frame), NSStringFromRect(contentRect), self.pageName);
    }
}

+ (void)p_collectView:(NSView *)view depth:(NSInteger)depth into:(NSMutableArray<NSString *> *)rows {
    if (!view || depth > 8 || rows.count > 200) return;
    NSMutableString *line = [NSMutableString stringWithFormat:
        @"%@%@ %@ hidden=%d alpha=%.2f",
        [@"" stringByPaddingToLength:depth * 2 withString:@" " startingAtIndex:0],
        NSStringFromClass([view class]),
        NSStringFromRect(view.frame),
        view.isHidden ? 1 : 0,
        view.alphaValue];
    if ([view isKindOfClass:[NSTextField class]]) {
        NSTextField *tf = (NSTextField *)view;
        [line appendFormat:@" | editable=%d selectable=%d enabled=%d focusable=%d",
             tf.isEditable ? 1 : 0, tf.isSelectable ? 1 : 0, tf.isEnabled ? 1 : 0,
             tf.acceptsFirstResponder ? 1 : 0];
        [line appendFormat:@" value=\"%@\" placeholder=\"%@\" attrPlaceholder=\"%@\"",
             tf.stringValue ?: @"", tf.placeholderString ?: @"<nil>",
             tf.placeholderAttributedString.string ?: @"<nil>"];
        [line appendFormat:@" textColor=%@ drawsBg=%d bordered=%d",
             tf.textColor ?: @"<nil>", tf.drawsBackground ? 1 : 0, tf.isBordered ? 1 : 0];
    }
    [rows addObject:line];
    for (NSView *sub in view.subviews) {
        [KuiklyRenderViewController p_collectView:sub depth:depth + 1 into:rows];
    }
}

- (void)viewWillDisappear {
    [super viewWillDisappear];
    self.viewVisible = NO;
    KR_DIAG_INFO(@"page.life", @"vc.viewWillDisappear page=%@", self.pageName);
    [self.delegator viewWillDisappear];
}

- (void)viewDidDisappear {
    [super viewDidDisappear];
    KR_DIAG_INFO(@"page.life", @"vc.viewDidDisappear page=%@", self.pageName);
    [self.delegator viewDidDisappear];
    [self logPerformanceMetrics];
}

#pragma mark - Public Methods

- (void)updateWithPageName:(NSString *)pageName 
                  pageData:(nullable NSDictionary<NSString *, id> *)data {
    NSParameterAssert(pageName.length > 0);
    
    if ([self shouldUpdateWithPageName:pageName data:data]) {
        NSString *oldPage = [self.pageName copy];
        NSString *oldFp = [self.pageDataFingerprint copy];
        _pageName = [pageName copy];
        _pageData = [self mergeExtendedParametersWithOriginalParameters:data];
        _pageDataFingerprint = [KuiklyRenderViewController fingerprintForDict:_pageData];
        
        [KRDiagnosticLog log:KRLogLevelInfo tag:@"page.update" message:@"vc.updateWithPageName" fields:(@{
            @"from": oldPage ?: @"<nil>",
            @"to": pageName ?: @"<nil>",
            @"from_data_fp": oldFp ?: @"<nil>",
            @"to_data_fp": _pageDataFingerprint ?: @"<nil>",
            @"to_data_keys": [_pageData allKeys] ?: @[],
        })];;
        // TODO: 实现页面动态更新逻辑
    } else {
        KR_DIAG_DEBUG(@"page.update", @"vc.updateWithPageName no-op page=%@", pageName);
    }
}

#pragma mark - Private Setup Methods

- (void)setupDelegatorWithPageName:(NSString *)pageName 
                              data:(NSDictionary<NSString *, id> *)data {
    _delegator = [[KuiklyRenderViewControllerBaseDelegator alloc] 
                  initWithPageName:pageName pageData:data];
    
    [self.delegator.performanceManager setMonitorType:KRMonitorType_ALL];
    self.delegator.delegate = self;
    [self.delegator addDelegatorLifeCycleListener:self.lifeCycleObserver];
}

- (void)setupView {
    self.view.wantsLayer = YES;
    self.view.layer.backgroundColor = NSColor.whiteColor.CGColor;
}

- (void)registerNotifications {
    NSNotificationCenter *center = NSNotificationCenter.defaultCenter;
    [center addObserver:self
               selector:@selector(handleKuiklyException:)
                   name:kKuiklyFatalExceptionNotification
                 object:nil];
}

- (void)unregisterNotifications {
    [NSNotificationCenter.defaultCenter removeObserver:self];
}

#pragma mark - Private Helper Methods

- (BOOL)shouldUpdateWithPageName:(NSString *)pageName 
                            data:(nullable NSDictionary<NSString *, id> *)data {
    BOOL pageNameChanged = ![self.pageName isEqualToString:pageName];
    BOOL dataChanged = ![self.pageData isEqualToDictionary:data ?: @{}];
    return pageNameChanged || dataChanged;
}

- (NSDictionary<NSString *, id> *)mergeExtendedParametersWithOriginalParameters:(nullable NSDictionary<NSString *, id> *)parameters {
    NSMutableDictionary<NSString *, id> *mergedParameters = [parameters ?: @{} mutableCopy];
    // 可在此添加扩展参数
    return [mergedParameters copy];
}

#pragma mark - Performance Logging

- (void)logPerformanceMetrics {
    KRPerformanceManager *manager = self.delegator.performanceManager;
    
    NSDictionary *startTimes = manager.stageStartTimes;
    NSDictionary *durations = manager.stageDurations;
    
    KRMemoryMonitor *memoryMonitor = manager.memoryMonitor;
    NSInteger memoryUsageMB = memoryMonitor.avgIncrementMemory / kBytesToMegabytes;
    
    KRFPSMonitor *mainFPSMonitor = manager.mainFPS;
    KRFPSMonitor *kotlinFPSMonitor = manager.kotlinFPS;
    
    [KRDiagnosticLog log:KRLogLevelInfo tag:@"perf" message:@"page.metrics" fields:(@{
        @"page": self.pageName ?: @"<nil>",
        @"memory_mb": @(memoryUsageMB),
        @"main_fps": @(mainFPSMonitor.avgFPS),
        @"kotlin_fps": @(kotlinFPSMonitor.avgFPS),
        @"stage_start_times": startTimes ?: @{},
        @"stage_durations": durations ?: @{},
    })];;
}

#pragma mark - Exception Handling

- (void)handleKuiklyException:(NSNotification *)notification {
    NSDictionary *userInfo = notification.userInfo;
    NSString *exceptionString = userInfo[kExceptionUserInfoKey];
    
    if (exceptionString.length == 0) {
        return;
    }
    
    NSArray<NSString *> *components = [exceptionString componentsSeparatedByString:@"\n"];
    if (components.count == 0) {
        return;
    }
    
    NSString *exceptionName = components.firstObject;
    NSArray<NSString *> *callStackArray = components.count > 1 
        ? [components subarrayWithRange:NSMakeRange(1, components.count - 1)]
        : @[];
    
    NSString *callStack = [callStackArray componentsJoinedByString:@"\n"];
    [KRDiagnosticLog log:KRLogLevelFatal tag:@"kuikly.exception" message:exceptionName ?: @"exception" fields:(@{
        @"page": self.pageName ?: @"<nil>",
        @"raw": exceptionString ?: @"",
        @"stack": callStack ?: @"",
    })];;
    
    // TODO: 集成崩溃上报系统
}

#pragma mark - KuiklyRenderViewControllerBaseDelegatorDelegate

- (NSView *)createLoadingView {
    NSView *loadingView = [[NSView alloc] initWithFrame:NSZeroRect];
    loadingView.wantsLayer = YES;
    loadingView.layer.backgroundColor = NSColor.whiteColor.CGColor;
    
    NSProgressIndicator *indicator = [self createLoadingIndicator];
    [loadingView addSubview:indicator];
    
    [NSLayoutConstraint activateConstraints:@[
        [indicator.centerXAnchor constraintEqualToAnchor:loadingView.centerXAnchor],
        [indicator.centerYAnchor constraintEqualToAnchor:loadingView.centerYAnchor]
    ]];
    
    return loadingView;
}

- (NSView *)createErrorView {
    NSView *errorView = [[NSView alloc] initWithFrame:NSZeroRect];
    errorView.wantsLayer = YES;
    errorView.layer.backgroundColor = NSColor.whiteColor.CGColor;
    
    NSTextField *errorLabel = [self createErrorLabel];
    [errorView addSubview:errorLabel];
    
    [NSLayoutConstraint activateConstraints:@[
        [errorLabel.centerXAnchor constraintEqualToAnchor:errorView.centerXAnchor],
        [errorLabel.centerYAnchor constraintEqualToAnchor:errorView.centerYAnchor]
    ]];
    
    return errorView;
}

- (void)fetchContextCodeWithPageName:(NSString *)pageName 
                      resultCallback:(KuiklyContextCodeCallback)callback {
    if (callback != nil) {
        // TODO: 根据实际项目配置动态获取framework名称
        callback(kDefaultFrameworkName, nil);
    }
}

- (void)contentViewDidLoad {
    CFTimeInterval loadDuration = (CFAbsoluteTimeGetCurrent() - self.creationTime) * 1000.0;
    [KRDiagnosticLog log:KRLogLevelInfo tag:@"page.life" message:@"vc.contentViewDidLoad" fields:(@{
        @"page": self.pageName ?: @"<nil>",
        @"load_ms": @(loadDuration),
    })];;
}

- (void)renderViewDidCreated {
    self.beginTime = CFAbsoluteTimeGetCurrent();
    KR_DIAG_INFO(@"page.life", @"renderViewDidCreated page=%@", self.pageName);
}

- (void)onUnhandledException:(NSString *)exReason 
                       stack:(NSString *)callstackStr 
                        mode:(KuiklyContextMode)mode {
    [KRDiagnosticLog log:KRLogLevelFatal tag:@"kuikly.unhandled" message:exReason ?: @"unhandled-exception" fields:(@{
        @"page": self.pageName ?: @"<nil>",
        @"stack": callstackStr ?: @"",
        @"mode": @(mode),
    })];;
    // TODO: 上报到监控系统
}

- (void)onPageLoadComplete:(BOOL)isSucceed 
                     error:(nullable NSError *)error 
                      mode:(KuiklyContextMode)mode {
    if (error != nil) {
        [KRDiagnosticLog log:KRLogLevelError tag:@"page.load" message:@"page.load.fail" fields:(@{
            @"page": self.pageName ?: @"<nil>",
            @"desc": error.localizedDescription ?: @"",
            @"code": @(error.code),
            @"domain": error.domain ?: @"",
            @"mode": @(mode),
        })];;
    } else {
        [KRDiagnosticLog log:KRLogLevelInfo tag:@"page.load" message:@"page.load.success" fields:(@{
            @"page": self.pageName ?: @"<nil>",
            @"load_ms": @((CFAbsoluteTimeGetCurrent() - self.creationTime) * 1000.0),
            @"mode": @(mode),
        })];;
    }
    
    // 获取性能数据用于分析
    id<KRPerformanceDataProtocol> performance = self.delegator.performanceManager;
    (void)performance; // 防止unused warning
}

- (NSDictionary<NSString *, NSObject *> *)contextPageData {
    return @{
        @"appId": kDefaultAppId,
        @"sysLang": NSLocale.preferredLanguages.firstObject ?: @"en",
        @"platform": kPlatformIdentifier
    };
}

- (NSString *)turboDisplayKey {
    return self.pageName;
}

#pragma mark - UI Factory Methods

- (NSProgressIndicator *)createLoadingIndicator {
    CGRect frame = CGRectMake(0, 0, kLoadingIndicatorSize, kLoadingIndicatorSize);
    NSProgressIndicator *indicator = [[NSProgressIndicator alloc] initWithFrame:frame];
    indicator.style = NSProgressIndicatorStyleSpinning;
    indicator.translatesAutoresizingMaskIntoConstraints = NO;
    [indicator startAnimation:nil];
    return indicator;
}

- (NSTextField *)createErrorLabel {
    NSTextField *label = [NSTextField labelWithString:@"加载失败"];
    label.translatesAutoresizingMaskIntoConstraints = NO;
    label.textColor = NSColor.redColor;
    label.font = [NSFont systemFontOfSize:kErrorLabelFontSize];
    label.alignment = NSTextAlignmentCenter;
    return label;
}

@end

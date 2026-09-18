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

import SwiftUI

/// 仅供第三方库使用的 AppDelegate。
///
/// WMPlayer 5.0 的 `+[WMPlayer IsiPhoneX]` 里访问 `UIApplication.sharedApplication.delegate.window`
/// （`UIApplicationDelegate.window` 是 optional 属性）。SwiftUI 生命周期下系统 delegate 不实现
/// `window`，会触发 `doesNotRecognizeSelector:` → 崩溃（打开视频即崩）。
/// 这里提供一个带 `window` 属性的 delegate，让该库能正常取到（nil 也安全，只是判定非刘海屏）。
class KuiklyAppDelegate: NSObject, UIApplicationDelegate {
    var window: UIWindow?
}

@main
struct iOSApp: App {
    @UIApplicationDelegateAdaptor(KuiklyAppDelegate.self) private var appDelegate

	var body: some Scene {
		WindowGroup {
			ContentView()
		}
	}
}

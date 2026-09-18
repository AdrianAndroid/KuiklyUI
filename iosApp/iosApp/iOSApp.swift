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

/// 供第三方库/系统使用的 AppDelegate。
///
/// `UIApplicationDelegate.window` 是 optional 属性，而 SwiftUI 生命周期下系统 delegate
/// 并不实现它；访问 `UIApplication.sharedApplication.delegate.window` 的库会触发
/// `doesNotRecognizeSelector:` 崩溃（历史上 WMPlayer 5.0 的 +IsiPhoneX 就是这样崩的）。
/// 这里提供一个带 `window` 属性的 delegate，让这类访问安全（nil 也可接受）。
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

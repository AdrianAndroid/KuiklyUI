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
import AppKit

@main
struct macAppApp: App {
    @NSApplicationDelegateAdaptor(macAppAppDelegate.self) private var appDelegate

    var body: some Scene {
        WindowGroup {
            ContentView()
                .onAppear {
                    Log.emit(.info, tag: "app.life", "Window first appeared",
                             fields: ["pid": ProcessInfo.processInfo.processIdentifier])
                }
        }
    }
}

/// AppKit-level hooks that SwiftUI does not yet expose directly.
final class macAppAppDelegate: NSObject, NSApplicationDelegate {
    func applicationDidFinishLaunching(_ notification: Notification) {
        Log.emit(.info, tag: "app.life", "applicationDidFinishLaunching",
                 fields: ["args": CommandLine.arguments])
        KRDiagnosticLog.bootstrap()
    }

    func applicationDidBecomeActive(_ notification: Notification) {
        Log.emit(.debug, tag: "app.life", "applicationDidBecomeActive")
    }

    func applicationWillResignActive(_ notification: Notification) {
        Log.emit(.debug, tag: "app.life", "applicationWillResignActive")
    }

    func applicationWillTerminate(_ notification: Notification) {
        Log.emit(.info, tag: "app.life", "applicationWillTerminate")
        KRDiagnosticLog.flushSync()
    }
}

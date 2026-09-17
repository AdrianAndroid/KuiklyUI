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
        // NOTE: `.defaultSize(width:height:)` / `.windowResizability` require macOS 13,
        // but this target deploys to macOS 11.5. The window is sized from the
        // AppDelegate instead (see `applyDefaultWindowSizeIfNeeded`).
    }
}

/// AppKit-level hooks that SwiftUI does not yet expose directly.
final class macAppAppDelegate: NSObject, NSApplicationDelegate {
    /// Same window size the router uses when it opens a page in a new window, so the
    /// SwiftUI root window and router windows are visually consistent.
    private static let defaultWindowSize = NSSize(width: 900, height: 650)
    private static let minimumWindowSize = NSSize(width: 720, height: 480)
    private var windowSizeObserver: NSObjectProtocol?
    private var didApplyWindowSize = false

    func applicationDidFinishLaunching(_ notification: Notification) {
        Log.emit(.info, tag: "app.life", "applicationDidFinishLaunching",
                 fields: ["args": CommandLine.arguments])
        KRDiagnosticLog.bootstrap()
        observeFirstWindowForSizing()
    }

    /// SwiftUI creates its window after `applicationDidFinishLaunching`, so wait for the
    /// first window to become key, then pin its size. Without this the WindowGroup default
    /// (which is not the size the Kuikly pages are designed for) is used.
    private func observeFirstWindowForSizing() {
        windowSizeObserver = NotificationCenter.default.addObserver(
            forName: NSWindow.didBecomeKeyNotification,
            object: nil,
            queue: .main
        ) { [weak self] note in
            guard let self, !self.didApplyWindowSize,
                  let window = note.object as? NSWindow else { return }
            self.didApplyWindowSize = true
            window.minSize = Self.minimumWindowSize
            window.setContentSize(Self.defaultWindowSize)
            window.center()
            Log.emit(.info, tag: "app.life", "applied default window size",
                     fields: ["w": Self.defaultWindowSize.width,
                              "h": Self.defaultWindowSize.height,
                              "content": NSStringFromRect(window.contentLayoutRect)])
            if let token = self.windowSizeObserver {
                NotificationCenter.default.removeObserver(token)
                self.windowSizeObserver = nil
            }
        }
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

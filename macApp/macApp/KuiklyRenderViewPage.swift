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

import Foundation
import SwiftUI

/// Hosts a Kuikly page directly (no navigation stack). Kept for pages that must
/// live at the top level; prefer `KuiklyNavigationViewPage` so `openPage` can push
/// inside the same window instead of spawning a new one.
struct KuiklyRenderViewPage : NSViewControllerRepresentable {
    typealias NSViewControllerType = KuiklyRenderViewController

    var pageName: String
    var data: Dictionary<String, Any>

    func makeNSViewController(context: Context) -> KuiklyRenderViewController {
        Log.emit(.info, tag: "ui.life", "makeNSViewController",
                 fields: ["page": pageName, "data_keys": Array(data.keys)])
        return KuiklyRenderViewController(pageName: pageName, pageData: data)
    }

    func updateNSViewController(_ nsViewController: KuiklyRenderViewController, context: Context) {
        Log.emit(.debug, tag: "ui.life", "updateNSViewController",
                 fields: ["page": pageName])
        nsViewController.update(withPageName: pageName, pageData: data)
    }
}

/// Hosts a Kuikly page inside a `KRNavigationController`, so `RouterModule.openPage`
/// pushes the next page onto the same window's stack (matching the iOS experience)
/// rather than opening a brand-new window.
///
/// `showsNavigationBar` is disabled because Kuikly pages draw their own header
/// (the 56pt row with the back arrow); enabling it would render a second bar.
struct KuiklyNavigationViewPage : NSViewControllerRepresentable {
    typealias NSViewControllerType = KRNavigationController

    var pageName: String
    var data: Dictionary<String, Any>

    func makeNSViewController(context: Context) -> KRNavigationController {
        Log.emit(.info, tag: "ui.life", "makeNavigationViewController",
                 fields: ["page": pageName, "data_keys": Array(data.keys)])
        let root = KuiklyRenderViewController(pageName: pageName, pageData: data)
        let nav = KRNavigationController(rootViewController: root)
        // Must be set before the nav controller's view loads so no bar/layout is built.
        nav.showsNavigationBar = false
        return nav
    }

    func updateNSViewController(_ navController: KRNavigationController, context: Context) {
        // The root page is created once in makeNSViewController; pushes/pops are driven
        // by RouterModule from the shared Kotlin side.
    }
}

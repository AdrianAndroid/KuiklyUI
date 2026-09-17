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

struct ContentView: View {
    var body: some View {
        // NOTE: do NOT add `.ignoresSafeArea()` here. On macOS that makes the content
        // view extend under the title bar, so the Kuikly page's own header (the 56pt
        // nav row) gets its top ~28pt hidden behind the title bar.
        KuiklyNavigationViewPage(pageName: "SftpHomePage", data: [:])
            .onAppear {
                Log.emit(.info, tag: "ui.life", "ContentView onAppear")
            }
            .onDisappear {
                Log.emit(.info, tag: "ui.life", "ContentView onDisappear")
            }
    }
}

#Preview {
    ContentView()
}

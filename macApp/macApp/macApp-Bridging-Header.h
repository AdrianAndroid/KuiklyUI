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

// Keep this header minimal: only the types Swift actually touches go here.
// Pulling in handlers that depend on VLCKit / libpag / SDWebImage will drag
// those header modules into the Swift front-end's transitive precompile pass
// (SwiftExplicitDependencyGeneratePcm) and fail to compile under arm64/x86_64
// if any of those umbrella headers are incomplete on this Xcode.
#import "KRDiagnosticLog.h"
#import "KuiklyRenderViewController.h"
#import "KRNavigationController.h"

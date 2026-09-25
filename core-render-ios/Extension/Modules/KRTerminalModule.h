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
#import "KRBaseModule.h"
NS_ASSUME_NONNULL_BEGIN

/**
 * 终端 shell（iOS/macOS，复用 NMSSH 会话）。
 *
 * - 远程终端：复用 `KRSftpSession` 已认证的 NMSSHSession，`channel.requestPty=YES` + `startShell`，
 *   输出经 `NMSSHChannelDelegate` 原始字节回调累积，按「绝对偏移」拉取（与 Web 网关一致）。
 * - 本地终端：iOS/macOS 无本地 shell → 显式返回错误（绝不伪报）。
 * - pty resize：NMSSH 未公开 API → no-op（保持默认尺寸）。
 *
 * 方法表与 commonMain `TerminalModule` 一致：open/read/write/resize/close。
 */
@interface KRTerminalModule : KRBaseModule
@end
NS_ASSUME_NONNULL_END

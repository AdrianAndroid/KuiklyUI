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
 * SFTP 播放历史 Module（iOS，§20.1 / §21.5）
 *
 * 全局单例，持久化用 `NSUserDefaults` key `sftp_playback_history`。
 * 容量 2000 条 LRU（§21.5.1）。
 */
@interface KRSftpPlaybackHistoryModule : KRBaseModule
@end

NS_ASSUME_NONNULL_END

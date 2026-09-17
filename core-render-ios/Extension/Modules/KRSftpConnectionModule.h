/*
 * Tencent is pleased to support the open source community by making KuiklyUI
 * available.
 * Copyright (C) 2025 Tencent. All rights reserved.
 * Licensed under the License is KuiklyUI;
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
 * SFTP 连接列表 Module（iOS/macOS，§17.3.1 / §21.4.5）
 *
 * - 持久化到 NSUserDefaults `sftp_connections_items`
 * - 删连接联动清收藏 + 历史（§21.4.5）
 * - 密码/密钥字段透传存储；Phase 1.2 接入 Keychain 加密
 */
@interface KRSftpConnectionModule : KRBaseModule
@end

NS_ASSUME_NONNULL_END

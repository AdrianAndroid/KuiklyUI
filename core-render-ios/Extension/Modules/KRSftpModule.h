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
 * SFTP 主 Module（iOS，§3.1 / §3.5 / §7.1 / §21.2）
 *
 * 通过 NMSSH 实现 SSH/SFTP；方法表与 commonMain SftpModule 一一对应。
 */
@interface KRSftpModule : KRBaseModule
@end
NS_ASSUME_NONNULL_END

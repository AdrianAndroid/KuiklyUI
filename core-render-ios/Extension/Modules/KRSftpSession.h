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
#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/**
 * SFTP 客户端（iOS，基于 NMSSH，§3.5 / §21.1.1）
 *
 * - Session 复用：sessionId → KRSftpSession 映射；空闲 30 分钟自动断开（§21.1.3）
 * - Channel 池：单 session 上最多 4 个并发 channel（§21.1.1 maxConcurrentChannels）
 *
 * **Phase 1 简化**：当前先实现最小可用版本，Session/Channel 池与空闲回收 Phase 1.2 接入。
 */
@interface KRSftpSession : NSObject

+ (NSString *)connect:(NSDictionary *)params;
+ (void)disconnect:(NSString *)sessionId;
+ (NSArray *)list:(NSString *)sessionId remotePath:(NSString *)remotePath;
+ (NSDictionary *)stat:(NSString *)sessionId remotePath:(NSString *)remotePath followSymlink:(BOOL)followSymlink;
+ (float)download:(NSDictionary *)params;
+ (float)upload:(NSDictionary *)params;
+ (BOOL)mkdir:(NSDictionary *)params;
+ (BOOL)rm:(NSDictionary *)params;
+ (BOOL)rename:(NSDictionary *)params;
+ (BOOL)move:(NSDictionary *)params;
+ (NSDictionary *)copy:(NSDictionary *)params;
+ (BOOL)chmod:(NSDictionary *)params;
+ (BOOL)chown:(NSDictionary *)params;
+ (BOOL)setMtime:(NSDictionary *)params;
+ (float)batchTask:(NSDictionary *)params;
+ (void)cancelBatchTask:(NSString *)taskId;
+ (void)shutdownAll;

/** 根据 sessionId 取 NMSSHSession（不存在则抛异常）。返回类型实际为 NMSSHSession *，用 id 透传避免 header 引入 NMSSH */
+ (id)sessionById:(NSString *)sessionId;
@end

NS_ASSUME_NONNULL_END

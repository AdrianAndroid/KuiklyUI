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
#import "KRSftpConnectionModule.h"
#import "KRLogModule.h"
#import "SftpErrorFormatter.h"
#import "KRSftpFavoritesModule.h"
#import "KRSftpPlaybackHistoryModule.h"

static NSString *const SFTP_CONNECTIONS_KEY = @"sftp_connections_items";

@implementation KRSftpConnectionModule

/// 该 Module 对同一份持久化 JSON 做「读-改-写」，且原先跑在并发全局队列上，
/// 并发调用会互相覆盖（丢更新）。统一串行到一条队列。
static dispatch_queue_t KRSftpConnectionModuleSerialQueue(void) {
    static dispatch_queue_t q;
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        q = dispatch_queue_create("com.tencent.kuikly.sftp.connections", DISPATCH_QUEUE_SERIAL);
    });
    return q;
}


- (void)add:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] kr_stringToDictionary];
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    dispatch_async(KRSftpConnectionModuleSerialQueue(), ^{
        @try {
            NSString *id = params[@"id"];
            if (!id || [id length] == 0) {
                id = [[NSUUID UUID] UUIDString];
            }
            NSMutableDictionary *conn = [NSMutableDictionary dictionaryWithDictionary:params];
            conn[@"id"] = id;
            conn[@"createdAt"] = @([NSDate date].timeIntervalSince1970 * 1000);
            NSMutableArray *items = [self loadAllMutable];
            // 移除同 id 旧记录
            for (NSInteger i = items.count - 1; i >= 0; i--) {
                if ([items[i][@"id"] isEqualToString:id]) {
                    [items removeObjectAtIndex:i];
                }
            }
            [items addObject:conn];
            [self saveAll:items];
            if (callback) callback(@{@"id": id});
        } @catch (NSException *e) {
            if (callback) callback(@{@"error": [SftpErrorFormatter formatException:e]});
        }
    });
}

- (void)update:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] kr_stringToDictionary];
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    dispatch_async(KRSftpConnectionModuleSerialQueue(), ^{
        @try {
            NSString *id = params[@"id"];
            if (!id || [id length] == 0) {
                if (callback) callback(@{@"error": [SftpErrorFormatter formatException:
                    [NSException exceptionWithName:@"SftpException" reason:@"missing id" userInfo:nil]]});
                return;
            }
            NSMutableArray *items = [self loadAllMutable];
            for (NSInteger i = items.count - 1; i >= 0; i--) {
                if ([items[i][@"id"] isEqualToString:id]) {
                    [items replaceObjectAtIndex:i withObject:[NSMutableDictionary dictionaryWithDictionary:params]];
                }
            }
            [self saveAll:items];
            if (callback) callback(@{@"ok": @YES});
        } @catch (NSException *e) {
            if (callback) callback(@{@"error": [SftpErrorFormatter formatException:e]});
        }
    });
}

- (void)remove:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] kr_stringToDictionary];
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    dispatch_async(KRSftpConnectionModuleSerialQueue(), ^{
        @try {
            NSString *id = params[@"id"];
            NSMutableArray *items = [self loadAllMutable];
            NSMutableArray *filtered = [NSMutableArray array];
            for (NSDictionary *item in items) {
                if (![item[@"id"] isEqualToString:id]) {
                    [filtered addObject:item];
                }
            }
            [self saveAll:filtered];
            // 联动清收藏 + 历史（§21.4.5）
            [self clearFavoritesAndHistoryByConnectionId:id];
            if (callback) callback(@{@"ok": @YES});
        } @catch (NSException *e) {
            if (callback) callback(@{@"error": [SftpErrorFormatter formatException:e]});
        }
    });
}

- (void)list:(NSDictionary *)args {
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    dispatch_async(KRSftpConnectionModuleSerialQueue(), ^{
        @try {
            NSArray *items = [self loadAllMutable];
            // 按 lastUsedAt DESC 排序
            NSArray *sorted = [items sortedArrayUsingComparator:^NSComparisonResult(NSDictionary *a, NSDictionary *b) {
                long long av = [a[@"lastUsedAt"] longLongValue];
                long long bv = [b[@"lastUsedAt"] longLongValue];
                return (bv > av) ? NSOrderedAscending : ((bv < av) ? NSOrderedDescending : NSOrderedSame);
            }];
            if (callback) callback(@{@"items": sorted ?: @[]});
        } @catch (NSException *e) {
            if (callback) callback(@{@"error": [SftpErrorFormatter formatException:e]});
        }
    });
}

- (void)get:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] kr_stringToDictionary];
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    dispatch_async(KRSftpConnectionModuleSerialQueue(), ^{
        @try {
            NSString *id = params[@"id"];
            NSArray *items = [self loadAllMutable];
            NSDictionary *found = nil;
            for (NSDictionary *item in items) {
                if ([item[@"id"] isEqualToString:id]) {
                    found = item;
                    break;
                }
            }
            if (found) {
                if (callback) callback(@{@"conn": found});
            } else {
                if (callback) callback(@{@"error": @"{\"code\":3001,\"msg\":\"connection not found\"}"});
            }
        } @catch (NSException *e) {
            if (callback) callback(@{@"error": [SftpErrorFormatter formatException:e]});
        }
    });
}

- (void)touchLastUsed:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] kr_stringToDictionary];
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    dispatch_async(KRSftpConnectionModuleSerialQueue(), ^{
        @try {
            NSString *id = params[@"id"];
            NSMutableArray *items = [self loadAllMutable];
            for (NSInteger i = 0; i < items.count; i++) {
                if ([items[i][@"id"] isEqualToString:id]) {
                    NSMutableDictionary *m = [NSMutableDictionary dictionaryWithDictionary:items[i]];
                    m[@"lastUsedAt"] = @([NSDate date].timeIntervalSince1970 * 1000);
                    [items replaceObjectAtIndex:i withObject:m];
                    break;
                }
            }
            [self saveAll:items];
            if (callback) callback(@{@"ok": @YES});
        } @catch (NSException *e) {
            if (callback) callback(@{@"error": [SftpErrorFormatter formatException:e]});
        }
    });
}

#pragma mark - Storage

- (NSMutableArray *)loadAllMutable {
    NSData *data = [[NSUserDefaults standardUserDefaults] objectForKey:SFTP_CONNECTIONS_KEY];
    if (!data) return [NSMutableArray array];
    NSArray *arr = [NSKeyedUnarchiver unarchiveObjectWithData:data];
    return arr ? [NSMutableArray arrayWithArray:arr] : [NSMutableArray array];
}

- (void)saveAll:(NSArray *)items {
    NSData *data = [NSKeyedArchiver archivedDataWithRootObject:items];
    [[NSUserDefaults standardUserDefaults] setObject:data forKey:SFTP_CONNECTIONS_KEY];
    [[NSUserDefaults standardUserDefaults] synchronize];
}

- (void)clearFavoritesAndHistoryByConnectionId:(NSString *)connectionId {
    // 直接调原生 storage 层（避免 Module 异步回调）
    @try {
        [self clearFavoritesByConnectionId:connectionId];
        [self clearHistoryByConnectionId:connectionId];
    } @catch (NSException *e) {
        // 忽略联动清理失败
    }
}

- (void)clearFavoritesByConnectionId:(NSString *)connectionId {
    NSString *key = @"sftp_favorites_items";
    NSData *data = [[NSUserDefaults standardUserDefaults] objectForKey:key];
    if (!data) return;
    NSArray *items = [NSKeyedUnarchiver unarchiveObjectWithData:data];
    NSMutableArray *filtered = [NSMutableArray array];
    for (NSDictionary *item in items) {
        if (![item[@"connectionId"] isEqualToString:connectionId]) {
            [filtered addObject:item];
        }
    }
    NSData *newData = [NSKeyedArchiver archivedDataWithRootObject:filtered];
    [[NSUserDefaults standardUserDefaults] setObject:newData forKey:key];
}

- (void)clearHistoryByConnectionId:(NSString *)connectionId {
    NSString *key = @"sftp_playback_history_items";
    NSData *data = [[NSUserDefaults standardUserDefaults] objectForKey:key];
    if (!data) return;
    NSArray *items = [NSKeyedUnarchiver unarchiveObjectWithData:data];
    NSMutableArray *filtered = [NSMutableArray array];
    for (NSDictionary *item in items) {
        if (![item[@"connectionId"] isEqualToString:connectionId]) {
            [filtered addObject:item];
        }
    }
    NSData *newData = [NSKeyedArchiver archivedDataWithRootObject:filtered];
    [[NSUserDefaults standardUserDefaults] setObject:newData forKey:key];
}

@end

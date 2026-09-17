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
#import "KRSftpPlaybackHistoryModule.h"
#import "NSObject+KR.h"

static NSString *const kHistoryKey = @"sftp_playback_history_items";
static const int kMaxCapacity = 2000;

@implementation KRSftpPlaybackHistoryModule

- (void)upsert:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] kr_stringToDictionary];
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{
        @try {
            NSString *id = params[@"id"] ?: [self buildId:params[@"connectionId"] remotePath:params[@"remotePath"]];
            NSMutableDictionary *record = [params mutableCopy];
            record[@"id"] = id;
            NSMutableArray *all = [[self loadAll] mutableCopy];
            BOOL found = NO;
            for (NSInteger i = 0; i < all.count; i++) {
                if ([all[i][@"id"] isEqualToString:id]) {
                    all[i] = record;
                    found = YES;
                    break;
                }
            }
            if (!found) [all addObject:record];
            // LRU 裁剪
            if (all.count > kMaxCapacity) {
                NSArray *sorted = [all sortedArrayUsingDescriptors:@[
                    [NSSortDescriptor sortDescriptorWithKey:@"lastPlayedAt" ascending:NO]
                ]];
                all = [[sorted subarrayWithRange:NSMakeRange(0, kMaxCapacity)] mutableCopy];
            }
            [self saveAll:all];
            if (callback) callback(@{@"ok": @YES});
        } @catch (NSException *e) {
            if (callback) callback(@{@"error": e.reason ?: @"unknown error"});
        }
    });
}

- (void)get:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] kr_stringToDictionary];
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    NSString *connectionId = params[@"connectionId"];
    NSString *remotePath = params[@"remotePath"];
    dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{
        NSString *id = [self buildId:connectionId remotePath:remotePath];
        NSArray *all = [self loadAll];
        NSDictionary *record = nil;
        for (NSDictionary *item in all) {
            if ([item[@"id"] isEqualToString:id]) { record = item; break; }
        }
        if (callback) {
            if (record) callback(@{@"record": record});
            else callback(@{@"ok": @YES});
        }
    });
}

- (void)listByDirectory:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] kr_stringToDictionary];
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    NSString *connectionId = params[@"connectionId"];
    NSString *directoryPath = params[@"directoryPath"];
    dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{
        NSArray *all = [self loadAll];
        NSMutableArray *result = [NSMutableArray array];
        for (NSDictionary *item in all) {
            if ([item[@"connectionId"] isEqualToString:connectionId]) {
                NSString *path = item[@"remotePath"];
                NSString *parent = [path stringByDeletingLastPathComponent];
                if (parent.length == 0) parent = @"/";
                if ([parent isEqualToString:directoryPath]) [result addObject:item];
            }
        }
        NSArray *sorted = [result sortedArrayUsingDescriptors:@[
            [NSSortDescriptor sortDescriptorWithKey:@"lastPlayedAt" ascending:NO]
        ]];
        if (callback) callback(@{@"records": sorted});
    });
}

- (void)listByConnection:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] kr_stringToDictionary];
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    NSString *connectionId = params[@"connectionId"];
    dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{
        NSArray *all = [self loadAll];
        NSMutableArray *result = [NSMutableArray array];
        for (NSDictionary *item in all) {
            if ([item[@"connectionId"] isEqualToString:connectionId]) [result addObject:item];
        }
        NSArray *sorted = [result sortedArrayUsingDescriptors:@[
            [NSSortDescriptor sortDescriptorWithKey:@"lastPlayedAt" ascending:NO]
        ]];
        if (callback) callback(@{@"records": sorted});
    });
}

- (void)remove:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] kr_stringToDictionary];
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    NSString *id = params[@"id"];
    dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{
        NSMutableArray *all = [[self loadAll] mutableCopy];
        NSMutableArray *filtered = [NSMutableArray array];
        for (NSDictionary *item in all) {
            if (![item[@"id"] isEqualToString:id]) [filtered addObject:item];
        }
        [self saveAll:filtered];
        if (callback) callback(@{@"ok": @YES});
    });
}

- (void)clearByConnection:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] kr_stringToDictionary];
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    NSString *connectionId = params[@"connectionId"];
    dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{
        NSMutableArray *all = [[self loadAll] mutableCopy];
        NSMutableArray *filtered = [NSMutableArray array];
        for (NSDictionary *item in all) {
            if (![item[@"connectionId"] isEqualToString:connectionId]) [filtered addObject:item];
        }
        [self saveAll:filtered];
        if (callback) callback(@{@"ok": @YES});
    });
}

- (void)markCompleted:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] kr_stringToDictionary];
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    NSString *connectionId = params[@"connectionId"];
    NSString *remotePath = params[@"remotePath"];
    dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{
        NSString *id = [self buildId:connectionId remotePath:remotePath];
        NSMutableArray *all = [[self loadAll] mutableCopy];
        for (NSInteger i = 0; i < all.count; i++) {
            NSMutableDictionary *item = [all[i] mutableCopy];
            if ([item[@"id"] isEqualToString:id]) {
                item[@"completed"] = @YES;
                item[@"position"] = item[@"duration"];
                all[i] = item;
                break;
            }
        }
        [self saveAll:all];
        if (callback) callback(@{@"ok": @YES});
    });
}

- (NSString *)buildId:(NSString *)connectionId remotePath:(NSString *)remotePath {
    NSString *key = [NSString stringWithFormat:@"%@|%@", connectionId, remotePath];
    return [NSString stringWithFormat:@"%lx", (unsigned long)[key hash]];
}

- (NSArray *)loadAll {
    NSData *data = [[NSUserDefaults standardUserDefaults] dataForKey:kHistoryKey];
    if (!data) return @[];
    NSError *err;
    NSArray *arr = [NSJSONSerialization JSONObjectWithData:data options:0 error:&err];
    return arr ?: @[];
}

- (void)saveAll:(NSArray *)arr {
    NSError *err;
    NSData *data = [NSJSONSerialization dataWithJSONObject:arr options:0 error:&err];
    [[NSUserDefaults standardUserDefaults] setObject:data forKey:kHistoryKey];
}

@end

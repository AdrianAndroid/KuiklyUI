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
#import "KRSftpFavoritesModule.h"
#import "NSObject+KR.h"

static NSString *const kFavoritesKey = @"sftp_favorites_items";

@implementation KRSftpFavoritesModule

- (void)add:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] kr_stringToDictionary];
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{
        @try {
            NSString *id = params[@"id"] ?: [[NSUUID UUID] UUIDString];
            NSMutableDictionary *item = [params mutableCopy];
            item[@"id"] = id;
            if (!item[@"starredAt"]) item[@"starredAt"] = @([[NSDate date] timeIntervalSince1970] * 1000);
            NSMutableArray *all = [[self loadAll] mutableCopy];
            [all addObject:item];
            [self saveAll:all];
            if (callback) callback(@{@"id": id});
        } @catch (NSException *e) {
            if (callback) callback(@{@"error": e.reason ?: @"unknown error"});
        }
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

- (void)removeByConnection:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] kr_stringToDictionary];
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    NSString *connectionId = params[@"connectionId"];
    dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{
        NSMutableArray *all = [[self loadAll] mutableCopy];
        NSMutableArray *filtered = [NSMutableArray array];
        NSInteger removed = 0;
        for (NSDictionary *item in all) {
            if ([item[@"connectionId"] isEqualToString:connectionId]) {
                removed++;
            } else {
                [filtered addObject:item];
            }
        }
        [self saveAll:filtered];
        if (callback) callback(@{@"ok": @YES, @"removedCount": @(removed)});
    });
}

- (void)list:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] kr_stringToDictionary];
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    NSString *connectionId = params[@"connectionId"];
    NSString *sortBy = params[@"sortBy"] ?: @"STARRED_AT";
    NSString *sortOrder = params[@"sortOrder"] ?: @"DESC";
    dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{
        NSArray *all = [self loadAll];
        NSMutableArray *filtered = [NSMutableArray array];
        for (NSDictionary *item in all) {
            if (!connectionId || [item[@"connectionId"] isEqualToString:connectionId]) {
                [filtered addObject:item];
            }
        }
        NSArray *sorted = [self sortList:filtered sortBy:sortBy sortOrder:sortOrder];
        if (callback) callback(@{@"items": sorted});
    });
}

- (void)isFavorited:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] kr_stringToDictionary];
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    NSString *connectionId = params[@"connectionId"];
    NSString *remotePath = params[@"remotePath"];
    dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{
        NSArray *all = [self loadAll];
        NSString *id = @"";
        for (NSDictionary *item in all) {
            if ([item[@"connectionId"] isEqualToString:connectionId] &&
                [item[@"remotePath"] isEqualToString:remotePath]) {
                id = item[@"id"];
                break;
            }
        }
        if (callback) callback(@{@"id": id});
    });
}

- (void)update:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] kr_stringToDictionary];
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    NSString *id = params[@"id"];
    dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{
        NSMutableArray *all = [[self loadAll] mutableCopy];
        for (NSInteger i = 0; i < all.count; i++) {
            NSMutableDictionary *item = [all[i] mutableCopy];
            if ([item[@"id"] isEqualToString:id]) {
                if (params[@"note"]) item[@"note"] = params[@"note"];
                if (params[@"iconOverride"]) item[@"iconOverride"] = params[@"iconOverride"];
                all[i] = item;
            }
        }
        [self saveAll:all];
        if (callback) callback(@{@"ok": @YES});
    });
}

- (void)search:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] kr_stringToDictionary];
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    NSString *keyword = params[@"keyword"];
    dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{
        NSArray *all = [self loadAll];
        NSMutableArray *result = [NSMutableArray array];
        NSString *lower = [keyword lowercaseString];
        for (NSDictionary *item in all) {
            NSString *name = [item[@"name"] lowercaseString];
            NSString *path = [item[@"remotePath"] lowercaseString];
            NSString *label = [item[@"connectionLabel"] lowercaseString];
            if ([name containsString:lower] || [path containsString:lower] || [label containsString:lower]) {
                [result addObject:item];
            }
        }
        if (callback) callback(@{@"items": result});
    });
}

- (NSArray *)loadAll {
    NSData *data = [[NSUserDefaults standardUserDefaults] dataForKey:kFavoritesKey];
    if (!data) return @[];
    NSError *err;
    NSArray *arr = [NSJSONSerialization JSONObjectWithData:data options:0 error:&err];
    return arr ?: @[];
}

- (void)saveAll:(NSArray *)arr {
    NSError *err;
    NSData *data = [NSJSONSerialization dataWithJSONObject:arr options:0 error:&err];
    [[NSUserDefaults standardUserDefaults] setObject:data forKey:kFavoritesKey];
}

- (NSArray *)sortList:(NSArray *)list sortBy:(NSString *)sortBy sortOrder:(NSString *)sortOrder {
    NSSortDescriptor *desc;
    if ([sortBy isEqualToString:@"NAME"]) {
        desc = [NSSortDescriptor sortDescriptorWithKey:@"name" ascending:[sortOrder isEqualToString:@"ASC"]];
    } else if ([sortBy isEqualToString:@"CONNECTION_LABEL"]) {
        desc = [NSSortDescriptor sortDescriptorWithKey:@"connectionLabel" ascending:[sortOrder isEqualToString:@"ASC"]];
    } else if ([sortBy isEqualToString:@"MTIME"]) {
        desc = [NSSortDescriptor sortDescriptorWithKey:@"mtime" ascending:[sortOrder isEqualToString:@"ASC"]];
    } else {
        desc = [NSSortDescriptor sortDescriptorWithKey:@"starredAt" ascending:[sortOrder isEqualToString:@"ASC"]];
    }
    return [list sortedArrayUsingDescriptors:@[desc]];
}

@end

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
#import "SftpErrorFormatter.h"

@implementation SftpErrorFormatter

+ (NSString *)formatException:(NSException *)e {
    int code = 0;
    NSString *msg = e.reason ?: @"未知错误";
    NSString *name = e.name ?: @"";
    if ([name containsString:@"ConnectException"] || [name containsString:@"SftpConnectException"]) code = 1001;
    else if ([name containsString:@"Auth"] || [name containsString:@"SftpAuthException"]) code = 1003;
    else if ([name containsString:@"HostKey"]) code = 1004;
    else if ([name containsString:@"Timeout"]) code = 1002;
    else if ([name containsString:@"NoSuch"]) code = 2003;      // NO_SUCH_FILE
    else if ([name containsString:@"Permission"]) code = 2001;  // PERMISSION_DENIED
    else if ([name containsString:@"NotImplemented"]) code = 9999;
    else code = 0;

    NSDictionary *json = @{
        @"code": @(code),
        @"msg": msg,
        @"detail": e.userInfo[@"detail"] ?: [NSNull null]
    };
    NSError *err;
    NSData *data = [NSJSONSerialization dataWithJSONObject:json options:0 error:&err];
    return data ? [[NSString alloc] initWithData:data encoding:NSUTF8StringEncoding] : @"{}";
}

+ (NSString *)formatNSError:(NSError *)error {
    int code = 0;
    NSString *msg = error.localizedDescription ?: @"未知错误";
    // NMSSH/NMSFTP errors
    if ([error.domain containsString:@"NMSSH"]) {
        if (error.code == 1001) code = 1001;  // connection
        else if (error.code == 1002) code = 1003;  // auth
        else code = 2999;
    }
    NSDictionary *json = @{
        @"code": @(code),
        @"msg": msg
    };
    NSError *err;
    NSData *data = [NSJSONSerialization dataWithJSONObject:json options:0 error:&err];
    return data ? [[NSString alloc] initWithData:data encoding:NSUTF8StringEncoding] : @"{}";
}

@end

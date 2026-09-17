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

import Foundation
import os.log

/// Thin Swift bridge over `KRDiagnosticLog`. All calls are fire-and-forget
/// on a background queue (no caller blocking). For richer structured content
/// use the ObjC API directly.
public enum KuiklyLog: String {
    case trace, debug, info, warn, error, fatal
}

public enum KuiklyLogSink {
    /// Subsystem + category that ends up in Console.app and `log show`.
    public static let subsystem = "com.tencent.kuikly.macApp"
    public static let category  = "diagnostic"
}

public enum Log {
    /// Asynchronous (non-blocking) structured log.
    public static func emit(_ level: KuiklyLog, tag: String, _ message: @autoclosure () -> String,
                            fields: [String: Any]? = nil) {
        let msg = message()
        let lvlInt = KRLogLevel(rawValue: numericLevel(level)) ?? KRLogLevel.info
        if let f = fields {
            KRDiagnosticLog.log(lvlInt, tag: tag, message: msg, fields: f)
        } else {
            KRDiagnosticLog.log(lvlInt, tag: tag, message: msg)
        }
    }

    private static func numericLevel(_ l: KuiklyLog) -> Int {
        switch l {
        case .trace: return 0
        case .debug: return 1
        case .info:  return 2
        case .warn:  return 3
        case .error: return 4
        case .fatal: return 5
        }
    }
}

/// Returns the JSONL log file path if logging has been initialized.
public func kuiklyLogFilePath() -> String? {
    return KRDiagnosticLog.logFilePath()
}

/// Returns the most recent log lines (newest last). Synchronous, but cheap —
/// reads a bounded in-memory ring buffer, not the file.
public func kuiklyRecentLogLines(_ max: Int = 200) -> [String] {
    return KRDiagnosticLog.recentLines(UInt(max))
}

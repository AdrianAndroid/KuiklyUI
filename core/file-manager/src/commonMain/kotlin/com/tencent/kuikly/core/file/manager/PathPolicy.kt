package com.tencent.kuikly.core.file.manager

/**
 * 路径越界检查：禁止 `..` 或绝对路径逃出 root。
 *
 * Local root = 应用私有外部存储（Android）/ Documents（iOS）/ 用户主目录（macOS, Electron）
 * Remote root = 登录时的 cwd（由 `serverId` 关联的会话提供）
 *
 * 不在 commonMain 做 `Platform.isXxx` 分支；root 由宿主提供。
 */
class PathPolicy(val root: String) {

    /**
     * 归一化并校验：
     * - 相对路径 → root 下拼接
     * - 以 root 开头的绝对路径 → 去掉 root 前缀
     * - 其它绝对路径 → 抛 [PathEscapeException]
     * - `..` 越出 root → 抛 [PathEscapeException]
     */
    fun normalize(path: String): String {
        val rootNorm = root.trimEnd('/')
        val rel = when {
            path.isEmpty() -> return rootNorm
            path == rootNorm -> return rootNorm
            path.startsWith("$rootNorm/") -> path.removePrefix(rootNorm)
            path.startsWith("/") || path.startsWith("\\") ->
                throw PathEscapeException("absolute path outside root: $path")
            else -> path
        }
        val stack = ArrayDeque<String>()
        for (seg in rel.split('/', '\\')) {
            when (seg) {
                "", "." -> { /* skip */ }
                ".." -> {
                    if (stack.isNotEmpty()) stack.removeLast()
                    else throw PathEscapeException("path escapes root: $path")
                }
                else -> stack.addLast(seg)
            }
        }
        return if (stack.isEmpty()) rootNorm else "$rootNorm/${stack.joinToString("/")}"
    }

    /** 断言 `path` 仍在 root 之下；越界抛 [PathEscapeException]。 */
    fun assertWithin(path: String) {
        normalize(path)
    }
}

class PathEscapeException(message: String) : RuntimeException(message)

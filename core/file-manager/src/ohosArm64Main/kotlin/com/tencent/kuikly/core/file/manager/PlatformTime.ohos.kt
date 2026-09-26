package com.tencent.kuikly.core.file.manager

import platform.posix.time

internal actual fun nowMillis(): Long = time(null) * 1000L

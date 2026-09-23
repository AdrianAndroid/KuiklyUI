package com.tencent.kuikly.core.file.manager

/** 跨平台当前时间（epoch millis）。各 target 提供 actual。 */
internal expect fun nowMillis(): Long

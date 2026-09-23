package com.tencent.kuikly.core.file.manager

internal actual fun nowMillis(): Long = (js("Date.now()") as Number).toLong()

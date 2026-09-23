package com.tencent.kuikly.core.file.manager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** D-L1-40/41：默认值。 */
class DefaultsTest {

    @Test
    fun configDefaults() {
        val c = FileManagerConfig()
        assertEquals(2L * 1024 * 1024, c.maxEditorBytes)
        assertEquals(listOf(3_000L, 5_000L, 10_000L, 20_000L, 30_000L), c.defaultRetryBackoffMs)
        assertEquals(5, c.retryMax)
        assertEquals(3, c.maxConcurrent)
        assertTrue(c.enableFileEditor)
    }
}

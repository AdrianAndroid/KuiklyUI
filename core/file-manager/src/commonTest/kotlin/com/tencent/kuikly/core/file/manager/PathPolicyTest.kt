package com.tencent.kuikly.core.file.manager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/** D-L1-24 / D-L1-25 / D-L1-26：路径越界检查。 */
class PathPolicyTest {

    @Test
    fun localEscapeDetected() {
        val p = PathPolicy("/home")
        assertFailsWith<PathEscapeException> { p.normalize("/home/../escape") }
    }

    @Test
    fun localNormalize() {
        val p = PathPolicy("/home")
        assertEquals("/home/a/b", p.normalize("/home/a/./b/../c/../b"))
        assertEquals("/home", p.normalize("/home"))
    }

    @Test
    fun remoteDotDotBlocked() {
        val p = PathPolicy("/srv/root")
        assertFailsWith<PathEscapeException> { p.normalize("/srv/root/../etc") }
    }

    @Test
    fun remoteAbsolutePathTreatedAsRelativeOrRejected() {
        val p = PathPolicy("/srv/root")
        // "/etc" 不在 root 下视为越界候选
        assertFailsWith<PathEscapeException> { p.normalize("/etc") }
    }

    @Test
    fun assertWithinOk() {
        val p = PathPolicy("/home")
        p.assertWithin("/home/sub")
        p.assertWithin("/home/sub/deeper")
        assertFailsWith<PathEscapeException> { p.assertWithin("/home/../escape") }
    }
}

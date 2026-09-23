package com.tencent.kuikly.core.file.manager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** D-L1-24..D-L1-27/33..D-L1-39：路径安全 + 文件操作计划。 */
class PlanOpsTest {

    private fun mod() = FileManagerModule(localRoot = "/L", remotePane = FileManagerModule.PaneSpec("srv", "/h"))

    @Test
    fun mkdirPlan() {
        assertEquals("/L/newDir", mod().planMkdir(Pane.Local, "newDir"))
    }

    @Test
    fun renamePlan() {
        val (from, to) = mod().planRename(Pane.Local, "a", "b")
        assertEquals("/L/a", from); assertEquals("/L/b", to)
    }

    @Test
    fun removePlan() {
        assertEquals(listOf("/L/a"), mod().planRemove(Pane.Local, listOf("a")))
    }

    @Test
    fun escapeRejected() {
        assertFailsWith<PathEscapeException> { mod().planMkdir(Pane.Local, "../escape") }
        assertFailsWith<PathEscapeException> { mod().normalize(Pane.Remote, "/etc/passwd") }
        assertFailsWith<PathEscapeException> { mod().normalize(Pane.Remote, "a/../../b") }
    }

    @Test
    fun removeRootProtected() {
        assertFailsWith<IllegalStateException> { mod().planRemove(Pane.Remote, listOf("/")) }
    }

    @Test
    fun symlinkListingPreserved() {
        // 后端应把符号链接如实上报（不跟随）；核心保留该类型
        val m = mod()
        m.setEntries(Pane.Local, "/L", listOf(BackendEntry("link", EntryType.Symlink, 0, 0), BackendEntry("f", EntryType.File, 0, 0)))
        assertTrue(m.state.value.local.entries.any { it.type == EntryType.Symlink })
    }
}

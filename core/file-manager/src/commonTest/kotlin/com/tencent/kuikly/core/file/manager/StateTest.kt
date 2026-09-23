package com.tencent.kuikly.core.file.manager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** D-L1-01/02/03/08/09/10/11：双栏独立浏览 / 导航 / 选择。 */
class StateTest {

    private fun mod() = FileManagerModule(localRoot = "/L", remotePane = FileManagerModule.PaneSpec("srv", "/h"))

    private fun FileManagerModule.seed() {
        setEntries(Pane.Local, "/L", listOf(BackendEntry("d", EntryType.Dir, 0, 0), BackendEntry("x", EntryType.File, 0, 0)))
        setEntries(Pane.Remote, "/h", listOf(BackendEntry("a", EntryType.Dir, 0, 0), BackendEntry("b", EntryType.File, 0, 0)))
    }

    @Test
    fun listIsolatedPerPane() {
        val m = mod(); m.seed()
        assertEquals("/L", m.state.value.local.cwd)
        assertEquals("/h", m.state.value.remote.cwd)
        assertTrue(m.state.value.local.entries.isNotEmpty())
        assertTrue(m.state.value.remote.entries.isNotEmpty())
        m.enter(Pane.Local, "d")
        assertEquals("/L/d", m.state.value.local.cwd)
        assertEquals("/h", m.state.value.remote.cwd)   // 远端不受影响
    }

    @Test
    fun enterClearsSelectionAndSetsLoading() {
        val m = mod(); m.seed()
        m.setSelection(Pane.Remote, setOf("a", "b"))
        m.enter(Pane.Remote, "a")
        assertEquals("/h/a", m.state.value.remote.cwd)
        assertEquals(emptySet(), m.state.value.remote.selection)
        assertTrue(m.state.value.remote.loading)        // 等宿主回填
    }

    @Test
    fun singleAndMultiSelection() {
        val m = mod(); m.seed()
        m.toggleSelection(Pane.Local, "x")
        assertEquals(setOf("x"), m.state.value.local.selection)
        m.toggleSelection(Pane.Local, "x")
        assertEquals(emptySet(), m.state.value.local.selection)
        m.toggleSelection(Pane.Remote, "a"); m.toggleSelection(Pane.Remote, "b")
        assertEquals(setOf("a", "b"), m.state.value.remote.selection)
    }

    @Test
    fun upFromRootIsNull() {
        val m = mod()
        assertNull(m.upPath(Pane.Local))               // 已是顶层
        assertNull(m.enter(Pane.Local, ".."))
        assertEquals("/L", m.state.value.local.cwd)
    }

    @Test
    fun upFromChildWorks() {
        val m = mod(); m.seed()
        m.enter(Pane.Local, "d")
        assertEquals("/L", m.upPath(Pane.Local))
        m.enter(Pane.Local, "..")
        assertEquals("/L", m.state.value.local.cwd)
    }

    @Test
    fun noSelectedItemsTransferRejected() {
        val m = mod()
        kotlin.test.assertFailsWith<IllegalStateException> { m.planTransfer(emptyList(), TransferDirection.Upload) }
    }
}

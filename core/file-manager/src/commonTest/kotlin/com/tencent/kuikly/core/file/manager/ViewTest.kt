package com.tencent.kuikly.core.file.manager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** D-L1-05/06/07：排序 / 过滤 / 加载与错误态。 */
class ViewTest {

    private fun mod(entries: List<BackendEntry>) =
        FileManagerModule(localRoot = "/L", remotePane = FileManagerModule.PaneSpec("srv", "/h"))
            .also { it.setEntries(Pane.Local, "/L", entries) }

    private val abc = listOf(
        BackendEntry("b", EntryType.File, 0, 0),
        BackendEntry("A", EntryType.File, 0, 0),
        BackendEntry("c", EntryType.File, 0, 0),
    )

    @Test
    fun sortByNameAscending() {
        val m = mod(abc)
        m.setSort(Pane.Local, SortKey.Name, SortOrder.Asc)
        assertEquals(listOf("A", "b", "c"), m.state.value.local.entries.map { it.name })
    }

    @Test
    fun sortByNameDescending() {
        val m = mod(abc)
        m.setSort(Pane.Local, SortKey.Name, SortOrder.Desc)
        assertEquals(listOf("c", "b", "A"), m.state.value.local.entries.map { it.name })
    }

    @Test
    fun dirsSortBeforeFiles() {
        val m = mod(
            listOf(
                BackendEntry("z_file", EntryType.File, 0, 0),
                BackendEntry("a_dir", EntryType.Dir, 0, 0),
                BackendEntry("m_file", EntryType.File, 0, 0),
                BackendEntry("b_dir", EntryType.Dir, 0, 0),
            ),
        )
        // 升序：目录（a_dir,b_dir）在前，文件（m_file,z_file）在后
        assertEquals(listOf("a_dir", "b_dir", "m_file", "z_file"), m.state.value.local.entries.map { it.name })
        // 降序：目录仍在最前，仅组内倒序
        m.setSort(Pane.Local, SortKey.Name, SortOrder.Desc)
        assertEquals(listOf("b_dir", "a_dir", "z_file", "m_file"), m.state.value.local.entries.map { it.name })
    }

    @Test
    fun filterCaseInsensitiveAndRecomputable() {
        val m = mod(listOf(BackendEntry("app.log", EntryType.File, 0, 0), BackendEntry("a.txt", EntryType.File, 0, 0), BackendEntry("LOG.txt", EntryType.File, 0, 0)))
        m.setFilter(Pane.Local, "log")
        assertEquals(setOf("app.log", "LOG.txt"), m.state.value.local.entries.map { it.name }.toSet())
        m.setFilter(Pane.Local, "")                    // 清空过滤应恢复全部（原始列表被保留）
        assertEquals(3, m.state.value.local.entries.size)
    }

    @Test
    fun errorStateClearsLoading() {
        val m = mod(emptyList())
        m.setLoading(Pane.Local, true)
        m.setError(Pane.Local, "boom")
        assertTrue(!m.state.value.local.loading)
        assertTrue(m.state.value.local.errorMsg?.contains("boom") == true)
    }
}

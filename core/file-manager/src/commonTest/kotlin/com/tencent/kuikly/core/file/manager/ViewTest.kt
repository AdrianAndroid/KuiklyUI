package com.tencent.kuikly.core.file.manager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** D-L1-05/06/07：排序 / 过滤 / 加载与错误态；D-L1-43：隐藏文件默认不显示（可切换）。 */
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

    private val mixed = listOf(
        BackendEntry("visible.txt", EntryType.File, 0, 0),
        BackendEntry(".secret.txt", EntryType.File, 0, 0),
        BackendEntry(".config", EntryType.Dir, 0, 0),
    )

    @Test
    fun hiddenFilesHiddenByDefault() {
        val m = mod(mixed)
        // 默认不显示隐藏文件（`.` 开头），且状态字段默认 false —— 与 UI 初始态一致
        assertTrue(!m.state.value.local.showHidden)
        assertEquals(listOf("visible.txt"), m.state.value.local.entries.map { it.name })
    }

    @Test
    fun setShowHiddenTogglesWithoutRefetch() {
        val m = mod(mixed)
        m.setShowHidden(Pane.Local, true)
        // 目录优先：.config 在文件之前
        assertEquals(listOf(".config", ".secret.txt", "visible.txt"), m.state.value.local.entries.map { it.name })
        m.setShowHidden(Pane.Local, false)             // 再次点击回到隐藏
        assertEquals(listOf("visible.txt"), m.state.value.local.entries.map { it.name })
    }

    @Test
    fun hidingDropsSelectionOfHiddenEntries() {
        val m = mod(mixed)
        m.setShowHidden(Pane.Local, true)
        m.setSelection(Pane.Local, setOf(".secret.txt", "visible.txt"))
        m.setShowHidden(Pane.Local, false)
        // 被隐藏的条目不能留在选中集合里（否则会「看不见却被上传/删除」）
        assertEquals(setOf("visible.txt"), m.state.value.local.selection)
    }

    @Test
    fun navigationKeepsShowHidden() {
        val m = mod(mixed)
        m.setShowHidden(Pane.Local, true)
        m.navigateTo(Pane.Local, "/L/sub")
        m.setEntries(Pane.Local, "/L/sub", mixed)
        assertTrue(m.state.value.local.showHidden)
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

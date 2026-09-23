package com.tencent.kuikly.core.file.manager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** D-L1-12/13/14/15/16：选区 → 传输请求计划（含记忆策略）。 */
class PlanTransferTest {

    private fun mod() = FileManagerModule(localRoot = "/L", remotePane = FileManagerModule.PaneSpec("srv", "/h"))

    @Test
    fun uploadPlanLocalToRemote() {
        val m = mod()
        val reqs = m.planTransfer(
            listOf(FileItem(local = "/L/a", remote = "/h/a", size = 10, name = "a"), FileItem(local = "/L/b", remote = "/h/b", size = 20, name = "b")),
            TransferDirection.Upload,
        )
        assertEquals(2, reqs.size)
        assertEquals(TransferDirection.Upload, reqs[0].direction)
        assertEquals("/L/a", reqs[0].localPath)
        assertEquals("/h/a", reqs[0].remotePath)
        assertEquals("srv", reqs[0].serverId)
        assertEquals(false, reqs[0].overwrite)
    }

    @Test
    fun downloadPlanRemoteToLocal() {
        val m = mod()
        val reqs = m.planTransfer(listOf(FileItem(local = "/L/x", remote = "/h/x", size = 5, name = "x")), TransferDirection.Download)
        assertEquals(TransferDirection.Download, reqs[0].direction)
        assertEquals("/L/x", reqs[0].localPath)
        assertEquals("/h/x", reqs[0].remotePath)
    }

    @Test
    fun dirFlagPropagated() {
        val m = mod()
        val reqs = m.planTransfer(listOf(FileItem(local = "/L/d", remote = "/h/d", size = 0, name = "d", isDir = true)), TransferDirection.Upload)
        assertTrue(reqs[0].isDir)
    }

    @Test
    fun rememberedOverwriteWithinTtl() {
        val m = mod()
        m.rememberAction(TransferDirection.Upload, ConflictAction.Overwrite, forMs = 60_000L)
        val reqs = m.planTransfer(listOf(FileItem(local = "/L/a", remote = "/h/a", size = 5, name = "a")), TransferDirection.Upload)
        assertEquals(true, reqs[0].overwrite)
    }

    @Test
    fun rememberedExpiredFallsBackToAsk() {
        val m = mod()
        m.rememberAction(TransferDirection.Upload, ConflictAction.Overwrite, forMs = -1L)
        val reqs = m.planTransfer(listOf(FileItem(local = "/L/a", remote = "/h/a", size = 5, name = "a")), TransferDirection.Upload)
        assertEquals(false, reqs[0].overwrite)
    }

    @Test
    fun defaultResolverSkipsEqualSize() {
        assertEquals(ConflictAction.Skip, DefaultConflictResolver(ConflictInfo("/h/a", sizeLocal = 5, sizeRemote = 5, mtimeLocal = 1, mtimeRemote = 1)).action)
        assertEquals(ConflictAction.Overwrite, DefaultConflictResolver(ConflictInfo("/h/a", sizeLocal = 5, sizeRemote = 7, mtimeLocal = 1, mtimeRemote = 1)).action)
    }
}

package com.tencent.kuikly.core.file.manager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** D-L1-17/18/19/21/22：传输状态机（宿主喂引擎事件）。 */
class TransferStateTest {

    private fun mod() = FileManagerModule(localRoot = "/L", remotePane = FileManagerModule.PaneSpec("srv", "/h"))
    private fun req() = TransferRequest(serverId = "srv", direction = TransferDirection.Upload, localPath = "/L/a", remotePath = "/h/a")

    @Test
    fun startedRegistersRunning() {
        val m = mod()
        m.onTransferStarted("t1", req(), bytesTotal = 100)
        val t = m.state.value.transfers.getValue("t1")
        assertEquals(TransferStatus.Running, t.status)
        assertEquals(100, t.bytesTotal)
    }

    @Test
    fun progressUpdatesAndCompletes() {
        val m = mod()
        m.onTransferStarted("t1", req(), bytesTotal = 100)
        m.onTransferProgress("t1", bytesSent = 40, bytesTotal = 100, currentFile = "/h/a")
        assertEquals(40, m.state.value.transfers.getValue("t1").bytesSent)
        m.onTransferProgress("t1", bytesSent = 100, bytesTotal = 100)
        assertEquals(TransferStatus.Done, m.state.value.transfers.getValue("t1").status)
    }

    @Test
    fun statusCanceledAndErrorAreTerminal() {
        val m = mod()
        m.onTransferStarted("t1", req(), bytesTotal = 10)
        m.onTransferStatus("t1", TransferStatus.Canceled)
        assertEquals(TransferStatus.Canceled, m.state.value.transfers.getValue("t1").status)
        assertTrue(m.state.value.transfers.getValue("t1").finishedAt != null)

        m.onTransferStarted("t2", req(), bytesTotal = 10)
        m.onTransferStatus("t2", TransferStatus.Error, "boom")
        assertEquals(TransferStatus.Error, m.state.value.transfers.getValue("t2").status)
        assertEquals("boom", m.state.value.transfers.getValue("t2").error)
    }

    @Test
    fun clearAndShutdownAreIdempotent() {
        val m = mod()
        m.onTransferStarted("t1", req(), bytesTotal = 10)
        m.clearTransfers()
        assertTrue(m.state.value.transfers.isEmpty())
        m.shutdown()
        m.shutdown()   // 幂等
        assertTrue(m.state.value.transfers.isEmpty())
    }
}

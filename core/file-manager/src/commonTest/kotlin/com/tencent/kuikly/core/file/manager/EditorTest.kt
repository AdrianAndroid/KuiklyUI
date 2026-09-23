package com.tencent.kuikly.core.file.manager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** D-L1-28/29/30/32/42：FileEditor 校验与保存计划（内容由宿主读取）。 */
class EditorTest {

    private fun mod(cfg: FileManagerConfig = FileManagerConfig()) =
        FileManagerModule(config = cfg, localRoot = "/L", remotePane = FileManagerModule.PaneSpec("srv", "/h"))

    @Test
    fun smallTextAccepted() {
        val s = mod().validateEditor("/h/note.txt", "hello".encodeToByteArray())
        assertNotNull(s)
        assertEquals("hello", s.content.decodeToString())
        assertEquals("/h/note.txt", s.path)
    }

    @Test
    fun tooLargeRejected() {
        assertNull(mod().validateEditor("/h/big.txt", ByteArray(3 * 1024 * 1024) { 'a'.code.toByte() }))
    }

    @Test
    fun binaryRejected() {
        assertNull(mod().validateEditor("/h/bin.dat", byteArrayOf(1, 2, 0, 3)))
    }

    @Test
    fun editorDisabledRejected() {
        assertNull(mod(FileManagerConfig(enableFileEditor = false)).validateEditor("/h/note.txt", "hello".encodeToByteArray()))
    }

    @Test
    fun savePlanUploadsBackToRemotePath() {
        val m = mod()
        val session = m.validateEditor("/h/note.txt", "v2".encodeToByteArray())!!
        val req = m.planEditorSave(session, localTempPath = "/tmp/edit-1")
        assertEquals(TransferDirection.Upload, req.direction)
        assertEquals("/tmp/edit-1", req.localPath)
        assertEquals("/h/note.txt", req.remotePath)
        assertEquals(true, req.overwrite)
    }
}

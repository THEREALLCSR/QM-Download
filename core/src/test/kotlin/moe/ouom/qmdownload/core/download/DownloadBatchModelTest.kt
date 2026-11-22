package moe.ouom.qmdownload.core.download

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 批次模型与路径/ID 规则的测试（统筹方编写）。
 */
class DownloadBatchModelTest {

    private fun tempFile(name: String): File =
        File(System.getProperty("java.io.tmpdir"), "qm_test_${System.nanoTime()}_$name")

    @Test
    fun `batch store round trip and upsert remove`() {
        val file = tempFile("batches.json")
        file.delete()
        try {
            val store = DownloadBatchStore(file)
            assertTrue(store.load().isEmpty(), "空文件应返回空列表")

            val a = DownloadBatch(
                id = "batch-a", playlistId = "123", playlistTitle = "我喜欢",
                subDir = "我喜欢", rootTarget = "/音乐", quality = "lossless", total = 1054,
            )
            val b = a.copy(id = "batch-b", playlistTitle = "进行曲", subDir = "进行曲", total = 39)
            store.save(listOf(a, b))
            assertEquals(listOf("batch-a", "batch-b"), store.load().map { it.id })

            store.upsert(a.copy(total = 2000))
            val loaded = store.load()
            assertEquals(2, loaded.size)
            assertEquals(2000, loaded.first { it.id == "batch-a" }.total)
            assertEquals("进行曲", loaded.first { it.id == "batch-b" }.playlistTitle)

            store.remove("batch-a")
            assertEquals(listOf("batch-b"), store.load().map { it.id })

            // 原子写不应遗留临时文件
            assertEquals(false, File(file.parentFile, "${file.name}.tmp").exists())
        } finally {
            file.delete()
        }
    }

    @Test
    fun `batch store tolerates corrupt json`() {
        val file = tempFile("bad.json")
        file.writeText("{ not json")
        try {
            assertTrue(DownloadBatchStore(file).load().isEmpty())
        } finally {
            file.delete()
        }
    }

    @Test
    fun `subdir name sanitizes playlist title`() {
        assertEquals("我喜欢", DownloadBatchPaths.subDirName("我喜欢"))
        assertEquals("a_b_c", DownloadBatchPaths.subDirName("a:b/c"))
        // sanitize 内部对空白已兜底为"未知"，subDirName 直接复用该结果
        assertEquals("未知", DownloadBatchPaths.subDirName("   "))
        assertTrue(DownloadBatchPaths.subDirName("x".repeat(300)).length <= 120)
    }

    @Test
    fun `resolve dir appends subdir only when present`() {
        // File(root, sub).path 在不同平台分隔符不同，用 File 构造期望值
        assertEquals(
            java.io.File("/音乐", "我喜欢").path,
            DownloadBatchPaths.resolveDir("/音乐", "我喜欢"),
        )
        assertEquals("/音乐", DownloadBatchPaths.resolveDir("/音乐", null))
        assertEquals("/音乐", DownloadBatchPaths.resolveDir("/音乐", "  "))
    }

    @Test
    fun `task id includes batch id to avoid cross batch overwrite`() {
        val track = moe.ouom.qmdownload.core.model.QQMusicTrack(
            songMid = "mid1", mediaMid = null, title = "t", artist = "a",
            album = null, coverUrl = null, durationMs = 0L,
        )
        val legacy = DownloadTaskRecord.fromTrack(track, "lossless", "/音乐")
        assertEquals("mid1-lossless", legacy.id)

        val batchA = DownloadTaskRecord.fromTrack(track, "lossless", "/音乐", "batch-a", "我喜欢", 0)
        val batchB = DownloadTaskRecord.fromTrack(track, "lossless", "/音乐", "batch-b", "进行曲", 3)
        assertEquals("batch-a:mid1:lossless", batchA.id)
        assertEquals("batch-b:mid1:lossless", batchB.id)
        assertTrue(batchA.id != batchB.id, "同歌跨批次必须生成不同任务 ID")
        assertEquals("我喜欢", batchA.subDir)
        assertEquals(3, batchB.seq)
    }
}

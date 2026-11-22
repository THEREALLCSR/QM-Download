package moe.ouom.qmdownload.core.download

import java.io.File
import moe.ouom.qmdownload.core.download.DownloadTaskStatus.COMPLETED
import moe.ouom.qmdownload.core.download.DownloadTaskStatus.DOWNLOADING
import moe.ouom.qmdownload.core.download.DownloadTaskStatus.FAILED
import moe.ouom.qmdownload.core.download.DownloadTaskStatus.PAUSED
import moe.ouom.qmdownload.core.download.DownloadTaskStatus.QUEUED
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 登录过期整队列暂停 / 恢复的纯逻辑测试（core 层）。
 */
class DownloadAuthPauseTest {

    private fun tempFile(name: String): File =
        File(System.getProperty("java.io.tmpdir"), "qm_auth_pause_${System.nanoTime()}_$name")

    private fun task(
        id: String,
        status: DownloadTaskStatus = QUEUED,
        pausedByAuth: Boolean = false,
        downloadedBytes: Long = 0L,
        retryCount: Int = 0,
        batchId: String? = null,
        seq: Int = 0,
        error: String? = null,
        updatedAt: Long = 1_000L,
    ): DownloadTaskRecord = DownloadTaskRecord(
        id = id,
        songMid = "mid-$id",
        mediaMid = null,
        title = "title-$id",
        artist = "artist",
        album = null,
        coverUrl = null,
        durationMs = 1_000L,
        quality = "lossless",
        target = "/音乐",
        status = status,
        pausedByAuth = pausedByAuth,
        downloadedBytes = downloadedBytes,
        retryCount = retryCount,
        error = error,
        updatedAt = updatedAt,
        batchId = batchId,
        seq = seq,
    )

    @Test
    fun `pauseAll marks active tasks and keeps manual paused and terminal ones`() {
        val now = 9_000L
        val queued = task("q", status = QUEUED, downloadedBytes = 10L, retryCount = 2, batchId = "b1", seq = 3)
        val downloading = task("d", status = DOWNLOADING, downloadedBytes = 50L)
        val manualPaused = task("p", status = PAUSED, pausedByAuth = false, downloadedBytes = 7L)
        val authPaused = task("ap", status = PAUSED, pausedByAuth = true, downloadedBytes = 8L)
        val completed = task("c", status = COMPLETED)
        val failed = task("f", status = FAILED, error = "boom")

        val result = DownloadAuthPause.pauseAll(
            listOf(queued, downloading, manualPaused, authPaused, completed, failed),
            now,
        )

        assertEquals(listOf("q", "d", "p", "ap", "c", "f"), result.map { it.id }, "顺序必须不变")

        val rq = result[0]
        assertEquals(PAUSED, rq.status)
        assertEquals(true, rq.pausedByAuth)
        assertEquals(10L, rq.downloadedBytes, "其余字段原样保留")
        assertEquals(2, rq.retryCount)
        assertEquals("b1", rq.batchId)
        assertEquals(3, rq.seq)
        assertEquals(now, rq.updatedAt)

        val rd = result[1]
        assertEquals(PAUSED, rd.status)
        assertEquals(true, rd.pausedByAuth)
        assertEquals(50L, rd.downloadedBytes)
        assertEquals(now, rd.updatedAt)

        // 已是 PAUSED 的（含用户手动暂停与已打标的）一律原样返回，不覆盖 pausedByAuth
        assertSame(manualPaused, result[2])
        assertSame(authPaused, result[3])
        assertSame(completed, result[4])
        assertSame(failed, result[5])
    }

    @Test
    fun `resumable selects only auth paused tasks in original order`() {
        val a = task("a", status = PAUSED, pausedByAuth = true)
        val manual = task("m", status = PAUSED, pausedByAuth = false)
        val c = task("c", status = PAUSED, pausedByAuth = true)
        val queued = task("q", status = QUEUED, pausedByAuth = true)
        val downloading = task("d", status = DOWNLOADING, pausedByAuth = true)
        val failed = task("f", status = FAILED, pausedByAuth = true)
        val completed = task("done", status = COMPLETED, pausedByAuth = true)

        val result = DownloadAuthPause.resumable(
            listOf(a, manual, c, queued, downloading, failed, completed),
        )

        assertEquals(listOf("a", "c"), result.map { it.id })
    }

    @Test
    fun `resumeAll requeues auth paused tasks and clears flag error and updatedAt`() {
        val now = 12_000L
        val authPaused = task(
            "a", status = PAUSED, pausedByAuth = true, error = "登录过期",
            downloadedBytes = 42L, retryCount = 3, batchId = "b", seq = 5, updatedAt = 1L,
        )
        val manualPaused = task("m", status = PAUSED, pausedByAuth = false)
        val queued = task("q", status = QUEUED)

        val result = DownloadAuthPause.resumeAll(listOf(authPaused, manualPaused, queued), now)

        assertEquals(listOf("a", "m", "q"), result.map { it.id }, "顺序必须不变")

        val r = result[0]
        assertEquals(QUEUED, r.status)
        assertEquals(false, r.pausedByAuth)
        assertEquals(null, r.error, "error 必须清空")
        assertEquals(now, r.updatedAt)
        assertEquals(42L, r.downloadedBytes, "下载进度字段保留")
        assertEquals(3, r.retryCount)
        assertEquals("b", r.batchId)
        assertEquals(5, r.seq)
        assertEquals(authPaused.title, r.title)
        assertEquals(authPaused.target, r.target)
        assertEquals(authPaused.quality, r.quality)

        assertSame(manualPaused, result[1], "手动暂停的任务原样返回")
        assertSame(queued, result[2])
    }

    @Test
    fun `clearPausedByAuth only clears the flag`() {
        val original = task("a", status = PAUSED, pausedByAuth = true, error = "x", downloadedBytes = 9L, updatedAt = 5L)

        val cleared = DownloadAuthPause.clearPausedByAuth(original)

        assertEquals(false, cleared.pausedByAuth)
        assertEquals(PAUSED, cleared.status, "只清 flag，不改状态")
        assertEquals("x", cleared.error)
        assertEquals(9L, cleared.downloadedBytes)
        assertEquals(5L, cleared.updatedAt)
        assertEquals(original.copy(pausedByAuth = false), cleared, "其他字段全部不变")

        val alreadyFalse = task("b", status = PAUSED, pausedByAuth = false)
        val clearedAgain = DownloadAuthPause.clearPausedByAuth(alreadyFalse)
        assertEquals(false, clearedAgain.pausedByAuth)
        assertEquals(PAUSED, clearedAgain.status)
    }

    @Test
    fun `pausedByAuth survives task store round trip`() {
        val file = tempFile("tasks.json")
        file.delete()
        try {
            val store = FileDownloadTaskStore(file)
            store.save(listOf(task("a", status = PAUSED, pausedByAuth = true), task("b", status = QUEUED)))

            val raw = file.readText()
            assertTrue(raw.contains("pausedByAuth"), "encode 必须写入 pausedByAuth 字段")

            val loaded = store.load()
            assertEquals(2, loaded.size)
            assertEquals(true, loaded.first { it.id == "a" }.pausedByAuth)
            assertEquals(PAUSED, loaded.first { it.id == "a" }.status)
            assertEquals(false, loaded.first { it.id == "b" }.pausedByAuth)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `legacy json without pausedByAuth decodes to false`() {
        val file = tempFile("legacy.json")
        file.delete()
        try {
            file.writeText(
                """[{"id":"old","songMid":"m","title":"t","artist":"a","target":"/音乐","quality":"lossless","status":"PAUSED"}]""",
            )
            val loaded = FileDownloadTaskStore(file).load()

            assertEquals(1, loaded.size)
            assertEquals(false, loaded[0].pausedByAuth, "旧 JSON 缺字段应解码为 false")
            assertEquals(PAUSED, loaded[0].status)
        } finally {
            file.delete()
        }
    }
}

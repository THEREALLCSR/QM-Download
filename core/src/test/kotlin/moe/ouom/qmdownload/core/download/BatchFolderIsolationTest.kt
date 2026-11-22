package moe.ouom.qmdownload.core.download

import moe.ouom.qmdownload.core.model.QQMusicTrack
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 歌单文件夹归属行为测试（统筹方编写）。
 *
 * 固化两条已向用户确认的语义：
 * 1. 同一歌单的多个批次，文件归到同一个 <下载根目录>/<歌单名>/ 文件夹；
 * 2. 同一首歌出现在不同歌单时，每个歌单对应文件夹下都能各下载一份，互不覆盖。
 *
 * 说明：真正的建目录/写文件在双端 UI 层（Desktop File、Android SAF），
 * core 只提供纯函数 subDirName / resolveDir / 任务 ID 规则；
 * 本测试按生产代码的拼装方式组合这些纯函数，验证落盘路径与任务身份。
 */
class BatchFolderIsolationTest {

    private val root = "/音乐"

    private fun track(songMid: String, title: String = "晴天", artist: String = "周杰伦") =
        QQMusicTrack(
            songMid = songMid, mediaMid = null, title = title, artist = artist,
            album = null, coverUrl = null, durationMs = 240_000L,
        )

    /** 模拟生产代码：<下载根目录>/<歌单子目录>/<歌手-歌曲.ext> */
    private fun targetPath(subDir: String?, fileName: String): String =
        File(DownloadBatchPaths.resolveDir(root, subDir), fileName).path

    @Test
    fun `same playlist across batches resolves to one folder`() {
        // 两次提交的是同一歌单（歌单名相同），即使 batchId 不同、曲目不同、时间不同
        val sub1 = DownloadBatchPaths.subDirName("我喜欢")
        val sub2 = DownloadBatchPaths.subDirName("我喜欢")

        assertEquals(sub1, sub2, "同一歌单名必须解析出相同的子目录名")
        assertEquals(
            DownloadBatchPaths.resolveDir(root, sub1),
            DownloadBatchPaths.resolveDir(root, sub2),
            "同一歌单的多个批次必须落到同一个文件夹",
        )

        // 且两个批次各生成独立任务（同歌单不同批次的曲目不会被去重掉）
        val songA = track("mid-a")
        val songB = track("mid-b", title = "稻香")
        val t1 = DownloadTaskRecord.fromTrack(songA, "flac", root, "batch-1", sub1, 0)
        val t2 = DownloadTaskRecord.fromTrack(songB, "flac", root, "batch-2", sub2, 0)
        assertNotEquals(t1.id, t2.id)
        assertEquals(sub1, t1.subDir)
        assertEquals(sub2, t2.subDir)
        assertEquals(
            targetPath(t1.subDir, DownloadFileNames.build(songA, "flac")).let { File(it).parent },
            targetPath(t2.subDir, DownloadFileNames.build(songB, "flac")).let { File(it).parent },
            "两批曲目的父目录应为同一个歌单文件夹",
        )
    }

    @Test
    fun `same song in different playlists downloads once per folder`() {
        val song = track("mid-shared")
        val fileName = DownloadFileNames.build(song, "flac")
        val subA = DownloadBatchPaths.subDirName("歌单A")
        val subB = DownloadBatchPaths.subDirName("歌单B")

        // 文件名相同（同一首歌），但归属目录不同 -> 两个不同的落盘路径
        assertEquals(fileName, DownloadFileNames.build(track("mid-shared"), "flac"))
        val pathA = targetPath(subA, fileName)
        val pathB = targetPath(subB, fileName)
        assertNotEquals(pathA, pathB, "不同歌单下同名歌曲必须落在不同路径，各自下载一份")
        assertTrue(pathA.endsWith("歌单A${File.separator}$fileName"))
        assertTrue(pathB.endsWith("歌单B${File.separator}$fileName"))

        // 任务 ID 含 batchId，两个批次的同歌任务互不覆盖
        val taskA = DownloadTaskRecord.fromTrack(song, "flac", root, "batch-a", subA, 0)
        val taskB = DownloadTaskRecord.fromTrack(song, "flac", root, "batch-b", subB, 0)
        assertNotEquals(taskA.id, taskB.id, "同歌跨歌单必须是两个独立任务，不能互相覆盖")
        assertEquals(subA, taskA.subDir)
        assertEquals(subB, taskB.subDir)
    }

    @Test
    fun `same song reselected in same playlist converges to same file path`() {
        // 同一歌单内不同批次重复勾选同一首歌：任务独立，但落盘路径相同
        // -> 第二份会被下载器的"已存在且合法则跳过"消化，不会重复占空间
        val song = track("mid-repeat")
        val sub = DownloadBatchPaths.subDirName("我喜欢")
        val fileName = DownloadFileNames.build(song, "flac")
        val t1 = DownloadTaskRecord.fromTrack(song, "flac", root, "batch-1", sub, 0)
        val t2 = DownloadTaskRecord.fromTrack(song, "flac", root, "batch-2", sub, 7)

        assertNotEquals(t1.id, t2.id, "同歌单内重复提交也应生成独立任务，避免互相覆盖")
        assertEquals(
            targetPath(t1.subDir, fileName),
            targetPath(t2.subDir, fileName),
            "同一歌单内同一首歌必须收敛到同一文件路径，才能被跳过逻辑去重",
        )
    }

    @Test
    fun `different playlist titles that sanitize identically merge folders`() {
        // 已知限制固化：不同歌单名清洗后若相同，会合并进同一文件夹。
        // 这条测试是文档性质——提醒后续若改进命名规则，需同步更新此预期与用户说明。
        assertEquals(
            DownloadBatchPaths.subDirName("A/B"),
            DownloadBatchPaths.subDirName("A:B"),
            "A/B 与 A:B 经 sanitize 都变为 A_B，属已知限制",
        )
    }
}

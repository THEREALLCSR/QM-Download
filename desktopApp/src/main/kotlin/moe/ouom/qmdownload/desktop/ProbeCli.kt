package moe.ouom.qmdownload.desktop

import java.io.File
import java.util.Properties
import kotlinx.coroutines.runBlocking
import moe.ouom.qmdownload.core.debug.QmDebugLog
import moe.ouom.qmdownload.core.download.AudioFileValidator
import moe.ouom.qmdownload.core.download.QQMusicDownloader
import moe.ouom.qmdownload.core.model.QQMusicTrack
import moe.ouom.qmdownload.core.playback.QQMusicUrlResolver
import okhttp3.OkHttpClient

/**
 * 调试用 CLI：不启动 UI，直接走与 App 相同的 core 下载链路。
 *
 * 用法：
 *   ./gradlew :desktopApp:probe \
 *     -Pqm.songmid=... -Pqm.mediaMid=... -Pqm.out=C:\temp\probe.flac
 */
fun main() {
    QmDebugLog.enabled = true
    QmDebugLog.sink = { println(it) }

    val sessionPath = System.getProperty("qm.session")
        ?: File(System.getProperty("user.dir"), "data/session.properties").absolutePath
    val songMid = System.getProperty("qm.songmid").orEmpty()
    val mediaMid = System.getProperty("qm.mediaMid").orEmpty().takeIf(String::isNotBlank)
    val quality = System.getProperty("qm.quality", "lossless")
    val out = System.getProperty("qm.out")
        ?: File(System.getProperty("java.io.tmpdir"), "qm-probe-$songMid.flac").absolutePath

    if (songMid.isBlank()) {
        error("缺少 -Dqm.songmid")
    }
    val session = runCatching {
        val properties = Properties().apply { File(sessionPath).inputStream().use(::load) }
        moe.ouom.qmdownload.core.model.QQMusicSession(
            cookie = properties.getProperty("cookie").orEmpty(),
            uin = properties.getProperty("uin").orEmpty(),
            musicKey = properties.getProperty("musicKey").orEmpty(),
        )
    }.getOrElse { error("读取会话失败：$sessionPath / ${it.message}") }

    val client = OkHttpClient()
    val track = QQMusicTrack(
        songMid = songMid,
        mediaMid = mediaMid,
        title = "probe",
        artist = "probe",
        album = null,
        coverUrl = null,
        durationMs = 0L,
    )
    runBlocking {
        val resolver = QQMusicUrlResolver(client) { session }
        val resolved = resolver.resolve(track, quality)
        println("RESOLVED quality=${resolved.qualityKey} ext=${resolved.extension}")
        val target = File(out)
        val downloader = QQMusicDownloader(client)
        downloader.downloadToFile(resolved.url, target) { done, total ->
            if (total != null && total > 0) print("\r${done * 100 / total}%")
        }
        println()
        println("FILE=${target.absolutePath} bytes=${target.length()}")
        AudioFileValidator.requireValid(target, resolved.extension, 0L)
        moe.ouom.qmdownload.core.download.AudioTagWriter.write(target, track, resolved.extension)
        println("AFTER_TAG bytes=${target.length()}")
        println("VALID")
    }
}

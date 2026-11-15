package moe.ouom.qmdownload.core.download

import moe.ouom.qmdownload.core.model.QQMusicTrack

/**
 * 文件命名：对齐 QQ 音乐 PC 的“歌手-歌曲名”格式。
 *
 * 例：`周杰伦-晴天.flac`
 */
object DownloadFileNames {
    private val illegal = Regex("""[<>:"/\\|?*\u0000-\u001F]""")

    fun build(track: QQMusicTrack, extension: String): String {
        val artist = sanitize(track.artist.ifBlank { "未知歌手" })
        val title = sanitize(track.title.ifBlank { "未知歌曲" })
        val ext = extension.trimStart('.').ifBlank { "mp3" }
        return "$artist-$title.$ext"
    }

    fun sanitize(raw: String): String = raw
        .replace(illegal, "_")
        .replace(Regex("""\s+"""), " ")
        .trim()
        .trim('.')
        .take(120)
        .ifBlank { "未知" }
}

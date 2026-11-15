package moe.ouom.qmdownload.android

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import moe.ouom.qmdownload.core.download.AudioFileValidator
import moe.ouom.qmdownload.core.download.AudioTagWriter
import moe.ouom.qmdownload.core.download.DownloadFileNames
import moe.ouom.qmdownload.core.download.DownloadOptions
import moe.ouom.qmdownload.core.download.QQMusicDownloader
import moe.ouom.qmdownload.core.model.QQMusicTrack
import moe.ouom.qmdownload.core.playback.QQMusicUrlResolver

/** Android 下载：先写缓存文件并打标签，再复制到用户选择的 SAF 目录。 */
class AndroidDownloadManager(
    private val context: Context,
    private val resolver: QQMusicUrlResolver,
    private val downloader: QQMusicDownloader,
) {
    suspend fun download(
        track: QQMusicTrack,
        treeUri: Uri,
        qualityKey: String,
        options: DownloadOptions = DownloadOptions(),
        onProgress: (downloaded: Long, total: Long?) -> Unit,
    ): String? {
        val resolved = resolver.resolve(track, qualityKey)
        val fileName = DownloadFileNames.build(track, resolved.extension)
        val tree = DocumentFile.fromTreeUri(context, treeUri)
            ?: error("下载目录不可用")
        // 已存在同名文件时跳过；小于 64KB 的旧占位文件删除后重下
        val existing = tree.findFile(fileName)
        if (existing != null) {
            if (existing.length() >= 64 * 1024L) return null
            existing.delete()
        }
        val temp = File(context.cacheDir, "qm_${System.nanoTime()}.${resolved.extension}")
        try {
            downloader.downloadToFile(resolved.url, temp, options) { done, total ->
                onProgress(done, total)
            }
            AudioFileValidator.requireValid(temp, resolved.extension, track.durationMs)
            AudioTagWriter.write(temp, track, resolved.extension)

            val targetName = uniqueName(tree, fileName)
            val mime = if (resolved.extension == "flac") "audio/flac" else "audio/mpeg"
            val document = tree.createFile(mime, targetName)
                ?: error("无法创建文件：$targetName")
            context.contentResolver.openOutputStream(document.uri)?.use { output ->
                temp.inputStream().use { input -> input.copyTo(output) }
            } ?: error("无法写入文件：$targetName")
            return targetName
        } finally {
            temp.delete()
        }
    }

    private fun uniqueName(tree: DocumentFile, fileName: String): String {
        if (tree.findFile(fileName) == null) return fileName
        val stem = fileName.substringBeforeLast('.', fileName)
        val ext = fileName.substringAfterLast('.', "")
        var index = 1
        while (true) {
            val candidate = if (ext.isBlank()) "$stem($index)" else "$stem($index).$ext"
            if (tree.findFile(candidate) == null) return candidate
            index++
        }
    }
}

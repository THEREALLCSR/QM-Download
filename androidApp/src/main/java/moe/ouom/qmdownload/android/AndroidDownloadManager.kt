package moe.ouom.qmdownload.android

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import moe.ouom.qmdownload.core.download.AudioFileValidator
import moe.ouom.qmdownload.core.download.AudioTagWriter
import moe.ouom.qmdownload.core.download.ByteRateLimiter
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
    /** 全局限速器，由 Application 注入；所有并行任务共用同一实例。 */
    private val limiter: ByteRateLimiter,
) {
    suspend fun download(
        track: QQMusicTrack,
        treeUri: Uri,
        qualityKey: String,
        /** 歌单名清洗后的子目录；空值时平铺到 tree 根（兼容旧任务）。 */
        subDir: String? = null,
        options: DownloadOptions = DownloadOptions(),
        onProgress: (downloaded: Long, total: Long?) -> Unit,
    ): String? {
        val resolved = resolver.resolve(track, qualityKey)
        val fileName = DownloadFileNames.build(track, resolved.extension)
        val tree = DocumentFile.fromTreeUri(context, treeUri)
            ?: error("下载目录不可用")
        // 歌单批次写入子目录；目录不存在则创建。SAF 授权针对整个 tree，子目录权限随之继承
        val folder = if (subDir.isNullOrBlank()) tree
        else tree.findFile(subDir)?.takeIf { it.isDirectory }
            ?: tree.createDirectory(subDir) ?: error("无法创建歌单目录：$subDir")
        // 已存在同名文件时跳过；小于 64KB 的旧占位文件删除后重下
        val existing = folder.findFile(fileName)
        if (existing != null) {
            if (existing.length() >= 64 * 1024L) return null
            existing.delete()
        }
        val temp = File(context.cacheDir, "qm_${System.nanoTime()}.${resolved.extension}")
        try {
            // 注入 Application 的共享限速器，UI 改速率时正在下载的任务立即生效
            val effectiveOptions = options.copy(limiter = limiter)
            downloader.downloadToFile(resolved.url, temp, effectiveOptions) { done, total ->
                onProgress(done, total)
            }
            AudioFileValidator.requireValid(temp, resolved.extension, track.durationMs)
            AudioTagWriter.write(temp, track, resolved.extension)

            val targetName = uniqueName(folder, fileName)
            val mime = if (resolved.extension == "flac") "audio/flac" else "audio/mpeg"
            val document = folder.createFile(mime, targetName)
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

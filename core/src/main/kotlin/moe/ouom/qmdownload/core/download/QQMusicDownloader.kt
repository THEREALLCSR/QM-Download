package moe.ouom.qmdownload.core.download

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import moe.ouom.qmdownload.core.debug.QmDebugLog
import moe.ouom.qmdownload.core.error.QmError
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

data class DownloadOptions(
    /** 是否允许大文件多连接；可在设置中关闭。 */
    val segmented: Boolean = true,
    val maxConnections: Int = 2,
    val minFileBytes: Long = 8L * 1024L * 1024L,
    val maxAttempts: Int = 4,
    /** 全局字节限速器；所有并行任务/分段共用同一实例时总带宽受控。null = 不限速。 */
    val limiter: ByteRateLimiter? = null,
)

/**
 * QQ 音乐下载器（S2）。
 *
 * - 大文件（>= minFileBytes）且服务端支持 Range 时，使用 2–4 连接分段；
 * - 分段失败/服务端不支持 Range 时回退单连接；
 * - 单连接与分段都支持 `.part` 续传、重试、取消；
 * - 完成后原子改名为目标文件。
 */
class QQMusicDownloader(private val httpClient: OkHttpClient) {

    suspend fun downloadToFile(
        url: String,
        target: File,
        options: DownloadOptions = DownloadOptions(),
        onProgress: (downloaded: Long, total: Long?) -> Unit = { _, _ -> },
    ): File = withContext(Dispatchers.IO) {
        target.parentFile?.mkdirs()
        val probe = probe(url)
        QmDebugLog.log(
            "Download",
            "probe total=${probe?.total} ranges=${probe?.ranges} url=${safeUrl(url)}",
        )
        val useSegments = options.segmented &&
            probe != null &&
            probe.ranges &&
            probe.total != null &&
            probe.total >= options.minFileBytes
        if (useSegments) {
            val total = requireNotNull(probe?.total)
            val connections = options.maxConnections.coerceIn(2, 4)
            QmDebugLog.log("Download", "segmented start total=$total conn=$connections")
            val ok = runCatching {
                downloadSegmented(url, target, total, connections, options, onProgress)
            }.onFailure {
                QmDebugLog.log("Download", "segmented failed: ${it.message}")
            }.isSuccess
            if (ok) return@withContext target
            // 分段失败：清理段文件，回退单连接
            deleteSegments(target)
        }
        downloadSingle(url, target, options, onProgress)
    }

    /** 兼容旧调用：直接写入 OutputStream（无断点）。 */
    suspend fun download(
        url: String,
        output: OutputStream,
        onProgress: (downloaded: Long, total: Long?) -> Unit = { _, _ -> },
    ) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "https://y.qq.com/")
            .build()
        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw QmError.Api(response.code, "下载失败 HTTP ${response.code}")
                }
                val total = response.body.contentLength().takeIf { it > 0 }
                var downloaded = 0L
                response.body.byteStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, total)
                    }
                }
                output.flush()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: QmError) {
            throw e
        } catch (e: IOException) {
            throw QmError.Network(e)
        }
    }

    private suspend fun downloadSingle(
        url: String,
        target: File,
        options: DownloadOptions,
        onProgress: (Long, Long?) -> Unit,
    ): File {
        val part = File(target.parentFile, target.name + ".part")
        QmDebugLog.log(
            "Download",
            "single start target=${target.name} partBytes=${if (part.exists()) part.length() else 0}",
        )
        var lastError: Exception? = null
        repeat(options.maxAttempts.coerceAtLeast(1)) { attempt ->
            try {
                transferWithResume(url, part, options.limiter, onProgress)
                if (part.length() <= 0L) throw IOException("下载内容为空")
                return commit(part, target)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                lastError = error
                if (!isRetryable(error) || attempt == options.maxAttempts - 1) {
                    throw mapError(error)
                }
                delay(backoffMs(attempt))
            }
        }
        throw mapError(lastError ?: IOException("下载失败"))
    }

    private suspend fun downloadSegmented(
        url: String,
        target: File,
        total: Long,
        connections: Int,
        options: DownloadOptions,
        onProgress: (Long, Long?) -> Unit,
    ): File {
        val part = File(target.parentFile, target.name + ".part")
        val segmentCount = connections.coerceAtMost(
            ((total + MIN_SEGMENT_BYTES - 1) / MIN_SEGMENT_BYTES).toInt().coerceAtLeast(1)
        )
        val ranges = buildList {
            val chunk = total / segmentCount
            for (index in 0 until segmentCount) {
                val start = index * chunk
                val end = if (index == segmentCount - 1) total - 1 else (start + chunk - 1)
                add(start to end)
            }
        }
        val segmentFiles = ranges.mapIndexed { index, _ ->
            File(target.parentFile, "${target.name}.part.$index")
        }
        val downloaded = AtomicLong(segmentFiles.sumOf { if (it.exists()) it.length() else 0L })
        onProgress(downloaded.get(), total)
        try {
            coroutineScope {
                ranges.mapIndexed { index, range ->
                    async {
                        downloadSegment(
                            url = url,
                            file = segmentFiles[index],
                            start = range.first,
                            end = range.second,
                            options = options,
                            onBytes = { bytes ->
                                val current = downloaded.addAndGet(bytes)
                                onProgress(current, total)
                            },
                        )
                    }
                }.awaitAll()
            }
            // 合并
            val merged = File(target.parentFile, target.name + ".part.merge")
            FileOutputStream(merged, false).use { output ->
                segmentFiles.forEach { file ->
                    file.inputStream().use { input -> input.copyTo(output) }
                }
            }
            if (merged.length() != total) {
                throw IOException("分段合并长度不匹配：${merged.length()}/$total")
            }
            if (part.exists()) part.delete()
            if (!merged.renameTo(part)) {
                merged.copyTo(part, overwrite = true)
                merged.delete()
            }
            segmentFiles.forEach { it.delete() }
            return commit(part, target)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            QmDebugLog.log("Download", "segmented error: ${error.message}")
            throw error
        }
    }

    private suspend fun downloadSegment(
        url: String,
        file: File,
        start: Long,
        end: Long,
        options: DownloadOptions,
        onBytes: (Long) -> Unit,
    ) {
        val expected = end - start + 1
        if (file.exists() && file.length() >= expected) return
        var offset = if (file.exists()) file.length() else 0L
        repeat(options.maxAttempts.coerceAtLeast(1)) { attempt ->
            try {
                val rangeStart = start + offset
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", "https://y.qq.com/")
                    .header("Range", "bytes=$rangeStart-$end")
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    if (response.code != 206) {
                        throw IOException("分段请求未返回 206：${response.code}")
                    }
                    val contentRange = response.header("Content-Range")
                    val match = contentRange?.let {
                        Regex("""bytes\s+(\d+)-(\d+)/(\d+)""").find(it)
                    }
                    val actualStart = match?.groupValues?.getOrNull(1)?.toLongOrNull()
                    if (actualStart != rangeStart) {
                        throw IOException("Content-Range 不匹配：$contentRange")
                    }
                    FileOutputStream(file, offset > 0L).use { output ->
                        response.body.byteStream().use { input ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                options.limiter?.acquire(read.toLong())
                                output.write(buffer, 0, read)
                                offset += read
                                onBytes(read.toLong())
                            }
                        }
                        output.flush()
                    }
                }
                if (file.length() != expected) {
                    throw IOException("分段长度不匹配：${file.length()}/$expected")
                }
                return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (!isRetryable(error) || attempt == options.maxAttempts - 1) {
                    throw mapError(error)
                }
                offset = if (file.exists()) file.length() else 0L
                delay(backoffMs(attempt))
            }
        }
    }

    private suspend fun transferWithResume(
        url: String,
        part: File,
        limiter: ByteRateLimiter?,
        onProgress: (Long, Long?) -> Unit,
    ) {
        var offset = if (part.exists()) part.length() else 0L
        val requestBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "https://y.qq.com/")
        if (offset > 0L) requestBuilder.header("Range", "bytes=$offset-")
        httpClient.newCall(requestBuilder.build()).execute().use { response ->
            if (response.code == 416) {
                part.delete()
                throw IOException("Range 不满足，准备从头下载")
            }
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            var total: Long? = null
            val contentRange = response.header("Content-Range")
            QmDebugLog.log(
                "Download",
                "response code=${response.code} range=$contentRange " +
                    "contentLength=${response.header("Content-Length")} offset=$offset",
            )
            when {
                response.code == 206 && contentRange != null -> {
                    val parsed = parseContentRange(contentRange)
                    if (parsed == null || parsed.first != offset) {
                        part.delete()
                        throw IOException("Content-Range 不匹配")
                    }
                    total = parsed.second
                }
                response.code == 200 -> {
                    offset = 0L
                    total = response.body.contentLength().takeIf { it > 0 }
                    FileOutputStream(part, false).use { }
                }
            }
            var downloaded = offset
            FileOutputStream(part, offset > 0L).use { output ->
                response.body.byteStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        limiter?.acquire(read.toLong())
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, total)
                    }
                }
                output.flush()
            }
            if (total != null && downloaded != total) {
                throw IOException("下载不完整：$downloaded/$total")
            }
        }
    }

    private fun probe(url: String): RemoteInfo? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "https://y.qq.com/")
            .method("HEAD", null)
            .build()
        runCatching {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val total = response.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0 }
                val ranges = response.header("Accept-Ranges")
                    ?.contains("bytes", ignoreCase = true) == true
                if (total != null) return RemoteInfo(total, ranges)
            }
        }
        // HEAD 不可用时，用 Range 0-0 探测
        val rangeRequest = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "https://y.qq.com/")
            .header("Range", "bytes=0-0")
            .build()
        return runCatching {
            httpClient.newCall(rangeRequest).execute().use { response ->
                if (response.code != 206) return null
                val contentRange = response.header("Content-Range") ?: return null
                val match = Regex("""bytes\s+(\d+)-(\d+)/(\d+)""").find(contentRange)
                    ?: return null
                val total = match.groupValues[3].toLongOrNull() ?: return null
                RemoteInfo(total, true)
            }
        }.getOrNull()
    }

    private fun commit(part: File, target: File): File {
        if (target.exists() && !target.delete()) throw IOException("无法覆盖已有文件")
        if (!part.renameTo(target)) {
            part.copyTo(target, overwrite = true)
            part.delete()
        }
        QmDebugLog.log(
            "Download",
            "done target=${target.name} bytes=${target.length()} head=${headHex(target)}",
        )
        return target
    }

    private fun deleteSegments(target: File) {
        target.parentFile?.listFiles()?.forEach { file ->
            if (file.name.startsWith(target.name + ".part.")) file.delete()
        }
    }

    private fun isRetryable(error: Throwable): Boolean = when (error) {
        is QmError.AuthExpired,
        is QmError.NoPermission,
        is QmError.NoResource,
        is QmError.InvalidInput -> false
        else -> true
    }

    private fun mapError(error: Throwable): QmError = when (error) {
        is QmError -> error
        is IOException -> QmError.Network(error)
        else -> QmError.Network(IOException(error.message ?: "下载失败", error))
    }

    private fun backoffMs(attempt: Int): Long =
        (1_000L shl attempt.coerceIn(0, 4)).coerceAtMost(16_000L)

    private fun parseContentRange(value: String): Pair<Long, Long>? {
        val match = Regex("""bytes\s+(\d+)-(\d+)/(\d+)""").find(value) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val total = match.groupValues[3].toLongOrNull() ?: return null
        return start to total
    }

    private fun safeUrl(url: String): String = runCatching {
        val parsed = url.toHttpUrl()
        "${parsed.scheme}://${parsed.host}${parsed.encodedPath}"
    }.getOrDefault("<invalid-url>")

    private fun headHex(file: File): String = runCatching {
        file.inputStream().use { input ->
            val bytes = ByteArray(8)
            val read = input.read(bytes)
            bytes.copyOf(read).joinToString("") { "%02x".format(it) }
        }
    }.getOrDefault("?")

    private data class RemoteInfo(val total: Long, val ranges: Boolean)

    private companion object {
        const val USER_AGENT = "QQMusic 14090008(android 15)"
        const val MIN_SEGMENT_BYTES = 4L * 1024L * 1024L
    }
}

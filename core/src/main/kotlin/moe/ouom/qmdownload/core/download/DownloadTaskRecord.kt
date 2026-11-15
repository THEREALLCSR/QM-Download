package moe.ouom.qmdownload.core.download

import java.io.File
import moe.ouom.qmdownload.core.model.QQMusicTrack
import org.json.JSONArray
import org.json.JSONObject

enum class DownloadTaskStatus {
    QUEUED,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED,
}

data class DownloadTaskRecord(
    val id: String,
    val songMid: String,
    val mediaMid: String?,
    val title: String,
    val artist: String,
    val album: String?,
    val coverUrl: String?,
    val durationMs: Long,
    val quality: String,
    /** Desktop 是绝对目录；Android 是 SAF tree URI。 */
    val target: String,
    val fileName: String? = null,
    val status: DownloadTaskStatus = DownloadTaskStatus.QUEUED,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val retryCount: Int = 0,
    val error: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    fun toTrack(): QQMusicTrack = QQMusicTrack(
        songMid = songMid,
        mediaMid = mediaMid,
        title = title,
        artist = artist,
        album = album,
        coverUrl = coverUrl,
        durationMs = durationMs,
    )

    companion object {
        fun fromTrack(
            track: QQMusicTrack,
            quality: String,
            target: String,
        ): DownloadTaskRecord = DownloadTaskRecord(
            id = "${track.songMid}-$quality",
            songMid = track.songMid,
            mediaMid = track.mediaMid,
            title = track.title,
            artist = track.artist,
            album = track.album,
            coverUrl = track.coverUrl,
            durationMs = track.durationMs,
            quality = quality,
            target = target,
        )
    }
}

/** 跨平台 JSON 任务存储；Desktop 与 Android 都可使用。 */
class FileDownloadTaskStore(private val file: File) {

    @Synchronized
    fun load(): List<DownloadTaskRecord> = runCatching {
        if (!file.exists()) return emptyList()
        val array = JSONArray(file.readText())
        buildList {
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.let { add(decode(it)) }
            }
        }
    }.getOrDefault(emptyList())

    @Synchronized
    fun save(tasks: List<DownloadTaskRecord>) {
        file.parentFile?.mkdirs()
        val array = JSONArray()
        tasks.forEach { array.put(encode(it)) }
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(array.toString())
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
    }

    @Synchronized
    fun upsert(task: DownloadTaskRecord) {
        val tasks = load().toMutableList()
        val index = tasks.indexOfFirst { it.id == task.id }
        if (index >= 0) tasks[index] = task else tasks.add(task)
        save(tasks)
    }

    @Synchronized
    fun remove(id: String) {
        val tasks = load().filterNot { it.id == id }
        save(tasks)
    }

    @Synchronized
    fun pending(): List<DownloadTaskRecord> =
        load().filter {
            it.status == DownloadTaskStatus.QUEUED ||
                it.status == DownloadTaskStatus.PAUSED ||
                it.status == DownloadTaskStatus.DOWNLOADING
        }

    private fun encode(task: DownloadTaskRecord): JSONObject = JSONObject()
        .put("id", task.id)
        .put("songMid", task.songMid)
        .put("mediaMid", task.mediaMid.orEmpty())
        .put("title", task.title)
        .put("artist", task.artist)
        .put("album", task.album.orEmpty())
        .put("coverUrl", task.coverUrl.orEmpty())
        .put("durationMs", task.durationMs)
        .put("quality", task.quality)
        .put("target", task.target)
        .put("fileName", task.fileName.orEmpty())
        .put("status", task.status.name)
        .put("downloadedBytes", task.downloadedBytes)
        .put("totalBytes", task.totalBytes)
        .put("retryCount", task.retryCount)
        .put("error", task.error.orEmpty())
        .put("updatedAt", task.updatedAt)

    private fun decode(json: JSONObject): DownloadTaskRecord = DownloadTaskRecord(
        id = json.optString("id"),
        songMid = json.optString("songMid"),
        mediaMid = json.optString("mediaMid").takeIf(String::isNotBlank),
        title = json.optString("title"),
        artist = json.optString("artist"),
        album = json.optString("album").takeIf(String::isNotBlank),
        coverUrl = json.optString("coverUrl").takeIf(String::isNotBlank),
        durationMs = json.optLong("durationMs", 0L),
        quality = json.optString("quality", "lossless"),
        target = json.optString("target"),
        fileName = json.optString("fileName").takeIf(String::isNotBlank),
        status = runCatching {
            DownloadTaskStatus.valueOf(json.optString("status"))
        }.getOrDefault(DownloadTaskStatus.QUEUED),
        downloadedBytes = json.optLong("downloadedBytes", 0L),
        totalBytes = json.optLong("totalBytes", 0L),
        retryCount = json.optInt("retryCount", 0),
        error = json.optString("error").takeIf(String::isNotBlank),
        updatedAt = json.optLong("updatedAt", System.currentTimeMillis()),
    )
}

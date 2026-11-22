package moe.ouom.qmdownload.core.download

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** 跨平台 JSON 批次存储；仿 [FileDownloadTaskStore]，Desktop 与 Android 都可使用。 */
class DownloadBatchStore(private val file: File) {

    @Synchronized
    fun load(): List<DownloadBatch> = runCatching {
        if (!file.exists()) return emptyList()
        val array = JSONArray(file.readText())
        buildList {
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.let { add(decode(it)) }
            }
        }
    }.getOrDefault(emptyList())

    @Synchronized
    fun save(batches: List<DownloadBatch>) {
        file.parentFile?.mkdirs()
        val array = JSONArray()
        batches.forEach { array.put(encode(it)) }
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(array.toString())
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
    }

    @Synchronized
    fun upsert(batch: DownloadBatch) {
        val batches = load().toMutableList()
        val index = batches.indexOfFirst { it.id == batch.id }
        if (index >= 0) batches[index] = batch else batches.add(batch)
        save(batches)
    }

    @Synchronized
    fun remove(id: String) {
        val batches = load().filterNot { it.id == id }
        save(batches)
    }

    private fun encode(batch: DownloadBatch): JSONObject = JSONObject()
        .put("id", batch.id)
        .put("playlistId", batch.playlistId.orEmpty())
        .put("playlistTitle", batch.playlistTitle)
        .put("subDir", batch.subDir)
        .put("rootTarget", batch.rootTarget)
        .put("quality", batch.quality)
        .put("total", batch.total)
        .put("createdAt", batch.createdAt)

    private fun decode(json: JSONObject): DownloadBatch = DownloadBatch(
        id = json.optString("id"),
        playlistId = json.optString("playlistId").takeIf(String::isNotBlank),
        playlistTitle = json.optString("playlistTitle"),
        subDir = json.optString("subDir"),
        rootTarget = json.optString("rootTarget"),
        quality = json.optString("quality"),
        total = json.optInt("total", 0),
        createdAt = json.optLong("createdAt", System.currentTimeMillis()),
    )
}

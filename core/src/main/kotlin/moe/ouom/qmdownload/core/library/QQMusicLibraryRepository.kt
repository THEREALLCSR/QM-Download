package moe.ouom.qmdownload.core.library

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.qmdownload.core.auth.qqMusicHash33
import moe.ouom.qmdownload.core.error.QmError
import moe.ouom.qmdownload.core.model.QQMusicPlaylist
import moe.ouom.qmdownload.core.model.QQMusicPlaylistTracks
import moe.ouom.qmdownload.core.model.QQMusicSession
import moe.ouom.qmdownload.core.model.QQMusicTrack
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * QQ 音乐歌单只读仓库。
 *
 * 只保留 QM-Download 需要的两个接口：
 * - GetPlaylistByUin：账号下的歌单；
 * - CgiGetDiss：歌单歌曲（分页）。
 *
 * 写接口（新建/加歌/删歌）不在本项目范围内。
 */
class QQMusicLibraryRepository(
    private val httpClient: OkHttpClient,
    private val sessionProvider: () -> QQMusicSession?,
) {

    suspend fun myPlaylists(): List<QQMusicPlaylist> = withContext(Dispatchers.IO) {
        val session = requireSession()
        val payload = musicuPayload(
            session = session,
            module = "music.musicasset.PlaylistBaseRead",
            method = "GetPlaylistByUin",
            param = JSONObject()
                .put("uin", session.uin)
                .put("dirType", 1),
        )
        val data = postMusicu(payload, session)
        val array = data.optJSONArray("v_playlist")
            ?: data.optJSONArray("playlist")
            ?: JSONArray()
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = sequenceOf("tid", "dirId", "dissid", "disstid")
                    .map { item.optString(it) }
                    .firstOrNull(String::isNotBlank)
                    ?: continue
                // 真实响应字段：dirName/songNum/picUrl（2026-11-15 实测）
                val title = sequenceOf("dirName", "dissname", "title", "name")
                    .map { item.optString(it) }
                    .firstOrNull(String::isNotBlank)
                    ?: "未命名歌单"
                val cover = sequenceOf("picUrl", "bigpicUrl", "imgurl", "logo", "cover")
                    .map { item.optString(it) }
                    .firstOrNull(String::isNotBlank)
                    ?.let(::normalizeCoverUrl)
                val dirId = sequenceOf("dirId", "dirid", "tid")
                    .map { item.optLong(it, 0L) }
                    .firstOrNull { it > 0L } ?: 0L
                val ownerUin = item.optString("uin").filter(Char::isDigit)
                add(
                    QQMusicPlaylist(
                        id = id,
                        dirId = dirId,
                        title = title,
                        coverUrl = cover,
                        trackCount = item.optInt(
                            "songNum",
                            item.optInt("songnum", item.optInt("song_num", 0)),
                        ),
                        playCount = item.optLong(
                            "play_cnt",
                            item.optLong("listennum", 0L),
                        ),
                        isOwn = ownerUin.isBlank() || ownerUin == session.uin,
                    )
                )
            }
        }
    }

    suspend fun playlistTracksPage(
        playlistId: String,
        begin: Int = 0,
        limit: Int = 100,
    ): QQMusicPlaylistTracks = withContext(Dispatchers.IO) {
        val session = requireSession()
        val numericId = playlistId.toLongOrNull()
            ?: throw QmError.InvalidInput("无效的歌单 ID：$playlistId")
        val payload = musicuPayload(
            session = session,
            module = "music.srfDissInfo.DissInfo",
            method = "CgiGetDiss",
            param = JSONObject()
                .put("disstid", numericId)
                .put("dirid", 0)
                .put("tag", true)
                .put("song_begin", begin.coerceAtLeast(0))
                .put("song_num", limit.coerceIn(1, 200))
                .put("userinfo", true)
                .put("orderlist", true)
                .put("onlysonglist", false),
        )
        val data = postMusicu(payload, session)
        val songs = data.optJSONArray("songlist") ?: JSONArray()
        val total = sequenceOf("total_song_num", "songnum", "song_num", "total")
            .map { data.optInt(it, 0) }
            .firstOrNull { it > 0 }
            ?: songs.length()
        val tracks = buildList {
            for (index in 0 until songs.length()) {
                val song = songs.optJSONObject(index) ?: continue
                val mid = sequenceOf("mid", "songmid", "songMid")
                    .map { song.optString(it) }
                    .firstOrNull(String::isNotBlank)
                    ?: continue
                val mediaMid = song.optJSONObject("file")
                    ?.optString("media_mid")
                    ?.takeIf(String::isNotBlank)
                val singers = song.optJSONArray("singer")?.let { array ->
                    buildList {
                        for (i in 0 until array.length()) {
                            array.optJSONObject(i)?.optString("name")
                                ?.takeIf(String::isNotBlank)
                                ?.let(::add)
                        }
                    }.joinToString(" / ")
                }.orEmpty()
                val albumObj = song.optJSONObject("album")
                val albumMid = albumObj?.optString("mid").orEmpty()
                add(
                    QQMusicTrack(
                        songMid = mid,
                        mediaMid = mediaMid,
                        title = sequenceOf("name", "title", "songname")
                            .map { song.optString(it) }
                            .firstOrNull(String::isNotBlank) ?: "未知歌曲",
                        artist = singers.ifBlank { "未知艺术家" },
                        album = albumObj?.optString("name")?.takeIf(String::isNotBlank),
                        coverUrl = if (albumMid.isNotBlank()) {
                            "https://y.gtimg.cn/music/photo_new/T002R300x300M000$albumMid.jpg"
                        } else {
                            null
                        },
                        durationMs = song.optLong("interval", 0L) * 1_000L,
                    )
                )
            }
        }
        QQMusicPlaylistTracks(tracks = tracks, total = total)
    }

    /**
     * 完整加载整个歌单：按服务端 total 逐页翻页，按 songMid 去重并保持顺序。
     *
     * 供双端“全选/整歌单下载”使用；原 [playlistTracksPage] 保持为单页接口以兼容。
     * 翻页过程带空页/无新增/不推进/页数与曲目数上限保护，不会无限循环。
     */
    suspend fun allPlaylistTracks(
        playlistId: String,
        pageSize: Int = PlaylistPaging.DEFAULT_PAGE_SIZE,
    ): List<QQMusicTrack> = loadAllPlaylistTracks(
        playlistId = playlistId,
        fetchPage = { id, begin, limit -> playlistTracksPage(id, begin, limit) },
        pageSize = pageSize,
    )

    private fun requireSession(): QQMusicSession =
        sessionProvider()?.takeIf { it.isLoggedIn }
            ?: throw QmError.AuthExpired()

    private fun musicuPayload(
        session: QQMusicSession,
        module: String,
        method: String,
        param: JSONObject,
    ): JSONObject {
        val gtk = qqMusicHash33(session.musicKey)
        return JSONObject()
            .put(
                "comm",
                JSONObject()
                    .put("ct", 24)
                    .put("cv", 0)
                    .put("uin", session.uin)
                    .put("g_tk", gtk)
                    .put("g_tk_new_20200303", gtk)
                    .put("format", "json")
                    .put("platform", "yqq.json")
                    .put("chid", "0")
                    .put("need_new_code", 1)
                    .put("inCharset", "utf-8")
                    .put("outCharset", "utf-8"),
            )
            .put(
                "req_0",
                JSONObject()
                    .put("module", module)
                    .put("method", method)
                    .put("param", param),
            )
    }

    private fun postMusicu(payload: JSONObject, session: QQMusicSession): JSONObject {
        val request = Request.Builder()
            .url(MUSICU_URL)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "https://y.qq.com/")
            .header("Accept", "application/json, text/plain, */*")
            .header("Cookie", session.cookie)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw QmError.Api(response.code, "HTTP ${response.code}")
                val root = JSONObject(response.body.string())
                val req = root.optJSONObject("req_0")
                    ?: throw QmError.Api(root.optInt("code", -1), "缺少 req_0")
                val code = req.optInt("code", -1)
                when (code) {
                    0 -> return req.optJSONObject("data") ?: JSONObject()
                    1000, 2001 -> throw QmError.AuthExpired()
                    else -> throw QmError.Api(code, req.optString("msg"))
                }
            }
        } catch (e: QmError) {
            throw e
        } catch (e: IOException) {
            throw QmError.Network(e)
        }
    }

    private fun normalizeCoverUrl(raw: String): String = when {
        raw.startsWith("http://") -> "https://${raw.removePrefix("http://")}"
        raw.startsWith("https://") -> raw
        raw.startsWith("//") -> "https:$raw"
        else -> raw
    }

    private companion object {
        const val MUSICU_URL = "https://u.y.qq.com/cgi-bin/musicu.fcg"
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

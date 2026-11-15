package moe.ouom.qmdownload.core.playback

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.qmdownload.core.auth.qqMusicHash33
import moe.ouom.qmdownload.core.debug.QmDebugLog
import moe.ouom.qmdownload.core.error.QmError
import moe.ouom.qmdownload.core.model.QQMusicSession
import moe.ouom.qmdownload.core.model.QQMusicTrack
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class ResolvedAudio(
    val url: String,
    val qualityKey: String,
    val extension: String,
)

private data class QualityCandidate(
    val prefix: String,
    val extension: String,
    val qualityKey: String,
)

private data class IdentityProfile(
    val label: String,
    val comm: JSONObject,
)

private val CANDIDATES = listOf(
    QualityCandidate("F000", "flac", "lossless"),
    QualityCandidate("M800", "mp3", "exhigh"),
    QualityCandidate("M500", "mp3", "standard"),
)

/**
 * VKey 地址解析（方案 B）。
 *
 * 身份策略：
 * 1. NeriPlayer 验证过的 Android 身份：ct=11 + authst=musicKey；
 * 2. Web 身份回退：ct=24 + g_tk。
 *
 * 降级策略：某一档 result!=0 / purl 为空 → 继续下一档，不能整体失败。
 */
class QQMusicUrlResolver(
    private val httpClient: OkHttpClient,
    private val sessionProvider: () -> QQMusicSession?,
) {
    suspend fun resolve(
        track: QQMusicTrack,
        qualityKey: String = "lossless",
    ): ResolvedAudio = withContext(Dispatchers.IO) {
        val session = sessionProvider()?.takeIf { it.isLoggedIn }
            ?: throw QmError.AuthExpired()
        val effectiveMediaMid = track.mediaMid?.takeIf(String::isNotBlank)
            ?: runCatching { fetchMediaMid(track.songMid, session) }.getOrNull()
        val candidates = candidatesFor(qualityKey)
        val profiles = identityProfiles(session)
        var lastError: QmError? = null
        QmDebugLog.log(
            "VKey",
            "resolve songMid=${track.songMid} mediaMid=${effectiveMediaMid.orEmpty()} " +
                "quality=$qualityKey candidates=${candidates.map { it.prefix }} " +
                "profiles=${profiles.map { it.label }} uin=${session.uin}",
        )

        for (profile in profiles) {
            for (candidate in candidates) {
                try {
                    val url = requestVkey(track, effectiveMediaMid, candidate, profile, session)
                    if (!url.isNullOrBlank()) {
                        QmDebugLog.log("VKey", "hit ${profile.label}/${candidate.prefix} url=${safeUrl(url)}")
                        return@withContext ResolvedAudio(
                            url = url,
                            qualityKey = candidate.qualityKey,
                            extension = candidate.extension,
                        )
                    }
                    QmDebugLog.log("VKey", "miss ${profile.label}/${candidate.prefix} (no purl)")
                } catch (auth: QmError.AuthExpired) {
                    // 这个身份被拒，换下一个身份
                    QmDebugLog.log("VKey", "auth expired ${profile.label}/${candidate.prefix}")
                    lastError = auth
                } catch (error: QmError) {
                    QmDebugLog.log("VKey", "error ${profile.label}/${candidate.prefix}: ${error.message}")
                    lastError = error
                }
            }
        }
        throw lastError ?: QmError.NoResource("当前歌曲没有可用音质（可能无版权或无下载权限）")
    }

    private fun identityProfiles(session: QQMusicSession): List<IdentityProfile> {
        val android = JSONObject()
            .put("ct", 11)
            .put("cv", 14_090_008)
            .put("v", 14_090_008)
            .put("qq", session.uin.ifBlank { "0" })
            .put("authst", session.musicKey)
            .put("tmeAppID", "qqmusic")
            .put("format", "json")
        val webGtk = qqMusicHash33(session.musicKey)
        val web = JSONObject()
            .put("ct", 24)
            .put("cv", 0)
            .put("uin", session.uin.ifBlank { "0" })
            .put("g_tk", webGtk)
            .put("g_tk_new_20200303", webGtk)
            .put("format", "json")
            .put("platform", "yqq.json")
            .put("chid", "0")
        return listOf(
            IdentityProfile("android", android),
            IdentityProfile("web", web),
        )
    }

    private fun requestVkey(
        track: QQMusicTrack,
        mediaMid: String?,
        candidate: QualityCandidate,
        profile: IdentityProfile,
        session: QQMusicSession,
    ): String? {
        val media = mediaMid?.takeIf(String::isNotBlank) ?: track.songMid
        val fileName = "${candidate.prefix}$media.${candidate.extension}"
        val guid = session.wid.takeIf(String::isNotBlank)
            ?: session.uin.ifBlank { "0" }
        val param = JSONObject()
            .put("uin", session.uin.ifBlank { "0" })
            .put("guid", guid)
            .put("songmid", JSONArray().put(track.songMid))
            .put("songtype", JSONArray().put(0))
            .put("filename", JSONArray().put(fileName))
            .put("vecPrefix", JSONArray().put(candidate.prefix))
            .put("downloadfrom", 0)
            .put("source", "search")
            .put("ctx", 0)
        val payload = JSONObject()
            .put("comm", profile.comm)
            .put(
                "req_0",
                JSONObject()
                    .put("module", "music.vkey.GetVkey")
                    .put("method", "UrlGetVkey")
                    .put("param", param),
            )
        val request = Request.Builder()
            .url(MUSICU_URL)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "https://y.qq.com/")
            .header("Cookie", session.cookie)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw QmError.Api(response.code, "HTTP ${response.code}")
                }
                val root = JSONObject(response.body.string())
                val req = root.optJSONObject("req_0")
                    ?: throw QmError.Api(root.optInt("code", -1), "缺少 req_0")
                val code = req.optInt("code", -1)
                when (code) {
                    0 -> Unit
                    1000, 2001 -> throw QmError.AuthExpired()
                    else -> throw QmError.Api(code, req.optString("msg"))
                }
                val data = req.optJSONObject("data") ?: return null
                val info = data.optJSONArray("midurlinfo")?.optJSONObject(0) ?: return null
                val result = info.optInt("result", 0)
                QmDebugLog.log(
                    "VKey",
                    "resp ${profile.label}/${candidate.prefix} code=$code result=$result " +
                        "retcode=${data.optInt("retcode", 0)} msg=${data.optString("msg").take(120)}",
                )
                // 该档无权限/无资源：返回 null，让外层继续降级
                if (result != 0) return null
                val purl = info.optString("purl").trim()
                if (purl.isBlank()) return null
                if (purl.startsWith("http://") || purl.startsWith("https://")) {
                    return upgradeHttps(purl)
                }
                val sip = data.optJSONArray("sip") ?: return null
                val domain = (0 until sip.length())
                    .mapNotNull { index -> sip.optString(index).trim().takeIf(String::isNotBlank) }
                    .firstOrNull { !it.startsWith("http://ws", ignoreCase = true) }
                    ?: (0 until sip.length())
                        .mapNotNull { index -> sip.optString(index).trim().takeIf(String::isNotBlank) }
                        .firstOrNull()
                    ?: "https://isure.stream.qqmusic.qq.com/"
                return upgradeHttps(domain + purl)
            }
        } catch (e: QmError) {
            throw e
        } catch (e: IOException) {
            throw QmError.Network(e)
        }
    }

    /** 搜索结果可能没有 media_mid，高音质必须先补查详情。 */
    private fun fetchMediaMid(songMid: String, session: QQMusicSession): String? {
        val payload = JSONObject()
            .put(
                "comm",
                JSONObject()
                    .put("ct", 24)
                    .put("cv", 0)
                    .put("uin", session.uin.ifBlank { "0" }),
            )
            .put(
                "req_0",
                JSONObject()
                    .put("module", "music.pf_song_detail_svr")
                    .put("method", "get_song_detail_yqq")
                    .put("param", JSONObject().put("song_mid", songMid)),
            )
        val request = Request.Builder()
            .url(MUSICU_URL)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "https://y.qq.com/")
            .header("Cookie", session.cookie)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val req = JSONObject(response.body.string()).optJSONObject("req_0") ?: return null
            if (req.optInt("code", -1) != 0) return null
            val track = req.optJSONObject("data")?.optJSONObject("track_info") ?: return null
            return track.optJSONObject("file")?.optString("media_mid")?.takeIf(String::isNotBlank)
        }
    }

    private fun candidatesFor(qualityKey: String): List<QualityCandidate> =
        when (qualityKey.trim().lowercase()) {
            "lossless", "hires" -> CANDIDATES
            "exhigh", "higher" -> CANDIDATES.drop(1)
            else -> CANDIDATES.drop(2)
        }

    private fun upgradeHttps(url: String): String =
        if (url.startsWith("http://", ignoreCase = true)) {
            "https://${url.substringAfter("://")}"
        } else {
            url
        }

    private fun safeUrl(url: String): String = runCatching {
        val parsed = url.toHttpUrl()
        "${parsed.scheme}://${parsed.host}${parsed.encodedPath}"
    }.getOrDefault("<invalid-url>")

    private companion object {
        const val MUSICU_URL = "https://u.y.qq.com/cgi-bin/musicu.fcg"
        const val USER_AGENT = "QQMusic 14090008(android 15)"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

package moe.ouom.qmdownload.core.profile

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.qmdownload.core.auth.qqMusicHash33
import moe.ouom.qmdownload.core.error.QmError
import moe.ouom.qmdownload.core.model.QQMusicAccountProfile
import moe.ouom.qmdownload.core.model.QQMusicSession
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** QQ 音乐账号资料（昵称/头像）。 */
class QQMusicProfileRepository(private val httpClient: OkHttpClient) {

    suspend fun load(session: QQMusicSession): QQMusicAccountProfile? =
        withContext(Dispatchers.IO) {
            if (!session.isLoggedIn) return@withContext null
            val url = PROFILE_URL.toHttpUrl().newBuilder()
                .addQueryParameter("g_tk", qqMusicHash33(session.musicKey).toString())
                .addQueryParameter("format", "json")
                .addQueryParameter("inCharset", "utf-8")
                .addQueryParameter("outCharset", "utf-8")
                .addQueryParameter("notice", "0")
                .addQueryParameter("cid", "205360838")
                .addQueryParameter("needNewCode", "0")
                .addQueryParameter("loginUin", session.uin)
                .addQueryParameter("hostUin", "0")
                .addQueryParameter("userid", session.uin)
                .addQueryParameter("reqfrom", "1")
                .build()
            val request = Request.Builder()
                .url(url)
                .header("Cookie", session.cookie)
                .header("Referer", "https://y.qq.com/")
                .header("User-Agent", USER_AGENT)
                .build()
            try {
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw QmError.Api(response.code, "HTTP ${response.code}")
                    }
                    val root = JSONObject(response.body.string())
                    if (root.optInt("code", -1) != 0) {
                        throw QmError.Api(root.optInt("code", -1), root.optString("msg"))
                    }
                    val data = root.optJSONObject("data") ?: root
                    val creator = data.optJSONObject("creator")
                        ?: data.optJSONObject("user")
                        ?: data.optJSONObject("profile")
                    val nickname = sequenceOf(
                        creator?.optString("nick"),
                        creator?.optString("nickname"),
                        data.optString("nick"),
                    ).firstOrNull { !it.isNullOrBlank() } ?: "QQ ${session.uin}"
                    val avatar = sequenceOf(
                        creator?.optString("headpic"),
                        creator?.optString("avatarUrl"),
                        data.optString("headpic"),
                    ).firstOrNull { !it.isNullOrBlank() }?.let(::normalize)
                    QQMusicAccountProfile(
                        nickname = nickname,
                        avatarUrl = avatar,
                    )
                }
            } catch (e: QmError) {
                throw e
            } catch (e: IOException) {
                throw QmError.Network(e)
            }
        }

    private fun normalize(raw: String): String = when {
        raw.startsWith("http://") -> "https://${raw.removePrefix("http://")}"
        raw.startsWith("//") -> "https:$raw"
        else -> raw
    }

    private companion object {
        const val PROFILE_URL = "https://c6.y.qq.com/rsc/fcgi-bin/fcg_get_profile_homepage.fcg"
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}

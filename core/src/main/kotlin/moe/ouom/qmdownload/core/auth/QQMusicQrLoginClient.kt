package moe.ouom.qmdownload.core.auth

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * File: moe.ouom.qmdownload.core.auth/QQMusicQrLoginClient
 */

import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * QQ 音乐扫码登录。
 *
 * 这套流程不是标准 OAuth，而是 QQ 互联的"四跳"：
 *
 * ```
 * ① ptqrshow     拿二维码图片 + qrsig（Cookie）
 * ② ptqrlogin    轮询扫码状态，成功时返回 uin + ptsigx
 * ③ check_sig    用 ptsigx 换 p_skey，再用 p_skey 算 g_tk 去 graph.qq.com 换 code
 * ④ musicu       用 code 换 QQ 音乐的 musicid + musickey（真正的播放凭据）
 * ```
 *
 * 最终我们把上一步拿到的凭据拼成一条 Cookie 串交给 [QQMusicSessionStore]，
 * 后续所有业务请求（歌单/播放/评论）都复用它。
 *
 * 说明：协议细节在 MeloX 项目中已用真实账号验证；这里做了精简（只保留 QQ 扫码，
 * 不含微信扫码），并把网络调用收敛到两个公开方法，便于将来加单元测试或替换实现。
 */
class QQMusicQrLoginClient(
    private val baseHttpClient: OkHttpClient,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    /** 二维码有效期：QQ 官方是 2 分钟，这里本地也设 2 分钟，避免无意义轮询。 */
    private val qrLifetimeMs = 2L * 60L * 1_000L

    /**
     * 第 ① 步：申请一张登录二维码。
     *
     * @return 包含 PNG 字节、qrsig 与过期时间的会话对象。
     */
    suspend fun createSession(): QQMusicQrLoginSession = withContext(Dispatchers.IO) {
        val cookieJar = QQMusicCookieJar()
        val client = baseHttpClient.newBuilder()
            .cookieJar(cookieJar)
            // 关键：登录流程的跳转全靠自己读 302 的 Location，绝不能自动跟随。
            .followRedirects(false)
            .followSslRedirects(false)
            .callTimeout(35, TimeUnit.SECONDS)
            .readTimeout(35, TimeUnit.SECONDS)
            .build()

        val url = PTQR_SHOW_URL.toHttpUrl().newBuilder()
            .addQueryParameter("appid", QQ_APP_ID)
            .addQueryParameter("e", "2")
            .addQueryParameter("l", "M")
            .addQueryParameter("s", "3")
            .addQueryParameter("d", "72")
            .addQueryParameter("v", "4")
            .addQueryParameter("t", (nowMillis() * 1_000_000L).toString())
            .addQueryParameter("daid", QQ_DAID)
            .addQueryParameter("pt_3rd_aid", QQ_MUSIC_OAUTH_APP_ID)
            .build()

        val request = Request.Builder()
            .url(url)
            .header("Referer", "https://xui.ptlogin2.qq.com/")
            .header("User-Agent", USER_AGENT)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("获取 QQ 登录二维码失败：HTTP ${response.code}")
            // qrsig 是服务端通过 Set-Cookie 下发的，必须从我们自己的 CookieJar 里取。
            val qrsig = cookieJar.loadForRequest(url)
                .firstOrNull { it.name == "qrsig" }
                ?.value
                ?: throw IOException("QQ 登录服务没有返回二维码标识")
            val image = response.body.bytes()
            if (image.isEmpty()) throw IOException("读取 QQ 登录二维码失败")
            QQMusicQrLoginSession(
                imageBytes = image,
                qrsig = qrsig,
                expiresAtMillis = nowMillis() + qrLifetimeMs,
                httpClient = client,
                cookieJar = cookieJar,
            )
        }
    }

    /**
     * 第 ② 步：轮询扫码状态。
     *
     * 扫码成功时会自动走完 ③④ 两步，直接返回 [QQMusicQrLoginState.Authorized]，
     * 调用方只需要关心"要不要继续轮询"。
     */
    suspend fun poll(session: QQMusicQrLoginSession): QQMusicQrLoginState =
        withContext(Dispatchers.IO) {
            if (nowMillis() >= session.expiresAtMillis) return@withContext QQMusicQrLoginState.Expired

            val step = pollScanStatus(session)
            when (step.event) {
                QQMusicQrLoginEvent.Waiting -> QQMusicQrLoginState.Waiting
                QQMusicQrLoginEvent.Scanned -> QQMusicQrLoginState.Scanned
                QQMusicQrLoginEvent.Expired -> QQMusicQrLoginState.Expired
                QQMusicQrLoginEvent.Rejected -> QQMusicQrLoginState.Rejected
                QQMusicQrLoginEvent.Connected -> {
                    val credential = exchangeForCredential(session, step.uin, step.sigX)
                    QQMusicQrLoginState.Authorized(credential.toCookie())
                }
            }
        }

    // ── ② 轮询扫码结果 ───────────────────────────────────────────────────────

    private fun pollScanStatus(session: QQMusicQrLoginSession): QQMusicQrStep {
        val url = PTQR_LOGIN_URL.toHttpUrl().newBuilder()
            .addQueryParameter("u1", "https://graph.qq.com/oauth2.0/login_jump")
            // ptqrtoken = hash33(qrsig, seed = 0)，注意不是 5381。
            .addQueryParameter("ptqrtoken", qqMusicHash33(session.qrsig, seed = 0).toString())
            .addQueryParameter("ptredirect", "0")
            .addQueryParameter("h", "1")
            .addQueryParameter("t", "1")
            .addQueryParameter("g", "1")
            .addQueryParameter("from_ui", "1")
            .addQueryParameter("ptlang", "2052")
            .addQueryParameter("action", "0-0-${nowMillis()}")
            .addQueryParameter("js_ver", "20102616")
            .addQueryParameter("js_type", "1")
            .addQueryParameter("pt_uistyle", "40")
            .addQueryParameter("aid", QQ_APP_ID)
            .addQueryParameter("daid", QQ_DAID)
            .addQueryParameter("pt_3rd_aid", QQ_MUSIC_OAUTH_APP_ID)
            .addQueryParameter("has_onekey", "1")
            .build()

        val request = Request.Builder()
            .url(url)
            .header("Referer", "https://xui.ptlogin2.qq.com/")
            .header("User-Agent", USER_AGENT)
            .build()

        return session.httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("查询 QQ 扫码状态失败：HTTP ${response.code}")
            parsePtuiCallback(response.body.string())
        }
    }

    // ── ③④ 授权并换取音乐凭据 ────────────────────────────────────────────────

    private fun exchangeForCredential(
        session: QQMusicQrLoginSession,
        uin: String,
        sigX: String,
    ): QQMusicCredential {
        // ③-1：check_sig 会把 p_skey 通过 Set-Cookie 种到 graph.qq.com 下。
        val checkUrl = CHECK_SIG_URL.toHttpUrl().newBuilder()
            .addQueryParameter("uin", uin)
            .addQueryParameter("pttype", "1")
            .addQueryParameter("service", "ptqrlogin")
            .addQueryParameter("nodirect", "0")
            .addQueryParameter("ptsigx", sigX)
            .addQueryParameter("s_url", "https://graph.qq.com/oauth2.0/login_jump")
            .addQueryParameter("ptlang", "2052")
            .addQueryParameter("ptredirect", "100")
            .addQueryParameter("aid", QQ_APP_ID)
            .addQueryParameter("daid", QQ_DAID)
            .addQueryParameter("pt_3rd_aid", QQ_MUSIC_OAUTH_APP_ID)
            .build()
        session.httpClient.newCall(
            Request.Builder()
                .url(checkUrl)
                .header("Referer", "https://xui.ptlogin2.qq.com/")
                .header("User-Agent", USER_AGENT)
                .build(),
        ).execute().use { response ->
            if (response.code >= 400) throw IOException("QQ 扫码凭据校验失败：HTTP ${response.code}")
        }

        val graphUrl = "https://graph.qq.com/".toHttpUrl()
        val pSKey = session.cookieJar.loadForRequest(graphUrl)
            .firstOrNull { it.name == "p_skey" }
            ?.value
            .orEmpty()
        if (pSKey.isBlank()) throw IOException("QQ 扫码校验没有返回授权票据")

        // ③-2：用 p_skey 算 g_tk（seed=5381），换取一次性 code。
        val form = FormBody.Builder()
            .add("response_type", "code")
            .add("client_id", QQ_MUSIC_OAUTH_APP_ID)
            .add("redirect_uri", "https://y.qq.com/portal/wx_redirect.html?login_type=1&surl=https://y.qq.com/")
            .add("scope", "get_user_info,get_app_friends")
            .add("state", "state")
            .add("switch", "")
            .add("from_ptlogin", "1")
            .add("src", "1")
            .add("update_auth", "1")
            .add("openapi", "1010_1030")
            .add("g_tk", qqMusicHash33(pSKey, seed = 5381).toString())
            .add("auth_time", (nowMillis() / 1_000L * 1_000L).toString())
            .add("ui", randomHex(16))
            .build()
        val code = session.httpClient.newCall(
            Request.Builder()
                .url("https://graph.qq.com/oauth2.0/authorize")
                .post(form)
                .header("Referer", "https://graph.qq.com/oauth2.0/login_jump")
                .header("User-Agent", USER_AGENT)
                .build(),
        ).execute().use { response ->
            val location = response.header("Location") ?: throw IOException("QQ 授权没有返回登录码")
            location.toHttpUrlOrNull()?.queryParameter("code")
                ?.takeIf(String::isNotBlank)
                ?: throw IOException("QQ 授权没有返回登录码")
        }

        // ④：用 code 换 musicid + musickey。
        val payload = JSONObject()
            .put(
                "comm",
                JSONObject()
                    .put("ct", 24)
                    .put("cv", 4_747_474)
                    .put("platform", "yqq.json")
                    .put("chid", "0")
                    .put("uin", 0)
                    .put("g_tk", 5381)
                    .put("g_tk_new_20200303", 5381)
                    .put("format", "json")
                    .put("inCharset", "utf-8")
                    .put("outCharset", "utf-8")
                    .put("notice", 0)
                    .put("needNewCode", 1)
                    .put("tmeLoginType", 2),
            )
            .put(
                "req_0",
                JSONObject()
                    .put("module", "QQConnectLogin.LoginServer")
                    .put("method", "QQLogin")
                    .put("param", JSONObject().put("code", code)),
            )

        val credential = session.httpClient.newCall(
            Request.Builder()
                .url(MUSICU_URL)
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .header("Referer", "https://y.qq.com/")
                .header("User-Agent", USER_AGENT)
                .build(),
        ).execute().use { response ->
            val root = runCatching { JSONObject(response.body.string()) }
                .getOrElse { throw IOException("QQ 音乐登录响应解析失败", it) }
            val requestResult = root.optJSONObject("req_0") ?: JSONObject()
            val resultCode = requestResult.optInt("code", -1)
            if (!response.isSuccessful || root.optInt("code", -1) != 0 || resultCode != 0) {
                throw IOException("QQ 音乐拒绝了扫码登录（错误码 $resultCode）")
            }
            parseCredential(requestResult.optJSONObject("data") ?: JSONObject())
        }

        if (!credential.isUsable) throw IOException("QQ 音乐登录成功但没有返回可用播放凭据")
        return credential
    }

    private companion object {
        const val QQ_APP_ID = "716027609"
        const val QQ_MUSIC_OAUTH_APP_ID = "100497308"
        const val QQ_DAID = "383"
        const val PTQR_SHOW_URL = "https://ssl.ptlogin2.qq.com/ptqrshow"
        const val PTQR_LOGIN_URL = "https://ssl.ptlogin2.qq.com/ptqrlogin"
        const val CHECK_SIG_URL = "https://ssl.ptlogin2.graph.qq.com/check_sig"
        const val MUSICU_URL = "https://u.y.qq.com/cgi-bin/musicu.fcg"
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36 NeriPlayer"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun randomHex(byteCount: Int): String {
            val bytes = ByteArray(byteCount)
            java.security.SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    }
}

/** 扫码会话：一张二维码 + 它对应的 Cookie 罐。 */
class QQMusicQrLoginSession internal constructor(
    val imageBytes: ByteArray,
    internal val qrsig: String,
    internal val expiresAtMillis: Long,
    internal val httpClient: OkHttpClient,
    internal val cookieJar: QQMusicCookieJar,
)

/** 扫码过程中的状态，UI 直接按它显示文案。 */
sealed interface QQMusicQrLoginState {
    data object Waiting : QQMusicQrLoginState
    data object Scanned : QQMusicQrLoginState
    data object Expired : QQMusicQrLoginState
    data object Rejected : QQMusicQrLoginState

    /** 登录成功，[cookie] 可以直接交给 QQMusicSessionStore.save()。 */
    data class Authorized(val cookie: String) : QQMusicQrLoginState
}

internal enum class QQMusicQrLoginEvent { Waiting, Scanned, Connected, Expired, Rejected }

internal data class QQMusicQrStep(
    val event: QQMusicQrLoginEvent,
    val uin: String = "",
    val sigX: String = "",
)

/**
 * 解析 QQ 登录服务返回的 `ptuiCB('67','0','','0','二维码认证中。','')` 文本。
 *
 * 状态码对照：
 *   66/408 未扫码 · 67/404 已扫码待确认 · 65/402 已过期 · 68/403 用户拒绝 ·
 *   0/405  成功（第 3 个参数里带 uin 与 ptsigx）
 */
internal fun parsePtuiCallback(raw: String): QQMusicQrStep {
    val callback = Regex("""ptuiCB\((.*)\)""").find(raw.trim())
        ?: throw IOException("无法解析 QQ 扫码状态")
    val args = Regex("""'((?:\\.|[^'])*)'""")
        .findAll(callback.groupValues[1])
        .map { match ->
            match.groupValues[1].replace("\\'", "'").replace("\\\\", "\\")
        }
        .toList()
    val code = args.firstOrNull()?.toIntOrNull() ?: throw IOException("QQ 扫码状态码无效")
    return when (code) {
        0, 405 -> {
            val callbackUrl = args.getOrNull(2)?.toHttpUrlOrNull()
                ?: throw IOException("QQ 授权地址无效")
            val uin = callbackUrl.queryParameter("uin").orEmpty()
            val sigX = callbackUrl.queryParameter("ptsigx").orEmpty()
            if (uin.isBlank() || sigX.isBlank()) throw IOException("QQ 授权响应缺少账号参数")
            QQMusicQrStep(QQMusicQrLoginEvent.Connected, uin = uin, sigX = sigX)
        }
        66, 408 -> QQMusicQrStep(QQMusicQrLoginEvent.Waiting)
        67, 404 -> QQMusicQrStep(QQMusicQrLoginEvent.Scanned)
        65, 402 -> QQMusicQrStep(QQMusicQrLoginEvent.Expired)
        68, 403 -> QQMusicQrStep(QQMusicQrLoginEvent.Rejected)
        else -> throw IOException("未知的 QQ 扫码状态：$code")
    }
}

/** 第 ④ 步拿到的音乐凭据。 */
internal data class QQMusicCredential(
    val musicId: String,
    val musicKey: String,
    val openId: String,
    val accessToken: String,
    val refreshToken: String,
    val unionId: String,
    val encryptedUin: String,
    val loginType: Int,
    /** App 写接口凭据（若登录响应提供）。 */
    val authst: String = "",
    val sid: String = "",
    val wid: String = "",
) {
    val isUsable: Boolean
        get() = musicId.isNotBlank() && musicKey.isNotBlank()
}

internal fun parseCredential(data: JSONObject): QQMusicCredential = QQMusicCredential(
    musicId = data.optString("str_musicid").ifBlank { data.opt("musicid")?.toString().orEmpty() },
    musicKey = data.optString("musickey"),
    openId = data.optString("openid"),
    accessToken = data.optString("access_token"),
    refreshToken = data.optString("refresh_token"),
    unionId = data.optString("unionid"),
    encryptedUin = data.optString("encryptUin"),
    loginType = data.optInt("loginType", data.optInt("login_type", 0)),
    authst = data.optString("authst"),
    sid = data.optString("sid"),
    wid = data.optString("wid").ifBlank { data.optString("str_wid") },
)

/**
 * 把凭据拼成一条 Cookie 串。
 *
 * 为什么要写这么多别名：
 *  - `uin` / `qqmusic_uin`：不同接口读不同的键；
 *  - `qm_keyst` / `qqmusic_key` / `music_key`：播放与网页接口各认一个；
 *  - `login_type`：1=微信、2=QQ（后续判断登录方式用）。
 */
internal fun QQMusicCredential.toCookie(): String {
    val uin = musicId.filter(Char::isDigit).trimStart('0')
    require(uin.isNotBlank() && musicKey.isNotBlank()) { "QQ 音乐登录凭据不完整" }
    val type = loginType.takeIf { it != 0 }
        ?: if (musicKey.startsWith("W_X")) 1 else 2
    return listOf(
        "uin" to uin,
        "qqmusic_uin" to uin,
        "qm_keyst" to musicKey,
        "qqmusic_key" to musicKey,
        "music_key" to musicKey,
        "login_type" to type.toString(),
        "psrf_qqopenid" to openId,
        "psrf_qqaccess_token" to accessToken,
        "psrf_qqrefresh_token" to refreshToken,
        "psrf_qqunionid" to unionId,
        "euin" to encryptedUin,
        "authst" to authst,
        "musicid" to musicId,
        "sid" to sid,
        "wid" to wid,
    )
        .filter { (_, value) -> value.isNotBlank() && value.none { it == ';' || it == '\r' || it == '\n' } }
        .joinToString("; ") { (key, value) -> "$key=$value" }
}

/** 登录流程专用的内存 CookieJar（不污染全局 OkHttp 的 Cookie 管理）。 */
class QQMusicCookieJar : CookieJar {
    private val cookies = linkedMapOf<String, Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        cookies.forEach { cookie -> this.cookies[cookieKey(cookie)] = cookie }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        cookies.entries.removeAll { (_, cookie) -> cookie.expiresAt < now }
        return cookies.values.filter { it.matches(url) }
    }

    private fun cookieKey(cookie: Cookie): String = "${cookie.name}|${cookie.domain}|${cookie.path}"
}

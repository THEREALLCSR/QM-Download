package moe.ouom.qmdownload.core.auth

import okhttp3.OkHttpClient
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/*
 * QM-Download - QQ 音乐登录态续签的 core 层单元测试。
 *
 * 本文件严格遵循"先测试后实现"：在 QQMusicSessionRefresher 生产实现落地之前，
 * 本测试应当编译失败（红灯）；实现落地后应全部通过（绿灯）。
 */
class QQMusicSessionRefresherTest {

    private companion object {
        const val NOW: Long = 2_000_000_000_000L
        const val HOUR_MS: Long = 3_600_000L
        const val THRESHOLD_HOURS: Long = 20L
    }

    private fun cookieOf(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("; ") { (key, value) -> "$key=$value" }

    /** 生成 createtime 为秒级的 cookie（测试注入 now，避免依赖系统时钟）。 */
    private fun cookieWithCreatetimeSeconds(deltaMs: Long): String = cookieOf(
        "uin" to "3396921622",
        "qm_keyst" to "Q_H_L_test",
        "psrf_musickey_createtime" to ((NOW - deltaMs) / 1_000L).toString(),
    )

    /** 生成 createtime 为毫秒级的 cookie。 */
    private fun cookieWithCreatetimeMillis(deltaMs: Long): String = cookieOf(
        "uin" to "3396921622",
        "qm_keyst" to "Q_H_L_test",
        "psrf_musickey_createtime" to (NOW - deltaMs).toString(),
    )

    private fun fullOldCookie(): String = cookieOf(
        "uin" to "3396921622",
        "qqmusic_uin" to "3396921622",
        "qm_keyst" to "OLD_KEY",
        "qqmusic_key" to "OLD_KEY",
        "music_key" to "OLD_KEY",
        "login_type" to "2",
        "psrf_qqopenid" to "OLD_OPENID",
        "psrf_qqaccess_token" to "OLD_ACC",
        "psrf_qqrefresh_token" to "OLD_REF",
        "psrf_qqunionid" to "OLD_UNION",
        "euin" to "OLD_EUIN",
        "authst" to "OLD_AUTHST",
        "musicid" to "3396921622",
        "sid" to "OLD_SID",
        "wid" to "OLD_WID",
        "psrf_musickey_createtime" to "OLD_CREATE",
        "psrf_access_token_expiresAt" to "OLD_EXPIRES",
    )

    // ── isNearExpiry ─────────────────────────────────────────────────────────

    @Test
    fun isNearExpiry_freshOneHour_returnsFalse() {
        val cookie = cookieWithCreatetimeSeconds(1L * HOUR_MS)
        assertFalse(QQMusicSessionRefresher.isNearExpiry(cookie, NOW, THRESHOLD_HOURS))
    }

    @Test
    fun isNearExpiry_justBeforeThreshold_19h59m59s_returnsFalse() {
        // 边界下侧：19h59m59s < 20h，不应续签
        val delta = 19L * HOUR_MS + 59L * 60L * 1_000L + 59L * 1_000L
        val cookie = cookieWithCreatetimeSeconds(delta)
        assertFalse(QQMusicSessionRefresher.isNearExpiry(cookie, NOW, THRESHOLD_HOURS))
    }

    @Test
    fun isNearExpiry_exactlyAtThreshold_20h_returnsTrue_inclusiveBoundary() {
        // 边界语义：>= 阈值即视为需要续签，恰好 20h 时返回 true（闭区间上界）
        val delta = THRESHOLD_HOURS * HOUR_MS
        val cookie = cookieWithCreatetimeSeconds(delta)
        assertTrue(QQMusicSessionRefresher.isNearExpiry(cookie, NOW, THRESHOLD_HOURS))
    }

    @Test
    fun isNearExpiry_twentyFiveHours_returnsTrue() {
        val cookie = cookieWithCreatetimeSeconds(25L * HOUR_MS)
        assertTrue(QQMusicSessionRefresher.isNearExpiry(cookie, NOW, THRESHOLD_HOURS))
    }

    @Test
    fun isNearExpiry_secondsAndMillisecondsCreatetime_areBothSupported() {
        val delta = 25L * HOUR_MS
        val secondsCookie = cookieWithCreatetimeSeconds(delta)
        val millisCookie = cookieWithCreatetimeMillis(delta)
        assertTrue(QQMusicSessionRefresher.isNearExpiry(secondsCookie, NOW, THRESHOLD_HOURS))
        assertTrue(QQMusicSessionRefresher.isNearExpiry(millisCookie, NOW, THRESHOLD_HOURS))

        val freshDelta = 1L * HOUR_MS
        assertFalse(
            QQMusicSessionRefresher.isNearExpiry(
                cookieWithCreatetimeMillis(freshDelta),
                NOW,
                THRESHOLD_HOURS,
            ),
        )
    }

    @Test
    fun isNearExpiry_missingOrIllegalCreatetime_returnsTrue() {
        assertTrue(QQMusicSessionRefresher.isNearExpiry("uin=3396921622; qm_keyst=K", NOW, THRESHOLD_HOURS))
        assertTrue(
            QQMusicSessionRefresher.isNearExpiry(
                "uin=3396921622; psrf_musickey_createtime=not-a-number",
                NOW,
                THRESHOLD_HOURS,
            ),
        )
        assertTrue(
            QQMusicSessionRefresher.isNearExpiry(
                "uin=3396921622; psrf_musickey_createtime=; qm_keyst=K",
                NOW,
                THRESHOLD_HOURS,
            ),
        )
    }

    @Test
    fun isNearExpiry_customThreshold_isHonored() {
        val twoHoursAgo = cookieWithCreatetimeSeconds(2L * HOUR_MS)
        assertTrue(QQMusicSessionRefresher.isNearExpiry(twoHoursAgo, NOW, thresholdHours = 1L))
        assertFalse(QQMusicSessionRefresher.isNearExpiry(twoHoursAgo, NOW, thresholdHours = 20L))
    }

    @Test
    fun isNearExpiry_defaultsToTwentyHours() {
        assertEquals(20L, QQMusicSessionRefresher.DEFAULT_NEAR_EXPIRY_HOURS)
        val cookie = cookieWithCreatetimeSeconds(21L * HOUR_MS)
        assertTrue(QQMusicSessionRefresher.isNearExpiry(cookie, NOW))
    }

    // ── buildRefreshBody ─────────────────────────────────────────────────────

    @Test
    fun buildRefreshBody_hasEnvelopeModuleAndMethod() {
        val body = JSONObject(QQMusicSessionRefresher.buildRefreshBody(fullOldCookie()))
        assertEquals(0, body.getInt("code"))
        val req1 = body.getJSONObject("req1")
        assertEquals(0, req1.getInt("code"))
        assertEquals("QQConnectLogin.LoginServer", req1.getString("module"))
        assertEquals("QQLogin", req1.getString("method"))
    }

    @Test
    fun buildRefreshBody_paramMapsEveryFieldFromOldCookie() {
        val param = JSONObject(QQMusicSessionRefresher.buildRefreshBody(fullOldCookie()))
            .getJSONObject("req1")
            .getJSONObject("param")

        assertEquals("OLD_REF", param.getString("refresh_token"))
        assertEquals("OLD_ACC", param.getString("access_token"))
        assertEquals("OLD_EXPIRES", param.getString("expired_at"))
        assertEquals("OLD_OPENID", param.getString("psrf_qqopenid"))
        assertEquals("3396921622", param.getString("musicid"))
        assertEquals("3396921622", param.getString("str_musicid"))
        assertEquals("OLD_KEY", param.getString("musickey"))
        assertEquals("OLD_CREATE", param.getString("musickeyCreateTime"))
        assertEquals("OLD_UNION", param.getString("unionid"))
        assertEquals("OLD_EUIN", param.getString("encryptUin"))
        assertEquals(0, param.getInt("onlyNeedAccessToken"))
        assertEquals(0, param.getInt("forceRefreshToken"))
    }

    @Test
    fun buildRefreshBody_uinIsDigitsOnlyWithLeadingZerosRemoved() {
        val cookie = fullOldCookie().replace("uin=3396921622", "uin=o003396921622")
        val param = JSONObject(QQMusicSessionRefresher.buildRefreshBody(cookie))
            .getJSONObject("req1")
            .getJSONObject("param")
        assertEquals("3396921622", param.getString("musicid"))
        assertEquals("3396921622", param.getString("str_musicid"))
    }

    @Test
    fun buildRefreshBody_musickeyFallsBackToQmKeystWhenAliasesMissing() {
        val cookie = cookieOf(
            "uin" to "3396921622",
            "qm_keyst" to "QM_KEYST_ONLY",
        )
        val param = JSONObject(QQMusicSessionRefresher.buildRefreshBody(cookie))
            .getJSONObject("req1")
            .getJSONObject("param")
        assertEquals("QM_KEYST_ONLY", param.getString("musickey"))
    }

    // ── mergeRefreshedCookie ─────────────────────────────────────────────────

    @Test
    fun mergeRefreshedCookie_updatesAllThreeMusickeyAliases() {
        val refreshed = JSONObject().put("musickey", "NEW_KEY")
        val values = cookieValues(QQMusicSessionRefresher.mergeRefreshedCookie(fullOldCookie(), refreshed))
        assertEquals("NEW_KEY", values["qm_keyst"])
        assertEquals("NEW_KEY", values["qqmusic_key"])
        assertEquals("NEW_KEY", values["music_key"])
    }

    @Test
    fun mergeRefreshedCookie_updatesTokensTimestampsUnionAndEuin() {
        val refreshed = JSONObject()
            .put("refresh_token", "NEW_REF")
            .put("access_token", "NEW_ACC")
            .put("musickeyCreateTime", "NEW_CREATE")
            .put("expired_at", "NEW_EXPIRES")
            .put("unionid", "NEW_UNION")
            .put("encryptUin", "NEW_EUIN")
        val values = cookieValues(QQMusicSessionRefresher.mergeRefreshedCookie(fullOldCookie(), refreshed))
        assertEquals("NEW_REF", values["psrf_qqrefresh_token"])
        assertEquals("NEW_ACC", values["psrf_qqaccess_token"])
        assertEquals("NEW_CREATE", values["psrf_musickey_createtime"])
        assertEquals("NEW_EXPIRES", values["psrf_access_token_expiresAt"])
        assertEquals("NEW_UNION", values["psrf_qqunionid"])
        assertEquals("NEW_EUIN", values["euin"])
    }

    @Test
    fun mergeRefreshedCookie_preservesUnrelatedFieldsAndKeepsStableOrder() {
        val refreshed = JSONObject().put("musickey", "NEW_KEY")
        val merged = QQMusicSessionRefresher.mergeRefreshedCookie(fullOldCookie(), refreshed)
        val values = cookieValues(merged)

        assertEquals("2", values["login_type"])
        assertEquals("OLD_AUTHST", values["authst"])
        assertEquals("OLD_SID", values["sid"])
        assertEquals("OLD_WID", values["wid"])
        assertEquals("3396921622", values["musicid"])

        val keys = merged.split("; ").map { it.substringBefore("=") }
        assertEquals(
            listOf(
                "uin", "qqmusic_uin", "qm_keyst", "qqmusic_key", "music_key", "login_type",
                "psrf_qqopenid", "psrf_qqaccess_token", "psrf_qqrefresh_token", "psrf_qqunionid",
                "euin", "authst", "musicid", "sid", "wid",
                "psrf_musickey_createtime", "psrf_access_token_expiresAt",
            ),
            keys,
        )
    }

    @Test
    fun mergeRefreshedCookie_blankZeroAndNullValuesDoNotOverwrite() {
        val refreshed = JSONObject()
            .put("refresh_token", "")
            .put("access_token", "   ")
            .put("musickey", "0")
            .put("unionid", JSONObject.NULL)
            .put("expired_at", JSONObject.NULL)
        val values = cookieValues(QQMusicSessionRefresher.mergeRefreshedCookie(fullOldCookie(), refreshed))
        assertEquals("OLD_REF", values["psrf_qqrefresh_token"])
        assertEquals("OLD_ACC", values["psrf_qqaccess_token"])
        assertEquals("OLD_KEY", values["qm_keyst"])
        assertEquals("OLD_UNION", values["psrf_qqunionid"])
        assertEquals("OLD_EXPIRES", values["psrf_access_token_expiresAt"])
    }

    @Test
    fun mergeRefreshedCookie_updatesUinOnlyWhenReturnedValueDiffers() {
        val different = JSONObject().put("str_musicid", "o003396921623")
        val changed = cookieValues(QQMusicSessionRefresher.mergeRefreshedCookie(fullOldCookie(), different))
        assertEquals("3396921623", changed["uin"])
        assertEquals("3396921623", changed["qqmusic_uin"])

        val same = JSONObject().put("musicid", "3396921622")
        val unchanged = cookieValues(QQMusicSessionRefresher.mergeRefreshedCookie(fullOldCookie(), same))
        assertEquals("3396921622", unchanged["uin"])

        val blank = JSONObject().put("str_musicid", "")
        val stillUnchanged = cookieValues(QQMusicSessionRefresher.mergeRefreshedCookie(fullOldCookie(), blank))
        assertEquals("3396921622", stillUnchanged["uin"])
    }

    @Test
    fun mergeRefreshedCookie_appendsMappedKeysMissingFromOldCookie() {
        val refreshed = JSONObject()
            .put("musickeyCreateTime", "NEW_CREATE")
            .put("expired_at", "NEW_EXPIRES")
        val merged = QQMusicSessionRefresher.mergeRefreshedCookie("uin=3396921622; qm_keyst=K", refreshed)
        val values = cookieValues(merged)
        assertEquals("3396921622", values["uin"])
        assertEquals("K", values["qm_keyst"])
        assertEquals("NEW_CREATE", values["psrf_musickey_createtime"])
        assertEquals("NEW_EXPIRES", values["psrf_access_token_expiresAt"])
    }

    // ── 任务 A：凭据字段补齐 ─────────────────────────────────────────────────

    @Test
    fun parseCredential_readsMusickeyCreateTimeAndExpiredAt() {
        val data = JSONObject()
            .put("str_musicid", "3396921622")
            .put("musickey", "Q_H_L_test")
            .put("musickeyCreateTime", "CT_VALUE")
            .put("expired_at", "EX_VALUE")
        val credential = parseCredential(data)
        assertEquals("CT_VALUE", credential.musicKeyCreateTime)
        assertEquals("EX_VALUE", credential.accessTokenExpiresAt)
    }

    @Test
    fun toCookie_appendsNewFieldsAndFiltersInvalidValues() {
        val credential = QQMusicCredential(
            musicId = "o003396921622",
            musicKey = "Q_H_L_test",
            openId = "",
            accessToken = "",
            refreshToken = "",
            unionId = "",
            encryptedUin = "",
            loginType = 2,
            musicKeyCreateTime = "CT_VALUE",
            accessTokenExpiresAt = "EX_VALUE",
        )
        val cookie = credential.toCookie()
        assertTrue(cookie.contains("psrf_musickey_createtime=CT_VALUE"))
        assertTrue(cookie.contains("psrf_access_token_expiresAt=EX_VALUE"))

        val invalid = credential.copy(musicKeyCreateTime = "bad;value\r\n", accessTokenExpiresAt = "")
        val filtered = invalid.toCookie()
        assertFalse(filtered.contains("psrf_musickey_createtime"))
        assertFalse(filtered.contains("psrf_access_token_expiresAt"))
    }

    // ── 契约签名与常量 ───────────────────────────────────────────────────────

    @Test
    fun refreshUrlAndSignatureMatchContract() {
        assertEquals(
            "https://u6.y.qq.com/cgi-bin/musicu.fcg?format=json&inCharset=utf8&outCharset=utf8",
            QQMusicSessionRefresher.REFRESH_URL,
        )
        // 编译期锁定 refresh 的公开签名，不打真实网络请求。
        val refreshFn: suspend (String, OkHttpClient) -> String = QQMusicSessionRefresher::refresh
        assertNotNull(refreshFn)
    }
}

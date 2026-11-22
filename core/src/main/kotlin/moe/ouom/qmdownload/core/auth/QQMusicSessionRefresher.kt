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
 * File: moe.ouom.qmdownload.core.auth/QQMusicSessionRefresher
 */

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * QQ 音乐登录态续签（core 层纯逻辑 + 一个发包入口）。
 *
 * 原理：续签与首次登录用的是同一个 `QQConnectLogin.LoginServer / QQLogin` 方法，
 * 区别只在入参——登录用 `code` 换凭据，续签用 `refresh_token` 加全套旧字段换凭据。
 * 续签必须在旧 keyst 仍有效时完成（推荐约 20h 时触发），彻底过期只能重新扫码。
 *
 * [isNearExpiry] / [buildRefreshBody] / [mergeRefreshedCookie] 都是纯函数，便于单测；
 * [refresh] 负责真正发包，不修改传入的 cookie，由调用方决定是否持久化返回值。
 */
object QQMusicSessionRefresher {
    /** keyst 年龄达到该阈值（小时）即认为需要续签，给旧凭据留出余量。 */
    const val DEFAULT_NEAR_EXPIRY_HOURS: Long = 20L

    const val REFRESH_URL: String =
        "https://u6.y.qq.com/cgi-bin/musicu.fcg?format=json&inCharset=utf8&outCharset=utf8"

    private const val HOUR_MILLIS: Long = 3_600_000L

    /** 大于该值的 createtime 视为毫秒（1e11 秒已是公元 5138 年，不会误判秒级时间戳）。 */
    private const val MILLIS_THRESHOLD: Long = 100_000_000_000L

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36 NeriPlayer"

    /**
     * 判断 cookie 是否临近 keyst 过期（需要续签）。
     *
     * 依据 `psrf_musickey_createtime`（Unix 秒；若值看起来是毫秒也要兼容）。
     * 缺字段/非法值 -> 视为需要续签（true），让上层尽早尝试；上层会因无凭据失败而降级。
     * nowMillis 用于测试注入；thresholdHours 默认 [DEFAULT_NEAR_EXPIRY_HOURS]。
     *
     * 边界语义：`age >= thresholdHours` 即返回 true（恰好 20h 时续签）；
     * 未来时间戳（age < 0）视为凭据很新，返回 false。
     */
    fun isNearExpiry(
        cookie: String,
        nowMillis: Long = System.currentTimeMillis(),
        thresholdHours: Long = DEFAULT_NEAR_EXPIRY_HOURS,
    ): Boolean {
        val raw = cookieValues(cookie)["psrf_musickey_createtime"] ?: return true
        if (raw.isBlank()) return true
        val numeric = raw.toLongOrNull() ?: return true
        if (numeric < 0L) return true
        val createdMillis = if (numeric >= MILLIS_THRESHOLD) numeric else numeric * 1_000L
        val thresholdMillis = thresholdHours.coerceAtLeast(0L) * HOUR_MILLIS
        return nowMillis - createdMillis >= thresholdMillis
    }

    /**
     * 纯函数：构造续签请求的 JSON body 字符串（不发包）。
     *
     * body 结构见分析报告第四节：code=0, req1{code=0, module="QQConnectLogin.LoginServer",
     * method="QQLogin", param{...}}。param 全部从旧 cookie 取值，缺少的键取空串。
     */
    fun buildRefreshBody(cookie: String): String {
        val values = cookieValues(cookie)
        val uin = normalizedUin(values["uin"] ?: values["qqmusic_uin"].orEmpty())
        val musicKey = sequenceOf("qqmusic_key", "qm_keyst", "music_key")
            .map { values[it].orEmpty() }
            .firstOrNull(String::isNotBlank)
            .orEmpty()
        val param = JSONObject()
            .put("onlyNeedAccessToken", 0)
            .put("forceRefreshToken", 0)
            .put("psrf_qqopenid", values["psrf_qqopenid"].orEmpty())
            .put("refresh_token", values["psrf_qqrefresh_token"].orEmpty())
            .put("access_token", values["psrf_qqaccess_token"].orEmpty())
            .put("expired_at", values["psrf_access_token_expiresAt"].orEmpty())
            .put("musicid", uin)
            .put("musickey", musicKey)
            .put("musickeyCreateTime", values["psrf_musickey_createtime"].orEmpty())
            .put("unionid", values["psrf_qqunionid"].orEmpty())
            .put("str_musicid", uin)
            .put("encryptUin", values["euin"].orEmpty())
        val req1 = JSONObject()
            .put("code", 0)
            .put("module", "QQConnectLogin.LoginServer")
            .put("method", "QQLogin")
            .put("param", param)
        return JSONObject()
            .put("code", 0)
            .put("req1", req1)
            .toString()
    }

    /**
     * 纯函数：把续签响应 req1.data 的新字段合并进旧 cookie，返回新 cookie 串。
     *
     * 映射：refresh_token->psrf_qqrefresh_token, access_token->psrf_qqaccess_token,
     * musickey->qm_keyst/qqmusic_key/music_key, musickeyCreateTime->psrf_musickey_createtime,
     * unionid->psrf_qqunionid, expired_at->psrf_access_token_expiresAt, encryptUin->euin,
     * str_musicid/musicid->uin/qqmusic_uin（若返回非空且与旧值不同则更新）。
     * 响应中为空/0（含 JSONObject.NULL）的字段不覆盖旧值；旧 cookie 中无关字段保留；顺序保持稳定。
     */
    fun mergeRefreshedCookie(oldCookie: String, refreshedData: JSONObject): String {
        // LinkedHashMap 保证旧字段顺序不变；新键追加到末尾。
        val merged = LinkedHashMap<String, String>()
        oldCookie.split(';')
            .map(String::trim)
            .forEach { entry ->
                val separator = entry.indexOf('=')
                if (separator <= 0) return@forEach
                val key = entry.substring(0, separator).trim()
                val value = entry.substring(separator + 1).trim()
                if (key.isNotBlank() && value.isNotBlank()) merged[key] = value
            }

        listOf(
            "refresh_token" to "psrf_qqrefresh_token",
            "access_token" to "psrf_qqaccess_token",
            "musickeyCreateTime" to "psrf_musickey_createtime",
            "unionid" to "psrf_qqunionid",
            "expired_at" to "psrf_access_token_expiresAt",
            "encryptUin" to "euin",
        ).forEach { (from, to) ->
            usableValue(refreshedData, from)?.let { merged[to] = it }
        }

        usableValue(refreshedData, "musickey")?.let { newKey ->
            listOf("qm_keyst", "qqmusic_key", "music_key").forEach { merged[it] = newKey }
        }

        val newUin = listOf("str_musicid", "musicid")
            .firstNotNullOfOrNull { key -> usableValue(refreshedData, key)?.let(::normalizedUin)?.takeIf(String::isNotBlank) }
        val oldUin = normalizedUin(merged["uin"].orEmpty())
        if (newUin != null && newUin != oldUin) {
            merged["uin"] = newUin
            merged["qqmusic_uin"] = newUin
        }

        return merged.entries.joinToString("; ") { (key, value) -> "$key=$value" }
    }

    /**
     * 真正发包续签：POST [REFRESH_URL]，请求头 Cookie=旧 cookie，body=[buildRefreshBody]，
     * 解析响应 code 与 req1.code 均为 0，取 req1.data 交 [mergeRefreshedCookie]，返回新 cookie 串。
     * 失败抛 IOException（含业务错误码信息）。不修改传入 cookie。
     */
    suspend fun refresh(cookie: String, httpClient: OkHttpClient): String = withContext(Dispatchers.IO) {
        val body = buildRefreshBody(cookie).toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(REFRESH_URL)
            .post(body)
            .header("Cookie", cookie)
            .header("Referer", "https://y.qq.com/")
            .header("User-Agent", USER_AGENT)
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("QQ 音乐续签失败：HTTP ${response.code}")
            val raw = response.body.string()
            val root = runCatching { JSONObject(raw) }
                .getOrElse { throw IOException("QQ 音乐续签响应解析失败", it) }
            val code = root.optInt("code", -1)
            val req1 = root.optJSONObject("req1")
            val req1Code = req1?.optInt("code", -1) ?: -1
            if (code != 0 || req1Code != 0) {
                throw IOException("QQ 音乐拒绝续签（code=$code, req1.code=$req1Code）")
            }
            mergeRefreshedCookie(cookie, req1?.optJSONObject("data") ?: JSONObject())
        }
    }

    /** uin 只保留纯数字并去掉前导 0，兼容 "o003396921622" 这类历史格式。 */
    private fun normalizedUin(raw: String): String = raw.filter(Char::isDigit).trimStart('0')

    /**
     * 响应字段可覆盖旧值的判定：非空白、不等于 "0"、且不含分号/换行等 Cookie 非法字符。
     * JSONObject.NULL 经 optString 得到空串，同样落入"不覆盖"分支。
     */
    private fun usableValue(data: JSONObject, key: String): String? {
        val raw = data.optString(key)
        if (raw.isBlank()) return null
        val value = raw.trim()
        if (value == "0") return null
        if (value.any { it == ';' || it == '\r' || it == '\n' }) return null
        return value
    }
}

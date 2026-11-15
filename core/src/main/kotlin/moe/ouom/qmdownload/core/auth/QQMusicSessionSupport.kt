package moe.ouom.qmdownload.core.auth

import moe.ouom.qmdownload.core.model.QQMusicSession

/**
 * QQ 音乐接口里到处用到的 `g_tk` 算法。
 *
 * 算法：从 seed 开始，每个字符执行 `h = h * 33 + char`，最后取低 31 位。
 * - seed = 0：二维码轮询的 ptqrtoken；
 * - seed = 5381：业务请求的 g_tk（用 musicKey 计算）。
 */
fun qqMusicHash33(value: String, seed: Int = 5381): Long {
    var hash = seed.toLong()
    value.forEach { char ->
        hash += (hash shl 5) + char.code
        hash = hash and 0xFFFF_FFFFL
    }
    return hash and 0x7FFF_FFFFL
}

/** 把一条 Cookie 串解析成 [QQMusicSession]。 */
fun parseQQMusicCookie(cookie: String): QQMusicSession {
    val values = cookieValues(cookie)
    val rawUin = sequenceOf("qqmusic_uin", "uin", "p_uin")
        .map { values[it].orEmpty() }
        .firstOrNull { it.isNotBlank() }
        .orEmpty()
    val uin = rawUin.filter(Char::isDigit).trimStart('0')
    val musicKey = sequenceOf("qm_keyst", "qqmusic_key", "music_key")
        .map { values[it].orEmpty() }
        .firstOrNull { it.isNotBlank() }
        .orEmpty()
    val wid = sequenceOf("wid", "str_wid")
        .map { values[it].orEmpty() }
        .firstOrNull { it.isNotBlank() }
        .orEmpty()
    return QQMusicSession(
        cookie = cookie.trim(),
        uin = uin,
        musicKey = musicKey,
        wid = wid,
    )
}

/** 解析 `a=1; b=2` 形式的 Cookie；空值/非法项直接忽略。 */
fun cookieValues(cookie: String): Map<String, String> = cookie
    .split(';')
    .map(String::trim)
    .mapNotNull { entry ->
        val separator = entry.indexOf('=')
        if (separator <= 0) null
        else entry.substring(0, separator).trim() to entry.substring(separator + 1).trim()
    }
    .filter { (key, value) -> key.isNotBlank() && value.isNotBlank() }
    .toMap()

/** 会话持久化接口；Android/Desktop 各自实现。 */
interface QQMusicSessionStore {
    fun load(): QQMusicSession?
    fun save(session: QQMusicSession)
    fun clear()
}

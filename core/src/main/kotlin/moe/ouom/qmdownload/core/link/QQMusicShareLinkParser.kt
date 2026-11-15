package moe.ouom.qmdownload.core.link

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * QQ 音乐歌单分享链接解析。
 *
 * 支持示例：
 * - 桌面/H5：https://y.qq.com/n/ryqq_v2/playlist/9735321048?ADTAG=h5_share_playlist
 * - 旧 H5：https://y.qq.com/n/ryqq/playlist/9735321048
 * - 微信/手机分享：
 *   https://i2.y.qq.com/n3/other/pages/details/playlist.html?platform=11&id=9616026161&ADTAG=wxfshare
 * - 纯数字 ID：9735321048
 *
 * 只接受 qq.com 域名的链接；解析失败返回 null。
 */
object QQMusicShareLinkParser {

    private val playlistPath = Regex("""/playlist/(\d+)""")
    private val numeric = Regex("""^\d{5,}$""")

    /** 移动端页面把歌单 ID 放在查询参数里。 */
    private val playlistQueryKeys = listOf(
        "id",
        "disstid",
        "dissid",
        "tid",
        "playlistId",
        "playlistid",
    )

    fun parse(input: String): String? {
        val text = input.trim()
        if (text.isEmpty()) return null
        if (numeric.matches(text)) return text

        val url = text.toHttpUrlOrNull() ?: return null
        if (!url.host.endsWith("qq.com", ignoreCase = true)) return null

        // 1) /playlist/{id}
        playlistPath.find(url.encodedPath)?.groupValues?.getOrNull(1)?.let { return it }

        // 2) 移动端 /n3/other/pages/details/playlist.html?id=...
        val path = url.encodedPath.lowercase()
        val pageLooksLikePlaylist = path.contains("playlist") || path.contains("taoge")
        if (!pageLooksLikePlaylist) return null
        for (key in playlistQueryKeys) {
            val value = url.queryParameter(key)?.trim().orEmpty()
            if (numeric.matches(value)) return value
        }
        return null
    }
}

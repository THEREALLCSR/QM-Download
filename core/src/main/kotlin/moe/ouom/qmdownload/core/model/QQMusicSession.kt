package moe.ouom.qmdownload.core.model

/**
 * QQ 音乐 Web 登录态。
 *
 * 与 NeriPlayer 的 QQMusicSession 对齐：
 * - uin：QQ 号 / 音乐账号 id；
 * - musicKey：Cookie 里的 qm_keyst，用于 g_tk 与 VKey；
 * - cookie：完整 Cookie 串。
 */
data class QQMusicSession(
    val cookie: String,
    val uin: String,
    val musicKey: String,
    /** App 设备号；VKey 的 guid 优先用它（如果登录响应带 wid）。 */
    val wid: String = "",
) {
    val isLoggedIn: Boolean
        get() = uin.isNotBlank() && musicKey.isNotBlank()
}

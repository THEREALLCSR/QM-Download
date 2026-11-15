package moe.ouom.qmdownload.core.debug

import java.time.LocalTime

/**
 * 轻量调试日志。
 *
 * 只在 Debug/需要排查时开启；不要记录完整 Cookie、musicKey、authst、vkey。
 * 平台层负责把日志落到文件（Desktop）或 Logcat（Android）。
 */
object QmDebugLog {
    @Volatile
    var enabled: Boolean = true

    @Volatile
    var sink: (String) -> Unit = { line -> println(line) }

    fun log(tag: String, message: String) {
        if (!enabled) return
        val line = "[${LocalTime.now().withNano(0)}][$tag] $message"
        runCatching { sink(line) }
    }
}

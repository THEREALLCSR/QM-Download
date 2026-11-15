package moe.ouom.qmdownload.core.download

import java.io.File
import moe.ouom.qmdownload.core.debug.QmDebugLog
import moe.ouom.qmdownload.core.error.QmError

/**
 * 下载结果校验。
 *
 * 为什么需要：
 * QQ 音乐 CDN 对“无版权/无权限”的曲目可能返回 HTTP 200 + 一个只有
 * FLAC 元数据、没有音频帧的占位文件（约 8KB）。只判断 HTTP 200 会得到
 * 一个能打开但没有声音的 .flac。
 *
 * 校验层次：
 * 1. 文件大小；
 * 2. 容器结构（FLAC 有音频帧 / MP3 有帧同步）；
 * 3. FLAC 时长（与歌曲预期时长比较，防止极短占位）。
 */
object AudioFileValidator {

    fun requireValid(file: File, extension: String, expectedDurationMs: Long = 0L) {
        val valid = isValid(file, extension, expectedDurationMs)
        QmDebugLog.log(
            "Validate",
            "file=${file.name} size=${file.length()} ext=$extension " +
                "expectedMs=$expectedDurationMs valid=$valid",
        )
        if (!valid) {
            throw QmError.NoResource("该歌曲可能无版权或当前账号无下载权限")
        }
    }

    fun isValid(file: File, extension: String, expectedDurationMs: Long = 0L): Boolean {
        if (!file.exists() || file.length() < MIN_BYTES) return false
        return when (extension.lowercase()) {
            "flac" -> isValidFlac(file, expectedDurationMs)
            "mp3" -> isValidMp3(file)
            else -> file.length() >= MIN_BYTES
        }
    }

    private fun isValidFlac(file: File, expectedDurationMs: Long): Boolean {
        val bytes = file.readBytes()
        if (bytes.size < 8) return false
        if (String(bytes, 0, 4, Charsets.US_ASCII) != "fLaC") return false
        var offset = 4
        var last = false
        var streamInfo: ByteArray? = null
        while (!last) {
            if (offset + 4 > bytes.size) return false
            val header = bytes[offset].toInt() and 0xFF
            last = header and 0x80 != 0
            val type = header and 0x7F
            val length = ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)
            offset += 4
            if (offset + length > bytes.size) return false
            if (type == 0 && streamInfo == null) {
                streamInfo = bytes.copyOfRange(offset, offset + length)
            }
            offset += length
        }
        // 元数据之后必须还有音频帧；FLAC 帧至少包含帧头与 CRC。
        if (bytes.size - offset <= 64) return false

        val info = streamInfo ?: return true
        if (info.size < 18) return true
        val sampleRate = ((info[10].toInt() and 0xFF) shl 12) or
            ((info[11].toInt() and 0xFF) shl 4) or
            ((info[12].toInt() and 0xF0) shr 4)
        val totalSamples = ((info[13].toLong() and 0x0F) shl 32) or
            ((info[14].toLong() and 0xFF) shl 24) or
            ((info[15].toLong() and 0xFF) shl 16) or
            ((info[16].toLong() and 0xFF) shl 8) or
            (info[17].toLong() and 0xFF)
        if (sampleRate <= 0 || totalSamples <= 0L) return false
        val durationMs = totalSamples * 1_000L / sampleRate
        QmDebugLog.log(
            "Validate",
            "flac sampleRate=$sampleRate totalSamples=$totalSamples durationMs=$durationMs",
        )
        if (durationMs < 3_000L) return false
        if (expectedDurationMs > 0L) {
            // 允许现场版/母带与歌单时长存在一定差异，但拒绝数量级不符
            if (durationMs < expectedDurationMs / 2L || durationMs > expectedDurationMs * 2L) {
                return false
            }
        }
        return true
    }

    private fun isValidMp3(file: File): Boolean {
        val bytes = file.readBytes()
        var offset = 0
        if (bytes.size >= 10 &&
            bytes[0] == 'I'.code.toByte() &&
            bytes[1] == 'D'.code.toByte() &&
            bytes[2] == '3'.code.toByte()
        ) {
            offset = 10 + (((bytes[6].toInt() and 0x7F) shl 21) or
                ((bytes[7].toInt() and 0x7F) shl 14) or
                ((bytes[8].toInt() and 0x7F) shl 7) or
                (bytes[9].toInt() and 0x7F))
        }
        if (offset + 4 > bytes.size) return false
        // 寻找 MPEG 帧同步 0xFFEx/0xFFFx
        for (index in offset until bytes.size - 1) {
            if ((bytes[index].toInt() and 0xFF) == 0xFF &&
                (bytes[index + 1].toInt() and 0xE0) == 0xE0
            ) {
                return bytes.size - index > 64
            }
        }
        return false
    }

    private const val MIN_BYTES = 64 * 1024L
}

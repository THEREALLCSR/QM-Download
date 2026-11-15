package moe.ouom.qmdownload.core.download

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import moe.ouom.qmdownload.core.model.QQMusicTrack

/**
 * 下载完成后的标签写入。
 *
 * 目的：QQ 音乐/其他播放器识别本地歌曲主要依赖标签，
 * 文件名只是辅助。P0 写入 TITLE/ARTIST/ALBUM。
 *
 * 只实现 MP3(ID3v2.3) 与 FLAC(Vorbis Comment)，不引入第三方标签库。
 */
object AudioTagWriter {

    fun write(file: File, track: QQMusicTrack, extension: String): Boolean = runCatching {
        when (extension.lowercase()) {
            "mp3" -> writeMp3(file, track)
            "flac" -> writeFlac(file, track)
            else -> false
        }
    }.getOrDefault(false)

    private fun writeMp3(file: File, track: QQMusicTrack): Boolean {
        val tag = buildId3v23(track)
        val original = file.readBytes()
        val hasId3 = original.size >= 10 &&
            original[0] == 'I'.code.toByte() &&
            original[1] == 'D'.code.toByte() &&
            original[2] == '3'.code.toByte()
        val body = if (hasId3) {
            val size = syncSafeToInt(original, 6)
            original.copyOfRange(10 + size, original.size)
        } else {
            original
        }
        file.writeBytes(tag + body)
        return true
    }

    private fun buildId3v23(track: QQMusicTrack): ByteArray {
        val frames = ByteArrayOutputStream()
        frames.write(textFrame("TIT2", track.title))
        frames.write(textFrame("TPE1", track.artist))
        track.album?.takeIf { it.isNotBlank() }?.let { frames.write(textFrame("TALB", it)) }
        val body = frames.toByteArray()
        val header = ByteArray(10)
        header[0] = 'I'.code.toByte()
        header[1] = 'D'.code.toByte()
        header[2] = '3'.code.toByte()
        header[3] = 3
        header[4] = 0
        header[5] = 0
        writeSyncSafe(header, 6, body.size)
        return header + body
    }

    private fun textFrame(id: String, value: String): ByteArray {
        val text = value.toByteArray(StandardCharsets.UTF_16LE)
        val content = byteArrayOf(1, 0xFF.toByte(), 0xFE.toByte()) + text
        val frame = ByteArray(10 + content.size)
        id.toByteArray(StandardCharsets.US_ASCII).copyInto(frame, 0)
        ByteBuffer.wrap(frame, 4, 4).order(ByteOrder.BIG_ENDIAN).putInt(content.size)
        content.copyInto(frame, 10)
        return frame
    }

    private fun writeSyncSafe(target: ByteArray, offset: Int, value: Int) {
        target[offset] = ((value shr 21) and 0x7F).toByte()
        target[offset + 1] = ((value shr 14) and 0x7F).toByte()
        target[offset + 2] = ((value shr 7) and 0x7F).toByte()
        target[offset + 3] = (value and 0x7F).toByte()
    }

    private fun syncSafeToInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0x7F) shl 21) or
            ((bytes[offset + 1].toInt() and 0x7F) shl 14) or
            ((bytes[offset + 2].toInt() and 0x7F) shl 7) or
            (bytes[offset + 3].toInt() and 0x7F)

    private fun writeFlac(file: File, track: QQMusicTrack): Boolean {
        val bytes = file.readBytes()
        if (bytes.size < 8 || bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) != "fLaC") {
            return false
        }
        data class Block(val type: Int, val data: ByteArray)
        val blocks = mutableListOf<Block>()
        var offset = 4
        var last = false
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
            blocks += Block(type, bytes.copyOfRange(offset, offset + length))
            offset += length
        }
        val comment = buildVorbisComment(track)
        val existing = blocks.indexOfFirst { it.type == 4 }
        if (existing >= 0) {
            blocks[existing] = Block(4, comment)
        } else {
            val streamInfo = blocks.indexOfFirst { it.type == 0 }
            if (streamInfo < 0) return false
            blocks.add(streamInfo + 1, Block(4, comment))
        }
        val output = ByteArrayOutputStream()
        output.write("fLaC".toByteArray(StandardCharsets.US_ASCII))
        blocks.forEachIndexed { index, block ->
            val isLast = index == blocks.lastIndex
            output.write((if (isLast) 0x80 else 0) or block.type)
            output.write((block.data.size shr 16) and 0xFF)
            output.write((block.data.size shr 8) and 0xFF)
            output.write(block.data.size and 0xFF)
            output.write(block.data)
        }
        // 关键：把元数据之后的音频帧原样追加，不能只写元数据。
        if (offset < bytes.size) {
            output.write(bytes, offset, bytes.size - offset)
        }
        val temp = File(file.parentFile, file.name + ".tagtmp")
        temp.writeBytes(output.toByteArray())
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
        return true
    }

    private fun buildVorbisComment(track: QQMusicTrack): ByteArray {
        val vendor = "QM-Download".toByteArray(StandardCharsets.UTF_8)
        val comments = buildList {
            add("TITLE=${track.title}")
            add("ARTIST=${track.artist}")
            track.album?.takeIf { it.isNotBlank() }?.let { add("ALBUM=$it") }
        }.map { it.toByteArray(StandardCharsets.UTF_8) }
        val output = ByteArrayOutputStream()
        fun le32(value: Int) {
            output.write(value and 0xFF)
            output.write((value shr 8) and 0xFF)
            output.write((value shr 16) and 0xFF)
            output.write((value shr 24) and 0xFF)
        }
        le32(vendor.size)
        output.write(vendor)
        le32(comments.size)
        comments.forEach { comment ->
            le32(comment.size)
            output.write(comment)
        }
        return output.toByteArray()
    }
}

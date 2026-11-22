package moe.ouom.qmdownload.core.download

import java.io.File

/** 批次子目录命名与路径解析（纯函数，便于在 :core:test 中直接测试）。 */
object DownloadBatchPaths {
    /** 复用 [DownloadFileNames.sanitize] 清洗歌单名；空值兜底“未命名歌单”。 */
    fun subDirName(playlistTitle: String): String =
        DownloadFileNames.sanitize(playlistTitle).takeIf { it.isNotBlank() } ?: "未命名歌单"

    /** subDir 非空 -> File(root, subDir).path；subDir 为 null/空 -> root。 */
    fun resolveDir(root: String, subDir: String?): String =
        if (subDir.isNullOrBlank()) root else File(root, subDir).path
}

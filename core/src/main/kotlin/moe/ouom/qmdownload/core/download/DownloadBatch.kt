package moe.ouom.qmdownload.core.download

/**
 * 一次提交 = 一个批次。
 *
 * @param id UUID，一次提交一个
 * @param playlistId 账号歌单 / 分享歌单 ID；手动场景可为 null
 * @param playlistTitle 原始标题，UI 显示用
 * @param subDir 清洗后的子目录名
 * @param rootTarget 用户选择的根：Desktop 绝对路径 / Android tree URI
 * @param quality 音质
 * @param total 提交时曲目数
 */
data class DownloadBatch(
    val id: String,
    val playlistId: String?,
    val playlistTitle: String,
    val subDir: String,
    val rootTarget: String,
    val quality: String,
    val total: Int,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * 根据批次提交总数与该批次当前剩余任务数，估算已完成数，clamp 到 [0, total]。
 *
 * 任务下载成功后会被删除记录，因此不能靠统计 COMPLETED 状态的任务来计算进度；
 * 调用方需先按 batchId 过滤出 remaining 再传入。total 为提交时曲目数，
 * 用户手动删任务可能导致偏差，故用 clamp 兜底。
 */
fun completedCount(total: Int, remaining: Int): Int =
    (total - remaining).coerceIn(0, maxOf(total, 0))

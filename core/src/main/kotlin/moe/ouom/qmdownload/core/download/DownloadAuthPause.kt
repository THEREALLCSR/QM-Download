package moe.ouom.qmdownload.core.download

/**
 * 登录过期时的队列暂停 / 恢复纯逻辑。
 *
 * 为什么是"整队列"而不是"单批次"：登录凭据是全局的，过期后所有任务都会失败，
 * 只暂停某批次会让其他批次继续撞 AuthExpired，因此统一暂停全部未完成任务。
 */
object DownloadAuthPause {

    /**
     * 登录过期：把未完成任务标记为因登录过期暂停。
     * - QUEUED -> PAUSED，pausedByAuth = true
     * - DOWNLOADING -> PAUSED，pausedByAuth = true
     * - 已是 PAUSED 的（用户手动暂停）保持原样（含 pausedByAuth 原值），不覆盖
     * - COMPLETED / FAILED 保持原样
     * 其余字段（downloadedBytes/retryCount/batchId/seq 等）原样保留；顺序不变。
     */
    fun pauseAll(tasks: List<DownloadTaskRecord>, now: Long = System.currentTimeMillis()): List<DownloadTaskRecord> =
        tasks.map { task ->
            when (task.status) {
                DownloadTaskStatus.QUEUED, DownloadTaskStatus.DOWNLOADING ->
                    task.copy(status = DownloadTaskStatus.PAUSED, pausedByAuth = true, updatedAt = now)
                else -> task
            }
        }

    /** 可自动恢复的任务：status == PAUSED 且 pausedByAuth == true。顺序保持。 */
    fun resumable(tasks: List<DownloadTaskRecord>): List<DownloadTaskRecord> =
        tasks.filter { it.status == DownloadTaskStatus.PAUSED && it.pausedByAuth }

    /**
     * 恢复：把 resumable 的任务改成 QUEUED、pausedByAuth = false、error = null、updatedAt = now；
     * 其余任务原样返回；顺序不变。
     */
    fun resumeAll(tasks: List<DownloadTaskRecord>, now: Long = System.currentTimeMillis()): List<DownloadTaskRecord> =
        tasks.map { task ->
            if (task.status == DownloadTaskStatus.PAUSED && task.pausedByAuth) {
                task.copy(
                    status = DownloadTaskStatus.QUEUED,
                    pausedByAuth = false,
                    error = null,
                    updatedAt = now,
                )
            } else {
                task
            }
        }

    /** 清除单个任务的 pausedByAuth（用户手动暂停/继续时调用，避免被自动恢复误触）。 */
    fun clearPausedByAuth(task: DownloadTaskRecord): DownloadTaskRecord =
        if (task.pausedByAuth) task.copy(pausedByAuth = false) else task
}

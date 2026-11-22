package moe.ouom.qmdownload.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.jvm.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import moe.ouom.qmdownload.core.debug.QmDebugLog
import moe.ouom.qmdownload.core.download.DownloadAuthPause
import moe.ouom.qmdownload.core.download.DownloadBatch
import moe.ouom.qmdownload.core.download.DownloadTaskRecord
import moe.ouom.qmdownload.core.download.DownloadTaskStatus
import moe.ouom.qmdownload.core.error.QmError
import moe.ouom.qmdownload.core.model.QQMusicTrack
import org.json.JSONArray
import org.json.JSONObject

/**
 * Android 前台下载服务（S2）。
 *
 * - 任务持久化；
 * - 并行任务数 1–6（读取下载页设置）；
 * - 单任务暂停/恢复；
 * - 失败自动重试 3 次。
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var schedulerJob: Job? = null
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val removedTasks = ConcurrentHashMap.newKeySet<String>()
    private val pausedAll = AtomicBoolean(false)
    private val cancelledAll = AtomicBoolean(false)
    /** 登录过期导致整队列自动暂停：调度器结束前据此停止前台服务并给出提示。 */
    @Volatile private var authPaused = false
    private val successCount = java.util.concurrent.atomic.AtomicInteger(0)
    private val failedCount = java.util.concurrent.atomic.AtomicInteger(0)

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as QmApplication
        // 重新登录成功后恢复因登录过期暂停的任务（优先于其它 action 处理）
        if (intent?.action == ACTION_RESUME_AUTH_PAUSED) {
            val before = app.taskStore.load()
            if (DownloadAuthPause.resumable(before).isEmpty()) {
                stopSelf()
                return START_NOT_STICKY
            }
            app.taskStore.save(DownloadAuthPause.resumeAll(before))
            authPaused = false
            // 旧实例可能仍在 finally 中退出，其 schedulerJob 仍是 isActive，会让 startScheduler 直接返回；
            // 这里先取消并置空，确保恢复时一定能启动新的调度（此时任务都已 PAUSED，无活跃下载会被误杀）。
            schedulerJob?.cancel()
            schedulerJob = null
            startForegroundCompat(buildNotification("已重新登录，继续下载…", 0, 0, true))
            startScheduler(app)
            return START_NOT_STICKY
        }
        val taskId = intent?.getStringExtra(EXTRA_TASK_ID)
        when (intent?.action) {
            ACTION_PAUSE_ALL -> {
                pausedAll.set(true)
                schedulerJob?.cancel()
                schedulerJob = null
                activeJobs.values.forEach { it.cancel() }
                updateNotification("已暂停", 0, 0, false)
                return START_NOT_STICKY
            }
            ACTION_RESUME_ALL -> {
                pausedAll.set(false)
                cancelledAll.set(false)
                val ids = app.taskStore.pending()
                    .filter { it.status != DownloadTaskStatus.COMPLETED }
                    .map { it.id }
                    .toSet()
                if (ids.isNotEmpty()) {
                    app.taskStore.save(
                        app.taskStore.load().map {
                            if (it.id in ids) {
                                DownloadAuthPause.clearPausedByAuth(it)
                                    .copy(status = DownloadTaskStatus.QUEUED)
                            } else {
                                it
                            }
                        }
                    )
                }
                startForegroundCompat(buildNotification("继续下载…", 0, 0, true))
                startScheduler(app)
                return START_NOT_STICKY
            }
            ACTION_CANCEL_ALL -> {
                cancelledAll.set(true)
                pausedAll.set(false)
                schedulerJob?.cancel()
                schedulerJob = null
                activeJobs.values.forEach { it.cancel() }
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PAUSE_TASK -> {
                if (taskId != null) {
                    activeJobs[taskId]?.cancel()
                    app.taskStore.load().firstOrNull { it.id == taskId }?.let {
                        app.taskStore.upsert(
                            DownloadAuthPause.clearPausedByAuth(it)
                                .copy(status = DownloadTaskStatus.PAUSED)
                        )
                    }
                }
                return START_NOT_STICKY
            }
            ACTION_RESUME_TASK -> {
                if (taskId != null) {
                    app.taskStore.load().firstOrNull { it.id == taskId }?.let {
                        app.taskStore.upsert(
                            DownloadAuthPause.clearPausedByAuth(it)
                                .copy(status = DownloadTaskStatus.QUEUED, error = null)
                        )
                    }
                    startForegroundCompat(buildNotification("继续下载…", 0, 0, true))
                    startScheduler(app)
                }
                return START_NOT_STICKY
            }
            ACTION_RETRY_TASK -> {
                if (taskId != null) {
                    app.taskStore.load().firstOrNull { it.id == taskId }?.let {
                        app.taskStore.upsert(
                            DownloadAuthPause.clearPausedByAuth(it).copy(
                                status = DownloadTaskStatus.QUEUED,
                                retryCount = 0,
                                error = null,
                            )
                        )
                    }
                    startForegroundCompat(buildNotification("重试下载…", 0, 0, true))
                    startScheduler(app)
                }
                return START_NOT_STICKY
            }
            ACTION_REMOVE_TASK -> {
                if (taskId != null) {
                    removedTasks.add(taskId)
                    activeJobs[taskId]?.cancel()
                    app.taskStore.remove(taskId)
                }
                return START_NOT_STICKY
            }
            ACTION_PAUSE_BATCH -> {
                val batchId = intent.getStringExtra(EXTRA_BATCH_ID)
                if (batchId != null) {
                    val all = app.taskStore.load()
                    all.filter { it.batchId == batchId }.forEach { task ->
                        activeJobs[task.id]?.cancel()
                        if (task.status != DownloadTaskStatus.COMPLETED) {
                            app.taskStore.upsert(
                                DownloadAuthPause.clearPausedByAuth(task)
                                    .copy(status = DownloadTaskStatus.PAUSED)
                            )
                        }
                    }
                }
                return START_NOT_STICKY
            }
            ACTION_RESUME_BATCH -> {
                val batchId = intent.getStringExtra(EXTRA_BATCH_ID)
                if (batchId != null) {
                    val all = app.taskStore.load()
                    val changed = all.filter {
                        it.batchId == batchId && it.status != DownloadTaskStatus.COMPLETED
                    }.map {
                        DownloadAuthPause.clearPausedByAuth(it)
                            .copy(status = DownloadTaskStatus.QUEUED, error = null)
                    }
                    if (changed.isNotEmpty()) {
                        val byId = changed.associateBy { it.id }
                        app.taskStore.save(all.map { byId[it.id] ?: it })
                    }
                    startForegroundCompat(buildNotification("继续下载…", 0, 0, true))
                    startScheduler(app)
                }
                return START_NOT_STICKY
            }
            ACTION_RETRY_BATCH -> {
                val batchId = intent.getStringExtra(EXTRA_BATCH_ID)
                if (batchId != null) {
                    val all = app.taskStore.load()
                    val changed = all.filter {
                        it.batchId == batchId && it.status != DownloadTaskStatus.COMPLETED
                    }.map {
                        DownloadAuthPause.clearPausedByAuth(it).copy(
                            status = DownloadTaskStatus.QUEUED, retryCount = 0, error = null,
                        )
                    }
                    if (changed.isNotEmpty()) {
                        val byId = changed.associateBy { it.id }
                        app.taskStore.save(all.map { byId[it.id] ?: it })
                    }
                    startForegroundCompat(buildNotification("重试下载…", 0, 0, true))
                    startScheduler(app)
                }
                return START_NOT_STICKY
            }
            ACTION_REMOVE_BATCH -> {
                val batchId = intent.getStringExtra(EXTRA_BATCH_ID)
                if (batchId != null) {
                    val all = app.taskStore.load()
                    all.filter { it.batchId == batchId }.forEach { task ->
                        // 先加入 removedTasks 再取消，避免 runTask 的取消分支写回 PAUSED
                        removedTasks.add(task.id)
                        activeJobs[task.id]?.cancel()
                    }
                    val ids = all.filter { it.batchId == batchId }.map { it.id }.toSet()
                    app.taskStore.save(all.filterNot { it.id in ids })
                    app.downloadBatchStore.remove(batchId)
                }
                return START_NOT_STICKY
            }
        }

        val treeUri = intent?.getStringExtra(EXTRA_TREE_URI)?.let(Uri::parse)
        val quality = intent?.getStringExtra(EXTRA_QUALITY) ?: "lossless"
        // 整歌单可能有上千首曲目：JSON 直接塞 Intent extra 会触发 Binder 1MB 事务上限，
        // 改为由调用方写入应用内部文件、这里只接收文件路径并恢复批次 + 曲目。
        val batchFile = intent?.getStringExtra(EXTRA_BATCH_FILE)
        val tracksFile = intent?.getStringExtra(EXTRA_TRACKS_FILE)
        var batch: DownloadBatch? = null
        var tracks: List<QQMusicTrack> = emptyList()
        if (!batchFile.isNullOrBlank()) {
            runCatching {
                val payload = JSONObject(File(batchFile).readText())
                batch = parseBatch(payload.optJSONObject("batch"))
                tracks = parseTracksArray(payload.optJSONArray("tracks"))
            }.onFailure { QmDebugLog.log("Service", "读取批次文件失败：${it.message}") }
        } else {
            // 兼容旧调用方：仅曲目文件 / 直接传 JSON
            tracks = if (!tracksFile.isNullOrBlank()) {
                runCatching { parseTracks(File(tracksFile).readText()) }
                    .onFailure { QmDebugLog.log("Service", "读取曲目文件失败：${it.message}") }
                    .getOrDefault(emptyList())
            } else {
                parseTracks(intent?.getStringExtra(EXTRA_TRACKS))
            }
            // 写文件失败时退回 Intent extra 的兜底批次信息
            batch = intent?.getStringExtra(EXTRA_BATCH_JSON)
                ?.let { runCatching { parseBatch(JSONObject(it)) }.getOrNull() }
        }
        if (treeUri == null || tracks.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Service 是 Android 侧任务写入方：批次与任务都在这里落库，Activity 只读展示
        batch?.let { app.downloadBatchStore.upsert(it) }
        val recordBatch = batch
        val existing = app.taskStore.load().filterNot { it.status == DownloadTaskStatus.COMPLETED }
        val records = tracks.mapIndexed { index, track ->
            DownloadTaskRecord.fromTrack(
                track,
                quality,
                treeUri.toString(),
                recordBatch?.id,
                recordBatch?.subDir,
                index,
            )
        }
        val recordIds = records.map { it.id }.toSet()
        app.taskStore.save(existing.filterNot { it.id in recordIds } + records)

        pausedAll.set(false)
        cancelledAll.set(false)
        successCount.set(0)
        failedCount.set(0)
        QmDebugLog.log("Service", "start tracks=${tracks.size} quality=$quality tree=$treeUri")
        startForegroundCompat(buildNotification("准备下载…", 0, 0, true))
        startScheduler(app)
        return START_NOT_STICKY
    }

    private fun startScheduler(app: QmApplication) {
        if (schedulerJob?.isActive == true) return
        schedulerJob = scope.launch {
            val prefs = getSharedPreferences("qm_download_ui", Context.MODE_PRIVATE)
            try {
                while (isActive && !pausedAll.get() && !cancelledAll.get()) {
                    val maxConcurrent = prefs.getInt("maxConcurrent", 2).coerceIn(1, 6)
                    val queued = app.taskStore.load().filter {
                        it.status == DownloadTaskStatus.QUEUED && !activeJobs.containsKey(it.id)
                    }
                    val slots = maxConcurrent - activeJobs.size
                    if (slots > 0) {
                        queued.take(slots).forEach { task ->
                            val job = scope.launch(start = CoroutineStart.LAZY) {
                                try {
                                    runTask(app, task.id)
                                } finally {
                                    activeJobs.remove(task.id)
                                }
                            }
                            activeJobs[task.id] = job
                            job.start()
                        }
                    }
                    if (activeJobs.isEmpty() && app.taskStore.pending().none {
                            it.status == DownloadTaskStatus.QUEUED
                        }
                    ) {
                        break
                    }
                    updateNotification(
                        "下载中 ${activeJobs.size}/$maxConcurrent",
                        successCount.get(),
                        successCount.get() + failedCount.get(),
                        false,
                    )
                    delay(500)
                }
            } finally {
                if (authPaused) {
                    // 登录过期已暂停整队列：给出明确提示后停止前台服务，等待重新登录自动恢复
                    updateNotification("登录已过期，任务已暂停，请重新登录", 0, 0, false)
                    stopForegroundCompat()
                    stopSelf()
                } else if (!pausedAll.get() && !cancelledAll.get()) {
                    val unfinished = app.taskStore.pending()
                        .filter { it.status != DownloadTaskStatus.COMPLETED }
                    if (unfinished.isEmpty()) {
                        val text = "完成 ${successCount.get()}，失败 ${failedCount.get()}"
                        updateNotification(text, 100, 100, false)
                        stopForegroundCompat()
                        stopSelf()
                    }
                }
            }
        }
    }

    private suspend fun runTask(app: QmApplication, id: String) {
        var task = app.taskStore.load().firstOrNull { it.id == id } ?: return
        // 每任务最多强制续签一次，避免续签成功但接口仍拒绝时无限循环
        var refreshAttempted = false
        while (true) {
            if (pausedAll.get() || cancelledAll.get()) {
                app.taskStore.upsert(task.copy(status = DownloadTaskStatus.PAUSED))
                return
            }
            app.taskStore.upsert(
                task.copy(
                    status = DownloadTaskStatus.DOWNLOADING,
                    error = null,
                    updatedAt = System.currentTimeMillis(),
                )
            )
            try {
                var lastPersist = 0L
                val saved = app.androidDownloadManager.download(
                    track = task.toTrack(),
                    treeUri = Uri.parse(task.target),
                    qualityKey = task.quality,
                    subDir = task.subDir,
                ) { downloaded, total ->
                    val now = System.currentTimeMillis()
                    if (now - lastPersist >= 500L) {
                        lastPersist = now
                        app.taskStore.upsert(
                            task.copy(
                                status = DownloadTaskStatus.DOWNLOADING,
                                downloadedBytes = downloaded,
                                totalBytes = total ?: 0L,
                                updatedAt = now,
                            )
                        )
                        val text = if (total != null && total > 0) {
                            "${task.title} ${downloaded * 100 / total}%"
                        } else {
                            "${task.title} ${downloaded / 1024}KB"
                        }
                        updateNotification(text, 0, 0, true)
                    }
                }
                app.taskStore.remove(id)
                successCount.incrementAndGet()
                QmDebugLog.log("Service", "saved ${saved ?: "existing"} ${task.title}")
                return
            } catch (cancelled: CancellationException) {
                if (!removedTasks.contains(id)) {
                    app.taskStore.upsert(
                        task.copy(
                            status = DownloadTaskStatus.PAUSED,
                            updatedAt = System.currentTimeMillis(),
                        )
                    )
                }
                throw cancelled
            } catch (error: Exception) {
                if (removedTasks.contains(id)) return
                if (error is QmError.AuthExpired) {
                    // 若仍持有 refresh_token，强制续签一次后用新登录态重试本任务
                    if (!refreshAttempted &&
                        app.sessionStore.load()?.cookie?.contains("psrf_qqrefresh_token=") == true
                    ) {
                        refreshAttempted = true
                        QmDebugLog.log("Service", "AuthExpired，尝试续签后重试：${task.title}")
                        if (app.ensureSessionFresh(force = true)) {
                            task = task.copy(
                                status = DownloadTaskStatus.QUEUED,
                                error = null,
                                updatedAt = System.currentTimeMillis(),
                            )
                            app.taskStore.upsert(task)
                            continue
                        }
                    }
                    // 续签失败 / 无凭据：暂停全部未完成任务，等待重新登录后自动恢复，不标 FAILED
                    app.taskStore.save(DownloadAuthPause.pauseAll(app.taskStore.load()))
                    authPaused = true
                    QmDebugLog.log("Service", "登录已过期，已暂停任务等待重新登录")
                    updateNotification("登录已过期，任务已暂停，请重新登录", 0, 0, false)
                    return
                }
                val retryable = error !is QmError.NoPermission &&
                    error !is QmError.NoResource &&
                    task.retryCount < MAX_RETRIES
                if (retryable) {
                    task = task.copy(
                        status = DownloadTaskStatus.QUEUED,
                        retryCount = task.retryCount + 1,
                        error = error.message,
                        updatedAt = System.currentTimeMillis(),
                    )
                    app.taskStore.upsert(task)
                    QmDebugLog.log(
                        "Service",
                        "retry ${task.retryCount}/$MAX_RETRIES ${task.title}: ${error.message}",
                    )
                    try {
                        delay(1_000L * task.retryCount)
                    } catch (retryCancelled: CancellationException) {
                        if (!removedTasks.contains(id)) {
                            app.taskStore.upsert(
                                task.copy(status = DownloadTaskStatus.PAUSED)
                            )
                        }
                        throw retryCancelled
                    }
                    continue
                }
                app.taskStore.upsert(
                    task.copy(
                        status = DownloadTaskStatus.FAILED,
                        error = error.message,
                        updatedAt = System.currentTimeMillis(),
                    )
                )
                failedCount.incrementAndGet()
                QmDebugLog.log("Service", "failed ${task.title}: ${error.message}")
                return
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        schedulerJob?.cancel()
        activeJobs.values.forEach { it.cancel() }
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundCompat(notification: android.app.Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun stopForegroundCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    private fun buildNotification(
        text: String,
        progress: Int,
        max: Int,
        indeterminate: Boolean,
    ): android.app.Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val pauseIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, DownloadService::class.java).setAction(ACTION_PAUSE_ALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val resumeIntent = PendingIntent.getService(
            this,
            3,
            Intent(this, DownloadService::class.java).setAction(ACTION_RESUME_ALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val cancelIntent = PendingIntent.getService(
            this,
            4,
            Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL_ALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("QM-Download")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(pausedAll.get() || schedulerJob?.isActive == true)
            .setContentIntent(openIntent)
            .addAction(
                0,
                if (pausedAll.get()) "继续" else "暂停",
                if (pausedAll.get()) resumeIntent else pauseIntent,
            )
            .addAction(0, "取消", cancelIntent)
            .apply {
                if (indeterminate) setProgress(0, 0, true) else setProgress(max, progress, false)
            }
            .build()
    }

    private fun updateNotification(
        text: String,
        progress: Int,
        max: Int,
        indeterminate: Boolean,
    ) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(
            NOTIFICATION_ID,
            buildNotification(text, progress, max, indeterminate),
        )
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "下载",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "QQ 音乐下载进度" }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun parseTracks(raw: String?): List<QQMusicTrack> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { parseTracksArray(JSONArray(raw)) }.getOrDefault(emptyList())
    }

    private fun parseTracksArray(array: JSONArray?): List<QQMusicTrack> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val songMid = item.optString("songMid").takeIf(String::isNotBlank) ?: continue
                add(
                    QQMusicTrack(
                        songMid = songMid,
                        mediaMid = item.optString("mediaMid").takeIf(String::isNotBlank),
                        title = item.optString("title", "未知歌曲"),
                        artist = item.optString("artist", "未知艺术家"),
                        album = item.optString("album").takeIf(String::isNotBlank),
                        coverUrl = item.optString("coverUrl").takeIf(String::isNotBlank),
                        durationMs = item.optLong("durationMs", 0L),
                    )
                )
            }
        }
    }

    private fun parseBatch(json: JSONObject?): DownloadBatch? {
        if (json == null) return null
        val id = json.optString("id").takeIf(String::isNotBlank) ?: return null
        return DownloadBatch(
            id = id,
            playlistId = json.optString("playlistId").takeIf(String::isNotBlank),
            playlistTitle = json.optString("playlistTitle"),
            subDir = json.optString("subDir"),
            rootTarget = json.optString("rootTarget"),
            quality = json.optString("quality", "lossless"),
            total = json.optInt("total", 0),
            createdAt = json.optLong("createdAt", System.currentTimeMillis()),
        )
    }

    companion object {
        const val ACTION_PAUSE_ALL = "moe.ouom.qmdownload.action.PAUSE_ALL"
        const val ACTION_RESUME_ALL = "moe.ouom.qmdownload.action.RESUME_ALL"
        const val ACTION_CANCEL_ALL = "moe.ouom.qmdownload.action.CANCEL_ALL"
        const val ACTION_PAUSE_TASK = "moe.ouom.qmdownload.action.PAUSE_TASK"
        const val ACTION_RESUME_TASK = "moe.ouom.qmdownload.action.RESUME_TASK"
        const val ACTION_RETRY_TASK = "moe.ouom.qmdownload.action.RETRY_TASK"
        const val ACTION_REMOVE_TASK = "moe.ouom.qmdownload.action.REMOVE_TASK"
        const val ACTION_PAUSE_BATCH = "moe.ouom.qmdownload.action.PAUSE_BATCH"
        const val ACTION_RESUME_BATCH = "moe.ouom.qmdownload.action.RESUME_BATCH"
        const val ACTION_RETRY_BATCH = "moe.ouom.qmdownload.action.RETRY_BATCH"
        const val ACTION_REMOVE_BATCH = "moe.ouom.qmdownload.action.REMOVE_BATCH"
        const val ACTION_RESUME_AUTH_PAUSED = "moe.ouom.qmdownload.action.RESUME_AUTH_PAUSED"
        const val EXTRA_TRACKS = "tracks"
        const val EXTRA_TRACKS_FILE = "tracks_file"
        const val EXTRA_BATCH_FILE = "batch_file"
        const val EXTRA_TREE_URI = "tree_uri"
        const val EXTRA_QUALITY = "quality"
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_BATCH_ID = "batch_id"
        private const val EXTRA_BATCH_JSON = "batch_json"
        private const val CHANNEL_ID = "qm_download"
        private const val NOTIFICATION_ID = 1001
        private const val MAX_RETRIES = 3

        /** 旧入口：无批次信息，Service 会生成 batchId=null 的旧式平铺任务。 */
        fun start(context: Context, tracks: List<QQMusicTrack>, treeUri: Uri, quality: String) {
            val array = tracksToArray(tracks)
            val intent = Intent(context, DownloadService::class.java)
                .putExtra(EXTRA_TREE_URI, treeUri.toString())
                .putExtra(EXTRA_QUALITY, quality)
            // 写入应用内部私有目录，仅把文件路径放进 Intent，规避 Binder 事务上限
            val file = writePayload(context, array.toString())
            if (file != null) {
                intent.putExtra(EXTRA_TRACKS_FILE, file.absolutePath)
            } else {
                // 极端情况下写文件失败，退回旧方式（小歌单仍可工作）
                intent.putExtra(EXTRA_TRACKS, array.toString())
            }
            startServiceCompat(context, intent)
        }

        /**
         * 批次入口：把批次与曲目写进同一个 payload 文件，Intent 只携带一个路径。
         * 避免拆成两次 Intent 传输，也规避 Binder 1MB 事务上限。
         */
        fun start(
            context: Context,
            batch: DownloadBatch,
            tracks: List<QQMusicTrack>,
            treeUri: Uri,
            quality: String,
        ) {
            val array = tracksToArray(tracks)
            val batchJson = JSONObject()
                .put("id", batch.id)
                .put("playlistId", batch.playlistId.orEmpty())
                .put("playlistTitle", batch.playlistTitle)
                .put("subDir", batch.subDir)
                .put("rootTarget", batch.rootTarget)
                .put("quality", batch.quality)
                .put("total", batch.total)
                .put("createdAt", batch.createdAt)
            val payload = JSONObject()
                .put("batch", batchJson)
                .put("tracks", array)
            val intent = Intent(context, DownloadService::class.java)
                .putExtra(EXTRA_TREE_URI, treeUri.toString())
                .putExtra(EXTRA_QUALITY, quality)
            val file = writePayload(context, payload.toString())
            if (file != null) {
                intent.putExtra(EXTRA_BATCH_FILE, file.absolutePath)
            } else {
                // 兜底：写文件失败时退回 Intent extra（小歌单仍可工作，批次信息不丢）
                intent.putExtra(EXTRA_TRACKS, array.toString())
                intent.putExtra(EXTRA_BATCH_JSON, batchJson.toString())
            }
            startServiceCompat(context, intent)
        }

        private fun tracksToArray(tracks: List<QQMusicTrack>): JSONArray = JSONArray().apply {
            tracks.forEach { track ->
                put(
                    JSONObject()
                        .put("songMid", track.songMid)
                        .put("mediaMid", track.mediaMid.orEmpty())
                        .put("title", track.title)
                        .put("artist", track.artist)
                        .put("album", track.album.orEmpty())
                        .put("coverUrl", track.coverUrl.orEmpty())
                        .put("durationMs", track.durationMs),
                )
            }
        }

        private fun writePayload(context: Context, json: String): File? = runCatching {
            val dir = File(context.filesDir, "pending_tracks")
            dir.mkdirs()
            // 清理一小时前的旧文件，避免反复提交后持续堆积
            dir.listFiles { candidate ->
                candidate.name.startsWith("tracks_") &&
                    candidate.lastModified() < System.currentTimeMillis() - 3_600_000L
            }?.forEach { runCatching { it.delete() } }
            val target = File(dir, "tracks_${System.currentTimeMillis()}_${UUID.randomUUID()}.json")
            target.writeText(json)
            target
        }.getOrNull()

        private fun startServiceCompat(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun taskAction(context: Context, action: String, taskId: String) {
            context.startService(
                Intent(context, DownloadService::class.java)
                    .setAction(action)
                    .putExtra(EXTRA_TASK_ID, taskId)
            )
        }

        /** 登录成功后调用：把因登录过期暂停的任务改回 QUEUED 并重新启动调度。 */
        fun resumeAuthPaused(context: Context) {
            val intent = Intent(context, DownloadService::class.java)
                .setAction(ACTION_RESUME_AUTH_PAUSED)
            startServiceCompat(context, intent)
        }

        fun pauseAll(context: Context) = taskAction(context, ACTION_PAUSE_ALL, "")
        fun resumeAll(context: Context) = taskAction(context, ACTION_RESUME_ALL, "")
        fun pauseTask(context: Context, id: String) = taskAction(context, ACTION_PAUSE_TASK, id)
        fun resumeTask(context: Context, id: String) = taskAction(context, ACTION_RESUME_TASK, id)
        fun retryTask(context: Context, id: String) = taskAction(context, ACTION_RETRY_TASK, id)
        fun removeTask(context: Context, id: String) = taskAction(context, ACTION_REMOVE_TASK, id)

        private fun batchAction(context: Context, action: String, batchId: String) {
            context.startService(
                Intent(context, DownloadService::class.java)
                    .setAction(action)
                    .putExtra(EXTRA_BATCH_ID, batchId)
            )
        }

        fun pauseBatch(context: Context, id: String) = batchAction(context, ACTION_PAUSE_BATCH, id)
        fun resumeBatch(context: Context, id: String) = batchAction(context, ACTION_RESUME_BATCH, id)
        fun retryBatch(context: Context, id: String) = batchAction(context, ACTION_RETRY_BATCH, id)
        fun removeBatch(context: Context, id: String) = batchAction(context, ACTION_REMOVE_BATCH, id)
    }
}

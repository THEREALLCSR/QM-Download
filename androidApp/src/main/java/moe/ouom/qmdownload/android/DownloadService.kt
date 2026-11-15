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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
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
    private val successCount = java.util.concurrent.atomic.AtomicInteger(0)
    private val failedCount = java.util.concurrent.atomic.AtomicInteger(0)

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as QmApplication
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
                                it.copy(status = DownloadTaskStatus.QUEUED)
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
                            it.copy(status = DownloadTaskStatus.PAUSED)
                        )
                    }
                }
                return START_NOT_STICKY
            }
            ACTION_RESUME_TASK -> {
                if (taskId != null) {
                    app.taskStore.load().firstOrNull { it.id == taskId }?.let {
                        app.taskStore.upsert(
                            it.copy(status = DownloadTaskStatus.QUEUED, error = null)
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
                            it.copy(
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
        }

        val treeUri = intent?.getStringExtra(EXTRA_TREE_URI)?.let(Uri::parse)
        val quality = intent?.getStringExtra(EXTRA_QUALITY) ?: "lossless"
        val tracks = parseTracks(intent?.getStringExtra(EXTRA_TRACKS))
        if (treeUri == null || tracks.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        val existing = app.taskStore.load().filterNot { it.status == DownloadTaskStatus.COMPLETED }
        val records = tracks.map { track ->
            DownloadTaskRecord.fromTrack(track, quality, treeUri.toString())
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
                if (!pausedAll.get() && !cancelledAll.get()) {
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
                val retryable = error !is QmError.AuthExpired &&
                    error !is QmError.NoPermission &&
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
        return runCatching {
            val array = JSONArray(raw)
            buildList {
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
        }.getOrDefault(emptyList())
    }

    companion object {
        const val ACTION_PAUSE_ALL = "moe.ouom.qmdownload.action.PAUSE_ALL"
        const val ACTION_RESUME_ALL = "moe.ouom.qmdownload.action.RESUME_ALL"
        const val ACTION_CANCEL_ALL = "moe.ouom.qmdownload.action.CANCEL_ALL"
        const val ACTION_PAUSE_TASK = "moe.ouom.qmdownload.action.PAUSE_TASK"
        const val ACTION_RESUME_TASK = "moe.ouom.qmdownload.action.RESUME_TASK"
        const val ACTION_RETRY_TASK = "moe.ouom.qmdownload.action.RETRY_TASK"
        const val ACTION_REMOVE_TASK = "moe.ouom.qmdownload.action.REMOVE_TASK"
        const val EXTRA_TRACKS = "tracks"
        const val EXTRA_TREE_URI = "tree_uri"
        const val EXTRA_QUALITY = "quality"
        const val EXTRA_TASK_ID = "task_id"
        private const val CHANNEL_ID = "qm_download"
        private const val NOTIFICATION_ID = 1001
        private const val MAX_RETRIES = 3

        fun start(context: Context, tracks: List<QQMusicTrack>, treeUri: Uri, quality: String) {
            val array = JSONArray()
            tracks.forEach { track ->
                array.put(
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
            val intent = Intent(context, DownloadService::class.java)
                .putExtra(EXTRA_TRACKS, array.toString())
                .putExtra(EXTRA_TREE_URI, treeUri.toString())
                .putExtra(EXTRA_QUALITY, quality)
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

        fun pauseAll(context: Context) = taskAction(context, ACTION_PAUSE_ALL, "")
        fun resumeAll(context: Context) = taskAction(context, ACTION_RESUME_ALL, "")
        fun pauseTask(context: Context, id: String) = taskAction(context, ACTION_PAUSE_TASK, id)
        fun resumeTask(context: Context, id: String) = taskAction(context, ACTION_RESUME_TASK, id)
        fun retryTask(context: Context, id: String) = taskAction(context, ACTION_RETRY_TASK, id)
        fun removeTask(context: Context, id: String) = taskAction(context, ACTION_REMOVE_TASK, id)
    }
}

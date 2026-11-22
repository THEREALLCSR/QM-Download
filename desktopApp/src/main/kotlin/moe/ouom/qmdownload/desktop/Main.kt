package moe.ouom.qmdownload.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.io.File
import java.awt.Desktop
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JFileChooser
import kotlin.system.exitProcess
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import okhttp3.Request
import moe.ouom.qmdownload.core.auth.QQMusicQrLoginClient
import moe.ouom.qmdownload.core.auth.QQMusicQrLoginSession
import moe.ouom.qmdownload.core.auth.QQMusicQrLoginState
import moe.ouom.qmdownload.core.auth.QQMusicSessionStore
import moe.ouom.qmdownload.core.auth.QQMusicSessionRefresher
import moe.ouom.qmdownload.core.auth.parseQQMusicCookie
import moe.ouom.qmdownload.core.debug.QmDebugLog
import moe.ouom.qmdownload.core.download.AudioFileValidator
import moe.ouom.qmdownload.core.download.AudioTagWriter
import moe.ouom.qmdownload.core.download.ByteRateLimiter
import moe.ouom.qmdownload.core.download.DownloadAuthPause
import moe.ouom.qmdownload.core.download.DownloadBatch
import moe.ouom.qmdownload.core.download.DownloadBatchPaths
import moe.ouom.qmdownload.core.download.DownloadBatchStore
import moe.ouom.qmdownload.core.download.DownloadFileNames
import moe.ouom.qmdownload.core.download.DownloadOptions
import moe.ouom.qmdownload.core.download.DownloadTaskRecord
import moe.ouom.qmdownload.core.download.DownloadTaskStatus
import moe.ouom.qmdownload.core.download.FileDownloadTaskStore
import moe.ouom.qmdownload.core.download.QQMusicDownloader
import moe.ouom.qmdownload.core.error.QmError
import moe.ouom.qmdownload.core.link.QQMusicShareLinkParser
import moe.ouom.qmdownload.core.library.PlaylistPaging
import moe.ouom.qmdownload.core.library.QQMusicLibraryRepository
import moe.ouom.qmdownload.core.model.QQMusicAccountProfile
import moe.ouom.qmdownload.core.model.QQMusicPlaylist
import moe.ouom.qmdownload.core.model.QQMusicSession
import moe.ouom.qmdownload.core.model.QQMusicTrack
import moe.ouom.qmdownload.core.playback.QQMusicUrlResolver
import moe.ouom.qmdownload.core.profile.QQMusicProfileRepository
import okhttp3.OkHttpClient
import org.jetbrains.skia.Image as SkiaImage

private const val APP_VERSION = "0.5"

fun main() {
    initDebugLogging()
    application {
        Window(
            onCloseRequest = {
                exitApplication()
                // 确保 OkHttp/协程线程不会阻止 JVM 退出
                exitProcess(0)
            },
            state = rememberWindowState(width = 1180.dp, height = 820.dp),
            title = "QM-Download v$APP_VERSION",
        ) {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DesktopApp()
                }
            }
        }
    }
}

private fun initDebugLogging() {
    val logFile = File(AppPaths.dataDir(), "logs/download.log")
    logFile.parentFile?.mkdirs()
    QmDebugLog.enabled = true
    QmDebugLog.sink = { line ->
        println(line)
        runCatching { logFile.appendText(line + "\n") }
    }
    QmDebugLog.log("App", "desktop started log=${logFile.absolutePath}")
}

private class PortableSessionStore : QQMusicSessionStore {
    private val file = AppPaths.dataDir().resolve("session.properties")

    override fun load(): QQMusicSession? = runCatching {
        if (!file.exists()) return null
        val properties = Properties().apply { file.inputStream().use(::load) }
        QQMusicSession(
            cookie = properties.getProperty("cookie").orEmpty(),
            uin = properties.getProperty("uin").orEmpty(),
            musicKey = properties.getProperty("musicKey").orEmpty(),
            wid = properties.getProperty("wid").orEmpty(),
        ).takeIf { it.isLoggedIn }
    }.getOrNull()

    override fun save(session: QQMusicSession) {
        file.parentFile?.mkdirs()
        val properties = Properties().apply {
            setProperty("cookie", session.cookie)
            setProperty("uin", session.uin)
            setProperty("musicKey", session.musicKey)
            setProperty("wid", session.wid)
        }
        file.outputStream().use { properties.store(it, "QM-Download QQ session") }
    }

    override fun clear() {
        runCatching { file.delete() }
    }
}

private object AppPaths {
    fun dataDir(): File {
        val packaged = System.getProperty("jpackage.app-path")?.let { File(it).parentFile }
        val executable = runCatching {
            ProcessHandle.current().info().command().orElse(null)
        }.getOrNull()
        val portable = executable
            ?.let { File(it).parentFile }
            ?.takeIf { File(it, "QM-Download.exe").exists() }
        val base = packaged ?: portable ?: File(System.getProperty("user.dir"))
        return File(base, "data").apply { mkdirs() }
    }

    fun configFile(): File = File(dataDir(), "config.properties")

    fun batchesFile(): File = File(dataDir(), "batches.json")
}

private enum class DesktopTab {
    LIBRARY,
    DOWNLOADS,
}

private const val MAX_RETRIES = 3

@Composable
private fun DesktopApp() {
    val client = remember { OkHttpClient() }
    val sessionStore = remember { PortableSessionStore() }
    var session by remember { mutableStateOf(sessionStore.load()) }
    var profile by remember { mutableStateOf<QQMusicAccountProfile?>(null) }
    var playlists by remember { mutableStateOf<List<QQMusicPlaylist>>(emptyList()) }
    var selected by remember { mutableStateOf<QQMusicPlaylist?>(null) }
    var tracks by remember { mutableStateOf<List<QQMusicTrack>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var qrSession by remember { mutableStateOf<QQMusicQrLoginSession?>(null) }
    var qrImage by remember { mutableStateOf<ImageBitmap?>(null) }
    var qrStatus by remember { mutableStateOf("等待扫码") }
    var selectedSongMids by remember { mutableStateOf<Set<String>>(emptySet()) }
    var downloadDir by remember { mutableStateOf(loadDownloadDir()) }
    var downloading by remember { mutableStateOf(false) }
    var downloadStatus by remember { mutableStateOf("") }
    var qualityKey by remember { mutableStateOf(loadQuality()) }
    var segmented by remember { mutableStateOf(loadSegmented()) }
    var avatarBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }
    val taskStore = remember {
        FileDownloadTaskStore(File(AppPaths.dataDir(), "tasks.json"))
    }
    val batchStore = remember { DownloadBatchStore(AppPaths.batchesFile()) }
    var batches by remember { mutableStateOf(batchStore.load()) }
    var speedLimitKbps by remember { mutableStateOf(loadSpeedLimit()) }
    val rateLimiter = remember { ByteRateLimiter(loadSpeedLimit() * 1024L) }
    var pendingCount by remember { mutableStateOf(taskStore.pending().size) }
    var tab by remember { mutableStateOf(DesktopTab.LIBRARY) }
    var maxConcurrent by remember { mutableStateOf(loadConcurrency()) }
    val taskJobs = remember { ConcurrentHashMap<String, Job>() }
    var tasks by remember { mutableStateOf(taskStore.load()) }
    var shareLink by remember { mutableStateOf("") }
    var shareError by remember { mutableStateOf<String?>(null) }
    var shareLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // 续签串行锁：启动自检、定时检查与 AuthExpired 兜底可能并发触发，避免同一 refresh_token 被并发使用
    val refreshMutex = remember { Mutex() }

    val library = remember(client) { QQMusicLibraryRepository(client) { session } }
    val profileRepo = remember(client) { QQMusicProfileRepository(client) }
    val resolver = remember(client) { QQMusicUrlResolver(client) { session } }
    val downloader = remember(client) { QQMusicDownloader(client) }

    suspend fun refresh() {
        val current = session ?: return
        loading = true
        error = null
        runCatching {
            profile = profileRepo.load(current)
            playlists = library.myPlaylists()
        }.onFailure { error = it.message }
        loading = false
    }

    /**
     * 登录态自检与续签（启动自检 / 定时检查 / AuthExpired 兜底三处共用）。
     *
     * - 始终从 sessionStore 读最新 cookie，不只依赖 Compose 快照；
     * - 非 force 时只在临近 keyst 过期（约 20h）才续签；force 用于 AuthExpired 兜底强行续签一次；
     * - 成功：校验 isLoggedIn -> 持久化 -> 更新 Compose 状态 -> 给出友好提示；
     * - 失败：一律降级——保留原 session 不动，让后续接口自然报 AuthExpired 并提示重新扫码；
     *   绝不因为续签失败就清空 session 或阻止进入主界面。
     *
     * @return 本次是否续签成功。
     */
    suspend fun ensureSessionFresh(force: Boolean = false): Boolean = refreshMutex.withLock {
        val stored = sessionStore.load()
        if (stored == null || !stored.isLoggedIn) return@withLock false
        val cookie = stored.cookie
        // 旧 Cookie 缺 psrf_musickey_createtime 时 isNearExpiry 返回 true，正好借续签补齐时间锚点
        if (!force && !QQMusicSessionRefresher.isNearExpiry(cookie)) return@withLock false

        val newCookie = runCatching { QQMusicSessionRefresher.refresh(cookie, client) }
            .getOrElse { e ->
                if (e is CancellationException) throw e
                QmDebugLog.log("SessionRefresh", "续签失败，保留原登录态：${e.message}")
                return@withLock false
            }
        val newSession = parseQQMusicCookie(newCookie)
        if (!newSession.isLoggedIn) {
            QmDebugLog.log("SessionRefresh", "续签响应登录态无效，保留原登录态")
            return@withLock false
        }
        sessionStore.save(newSession)
        session = newSession
        downloadStatus = "登录态已自动续期"
        QmDebugLog.log("SessionRefresh", "登录态自动续期成功")
        true
    }

    suspend fun downloadOne(id: String, success: AtomicInteger, skipped: AtomicInteger) {
        var task = taskStore.load().firstOrNull { it.id == id } ?: return
        // 每个任务最多强制续签重试一次，避免续签成功但接口仍拒绝时无限循环
        var refreshAttempted = false
        if (task.status == DownloadTaskStatus.COMPLETED) return
        taskJobs[id] = currentCoroutineContext()[Job] ?: return
        try {
            while (true) {
                taskStore.upsert(
                    task.copy(
                        status = DownloadTaskStatus.DOWNLOADING,
                        error = null,
                        updatedAt = System.currentTimeMillis(),
                    )
                )
                try {
                    val track = task.toTrack()
                    downloadStatus = "解析：${track.title}"
                    val resolved = resolver.resolve(track, task.quality)
                    // 批次任务写入 <target>/<歌单名> 子目录；旧任务仍平铺到 target 根
                    val dir = task.subDir?.let { File(task.target, it) } ?: File(task.target)
                    dir.mkdirs()
                    val fileName = DownloadFileNames.build(track, resolved.extension)
                    val existing = File(dir, fileName)
                    if (existing.exists() &&
                        AudioFileValidator.isValid(existing, resolved.extension, track.durationMs)
                    ) {
                        skipped.incrementAndGet()
                        taskStore.remove(id)
                        downloadStatus = "已存在，跳过：${existing.name}"
                        return
                    }
                    existing.delete()
                    val target = uniqueTarget(dir, fileName)
                    task = task.copy(
                        fileName = target.name,
                        status = DownloadTaskStatus.DOWNLOADING,
                        updatedAt = System.currentTimeMillis(),
                    )
                    taskStore.upsert(task)
                    downloadStatus = "下载：${target.name}"
                    var lastPersistAt = 0L
                    downloader.downloadToFile(
                        url = resolved.url,
                        target = target,
                        options = DownloadOptions(
                            segmented = segmented,
                            maxConnections = if (segmented) 2 else 1,
                            maxAttempts = 4,
                            limiter = rateLimiter,
                        ),
                    ) { done, total ->
                        downloadStatus = if (total != null && total > 0) {
                            "${target.name} ${done * 100 / total}%"
                        } else {
                            "${target.name} ${done / 1024}KB"
                        }
                        val now = System.currentTimeMillis()
                        if (now - lastPersistAt >= 500L) {
                            lastPersistAt = now
                            taskStore.upsert(
                                task.copy(
                                    status = DownloadTaskStatus.DOWNLOADING,
                                    fileName = target.name,
                                    downloadedBytes = done,
                                    totalBytes = total ?: 0L,
                                    updatedAt = now,
                                )
                            )
                        }
                    }
                    AudioFileValidator.requireValid(
                        target, resolved.extension, track.durationMs,
                    )
                    AudioTagWriter.write(target, track, resolved.extension)
                    taskStore.remove(id)
                    success.incrementAndGet()
                    return
                } catch (cancelled: CancellationException) {
                    // .part 位于任务实际写入目录（批次任务在歌单子目录），取消时按真实路径统计续传字节
                    val partDir = DownloadBatchPaths.resolveDir(task.target, task.subDir)
                    val part = task.fileName?.let { File(partDir, "$it.part") }
                    taskStore.upsert(
                        task.copy(
                            status = DownloadTaskStatus.PAUSED,
                            downloadedBytes = part?.length() ?: task.downloadedBytes,
                            updatedAt = System.currentTimeMillis(),
                        )
                    )
                    return
                } catch (e: Exception) {
                    // 登录过期优先处理：先尝试强制续签一次（仍持有 refresh_token 且本任务未强刷过）
                    if (e is QmError.AuthExpired) {
                        if (!refreshAttempted &&
                            sessionStore.load()?.cookie?.contains("psrf_qqrefresh_token=") == true
                        ) {
                            refreshAttempted = true
                            if (ensureSessionFresh(force = true)) {
                                taskStore.upsert(
                                    task.copy(
                                        status = DownloadTaskStatus.QUEUED,
                                        error = null,
                                        updatedAt = System.currentTimeMillis(),
                                    )
                                )
                                downloadStatus = "登录态已续期，重试：${task.title}"
                                continue
                            }
                        }
                        // 续签失败或无凭据：暂停全部未完成任务（不标 FAILED），等待重新登录后自动恢复
                        // 登录凭据是全局的，必须整队列暂停，避免其他任务继续撞 AuthExpired
                        taskStore.save(DownloadAuthPause.pauseAll(taskStore.load()))
                        tasks = taskStore.load()
                        val pausedCount = DownloadAuthPause.resumable(tasks).size
                        downloadStatus = "登录已过期，已暂停 $pausedCount 个任务，重新登录后将自动继续"
                        error = "登录已过期，任务已暂停"
                        QmDebugLog.log("Download", "AuthExpired：已暂停 $pausedCount 个任务，等待重新登录")
                        return
                    }
                    // 非登录类错误：保持既有自动重试 / FAILED 逻辑（AuthExpired 已在上面 return）
                    val retryable = e !is QmError.NoPermission &&
                        e !is QmError.NoResource &&
                        task.retryCount < MAX_RETRIES
                    if (retryable) {
                        task = task.copy(
                            status = DownloadTaskStatus.QUEUED,
                            retryCount = task.retryCount + 1,
                            error = e.message,
                            updatedAt = System.currentTimeMillis(),
                        )
                        taskStore.upsert(task)
                        downloadStatus = "${task.title} 失败，自动重试 ${task.retryCount}/$MAX_RETRIES"
                        try {
                            delay(1_000L * task.retryCount)
                        } catch (retryCancelled: CancellationException) {
                            taskStore.upsert(
                                task.copy(status = DownloadTaskStatus.PAUSED)
                            )
                            return
                        }
                        continue
                    }
                    taskStore.upsert(
                        task.copy(
                            status = DownloadTaskStatus.FAILED,
                            error = e.message,
                            updatedAt = System.currentTimeMillis(),
                        )
                    )
                    error = "${task.title}：${e.message}"
                    return
                }
            }
        } finally {
            taskJobs.remove(id)
        }
    }

    suspend fun runQueue(ids: List<String>) {
        downloading = true
        error = null
        val success = AtomicInteger(0)
        val skipped = AtomicInteger(0)
        try {
            supervisorScope {
                // 待启动任务（保持调用方传入的顺序与批次隔离）
                val pendingIds = ids.toMutableList()
                var nextIndex = 0
                val activeJobs = mutableListOf<Job>()
                // 每轮循环都重新读取 maxConcurrent：下载过程中调大/调小并发数，
                // 只对"尚未启动"的任务生效，已启动的任务不中断（暂停/取消仍走原逻辑）
                while (nextIndex < pendingIds.size || activeJobs.isNotEmpty()) {
                    activeJobs.removeAll { it.isCompleted }
                    // 每轮读一次当前状态：登录过期暂停后剩余任务已是 PAUSED，不能被重新拉起
                    val statusById = taskStore.load().associate { it.id to it.status }
                    val slots = (maxConcurrent.coerceIn(1, 6) - activeJobs.size).coerceAtLeast(0)
                    var launched = 0
                    while (nextIndex < pendingIds.size && launched < slots) {
                        val id = pendingIds[nextIndex++]
                        // 仅仍为 QUEUED 的任务才启动并占用并发槽；非 QUEUED 直接跳过且不占槽
                        if (statusById[id] == DownloadTaskStatus.QUEUED) {
                            activeJobs += async { downloadOne(id, success, skipped) }
                            launched++
                        }
                    }
                    if (activeJobs.isNotEmpty()) {
                        // 短轮询：等待任务完成的同时，让并发数调大后能尽快补满空位
                        delay(150L)
                    }
                }
            }
            // 若仍有因登录过期暂停的任务，保留“已暂停”提示，不用“完成 x，跳过 y”覆盖
            if (DownloadAuthPause.resumable(taskStore.load()).isEmpty()) {
                downloadStatus = "完成 ${success.get()}，跳过 ${skipped.get()}"
            }
        } finally {
            downloading = false
            downloadJob = null
            pendingCount = taskStore.pending().size
        }
    }

    // A. 启动时登录态自检与续签：失败会降级保留原 session，不阻断进入主界面
    LaunchedEffect(Unit) {
        ensureSessionFresh()
    }

    // B. 前台运行期间每 6 小时检查一次，避免长时间运行跨过 keyst 过期点
    LaunchedEffect(Unit) {
        while (true) {
            delay(6 * 60 * 60 * 1000L)
            ensureSessionFresh()
        }
    }

    LaunchedEffect(session) {
        if (session != null) refresh()
    }

    LaunchedEffect(Unit) {
        while (true) {
            tasks = taskStore.load()
            delay(1_000)
        }
    }

    LaunchedEffect(profile?.avatarUrl) {
        val url = profile?.avatarUrl ?: return@LaunchedEffect
        avatarBitmap = runCatching {
            withContext(Dispatchers.IO) {
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val bytes = response.body.bytes()
                    SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
                }
            }
        }.getOrNull()
    }

    if (session == null) {
        LoginScreen(
            qrImage = qrImage,
            qrStatus = qrStatus,
            error = error,
            onRequestQr = {
                scope.launch {
                    runCatching {
                        val created = QQMusicQrLoginClient(client).createSession()
                        qrSession = created
                        qrImage = SkiaImage.makeFromEncoded(created.imageBytes).toComposeImageBitmap()
                        qrStatus = "等待扫码"
                    }.onFailure { error = it.message }
                }
            },
        )
        LaunchedEffect(qrSession) {
            val active = qrSession ?: return@LaunchedEffect
            val loginClient = QQMusicQrLoginClient(client)
            while (true) {
                delay(2_000)
                val state = runCatching { loginClient.poll(active) }.getOrElse {
                    qrStatus = it.message ?: "轮询失败"
                    continue
                }
                when (state) {
                    is QQMusicQrLoginState.Authorized -> {
                        val parsed = parseQQMusicCookie(state.cookie)
                        sessionStore.save(parsed)
                        session = parsed
                        qrStatus = "登录成功"
                        // 登录恢复：把因登录过期自动暂停的任务恢复并自动继续下载
                        val before = taskStore.load()
                        val resumed = DownloadAuthPause.resumable(before)
                        if (resumed.isNotEmpty()) {
                            taskStore.save(DownloadAuthPause.resumeAll(before))
                            tasks = taskStore.load()
                            pendingCount = taskStore.pending().size
                            tab = DesktopTab.DOWNLOADS
                            downloadStatus = "登录已恢复，继续下载 ${resumed.size} 个任务"
                            val previous = downloadJob
                            downloadJob = scope.launch {
                                previous?.join()
                                // 旧队列 finally 可能已把 downloadJob 置空，这里补回当前新 job，
                                // 确保“全部暂停/退出登录”能取消到它
                                downloadJob = coroutineContext[Job]
                                runQueue(resumed.map { it.id })
                            }
                        }
                        break
                    }
                    QQMusicQrLoginState.Scanned -> qrStatus = "已扫码，请在手机上确认"
                    QQMusicQrLoginState.Expired -> {
                        qrStatus = "二维码已过期，请刷新"
                        qrSession = null
                        qrImage = null
                        break
                    }
                    QQMusicQrLoginState.Rejected -> {
                        qrStatus = "已拒绝登录"
                        qrSession = null
                        qrImage = null
                        break
                    }
                    QQMusicQrLoginState.Waiting -> qrStatus = "等待扫码"
                }
            }
        }
        return
    }

    if (tab == DesktopTab.LIBRARY) {
        HomeScreen(
            profile = profile,
            session = session!!,
            playlists = playlists,
            selected = selected,
            tracks = tracks,
            loading = loading,
            error = error,
            selectedSongMids = selectedSongMids,
            downloadDir = downloadDir,
            qualityKey = qualityKey,
            avatarBitmap = avatarBitmap,
            segmented = segmented,
            downloading = downloading,
            downloadStatus = downloadStatus,
            onOpenDownloads = { tab = DesktopTab.DOWNLOADS },
        onSelectPlaylist = { playlist ->
            selected = playlist
            selectedSongMids = emptySet()
            scope.launch {
                loading = true
                error = null
                runCatching {
                    // 完整加载整首歌单（按 total 翻页 + songMid 去重），全选才不会被单页 99 首封顶
                    tracks = library.allPlaylistTracks(playlist.id)
                    if (tracks.size >= PlaylistPaging.MAX_TRACKS) {
                        error = "歌单较大，已按上限加载 ${tracks.size} 首"
                    }
                }.onFailure { error = it.message }
                loading = false
            }
        },
        onToggleTrack = { track ->
            selectedSongMids = if (track.songMid in selectedSongMids) {
                selectedSongMids - track.songMid
            } else {
                selectedSongMids + track.songMid
            }
        },
        onToggleAll = {
            selectedSongMids = if (selectedSongMids.size == tracks.size && tracks.isNotEmpty()) {
                emptySet()
            } else {
                tracks.map { it.songMid }.toSet()
            }
        },
        onChooseDir = {
            val chooser = JFileChooser().apply {
                fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                dialogTitle = "选择下载目录"
                currentDirectory = downloadDir
            }
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                val chosenDir = chooser.selectedFile
                downloadDir = chosenDir
                saveDownloadDir(chosenDir)
            }
        },
        onQualityChange = {
            qualityKey = it
            saveQuality(it)
        },
        onSegmentedChange = {
            segmented = it
            saveSegmented(it)
        },
        onShareLinkChange = {
            shareLink = it
            shareError = null
        },
        onOpenShare = {
            val link = shareLink.trim()
            val playlistId = QQMusicShareLinkParser.parse(link)
            if (playlistId == null) {
                shareError = "不是有效的 QQ 音乐歌单链接"
                return@HomeScreen
            }
            scope.launch {
                shareLoading = true
                shareError = null
                runCatching {
                    // 分享歌单同样完整翻页加载，避免只能选到第一页的 99 首
                    val loaded = library.allPlaylistTracks(playlistId)
                    selected = QQMusicPlaylist(
                        id = playlistId,
                        dirId = 0L,
                        title = "分享歌单 $playlistId",
                        coverUrl = null,
                        trackCount = loaded.size,
                        isOwn = false,
                    )
                    tracks = loaded
                    selectedSongMids = emptySet()
                    if (loaded.size >= PlaylistPaging.MAX_TRACKS) {
                        shareError = "歌单较大，已按上限加载 ${loaded.size} 首"
                    }
                }.onFailure { shareError = it.message }
                shareLoading = false
            }
        },
        shareLink = shareLink,
        shareError = shareError,
        shareLoading = shareLoading,
        onDownload = {
            val dir = downloadDir
            val chosen = tracks.filter { it.songMid in selectedSongMids }
            if (dir == null || chosen.isEmpty() || downloading) return@HomeScreen
            // 一次提交 = 一个批次；selected 可能为 null（如异常场景），做兜底
            val batch = DownloadBatch(
                id = UUID.randomUUID().toString(),
                playlistId = selected?.id,
                playlistTitle = selected?.title ?: "未命名歌单",
                subDir = DownloadBatchPaths.subDirName(selected?.title ?: ""),
                rootTarget = dir.absolutePath,
                quality = qualityKey,
                total = chosen.size,
            )
            val records = chosen.mapIndexed { i, t ->
                DownloadTaskRecord.fromTrack(t, qualityKey, dir.absolutePath, batch.id, batch.subDir, i)
            }
            batchStore.upsert(batch)
            batches = batchStore.load()
            val recordIds = records.map { it.id }.toSet()
            // 新 ID 已含 batchId，filterNot 不会误删其他批次的任务
            taskStore.save(
                taskStore.load().filterNot { it.id in recordIds } + records
            )
            pendingCount = taskStore.pending().size
            downloadJob = scope.launch { runQueue(records.map { it.id }) }
            tab = DesktopTab.DOWNLOADS
        },
        onLogout = {
            downloadJob?.cancel()
            downloadJob = null
            downloading = false
            sessionStore.clear()
            session = null
            profile = null
            playlists = emptyList()
            selected = null
            tracks = emptyList()
            selectedSongMids = emptySet()
            qrSession = null
            qrImage = null
            qrStatus = "等待扫码"
            downloadStatus = ""
            error = null
        },
        )
    } else {
        DesktopDownloadsScreen(
            tasks = tasks,
            batches = batches,
            authPausedCount = tasks.count {
                it.status == DownloadTaskStatus.PAUSED && it.pausedByAuth
            },
            downloading = downloading,
            maxConcurrent = maxConcurrent,
            onMaxConcurrentChange = {
                maxConcurrent = it
                saveConcurrency(it)
            },
            speedLimitKbps = speedLimitKbps,
            // 选择即改共享限速器速率，对正在下载的任务立即生效，并持久化配置
            onSpeedLimitChange = { kbps ->
                speedLimitKbps = kbps
                rateLimiter.setRate(kbps * 1024L)
                saveSpeedLimit(kbps)
            },
            onBack = { tab = DesktopTab.LIBRARY },
            onPauseAll = {
                taskJobs.values.forEach { it.cancel() }
                downloadJob?.cancel()
                downloadJob = null
                downloading = false
                downloadStatus = "已暂停"
                pendingCount = taskStore.pending().size
            },
            onResumeAll = {
                if (!downloading) {
                    val pending = taskStore.pending()
                    if (pending.isNotEmpty()) {
                        val ids = pending.map { it.id }.toSet()
                        taskStore.save(
                            taskStore.load().map {
                                if (it.id in ids) {
                                    // 手动继续：清掉自动暂停标记，避免重复自动恢复
                                    DownloadAuthPause.clearPausedByAuth(
                                        it.copy(status = DownloadTaskStatus.QUEUED)
                                    )
                                } else it
                            }
                        )
                        pendingCount = pending.size
                        downloadJob = scope.launch { runQueue(pending.map { it.id }) }
                    }
                }
            },
            onPauseTask = { id ->
                val job = taskJobs[id]
                if (job != null) {
                    job.cancel()
                } else {
                    taskStore.load().firstOrNull { it.id == id }?.let {
                        // 手动暂停：清掉自动暂停标记，避免重新登录时被自动恢复
                        taskStore.upsert(
                            DownloadAuthPause.clearPausedByAuth(
                                it.copy(status = DownloadTaskStatus.PAUSED)
                            )
                        )
                    }
                }
                tasks = taskStore.load()
            },
            onResumeTask = { id ->
                taskStore.load().firstOrNull { it.id == id }?.let {
                    taskStore.upsert(
                        DownloadAuthPause.clearPausedByAuth(
                            it.copy(status = DownloadTaskStatus.QUEUED)
                        )
                    )
                    if (taskJobs[id] == null) {
                        scope.launch {
                            downloadOne(id, AtomicInteger(0), AtomicInteger(0))
                        }
                    }
                }
                tasks = taskStore.load()
            },
            onRetryTask = { id ->
                taskStore.load().firstOrNull { it.id == id }?.let {
                    taskStore.upsert(
                        DownloadAuthPause.clearPausedByAuth(
                            it.copy(
                                status = DownloadTaskStatus.QUEUED,
                                error = null,
                                retryCount = 0,
                            )
                        )
                    )
                    if (taskJobs[id] == null) {
                        scope.launch {
                            downloadOne(id, AtomicInteger(0), AtomicInteger(0))
                        }
                    }
                }
                tasks = taskStore.load()
            },
            onRemoveTask = { id ->
                taskJobs[id]?.cancel()
                taskStore.load().firstOrNull { it.id == id }?.let { task ->
                    // .part 位于任务实际写入目录（批次任务在歌单子目录）
                    val taskDir = DownloadBatchPaths.resolveDir(task.target, task.subDir)
                    task.fileName?.let { name ->
                        File(taskDir, "$name.part").delete()
                        File(taskDir).listFiles()?.forEach { file ->
                            if (file.name.startsWith("$name.part.")) file.delete()
                        }
                    }
                }
                taskStore.remove(id)
                tasks = taskStore.load()
                pendingCount = taskStore.pending().size
            },
            onOpenFolder = { task ->
                // 批次任务打开歌单子目录，旧任务打开 target 根
                val dir = DownloadBatchPaths.resolveDir(task.target, task.subDir)
                runCatching { Desktop.getDesktop().open(File(dir)) }
            },
            // 暂停批次：仅取消该批次任务的 job（supervisorScope 下不影响其他批次）
            onPauseBatch = { batchId ->
                taskStore.load().filter { it.batchId == batchId }.forEach { task ->
                    taskJobs[task.id]?.cancel()
                    if (task.status == DownloadTaskStatus.QUEUED) {
                        taskStore.upsert(task.copy(status = DownloadTaskStatus.PAUSED))
                    }
                }
                downloadStatus = "已暂停批次"
                tasks = taskStore.load()
                pendingCount = taskStore.pending().size
            },
            // 继续批次：把该批次的待处理任务改回 QUEUED 并重新入队
            onResumeBatch = { batchId ->
                if (!downloading) {
                    val pending = taskStore.pending().filter { it.batchId == batchId }
                    if (pending.isNotEmpty()) {
                        val ids = pending.map { it.id }.toSet()
                        taskStore.save(
                            taskStore.load().map {
                                if (it.id in ids) {
                                    // 手动继续：清掉自动暂停标记，避免重复自动恢复
                                    DownloadAuthPause.clearPausedByAuth(
                                        it.copy(status = DownloadTaskStatus.QUEUED)
                                    )
                                } else it
                            }
                        )
                        pendingCount = taskStore.pending().size
                        downloadJob = scope.launch { runQueue(pending.map { it.id }) }
                    }
                }
            },
            // 重试批次：FAILED 任务清空错误、重试计数归零并重新入队
            onRetryBatch = { batchId ->
                val failed = taskStore.load().filter {
                    it.batchId == batchId && it.status == DownloadTaskStatus.FAILED
                }
                if (failed.isNotEmpty()) {
                    val ids = failed.map { it.id }.toSet()
                    taskStore.save(
                        taskStore.load().map {
                            if (it.id in ids) {
                                // 手动重试：清掉自动暂停标记，避免重复自动恢复
                                DownloadAuthPause.clearPausedByAuth(
                                    it.copy(status = DownloadTaskStatus.QUEUED, error = null, retryCount = 0)
                                )
                            } else it
                        }
                    )
                    pendingCount = taskStore.pending().size
                    if (!downloading) {
                        downloadJob = scope.launch { runQueue(failed.map { it.id }) }
                    }
                    tasks = taskStore.load()
                }
            },
            // 删除批次：只删批次记录、任务记录与 .part，不删除已下载的音频文件
            onRemoveBatch = { batchId ->
                taskStore.load().filter { it.batchId == batchId }.forEach { task ->
                    taskJobs[task.id]?.cancel()
                    val taskDir = DownloadBatchPaths.resolveDir(task.target, task.subDir)
                    task.fileName?.let { name ->
                        File(taskDir, "$name.part").delete()
                        File(taskDir).listFiles()?.forEach { file ->
                            if (file.name.startsWith("$name.part.")) file.delete()
                        }
                    }
                    taskStore.remove(task.id)
                }
                batchStore.remove(batchId)
                batches = batchStore.load()
                tasks = taskStore.load()
                pendingCount = taskStore.pending().size
                downloadStatus = "已删除批次"
            },
            onOpenBatchFolder = { batch ->
                val dir = DownloadBatchPaths.resolveDir(batch.rootTarget, batch.subDir)
                runCatching { Desktop.getDesktop().open(File(dir)) }
            },
        )
    }
}

@Composable
private fun LoginScreen(
    qrImage: ImageBitmap?,
    qrStatus: String,
    error: String?,
    onRequestQr: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("QM-Download", style = MaterialTheme.typography.headlineMedium)
        Text("扫码登录 QQ 音乐", style = MaterialTheme.typography.titleMedium)
        if (qrImage == null) {
            Button(onClick = onRequestQr) { Text("获取登录二维码") }
        } else {
            Image(qrImage, "QQ 登录二维码", Modifier.size(260.dp))
            Text(qrStatus)
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun HomeScreen(
    profile: QQMusicAccountProfile?,
    session: QQMusicSession,
    playlists: List<QQMusicPlaylist>,
    selected: QQMusicPlaylist?,
    tracks: List<QQMusicTrack>,
    loading: Boolean,
    error: String?,
    selectedSongMids: Set<String>,
    downloadDir: File?,
    qualityKey: String,
    avatarBitmap: ImageBitmap?,
    segmented: Boolean,
    downloading: Boolean,
    downloadStatus: String,
    shareLink: String,
    shareError: String?,
    shareLoading: Boolean,
    onSelectPlaylist: (QQMusicPlaylist) -> Unit,
    onToggleTrack: (QQMusicTrack) -> Unit,
    onToggleAll: () -> Unit,
    onChooseDir: () -> Unit,
    onQualityChange: (String) -> Unit,
    onSegmentedChange: (Boolean) -> Unit,
    onShareLinkChange: (String) -> Unit,
    onOpenShare: () -> Unit,
    onDownload: () -> Unit,
    onOpenDownloads: () -> Unit,
    onLogout: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.width(320.dp).fillMaxHeight().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (avatarBitmap != null) {
                        Image(
                            bitmap = avatarBitmap,
                            contentDescription = "头像",
                            modifier = Modifier.size(44.dp).clip(CircleShape),
                            contentScale = ContentScale.Crop,
                        )
                    }
                    Text(
                        profile?.nickname ?: "QQ ${session.uin}",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(onClick = onOpenDownloads) { Text("下载") }
                    OutlinedButton(onClick = onLogout) { Text("退出登录") }
                }
            }
            OutlinedTextField(
                value = shareLink,
                onValueChange = onShareLinkChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("粘贴 QQ 音乐歌单链接") },
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Button(onClick = onOpenShare, enabled = !shareLoading) {
                    Text(if (shareLoading) "打开中…" else "打开分享歌单")
                }
            }
            shareError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text("我的歌单", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            if (loading) CircularProgressIndicator(Modifier.size(24.dp))
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(playlists) { playlist ->
                    Text(
                        text = "${playlist.title}（${playlist.trackCount}）",
                        modifier = Modifier.fillMaxWidth()
                            .background(
                                if (selected?.id == playlist.id) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surface
                                }
                            )
                            .clickable { onSelectPlaylist(playlist) }
                            .padding(10.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        HorizontalDivider(modifier = Modifier.fillMaxHeight().width(1.dp))
        Column(modifier = Modifier.weight(1f).fillMaxHeight().padding(16.dp)) {
            Text(selected?.title ?: "选择左侧歌单", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("音质", style = MaterialTheme.typography.bodySmall)
                listOf(
                    "lossless" to "无损",
                    "exhigh" to "320k",
                    "standard" to "128k",
                ).forEach { (key, label) ->
                    if (qualityKey == key) {
                        Button(onClick = { onQualityChange(key) }) { Text(label) }
                    } else {
                        OutlinedButton(onClick = { onQualityChange(key) }) { Text(label) }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Checkbox(
                    checked = segmented,
                    onCheckedChange = onSegmentedChange,
                )
                Text("多连接加速（实验，2 连接）", style = MaterialTheme.typography.bodySmall)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onToggleAll, enabled = tracks.isNotEmpty()) { Text("全选/取消") }
                Button(onClick = onChooseDir) { Text("选择目录") }
                Text(
                    downloadDir?.absolutePath ?: "未选择下载目录",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onDownload,
                    enabled = !downloading && selectedSongMids.isNotEmpty() && downloadDir != null,
                ) { Text(if (downloading) "下载中…" else "下载所选（${selectedSongMids.size}）") }
                Text(downloadStatus, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(tracks) { track ->
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clickable { onToggleTrack(track) }
                            .padding(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = track.songMid in selectedSongMids,
                            onCheckedChange = { onToggleTrack(track) },
                        )
                        Column {
                            Text(track.title, fontWeight = FontWeight.Medium)
                            Text(
                                "${track.artist} · ${track.album ?: "未知专辑"}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun loadDownloadDir(): File? = runCatching {
    if (!AppPaths.configFile().exists()) return null
    val properties = Properties().apply { AppPaths.configFile().inputStream().use(::load) }
    properties.getProperty("downloadDir")?.takeIf { it.isNotBlank() }?.let(::File)
}.getOrNull()

private fun loadQuality(): String = runCatching {
    if (!AppPaths.configFile().exists()) return "lossless"
    val properties = Properties().apply { AppPaths.configFile().inputStream().use(::load) }
    properties.getProperty("quality")?.takeIf { it in setOf("lossless", "exhigh", "standard") }
        ?: "lossless"
}.getOrDefault("lossless")

private fun saveDownloadDir(dir: File) = updateConfig("downloadDir", dir.absolutePath)

private fun saveQuality(quality: String) = updateConfig("quality", quality)

private fun loadSegmented(): Boolean = runCatching {
    if (!AppPaths.configFile().exists()) return true
    val properties = Properties().apply { AppPaths.configFile().inputStream().use(::load) }
    properties.getProperty("segmented")?.toBooleanStrictOrNull() ?: true
}.getOrDefault(true)

private fun saveSegmented(enabled: Boolean) =
    updateConfig("segmented", enabled.toString())

private fun loadConcurrency(): Int = runCatching {
    if (!AppPaths.configFile().exists()) return 2
    val properties = Properties().apply { AppPaths.configFile().inputStream().use(::load) }
    properties.getProperty("maxConcurrent")?.toIntOrNull()?.coerceIn(1, 6) ?: 2
}.getOrDefault(2)

private fun saveConcurrency(count: Int) =
    updateConfig("maxConcurrent", count.coerceIn(1, 6).toString())

/** 限速值以 KB/s 存配置；0 = 不限速，默认 0（不改变现有行为）。 */
private fun loadSpeedLimit(): Int = runCatching {
    if (!AppPaths.configFile().exists()) return 0
    val properties = Properties().apply { AppPaths.configFile().inputStream().use(::load) }
    properties.getProperty("speedLimitKbps")?.toIntOrNull()?.coerceAtLeast(0) ?: 0
}.getOrDefault(0)

private fun saveSpeedLimit(kbps: Int) =
    updateConfig("speedLimitKbps", kbps.coerceAtLeast(0).toString())

private fun updateConfig(key: String, value: String) {
    runCatching {
        val properties = Properties()
        if (AppPaths.configFile().exists()) {
            AppPaths.configFile().inputStream().use(properties::load)
        }
        properties.setProperty(key, value)
        AppPaths.configFile().outputStream().use { properties.store(it, "QM-Download config") }
    }
}

private fun uniqueTarget(dir: File, fileName: String): File {
    val base = File(dir, fileName)
    if (!base.exists()) return base
    val stem = fileName.substringBeforeLast('.', fileName)
    val extension = fileName.substringAfterLast('.', "")
    var index = 1
    while (true) {
        val suffix = if (extension.isBlank()) "($index)" else "($index).$extension"
        val candidate = File(dir, "$stem$suffix")
        if (!candidate.exists()) return candidate
        index++
    }
}

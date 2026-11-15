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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JFileChooser
import kotlin.system.exitProcess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import moe.ouom.qmdownload.core.auth.QQMusicQrLoginClient
import moe.ouom.qmdownload.core.auth.QQMusicQrLoginSession
import moe.ouom.qmdownload.core.auth.QQMusicQrLoginState
import moe.ouom.qmdownload.core.auth.QQMusicSessionStore
import moe.ouom.qmdownload.core.auth.parseQQMusicCookie
import moe.ouom.qmdownload.core.debug.QmDebugLog
import moe.ouom.qmdownload.core.download.AudioFileValidator
import moe.ouom.qmdownload.core.download.AudioTagWriter
import moe.ouom.qmdownload.core.download.DownloadFileNames
import moe.ouom.qmdownload.core.download.DownloadOptions
import moe.ouom.qmdownload.core.download.DownloadTaskRecord
import moe.ouom.qmdownload.core.download.DownloadTaskStatus
import moe.ouom.qmdownload.core.download.FileDownloadTaskStore
import moe.ouom.qmdownload.core.download.QQMusicDownloader
import moe.ouom.qmdownload.core.error.QmError
import moe.ouom.qmdownload.core.link.QQMusicShareLinkParser
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
    var pendingCount by remember { mutableStateOf(taskStore.pending().size) }
    var tab by remember { mutableStateOf(DesktopTab.LIBRARY) }
    var maxConcurrent by remember { mutableStateOf(loadConcurrency()) }
    val taskJobs = remember { ConcurrentHashMap<String, Job>() }
    var tasks by remember { mutableStateOf(taskStore.load()) }
    var shareLink by remember { mutableStateOf("") }
    var shareError by remember { mutableStateOf<String?>(null) }
    var shareLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

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

    suspend fun downloadOne(id: String, success: AtomicInteger, skipped: AtomicInteger) {
        var task = taskStore.load().firstOrNull { it.id == id } ?: return
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
                    val dir = File(task.target)
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
                    val part = task.fileName?.let { File(File(task.target), "$it.part") }
                    taskStore.upsert(
                        task.copy(
                            status = DownloadTaskStatus.PAUSED,
                            downloadedBytes = part?.length() ?: task.downloadedBytes,
                            updatedAt = System.currentTimeMillis(),
                        )
                    )
                    return
                } catch (e: Exception) {
                    val retryable = e !is QmError.AuthExpired &&
                        e !is QmError.NoPermission &&
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
                    if (e is QmError.AuthExpired) downloadStatus = "登录过期，请重新登录"
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
                val semaphore = Semaphore(maxConcurrent.coerceIn(1, 6))
                val jobs = ids.map { id ->
                    async { semaphore.withPermit { downloadOne(id, success, skipped) } }
                }
                jobs.forEach { job ->
                    runCatching { job.await() }
                }
            }
            downloadStatus = "完成 ${success.get()}，跳过 ${skipped.get()}"
        } finally {
            downloading = false
            downloadJob = null
            pendingCount = taskStore.pending().size
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
                runCatching {
                    tracks = library.playlistTracksPage(playlist.id).tracks
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
                    val page = library.playlistTracksPage(playlistId)
                    selected = QQMusicPlaylist(
                        id = playlistId,
                        dirId = 0L,
                        title = "分享歌单 $playlistId",
                        coverUrl = null,
                        trackCount = page.total,
                        isOwn = false,
                    )
                    tracks = page.tracks
                    selectedSongMids = emptySet()
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
            val records = chosen.map {
                DownloadTaskRecord.fromTrack(it, qualityKey, dir.absolutePath)
            }
            val recordIds = records.map { it.id }.toSet()
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
            downloading = downloading,
            maxConcurrent = maxConcurrent,
            onMaxConcurrentChange = {
                maxConcurrent = it
                saveConcurrency(it)
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
                                if (it.id in ids) it.copy(status = DownloadTaskStatus.QUEUED) else it
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
                        taskStore.upsert(it.copy(status = DownloadTaskStatus.PAUSED))
                    }
                }
                tasks = taskStore.load()
            },
            onResumeTask = { id ->
                taskStore.load().firstOrNull { it.id == id }?.let {
                    taskStore.upsert(it.copy(status = DownloadTaskStatus.QUEUED))
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
                        it.copy(
                            status = DownloadTaskStatus.QUEUED,
                            error = null,
                            retryCount = 0,
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
                    task.fileName?.let { name ->
                        File(task.target, "$name.part").delete()
                        File(task.target).listFiles()?.forEach { file ->
                            if (file.name.startsWith("$name.part.")) file.delete()
                        }
                    }
                }
                taskStore.remove(id)
                tasks = taskStore.load()
                pendingCount = taskStore.pending().size
            },
            onOpenFolder = { task ->
                runCatching { Desktop.getDesktop().open(File(task.target)) }
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

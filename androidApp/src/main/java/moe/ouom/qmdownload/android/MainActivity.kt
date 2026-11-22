package moe.ouom.qmdownload.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.UUID
import moe.ouom.qmdownload.core.auth.QQMusicQrLoginClient
import moe.ouom.qmdownload.core.auth.QQMusicQrLoginSession
import moe.ouom.qmdownload.core.auth.QQMusicQrLoginState
import moe.ouom.qmdownload.core.auth.parseQQMusicCookie
import moe.ouom.qmdownload.core.download.DownloadBatch
import moe.ouom.qmdownload.core.download.DownloadBatchPaths
import moe.ouom.qmdownload.core.download.DownloadTaskRecord
import moe.ouom.qmdownload.core.download.DownloadTaskStatus
import moe.ouom.qmdownload.core.library.PlaylistPaging
import moe.ouom.qmdownload.core.link.QQMusicShareLinkParser
import moe.ouom.qmdownload.core.model.QQMusicAccountProfile
import moe.ouom.qmdownload.core.model.QQMusicPlaylist
import moe.ouom.qmdownload.core.model.QQMusicSession
import moe.ouom.qmdownload.core.model.QQMusicTrack

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as QmApplication
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    QmApp(app)
                }
            }
        }
    }
}

private enum class AndroidTab {
    LIBRARY,
    DOWNLOADS,
}

private fun DownloadTaskRecord.isPending(): Boolean =
    status == DownloadTaskStatus.QUEUED ||
        status == DownloadTaskStatus.PAUSED ||
        status == DownloadTaskStatus.DOWNLOADING

@Composable
private fun QmApp(app: QmApplication) {
    val context = LocalContext.current
    val preferences = remember {
        context.getSharedPreferences("qm_download_ui", android.content.Context.MODE_PRIVATE)
    }
    var session by remember { mutableStateOf(app.sessionStore.load()) }
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
    var downloadTreeUri by remember {
        mutableStateOf(preferences.getString("downloadTreeUri", null)?.let(Uri::parse))
    }
    var downloading by remember { mutableStateOf(false) }
    var downloadStatus by remember { mutableStateOf("") }
    var qualityKey by remember {
        mutableStateOf(preferences.getString("quality", "lossless") ?: "lossless")
    }
    var avatarBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var shareLink by remember { mutableStateOf("") }
    var shareError by remember { mutableStateOf<String?>(null) }
    var shareLoading by remember { mutableStateOf(false) }
    var tasks by remember { mutableStateOf(app.taskStore.load()) }
    var batches by remember { mutableStateOf(app.downloadBatchStore.load()) }
    var maxConcurrent by remember {
        mutableStateOf(preferences.getInt("maxConcurrent", 2).coerceIn(1, 6))
    }
    // 限速持久化值（KB/s，0=不限）；启动时同步到共享限速器
    var speedLimitKbps by remember { mutableStateOf(preferences.getInt("speedLimitKbps", 0)) }
    var tab by remember { mutableStateOf(AndroidTab.LIBRARY) }
    val pendingCount = tasks.count { it.isPending() }
    val scope = rememberCoroutineScope()

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            preferences.edit().putString("downloadTreeUri", uri.toString()).apply()
            downloadTreeUri = uri
        }
    }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    fun ensureNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        return granted
    }

    // 前台续签复查间隔：6 小时。是否真正续签由 ensureSessionFresh 依据 keyst 年龄决定。
    val periodicRefreshIntervalMs = 6 * 60 * 60 * 1000L

    /**
     * 检查并（按需）续签登录态，启动续签与前台定时复查共用此逻辑。
     *
     * 真正的续签由 [QmApplication.ensureSessionFresh] 统一完成（内部 Mutex 串行化，
     * 与 DownloadService 的 AuthExpired 兜底共用同一入口）；这里只负责把结果同步到
     * Compose 内存 session 并给出友好提示。
     * 失败降级：QmApplication 已保留原 session 并记日志，此处无需额外处理。
     */
    suspend fun ensureSessionFresh(force: Boolean = false) {
        val refreshed = app.ensureSessionFresh(force)
        if (refreshed) {
            app.sessionStore.load()?.let { session = it }
            android.widget.Toast.makeText(
                context,
                "登录态已自动续签",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
    }

    suspend fun refresh() {
        val current = session ?: return
        loading = true
        error = null
        runCatching {
            profile = app.profileRepository.load(current)
            playlists = app.library.myPlaylists()
        }.onFailure { error = it.message }
        loading = false
    }

    LaunchedEffect(session) {
        if (session != null) refresh()
    }

    LaunchedEffect(Unit) {
        // 启动即检查并续签一次（失败降级，不阻塞进入主界面）；前台每 6 小时复查一次。
        // ensureSessionFresh 每次都读最新 store，故此处只启动一次循环即可覆盖后续扫码登录的场景。
        ensureSessionFresh()
        while (true) {
            delay(periodicRefreshIntervalMs)
            ensureSessionFresh()
        }
    }

    LaunchedEffect(Unit) {
        // 启动时按持久化值设置一次全局限速，之后由下载页下拉实时修改
        app.rateLimiter.setRate(speedLimitKbps * 1024L)
        while (true) {
            tasks = app.taskStore.load()
            batches = app.downloadBatchStore.load()
            delay(1_000)
        }
    }

    LaunchedEffect(profile?.avatarUrl) {
        val url = profile?.avatarUrl ?: return@LaunchedEffect
        avatarBitmap = runCatching {
            withContext(Dispatchers.IO) {
                val request = Request.Builder().url(url).build()
                app.httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val bytes = response.body.bytes()
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
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
                        val created = QQMusicQrLoginClient(app.httpClient).createSession()
                        qrSession = created
                        qrImage = BitmapFactory.decodeByteArray(
                            created.imageBytes, 0, created.imageBytes.size,
                        )?.asImageBitmap()
                        qrStatus = "等待扫码"
                    }.onFailure { error = it.message }
                }
            },
        )
        LaunchedEffect(qrSession) {
            val active = qrSession ?: return@LaunchedEffect
            val loginClient = QQMusicQrLoginClient(app.httpClient)
            while (true) {
                delay(2_000)
                val state = runCatching { loginClient.poll(active) }.getOrElse {
                    qrStatus = it.message ?: "轮询失败"
                    continue
                }
                when (state) {
                    is QQMusicQrLoginState.Authorized -> {
                        val parsed = parseQQMusicCookie(state.cookie)
                        app.sessionStore.save(parsed)
                        session = parsed
                        // 重新登录成功：若存在因登录过期暂停的任务，自动恢复并继续下载
                        val hasAuthPaused = app.taskStore.load().any {
                            it.status == DownloadTaskStatus.PAUSED && it.pausedByAuth
                        }
                        if (hasAuthPaused) {
                            DownloadService.resumeAuthPaused(context)
                        }
                        break
                    }
                    QQMusicQrLoginState.Scanned -> qrStatus = "已扫码，请在手机上确认"
                    QQMusicQrLoginState.Expired -> {
                        qrStatus = "二维码已过期，请重新获取"
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

    if (tab == AndroidTab.LIBRARY) {
        HomeScreen(
        session = session!!,
        profile = profile,
        playlists = playlists,
        selected = selected,
        tracks = tracks,
        loading = loading,
        error = error,
        selectedSongMids = selectedSongMids,
        downloadTreeUri = downloadTreeUri,
        qualityKey = qualityKey,
        avatarBitmap = avatarBitmap,
        downloading = downloading,
        downloadStatus = downloadStatus,
        onSelectPlaylist = { playlist ->
            selected = playlist
            selectedSongMids = emptySet()
            scope.launch {
                loading = true
                error = null
                runCatching {
                    // 完整加载整首歌单（按 total 翻页 + songMid 去重），全选才不会被单页 99 首封顶
                    tracks = app.library.allPlaylistTracks(playlist.id)
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
            folderPicker.launch(null)
        },
        onQualityChange = { key ->
            qualityKey = key
            preferences.edit().putString("quality", key).apply()
        },
        onShareLinkChange = {
            shareLink = it
            shareError = null
        },
        onOpenShare = {
            val playlistId = QQMusicShareLinkParser.parse(shareLink.trim())
            if (playlistId == null) {
                shareError = "不是有效的 QQ 音乐歌单链接"
                return@HomeScreen
            }
            scope.launch {
                shareLoading = true
                shareError = null
                runCatching {
                    // 分享歌单同样完整翻页加载，避免只能选到第一页的 99 首
                    val loaded = app.library.allPlaylistTracks(playlistId)
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
        pendingCount = pendingCount,
        onOpenDownloads = { tab = AndroidTab.DOWNLOADS },
        onDownload = {
            val tree = downloadTreeUri
            val chosen = tracks.filter { it.songMid in selectedSongMids }
            if (tree == null || chosen.isEmpty() || downloading) return@HomeScreen
            if (!ensureNotificationPermission()) {
                downloadStatus = "请允许通知权限后重试"
                return@HomeScreen
            }
            // 一次提交 = 一个批次；Service 负责把批次与任务落库，Activity 不双写
            val playlistTitle = selected?.title ?: "未命名歌单"
            val batch = DownloadBatch(
                id = UUID.randomUUID().toString(),
                playlistId = selected?.id,
                playlistTitle = playlistTitle,
                subDir = DownloadBatchPaths.subDirName(playlistTitle),
                rootTarget = tree.toString(),
                quality = qualityKey,
                total = chosen.size,
            )
            DownloadService.start(context, batch, chosen, tree, qualityKey)
            tasks = app.taskStore.load()
            batches = app.downloadBatchStore.load()
            downloadStatus = "已提交后台下载"
            tab = AndroidTab.DOWNLOADS
        },
        onLogout = {
            app.sessionStore.clear()
            session = null
            selected = null
            tracks = emptyList()
            playlists = emptyList()
            profile = null
            selectedSongMids = emptySet()
        },
        onBackToPlaylists = {
            selected = null
            tracks = emptyList()
        },
                )
            } else {
                AndroidDownloadsScreen(
                    tasks = tasks,
                    batches = batches,
                    authPausedCount = tasks.count {
                        it.status == DownloadTaskStatus.PAUSED && it.pausedByAuth
                    },
                    maxConcurrent = maxConcurrent,
                    speedLimitKbps = speedLimitKbps,
                    onMaxConcurrentChange = {
                        maxConcurrent = it
                        preferences.edit().putInt("maxConcurrent", it).apply()
                    },
                    onSpeedLimitChange = { kbps ->
                        speedLimitKbps = kbps
                        // 立即生效：Service 与 manager 共用 application 的同一个限速器
                        app.rateLimiter.setRate(kbps * 1024L)
                        preferences.edit().putInt("speedLimitKbps", kbps).apply()
                    },
                    onPauseAll = { DownloadService.pauseAll(context) },
                    onResumeAll = { DownloadService.resumeAll(context) },
                    onPauseTask = { id -> DownloadService.pauseTask(context, id) },
                    onResumeTask = { id -> DownloadService.resumeTask(context, id) },
                    onRetryTask = { id -> DownloadService.retryTask(context, id) },
                    onRemoveTask = { id ->
                        DownloadService.removeTask(context, id)
                        app.taskStore.remove(id)
                        tasks = app.taskStore.load()
                    },
                    onPauseBatch = { id -> DownloadService.pauseBatch(context, id) },
                    onResumeBatch = { id -> DownloadService.resumeBatch(context, id) },
                    onRetryBatch = { id -> DownloadService.retryBatch(context, id) },
                    onRemoveBatch = { id ->
                        DownloadService.removeBatch(context, id)
                        tasks = app.taskStore.load()
                        batches = app.downloadBatchStore.load()
                    },
                    onOpenBatchFolder = { batch ->
                        // SAF 只授权整个 tree，无法直接定位子目录；这里提示目标路径文本
                        val path = if (batch.subDir.isNotBlank()) {
                            "${batch.rootTarget} / ${batch.subDir}"
                        } else {
                            batch.rootTarget
                        }
                        android.widget.Toast.makeText(
                            context,
                            "下载目录：$path",
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                    },
                    onBack = { tab = AndroidTab.LIBRARY },
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
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("QM-Download", style = MaterialTheme.typography.headlineMedium)
        Text("扫码登录 QQ 音乐", style = MaterialTheme.typography.titleMedium)
        if (qrImage == null) {
            Button(onClick = onRequestQr) { Text("获取登录二维码") }
        } else {
            Image(qrImage, "QQ 登录二维码", Modifier.size(240.dp))
            Text(qrStatus)
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun HomeScreen(
    session: QQMusicSession,
    profile: QQMusicAccountProfile?,
    playlists: List<QQMusicPlaylist>,
    selected: QQMusicPlaylist?,
    tracks: List<QQMusicTrack>,
    loading: Boolean,
    error: String?,
    selectedSongMids: Set<String>,
    downloadTreeUri: Uri?,
    qualityKey: String,
    avatarBitmap: ImageBitmap?,
    downloading: Boolean,
    downloadStatus: String,
    shareLink: String,
    shareError: String?,
    shareLoading: Boolean,
    pendingCount: Int,
    onSelectPlaylist: (QQMusicPlaylist) -> Unit,
    onToggleTrack: (QQMusicTrack) -> Unit,
    onToggleAll: () -> Unit,
    onChooseDir: () -> Unit,
    onQualityChange: (String) -> Unit,
    onShareLinkChange: (String) -> Unit,
    onOpenShare: () -> Unit,
    onOpenDownloads: () -> Unit,
    onDownload: () -> Unit,
    onLogout: () -> Unit,
    onBackToPlaylists: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        BoxWithConstraints(Modifier.weight(1f)) {
            val wide = maxWidth > 700.dp
            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    PlaylistPane(
                        Modifier.width(300.dp).fillMaxHeight(),
                        session, profile, avatarBitmap, playlists, selected, loading, error,
                        pendingCount, shareLink, shareError, shareLoading,
                        onSelectPlaylist, onOpenDownloads, onShareLinkChange, onOpenShare, onLogout,
                    )
                    HorizontalDivider(Modifier.fillMaxHeight().width(1.dp))
                    TrackPane(
                        Modifier.weight(1f).fillMaxHeight(), selected, tracks, selectedSongMids,
                        downloadTreeUri, qualityKey, downloading, downloadStatus, onToggleTrack,
                        onToggleAll, onChooseDir, onQualityChange, onDownload,
                    )
                }
            } else if (selected == null) {
                PlaylistPane(
                    Modifier.fillMaxSize(),
                    session, profile, avatarBitmap, playlists, selected, loading, error,
                    pendingCount, shareLink, shareError, shareLoading,
                    onSelectPlaylist, onOpenDownloads, onShareLinkChange, onOpenShare, onLogout,
                )
            } else {
                Column(Modifier.fillMaxSize()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = onBackToPlaylists) { Text("返回") }
                        Spacer(Modifier.width(8.dp))
                        Text(selected.title, fontWeight = FontWeight.SemiBold)
                    }
                    TrackPane(
                        Modifier.fillMaxSize(), selected, tracks, selectedSongMids,
                        downloadTreeUri, qualityKey, downloading, downloadStatus, onToggleTrack,
                        onToggleAll, onChooseDir, onQualityChange, onDownload,
                    )
                }
            }
        }
    }
}

@Composable
private fun ShareLinkBar(
    link: String,
    error: String?,
    loading: Boolean,
    onChange: (String) -> Unit,
    onOpen: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = link,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("粘贴 QQ 音乐歌单链接") },
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = onOpen, enabled = !loading) {
                Text(if (loading) "打开中…" else "打开分享歌单")
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun PlaylistPane(
    modifier: Modifier,
    session: QQMusicSession,
    profile: QQMusicAccountProfile?,
    avatarBitmap: ImageBitmap?,
    playlists: List<QQMusicPlaylist>,
    selected: QQMusicPlaylist?,
    loading: Boolean,
    error: String?,
    pendingCount: Int,
    shareLink: String,
    shareError: String?,
    shareLoading: Boolean,
    onSelectPlaylist: (QQMusicPlaylist) -> Unit,
    onOpenDownloads: () -> Unit,
    onShareLinkChange: (String) -> Unit,
    onOpenShare: () -> Unit,
    onLogout: () -> Unit,
) {
    Column(modifier.padding(8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (avatarBitmap != null) {
                        Image(
                            bitmap = avatarBitmap,
                            contentDescription = "头像",
                            modifier = Modifier.size(40.dp).clip(CircleShape),
                            contentScale = ContentScale.Crop,
                        )
                    }
                    Text(
                        profile?.nickname ?: "QQ ${session.uin}",
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text("我的歌单", style = MaterialTheme.typography.titleMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = onOpenDownloads) {
                    Text(if (pendingCount > 0) "下载($pendingCount)" else "下载")
                }
                Button(onClick = onLogout) { Text("退出登录") }
            }
        }
        ShareLinkBar(
            link = shareLink,
            error = shareError,
            loading = shareLoading,
            onChange = onShareLinkChange,
            onOpen = onOpenShare,
        )
        if (loading) CircularProgressIndicator(Modifier.size(20.dp))
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(playlists) { playlist ->
                Text(
                    "${playlist.title}（${playlist.trackCount}）",
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
    }
}

@Composable
private fun TrackPane(
    modifier: Modifier,
    selected: QQMusicPlaylist?,
    tracks: List<QQMusicTrack>,
    selectedSongMids: Set<String>,
    downloadTreeUri: Uri?,
    qualityKey: String,
    downloading: Boolean,
    downloadStatus: String,
    onToggleTrack: (QQMusicTrack) -> Unit,
    onToggleAll: () -> Unit,
    onChooseDir: () -> Unit,
    onQualityChange: (String) -> Unit,
    onDownload: () -> Unit,
) {
    Column(modifier.padding(8.dp)) {
        Text(selected?.title ?: "选择歌单", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("音质", style = MaterialTheme.typography.bodySmall)
            listOf("lossless" to "无损", "exhigh" to "320k", "standard" to "128k")
                .forEach { (key, label) ->
                    if (qualityKey == key) {
                        Button(onClick = { onQualityChange(key) }) { Text(label) }
                    } else {
                        OutlinedButton(onClick = { onQualityChange(key) }) { Text(label) }
                    }
                }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onToggleAll, enabled = tracks.isNotEmpty()) { Text("全选") }
            Button(onClick = onChooseDir) { Text("目录") }
            Text(
                downloadTreeUri?.lastPathSegment ?: "未选择目录",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onDownload,
                enabled = !downloading && selectedSongMids.isNotEmpty() && downloadTreeUri != null,
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

package moe.ouom.qmdownload.android

import android.app.Application
import android.content.Context
import android.util.Log
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.ouom.qmdownload.core.auth.QQMusicSessionRefresher
import moe.ouom.qmdownload.core.auth.QQMusicSessionStore
import moe.ouom.qmdownload.core.auth.parseQQMusicCookie
import moe.ouom.qmdownload.core.debug.QmDebugLog
import moe.ouom.qmdownload.core.download.ByteRateLimiter
import moe.ouom.qmdownload.core.download.DownloadBatchStore
import moe.ouom.qmdownload.core.download.FileDownloadTaskStore
import moe.ouom.qmdownload.core.download.QQMusicDownloader
import moe.ouom.qmdownload.core.library.QQMusicLibraryRepository
import moe.ouom.qmdownload.core.model.QQMusicSession
import moe.ouom.qmdownload.core.playback.QQMusicUrlResolver
import moe.ouom.qmdownload.core.profile.QQMusicProfileRepository
import okhttp3.OkHttpClient

class QmApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        QmDebugLog.enabled = true
        QmDebugLog.sink = { line -> Log.d("QM-Download", line) }
    }

    val httpClient: OkHttpClient by lazy { OkHttpClient() }

    val sessionStore: QQMusicSessionStore by lazy { AndroidSessionStore(this) }

    val library: QQMusicLibraryRepository by lazy {
        QQMusicLibraryRepository(httpClient) { sessionStore.load() }
    }

    val profileRepository: QQMusicProfileRepository by lazy {
        QQMusicProfileRepository(httpClient)
    }

    val urlResolver: QQMusicUrlResolver by lazy {
        QQMusicUrlResolver(httpClient) { sessionStore.load() }
    }

    val downloader: QQMusicDownloader by lazy { QQMusicDownloader(httpClient) }

    /** 全局字节限速器；默认不限速，下载页选择后由 UI 调用 setRate 立即生效。 */
    val rateLimiter: ByteRateLimiter by lazy { ByteRateLimiter(0L) }

    val androidDownloadManager: AndroidDownloadManager by lazy {
        AndroidDownloadManager(this, urlResolver, downloader, rateLimiter)
    }

    val taskStore: FileDownloadTaskStore by lazy {
        FileDownloadTaskStore(File(filesDir, "download_tasks.json"))
    }

    /** 批次存储；与任务记录同目录，Service 负责写入，Activity 只读展示。 */
    val downloadBatchStore: DownloadBatchStore by lazy {
        DownloadBatchStore(File(filesDir, "download_batches.json"))
    }

    /** 登录态续签的唯一入口锁：Activity 与 DownloadService 共用，串行化同一 refresh_token。 */
    private val sessionRefreshMutex = Mutex()

    /**
     * 检查并（按需）续签登录态；Activity 与 DownloadService 共用，Mutex 串行化，
     * 避免同一 refresh_token 被并发使用。
     * 失败一律降级：保留原 session，仅记日志，返回 false。
     * @return 本次是否续签成功
     */
    suspend fun ensureSessionFresh(force: Boolean = false): Boolean = sessionRefreshMutex.withLock {
        val current = sessionStore.load() ?: return@withLock false
        if (!current.isLoggedIn) return@withLock false
        if (!force && !QQMusicSessionRefresher.isNearExpiry(current.cookie)) return@withLock false
        val newCookie = runCatching { QQMusicSessionRefresher.refresh(current.cookie, httpClient) }
            .getOrElse { e ->
                if (e is CancellationException) throw e
                QmDebugLog.log("SessionRefresh", "登录态续签失败，保留原登录态：${e.message}")
                return@withLock false
            }
        val newSession = parseQQMusicCookie(newCookie)
        if (!newSession.isLoggedIn) {
            QmDebugLog.log("SessionRefresh", "续签返回的 Cookie 未登录，忽略本次结果")
            return@withLock false
        }
        sessionStore.save(newSession)
        QmDebugLog.log("SessionRefresh", "登录态续签成功")
        true
    }
}

/** 会话存 SharedPreferences；只存 Cookie 串/uin/musicKey。 */
class AndroidSessionStore(context: Context) : QQMusicSessionStore {
    private val preferences =
        context.getSharedPreferences("qm_download_session", Context.MODE_PRIVATE)

    override fun load(): QQMusicSession? {
        val cookie = preferences.getString("cookie", null) ?: return null
        return parseQQMusicCookie(cookie).takeIf { it.isLoggedIn }
    }

    override fun save(session: QQMusicSession) {
        preferences.edit()
            .putString("cookie", session.cookie)
            .putString("uin", session.uin)
            .putString("musicKey", session.musicKey)
            .apply()
    }

    override fun clear() {
        preferences.edit().clear().apply()
    }
}

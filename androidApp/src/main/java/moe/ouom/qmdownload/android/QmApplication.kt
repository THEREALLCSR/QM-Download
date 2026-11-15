package moe.ouom.qmdownload.android

import android.app.Application
import android.content.Context
import android.util.Log
import java.io.File
import moe.ouom.qmdownload.core.auth.QQMusicSessionStore
import moe.ouom.qmdownload.core.auth.parseQQMusicCookie
import moe.ouom.qmdownload.core.debug.QmDebugLog
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

    val androidDownloadManager: AndroidDownloadManager by lazy {
        AndroidDownloadManager(this, urlResolver, downloader)
    }

    val taskStore: FileDownloadTaskStore by lazy {
        FileDownloadTaskStore(File(filesDir, "download_tasks.json"))
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

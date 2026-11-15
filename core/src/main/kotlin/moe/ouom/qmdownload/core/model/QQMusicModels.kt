package moe.ouom.qmdownload.core.model

data class QQMusicAccountProfile(
    val nickname: String,
    val avatarUrl: String?,
)

data class QQMusicPlaylist(
    val id: String,
    val dirId: Long,
    val title: String,
    val coverUrl: String?,
    val trackCount: Int,
    val playCount: Long = 0L,
    val isOwn: Boolean = true,
)

data class QQMusicTrack(
    val songMid: String,
    val mediaMid: String?,
    val title: String,
    val artist: String,
    val album: String?,
    val coverUrl: String?,
    val durationMs: Long,
)

data class QQMusicPlaylistTracks(
    val tracks: List<QQMusicTrack>,
    val total: Int,
)

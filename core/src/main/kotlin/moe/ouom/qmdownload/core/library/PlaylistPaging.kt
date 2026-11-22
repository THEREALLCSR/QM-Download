package moe.ouom.qmdownload.core.library

import moe.ouom.qmdownload.core.model.QQMusicPlaylistTracks
import moe.ouom.qmdownload.core.model.QQMusicTrack

/**
 * 歌单完整分页加载的纯逻辑（无网络、无平台 UI 依赖）。
 *
 * 拆出 [fetchPage] 作为可注入的测试接缝：单测无需真实会话/网络即可覆盖
 * 多页拼接、songMid 去重保序以及各种提前终止条件。
 *
 * 分页语义：
 * - `begin` 是服务端 `song_begin`（原始曲目窗口的起始下标）；
 * - 每页按“本页有效曲目数”推进（无 songMid 的条目在 [QQMusicLibraryRepository]
 *   中被过滤，因此相邻窗口边缘会有少量重叠，由去重消解）；
 * - 以服务端 `total` 为主要终止条件，并叠加空页/无新增/不推进/上限等保护，
 *   保证即使接口行为异常也不会无限循环。
 */
object PlaylistPaging {
    /** 默认每页请求条数，与 QQ 音乐实测支持的分页大小一致。 */
    const val DEFAULT_PAGE_SIZE = 100

    /** 安全上限：最多翻 1000 页。 */
    const val MAX_PAGES = 1_000

    /** 安全上限：最多保留 100000 首曲目。 */
    const val MAX_TRACKS = 100_000

    /**
     * 按 [fetchPage] 逐页加载整个歌单，返回按 songMid 去重且保持服务端顺序的曲目列表。
     *
     * @param playlistId 歌单 ID，原样透传给 [fetchPage]
     * @param fetchPage 单页取数函数，参数为 (playlistId, begin, limit)
     * @param pageSize 每页请求条数，会被约束到 1..200
     * @param maxPages 最多翻页数（安全上限）
     * @param maxTracks 最多返回曲目数（安全上限）
     */
    suspend fun loadAll(
        playlistId: String,
        fetchPage: suspend (playlistId: String, begin: Int, limit: Int) -> QQMusicPlaylistTracks,
        pageSize: Int = DEFAULT_PAGE_SIZE,
        maxPages: Int = MAX_PAGES,
        maxTracks: Int = MAX_TRACKS,
    ): List<QQMusicTrack> {
        val safePageSize = pageSize.coerceIn(1, 200)
        val safeMaxPages = maxPages.coerceAtLeast(1)
        val safeMaxTracks = maxTracks.coerceAtLeast(1)

        val seen = HashSet<String>()
        val result = ArrayList<QQMusicTrack>()
        var begin = 0
        var pages = 0
        var total = 0
        // 连续空页数：整页被过滤掉 mid 的情形允许跳过，但连续两页为空即终止
        var emptyPages = 0

        while (pages < safeMaxPages && result.size < safeMaxTracks) {
            if (total > 0 && begin >= total) break

            val page = fetchPage(playlistId, begin, safePageSize)
            pages++
            if (page.total > 0) total = page.total

            var added = 0
            for (track in page.tracks) {
                if (!seen.add(track.songMid)) continue
                if (result.size >= safeMaxTracks) break
                result.add(track)
                added++
            }

            if (page.tracks.isEmpty()) {
                emptyPages++
                if (emptyPages >= 2) break
            } else {
                emptyPages = 0
                // 有曲目但全是重复：说明服务端未按 begin 推进，立即终止避免死循环
                if (added == 0) break
            }

            // 空页时按 pageSize 跳过整个服务端窗口，避免被无 mid 的整页卡住
            val step = if (page.tracks.isEmpty()) safePageSize else page.tracks.size
            val next = begin.toLong() + step.toLong()
            // begin 不推进或溢出：立即终止，杜绝死循环
            if (next <= begin.toLong() || next > Int.MAX_VALUE.toLong()) break
            begin = next.toInt()
        }
        return result
    }
}

/**
 * 顶层便捷入口：等价于 [PlaylistPaging.loadAll]，便于测试直接 import 调用。
 */
suspend fun loadAllPlaylistTracks(
    playlistId: String,
    fetchPage: suspend (playlistId: String, begin: Int, limit: Int) -> QQMusicPlaylistTracks,
    pageSize: Int = PlaylistPaging.DEFAULT_PAGE_SIZE,
    maxPages: Int = PlaylistPaging.MAX_PAGES,
    maxTracks: Int = PlaylistPaging.MAX_TRACKS,
): List<QQMusicTrack> = PlaylistPaging.loadAll(playlistId, fetchPage, pageSize, maxPages, maxTracks)

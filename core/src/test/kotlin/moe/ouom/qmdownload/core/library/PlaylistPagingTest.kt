package moe.ouom.qmdownload.core.library

import kotlinx.coroutines.runBlocking
import moe.ouom.qmdownload.core.model.QQMusicPlaylistTracks
import moe.ouom.qmdownload.core.model.QQMusicTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 歌单完整分页加载的回归测试（统筹方编写，用于检验修复实现）。
 *
 * 重点覆盖原始缺陷："全选最多 99 首"——即歌单详情只取第一页、丢弃服务端 total。
 * 这里用可注入的 fetchPage 接缝模拟服务端分页，无需真实会话/网络。
 */
class PlaylistPagingTest {

    private fun track(mid: String) = QQMusicTrack(
        songMid = mid,
        mediaMid = null,
        title = "title-$mid",
        artist = "artist",
        album = null,
        coverUrl = null,
        durationMs = 0L,
    )

    @Test
    fun `single page within total returns all tracks`() = runBlocking {
        val calls = mutableListOf<Pair<Int, Int>>()
        val result = PlaylistPaging.loadAll(
            playlistId = "1",
            fetchPage = { _, begin, limit ->
                calls += begin to limit
                QQMusicPlaylistTracks(listOf(track("a"), track("b"), track("c")), total = 3)
            },
            pageSize = 100,
        )
        assertEquals(listOf("a", "b", "c"), result.map { it.songMid })
        assertEquals(1, calls.size, "total=3 < pageSize 时不应再翻页")
        assertEquals(0 to 100, calls.first())
    }

    @Test
    fun `load full playlist beyond single page using server total`() = runBlocking {
        // 复现真实缺陷场景：歌单共 250 首，每页 100；旧实现只取第一页 99 首。
        val all = (1..250).map { track("m$it") }
        val calls = mutableListOf<Int>()
        val result = PlaylistPaging.loadAll(
            playlistId = "1",
            fetchPage = { _, begin, limit ->
                calls += begin
                val from = begin.coerceAtMost(all.size)
                val to = (begin + limit).coerceAtMost(all.size)
                QQMusicPlaylistTracks(all.subList(from, to), total = all.size)
            },
            pageSize = 100,
        )
        assertEquals(250, result.size, "整歌单应全部加载，不受单页 99 首限制")
        assertEquals(listOf(0, 100, 200), calls, "应按 total 翻到第 3 页")
        assertEquals("m1", result.first().songMid)
        assertEquals("m250", result.last().songMid)
    }

    @Test
    fun `dedup by songMid across overlapping windows and filter out blank mids`() = runBlocking {
        // 真实服务端语义：song_begin 是原始窗口下标，仓库会过滤无 songMid 的条目，
        // 因此按有效曲目数推进时相邻页会重叠；必须靠去重消解且不留空洞。
        val total = 250
        val raw: List<QQMusicTrack?> = (0 until total).map { i ->
            if (i % 10 == 0) null else track("m$i") // 每 10 首有 1 首无 mid
        }
        val calls = mutableListOf<Int>()
        val result = PlaylistPaging.loadAll(
            playlistId = "1",
            fetchPage = { _, begin, limit ->
                calls += begin
                val from = begin.coerceAtMost(total)
                val to = (begin + limit).coerceAtMost(total)
                val page = raw.subList(from, to).filterNotNull()
                QQMusicPlaylistTracks(page, total = total)
            },
            pageSize = 100,
        )
        val expected = (0 until total).filter { it % 10 != 0 }.map { "m$it" }
        assertEquals(expected, result.map { it.songMid }, "去重后应保留全部有效曲目且顺序不变")
        assertEquals(expected.size, result.map { it.songMid }.toSet().size, "不应有重复 songMid")
        assertTrue(calls.size in 2..PlaylistPaging.MAX_PAGES, "应发生翻页，且调用次数受控")
    }

    @Test
    fun `stops when server ignores begin and returns no new tracks`() = runBlocking {
        var calls = 0
        val result = PlaylistPaging.loadAll(
            playlistId = "1",
            fetchPage = { _, _, _ ->
                calls++
                QQMusicPlaylistTracks(listOf(track("x"), track("y")), total = 100)
            },
            pageSize = 10,
        )
        assertEquals(listOf("x", "y"), result.map { it.songMid })
        assertEquals(2, calls, "服务端不推进时应立即终止，避免无限循环")
    }

    @Test
    fun `stops after two consecutive empty pages`() = runBlocking {
        var calls = 0
        val result = PlaylistPaging.loadAll(
            playlistId = "1",
            fetchPage = { _, _, _ ->
                calls++
                QQMusicPlaylistTracks(emptyList(), total = 0)
            },
            pageSize = 10,
        )
        assertTrue(result.isEmpty())
        assertEquals(2, calls, "连续空页应终止")
    }

    @Test
    fun `respects maxPages safety cap`() = runBlocking {
        var calls = 0
        val result = PlaylistPaging.loadAll(
            playlistId = "1",
            fetchPage = { _, _, _ ->
                calls++
                QQMusicPlaylistTracks(listOf(track("n$calls")), total = 100_000)
            },
            pageSize = 1,
            maxPages = 3,
        )
        assertEquals(3, calls)
        assertEquals(3, result.size)
    }

    @Test
    fun `respects maxTracks safety cap`() = runBlocking {
        var beginSeed = 0
        val result = PlaylistPaging.loadAll(
            playlistId = "1",
            fetchPage = { _, _, limit ->
                val t = (0 until limit).map { track("t${beginSeed + it}") }
                beginSeed += limit
                QQMusicPlaylistTracks(t, total = 100_000)
            },
            pageSize = 10,
            maxTracks = 15,
        )
        assertEquals(15, result.size, "曲目数不得超过 maxTracks")
    }

    @Test
    fun `pageSize is coerced into 1 to 200`() = runBlocking {
        val limits = mutableListOf<Int>()
        PlaylistPaging.loadAll(
            playlistId = "1",
            fetchPage = { _, _, limit ->
                limits += limit
                QQMusicPlaylistTracks(emptyList(), total = 0)
            },
            pageSize = 999,
        )
        assertEquals(200, limits.first())

        limits.clear()
        PlaylistPaging.loadAll(
            playlistId = "1",
            fetchPage = { _, _, limit ->
                limits += limit
                QQMusicPlaylistTracks(emptyList(), total = 0)
            },
            pageSize = 0,
        )
        assertEquals(1, limits.first())
    }

    @Test
    fun `top level helper delegates to paging object`() = runBlocking {
        val result = loadAllPlaylistTracks(
            playlistId = "1",
            fetchPage = { _, _, _ -> QQMusicPlaylistTracks(listOf(track("z")), total = 1) },
        )
        assertEquals(listOf("z"), result.map { it.songMid })
    }
}

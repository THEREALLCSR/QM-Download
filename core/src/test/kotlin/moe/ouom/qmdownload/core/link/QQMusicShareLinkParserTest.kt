package moe.ouom.qmdownload.core.link

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QQMusicShareLinkParserTest {

    @Test
    fun parsesRyqqV2ShareLink() {
        val url = "https://y.qq.com/n/ryqq_v2/playlist/9735321048" +
            "?ADTAG=h5_share_playlist&redirecttag=mn.redirect.custom&mnst=0.91"
        assertEquals("9735321048", QQMusicShareLinkParser.parse(url))
    }

    @Test
    fun parsesLegacyPlaylistPath() {
        assertEquals(
            "9735321048",
            QQMusicShareLinkParser.parse("https://y.qq.com/n/ryqq/playlist/9735321048"),
        )
    }

    @Test
    fun acceptsBareId() {
        assertEquals("9735321048", QQMusicShareLinkParser.parse("9735321048"))
    }

    @Test
    fun parsesWechatMobilePlaylistUrl() {
        val url = "https://i2.y.qq.com/n3/other/pages/details/playlist.html" +
            "?platform=11&appshare=android_qq&appversion=20090508" +
            "&hosteuin=owcq7eolNeni7z**&id=9616026161&ADTAG=wxfshare&mmkey"
        assertEquals("9616026161", QQMusicShareLinkParser.parse(url))
    }

    @Test
    fun parsesMobilePlaylistDisstidQuery() {
        val url = "https://y.qq.com/n/m/detail/taoge.html?disstid=9616026161"
        assertEquals("9616026161", QQMusicShareLinkParser.parse(url))
    }

    @Test
    fun ignoresMobileIdOnNonPlaylistPage() {
        val url = "https://i2.y.qq.com/n3/other/pages/details/song.html?id=9616026161"
        assertNull(QQMusicShareLinkParser.parse(url))
    }

    @Test
    fun rejectsNonQqHost() {
        assertNull(QQMusicShareLinkParser.parse("https://example.com/playlist/9735321048"))
    }

    @Test
    fun rejectsMissingId() {
        assertNull(QQMusicShareLinkParser.parse("https://y.qq.com/n/ryqq/playlist/abc"))
    }
}

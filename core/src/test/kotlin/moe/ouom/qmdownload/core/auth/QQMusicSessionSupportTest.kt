package moe.ouom.qmdownload.core.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QQMusicSessionSupportTest {

    @Test
    fun parsesCookieAndMusicKey() {
        val session = parseQQMusicCookie(
            "uin=o03396921622; qm_keyst=Q_H_L_test; qqmusic_key=Q_H_L_test; " +
                "qqmusic_uin=3396921622; login_type=1",
        )
        assertEquals("3396921622", session.uin)
        assertEquals("Q_H_L_test", session.musicKey)
        assertTrue(session.isLoggedIn)
    }

    @Test
    fun missingKeyMeansNotLoggedIn() {
        val session = parseQQMusicCookie("uin=o03396921622; login_type=1")
        assertFalse(session.isLoggedIn)
    }

    @Test
    fun gtkUsesHash33() {
        // g_tk 只要求稳定；这里锁定算法，防止后续重构改变
        assertEquals(1926538631L, qqMusicHash33("Q_H_L_test"))
    }
}

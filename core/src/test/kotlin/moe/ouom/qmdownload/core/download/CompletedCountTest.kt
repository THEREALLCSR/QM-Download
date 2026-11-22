package moe.ouom.qmdownload.core.download

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 批次完成数纯函数测试（统筹方编写，检验"进度恒为 0"的修复）。
 */
class CompletedCountTest {

    @Test
    fun `full progress when no tasks remain`() {
        assertEquals(1054, completedCount(1054, 0))
    }

    @Test
    fun `zero progress when all tasks remain`() {
        assertEquals(0, completedCount(1054, 1054))
    }

    @Test
    fun `partial progress`() {
        assertEquals(40, completedCount(1054, 1014))
        assertEquals(5, completedCount(39, 34))
    }

    @Test
    fun `clamps when remaining exceeds total after manual deletion`() {
        // 用户手动删任务可能导致 remaining 口径异常，不允许出现负数完成数
        assertEquals(0, completedCount(10, 15))
    }

    @Test
    fun `clamps when remaining is negative`() {
        // 异常口径：剩余为负时完成数不应超过 total
        assertEquals(10, completedCount(10, -3))
    }

    @Test
    fun `handles zero total`() {
        assertEquals(0, completedCount(0, 0))
        assertEquals(0, completedCount(0, 3))
    }
}

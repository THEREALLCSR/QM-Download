package moe.ouom.qmdownload.core.download

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 全局字节限速器测试（统筹方编写，用于检验限速实现）。
 *
 * 采用小速率 + 小字节量，总耗时控制在数秒内；时间断言用宽松阈值，
 * 只验证"限速确实生效"的语义，不追求精确速率，避免 CI 抖动误报。
 */
class ByteRateLimiterTest {

    @Test
    fun unlimitedRateReturnsImmediately() = runBlocking {
        val limiter = ByteRateLimiter(0L)
        val elapsed = measureTimeMillis {
            repeat(50) { limiter.acquire(64L * 1024L) }
        }
        assertTrue(elapsed < 1_000L, "不限速时应立即返回，实测 ${elapsed}ms")
    }

    @Test
    fun rateLimitingThrottlesTotalBytes() = runBlocking {
        // 32KB/s，请求 4×16KB = 64KB，理论约 2 秒；阈值放宽到 > 800ms
        val limiter = ByteRateLimiter(32L * 1024L)
        val elapsed = measureTimeMillis {
            repeat(4) { limiter.acquire(16L * 1024L) }
        }
        assertTrue(elapsed > 800L, "限速未生效：64KB@32KB/s 仅耗时 ${elapsed}ms")
    }

    @Test
    fun setRateChangesSpeedAtRuntime() = runBlocking {
        val limiter = ByteRateLimiter(0L)
        assertEquals(0L, limiter.bytesPerSecond)
        limiter.setRate(32L * 1024L)
        assertEquals(32L * 1024L, limiter.bytesPerSecond)
        val elapsed = measureTimeMillis { limiter.acquire(32L * 1024L) }
        assertTrue(elapsed > 400L, "改速率后应生效，实测 ${elapsed}ms")
    }

    @Test
    fun tokenBucketCapsBurstAfterIdle() = runBlocking {
        // 限 32KB/s，空闲 700ms 后一次请求 64KB；令牌上限 1 秒量，
        // 因此仍需等待剩余部分，不会瞬间放行。
        val limiter = ByteRateLimiter(32L * 1024L)
        delay(700L)
        val elapsed = measureTimeMillis { limiter.acquire(64L * 1024L) }
        assertTrue(elapsed > 500L, "突发未被限制，实测 ${elapsed}ms")
    }

    @Test
    fun acquireIsCancellable() = runBlocking {
        val limiter = ByteRateLimiter(1L) // 极慢，必然进入等待
        var cancelled = false
        val job = launch {
            try {
                limiter.acquire(1_000L)
            } catch (e: CancellationException) {
                cancelled = true
            }
        }
        delay(50L)
        job.cancel()
        delay(50L)
        assertTrue(cancelled, "acquire 等待期间应能被协程取消中断")
    }
}

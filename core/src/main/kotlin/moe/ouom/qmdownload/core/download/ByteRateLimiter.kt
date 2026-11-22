package moe.ouom.qmdownload.core.download

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 全局字节限速器（令牌桶）。
 *
 * - 速率为 [bytesPerSecond]；令牌上限为 1 秒的量，防止长时间空闲后无限累积；
 * - rate <= 0 表示不限速，[acquire] 立即返回；
 * - 令牌状态由 [Mutex] 保护，分段下载的多个协程并发 acquire 时按总带宽节流；
 * - 初始令牌为 0，构造后第一次 acquire 即按速率节流（不预置免费额度）；
 * - 等待使用 delay，且等待前后均检查 ensureActive，可被协程取消立刻中断。
 */
class ByteRateLimiter(initialBytesPerSecond: Long = 0L) {
    /** 每秒允许的字节数；0 表示不限速。可运行时修改，立即生效。 */
    @Volatile
    var bytesPerSecond: Long = initialBytesPerSecond

    /** 令牌状态由该锁保护，分段下载的多协程并发 acquire 时不会重复扣减。 */
    private val mutex = Mutex()

    /** 当前可用令牌（字节）。初始为空，随时间按速率补充，上限为 1 秒量。 */
    private var tokens: Double = 0.0

    /** 上次补充令牌的时间戳（纳秒）。 */
    private var lastRefillNanos: Long = System.nanoTime()

    /** 运行时改速率；与直接写 [bytesPerSecond] 等价，二者都会立即生效。 */
    fun setRate(bytesPerSecond: Long) {
        this.bytesPerSecond = bytesPerSecond
    }

    /**
     * 获取 [bytes] 个字节的令牌；令牌不足时用 delay 等待补足。
     *
     * 不限速（rate <= 0）或 [bytes] <= 0 时立即返回，零开销。
     */
    suspend fun acquire(bytes: Long) {
        if (bytes <= 0L) return
        while (true) {
            currentCoroutineContext().ensureActive()
            val fastRate = bytesPerSecond
            if (fastRate <= 0L) return

            var waitNanos = 0L
            mutex.withLock {
                // 锁内再读一次，保证补充计算与当前速率一致（速率可被并发修改）
                val rate = bytesPerSecond
                if (rate <= 0L) {
                    waitNanos = -1L
                    return@withLock
                }
                val now = System.nanoTime()
                val elapsed = now - lastRefillNanos
                if (elapsed > 0L) {
                    val capacity = rate.toDouble()
                    // 令牌上限 = 1 秒量，避免长时间空闲后突发无限累积
                    tokens = minOf(
                        capacity,
                        tokens + elapsed.toDouble() * rate / 1_000_000_000.0,
                    )
                    lastRefillNanos = now
                }
                // 速率被改小时按新上限裁剪已有令牌
                val capacity = rate.toDouble()
                if (tokens > capacity) tokens = capacity

                val granted = minOf(tokens, bytes.toDouble())
                tokens -= granted
                val remaining = bytes - granted.toLong()
                if (remaining > 0L) {
                    waitNanos = (remaining.toDouble() * 1_000_000_000.0 / rate).toLong() + 1L
                }
            }

            if (waitNanos < 0L) return
            if (waitNanos == 0L) return
            // 向上取整到毫秒并至少 delay 1ms，保证可取消且有实际推进
            delay(maxOf(1L, (waitNanos + 999_999L) / 1_000_000L))
            // 等待结束后立刻复查取消，避免被取消的协程继续补令牌/再次等待
            currentCoroutineContext().ensureActive()
        }
    }
}

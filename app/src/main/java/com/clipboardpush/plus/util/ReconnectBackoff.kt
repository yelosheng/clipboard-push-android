package com.clipboardpush.plus.util

/**
 * 断线重连的指数退避计算。
 *
 * 抖动只做减法，不做加法，这样 [MAX_DELAY_MS] 是一个真正的上界——
 * 便于推理"最坏情况多久重试一次"，也让单元测试可以用 jitter = 0.0 取到确定值。
 */
object ReconnectBackoff {

    const val BASE_DELAY_MS = 2_000L
    const val MAX_DELAY_MS = 60_000L
    const val JITTER_RATIO = 0.2

    /** 移位次数的上限，避免 attempt 很大时 shl 溢出。2000 shl 20 已远超 MAX_DELAY_MS。 */
    private const val MAX_SHIFT = 20

    /**
     * @param attempt 已经失败的次数，从 0 开始。负数按 0 处理。
     * @param jitter  [0.0, 1.0]，越大延迟越短。越界会被夹紧。默认随机。
     */
    fun delayForAttempt(attempt: Int, jitter: Double = Math.random()): Long {
        val shift = attempt.coerceIn(0, MAX_SHIFT)
        val capped = (BASE_DELAY_MS shl shift).coerceAtMost(MAX_DELAY_MS)
        val safeJitter = jitter.coerceIn(0.0, 1.0)
        val reduced = capped - capped * JITTER_RATIO * safeJitter
        return reduced.toLong().coerceIn(0L, MAX_DELAY_MS)
    }
}

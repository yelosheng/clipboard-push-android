package com.clipboardpush.plus.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionLivenessTest {

    private val threshold = ConnectionLiveness.STALE_THRESHOLD_MS

    @Test
    fun `no proof of life ever recorded is stale`() {
        assertTrue(ConnectionLiveness.isStale(lastProofAtMs = 0L, nowMs = 1_000_000L))
    }

    @Test
    fun `negative timestamp is stale`() {
        assertTrue(ConnectionLiveness.isStale(lastProofAtMs = -1L, nowMs = 1_000_000L))
    }

    @Test
    fun `fresh proof is not stale`() {
        val now = 1_000_000L
        assertFalse(ConnectionLiveness.isStale(lastProofAtMs = now, nowMs = now))
        assertFalse(ConnectionLiveness.isStale(lastProofAtMs = now - 1_000L, nowMs = now))
    }

    @Test
    fun `exactly at threshold is not yet stale`() {
        val now = 1_000_000L
        assertFalse(ConnectionLiveness.isStale(lastProofAtMs = now - threshold, nowMs = now))
    }

    @Test
    fun `one millisecond past threshold is stale`() {
        val now = 1_000_000L
        assertTrue(ConnectionLiveness.isStale(lastProofAtMs = now - threshold - 1, nowMs = now))
    }

    @Test
    fun `a long freeze is stale`() {
        val now = 1_000_000L
        // 冻结 10 分钟后解冻：远超阈值
        assertTrue(ConnectionLiveness.isStale(lastProofAtMs = now - 600_000L, nowMs = now))
    }

    @Test
    fun `clock moving backwards is treated as stale rather than fresh`() {
        // 时钟回拨（NTP 校正 / 用户改时间）会让 now - last 变成负数。
        // 绝不能因此判定"很新鲜"，否则僵尸连接会被永久放行。
        val now = 1_000_000L
        assertTrue(ConnectionLiveness.isStale(lastProofAtMs = now + 60_000L, nowMs = now))
    }

    @Test
    fun `custom threshold is honoured`() {
        val now = 1_000_000L
        assertFalse(ConnectionLiveness.isStale(now - 5_000L, now, thresholdMs = 10_000L))
        assertTrue(ConnectionLiveness.isStale(now - 15_000L, now, thresholdMs = 10_000L))
    }

    @Test
    fun `threshold comfortably exceeds one heartbeat cycle`() {
        // 心跳是 15s 间隔 + 8s 超时 = 23s 一轮。阈值必须能容忍若干轮抖动，
        // 否则正常网络波动会触发不必要的重连。
        assertTrue(threshold > 23_000L * 2)
    }
}

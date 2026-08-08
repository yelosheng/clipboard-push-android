package com.clipboardpush.plus.util

/**
 * 判断"距离上一次拿到连接存活证明是否已经太久"。
 *
 * 存在的理由：僵尸连接下 socket 对象自认为还连着、状态机停在 CONNECTED，
 * 但服务器早已把这个会话踢掉。仅凭一个布尔标志无法区分"刚刚才 pong 过"和
 * "上次 pong 是昨天的事"，所以必须记时间戳并按时间判定。
 */
object ConnectionLiveness {

    /**
     * 心跳一轮是 15s 间隔 + 8s 超时 = 23s。取 90s 允许连续几轮抖动，
     * 又能让一次真正的冻结（通常以分钟计）被稳稳判定为失效。
     */
    const val STALE_THRESHOLD_MS = 90_000L

    /**
     * @param lastProofAtMs 上次收到 server_pong（或刚建立连接）的时刻，0 表示从未有过。
     * @param nowMs 当前时刻。
     * @return true 表示这条连接不可信，应当强制重连。
     */
    fun isStale(
        lastProofAtMs: Long,
        nowMs: Long,
        thresholdMs: Long = STALE_THRESHOLD_MS
    ): Boolean {
        // 从未有过存活证明
        if (lastProofAtMs <= 0L) return true
        // 时钟回拨：宁可多重连一次，也不能把僵尸连接当成新鲜的放行
        if (nowMs < lastProofAtMs) return true
        return nowMs - lastProofAtMs > thresholdMs
    }
}

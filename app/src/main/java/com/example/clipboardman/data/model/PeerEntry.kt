package com.example.clipboardman.data.model

data class PeerEntry(
    val room: String,
    val server: String,
    val key: String,
    val localIp: String? = null,
    val localPort: Int? = null,
    val displayName: String,
    val lastConnectedAt: Long = System.currentTimeMillis()
) {
    companion object {
        /**
         * Upserts [newPeer] into [current] by room ID, trims to [maxSize] newest entries.
         * Result is ordered newest-first.
         */
        fun upsert(
            current: List<PeerEntry>,
            newPeer: PeerEntry,
            maxSize: Int = 5
        ): List<PeerEntry> {
            val filtered = current.filter { it.room != newPeer.room }
            return (listOf(newPeer) + filtered).take(maxSize)
        }
    }
}

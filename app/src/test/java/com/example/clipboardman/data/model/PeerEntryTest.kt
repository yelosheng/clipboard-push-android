package com.example.clipboardman.data.model

import org.junit.Assert.*
import org.junit.Test

class PeerEntryTest {

    private fun peer(room: String, name: String = "PC") = PeerEntry(
        room = room, server = "s", key = "k", displayName = name, lastConnectedAt = 0L
    )

    @Test
    fun `upsert adds new entry at front`() {
        val result = PeerEntry.upsert(listOf(peer("b")), peer("a"))
        assertEquals(listOf("a", "b"), result.map { it.room })
    }

    @Test
    fun `upsert deduplicates by room keeping newest`() {
        val old = peer("a").copy(displayName = "old")
        val new = peer("a").copy(displayName = "new")
        val result = PeerEntry.upsert(listOf(old, peer("b")), new)
        assertEquals(1, result.count { it.room == "a" })
        assertEquals("new", result.first().displayName)
    }

    @Test
    fun `upsert trims to maxSize evicting oldest`() {
        val current = (1..5).map { peer("room$it") }
        val result = PeerEntry.upsert(current, peer("room6"))
        assertEquals(5, result.size)
        assertEquals("room6", result.first().room)
        assertFalse(result.any { it.room == "room1" })
    }

    @Test
    fun `upsert on empty list returns single entry`() {
        val result = PeerEntry.upsert(emptyList(), peer("x"))
        assertEquals(listOf("x"), result.map { it.room })
    }
}

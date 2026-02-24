package com.example.clipboardman.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.example.clipboardman.data.model.PeerEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SettingsRepositoryTest {

    @get:Rule
    val tmpFolder: TemporaryFolder = TemporaryFolder.builder().assureDeletion().build()

    private val testDispatcher = StandardTestDispatcher()

    // A separate scope for the DataStore — must outlive runTest but be cancelled in @After.
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var repo: SettingsRepository

    private fun peer(room: String, name: String = "PC $room") = PeerEntry(
        room = room,
        server = "relay.example.com",
        key = "key$room",
        localIp = null,
        localPort = null,
        displayName = name,
        lastConnectedAt = System.currentTimeMillis()
    )

    @Before
    fun setUp() {
        // A fresh CoroutineScope per test — not the runTest TestScope — so that DataStore's
        // internal actor coroutines don't cause UncompletedCoroutinesError.
        dataStoreScope = CoroutineScope(testDispatcher)
        val dsFolder = tmpFolder.newFolder()
        val dsFile = File(dsFolder, "test_settings.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            scope = dataStoreScope,
            produceFile = { dsFile }
        )
        repo = SettingsRepository(
            context = androidx.test.core.app.ApplicationProvider.getApplicationContext(),
            dataStore = dataStore
        )
    }

    @After
    fun tearDown() {
        dataStoreScope.cancel()
    }

    @Test
    fun `addOrUpdateRecentPeer inserts new entry`() = runTest(testDispatcher) {
        repo.addOrUpdateRecentPeer(peer("room1"))
        val peers = repo.recentPeersFlow.first()
        assertEquals(1, peers.size)
        assertEquals("room1", peers[0].room)
    }

    @Test
    fun `addOrUpdateRecentPeer updates existing entry by room`() = runTest(testDispatcher) {
        repo.addOrUpdateRecentPeer(peer("room1", "Old Name"))
        repo.addOrUpdateRecentPeer(peer("room1", "New Name"))
        val peers = repo.recentPeersFlow.first()
        assertEquals(1, peers.size)
        assertEquals("New Name", peers[0].displayName)
    }

    @Test
    fun `addOrUpdateRecentPeer caps list at 5`() = runTest(testDispatcher) {
        repeat(6) { i -> repo.addOrUpdateRecentPeer(peer("room$i")) }
        val peers = repo.recentPeersFlow.first()
        assertEquals(5, peers.size)
    }

    @Test
    fun `updateRecentPeerDisplayName only affects matching room`() = runTest(testDispatcher) {
        repo.addOrUpdateRecentPeer(peer("roomA", "Alpha"))
        repo.addOrUpdateRecentPeer(peer("roomB", "Beta"))
        repo.updateRecentPeerDisplayName("roomA", "Alpha Updated")
        val peers = repo.recentPeersFlow.first()
        val a = peers.first { it.room == "roomA" }
        val b = peers.first { it.room == "roomB" }
        assertEquals("Alpha Updated", a.displayName)
        assertEquals("Beta", b.displayName)
    }

    @Test
    fun `removeRecentPeer removes correct entry`() = runTest(testDispatcher) {
        repo.addOrUpdateRecentPeer(peer("roomA"))
        repo.addOrUpdateRecentPeer(peer("roomB"))
        repo.removeRecentPeer("roomA")
        val peers = repo.recentPeersFlow.first()
        assertEquals(1, peers.size)
        assertEquals("roomB", peers[0].room)
    }
}

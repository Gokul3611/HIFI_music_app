package com.stash.core.media.recommendation

import com.google.common.truth.Truth.assertThat
import com.stash.core.model.Track
import org.junit.Test

class AiMoodShuffleEngineTest {

    private val engine = AiMoodShuffleEngine()

    private fun track(
        id: Long,
        title: String,
        artist: String,
        album: String = "",
        durationMs: Long = 180000L,
        playCount: Int = 0,
        liked: Boolean = false,
    ) = Track(
        id = id,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        playCount = playCount,
        stashLikedAt = if (liked) 123456L else null,
    )

    @Test
    fun `shuffle preserves all tracks without duplicates`() {
        val pool = (1L..25L).map { i ->
            track(i, "Song $i", "Artist ${i % 5}", "Album ${i % 3}")
        }

        val shuffled = engine.shuffle(pool, ListeningMood.FLOW)

        assertThat(shuffled).hasSize(pool.size)
        assertThat(shuffled.map { it.id }.toSet()).hasSize(pool.size)
    }

    @Test
    fun `energetic mood ranks high-energy keywords first`() {
        val energeticTrack = track(1L, "Hard Bass Party Remix", "DJ Overdrive", durationMs = 190000L)
        val chillTrack = track(2L, "Soft Rain Meditation", "Ambient Peace", durationMs = 300000L)
        val pool = listOf(chillTrack, energeticTrack)

        val shuffled = engine.shuffle(pool, ListeningMood.ENERGETIC)

        // Energetic track should be sequenced at the front of the queue
        assertThat(shuffled.first()).isEqualTo(energeticTrack)
    }

    @Test
    fun `chill mood prioritizes ambient and slow songs`() {
        val energeticTrack = track(1L, "Club Rave Anthem", "DJ Hyper", durationMs = 180000L)
        val chillTrack = track(2L, "Lofi Sleep Breeze", "Calm Dream", durationMs = 210000L)
        val pool = listOf(energeticTrack, chillTrack)

        val shuffled = engine.shuffle(pool, ListeningMood.CHILL)

        assertThat(shuffled.first()).isEqualTo(chillTrack)
    }

    @Test
    fun `flow sequence avoids clustering same artist indefinitely`() {
        val sameArtistTracks = (1L..5L).map { track(it, "Song $it", "Same Artist") }
        val otherTracks = (6L..10L).map { track(it, "Different $it", "Other Artist $it") }
        val pool = sameArtistTracks + otherTracks

        val flow = engine.shuffle(pool, ListeningMood.FLOW)

        // No more than 2 consecutive tracks from the same artist in the flow
        var maxConsecutive = 1
        var currentConsecutive = 1
        for (i in 1 until flow.size) {
            if (flow[i].artist == flow[i - 1].artist) {
                currentConsecutive++
                if (currentConsecutive > maxConsecutive) maxConsecutive = currentConsecutive
            } else {
                currentConsecutive = 1
            }
        }

        assertThat(maxConsecutive).isAtMost(2)
    }
}

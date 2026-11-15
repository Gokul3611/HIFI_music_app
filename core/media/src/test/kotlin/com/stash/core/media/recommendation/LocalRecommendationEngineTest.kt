package com.stash.core.media.recommendation

import com.google.common.truth.Truth.assertThat
import com.stash.core.model.Track
import org.junit.Test

class LocalRecommendationEngineTest {

    private val engine = LocalRecommendationEngine()

    private fun track(
        id: Long,
        title: String,
        artist: String,
        album: String = "",
        durationMs: Long = 180000L,
        bitsPerSample: Int? = null,
        sampleRateHz: Int? = null,
    ) = Track(
        id = id,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        bitsPerSample = bitsPerSample,
        sampleRateHz = sampleRateHz,
    )

    @Test
    fun `same artist ranks highest`() {
        val seed = track(1L, "Starboy", "The Weeknd", "Starboy", 230000L)
        val candidate1 = track(2L, "Blinding Lights", "The Weeknd", "After Hours", 200000L)
        val candidate2 = track(3L, "Shape of You", "Ed Sheeran", "Divide", 230000L)

        val recommendations = engine.findSimilarTracks(seed, listOf(candidate1, candidate2))

        assertThat(recommendations).containsExactly(candidate1, candidate2).inOrder()
    }

    @Test
    fun `same album tracks rank higher than unrelated tracks`() {
        val seed = track(1L, "Track 1", "Artist A", "Masterpiece Album")
        val candidateAlbum = track(2L, "Track 2", "Artist A", "Masterpiece Album")
        val candidateDifferent = track(3L, "Other Song", "Different Artist", "Other Album")

        val recommendations = engine.findSimilarTracks(seed, listOf(candidateDifferent, candidateAlbum))

        assertThat(recommendations.first()).isEqualTo(candidateAlbum)
    }

    @Test
    fun `excludes seed itself and already queued IDs`() {
        val seed = track(1L, "Song A", "Artist X")
        val candidate1 = track(1L, "Song A", "Artist X") // Same as seed
        val candidate2 = track(2L, "Song B", "Artist X") // In excludeIds
        val candidate3 = track(3L, "Song C", "Artist X")

        val recommendations = engine.findSimilarTracks(
            seed = seed,
            pool = listOf(candidate1, candidate2, candidate3),
            excludeIds = setOf(2L),
        )

        assertThat(recommendations).containsExactly(candidate3)
    }

    @Test
    fun `duration proximity boosts similar length songs`() {
        val seed = track(1L, "Focus", "Ambient", durationMs = 300000L) // 5 min
        val candSimilarLen = track(2L, "Meditation", "Soundscape", durationMs = 310000L) // ~5 min
        val candShort = track(3L, "Jingle", "Soundscape", durationMs = 30000L) // 30 sec

        val recommendations = engine.findSimilarTracks(seed, listOf(candShort, candSimilarLen))

        assertThat(recommendations.first()).isEqualTo(candSimilarLen)
    }

    @Test
    fun `audiophile hi-res format matching adds synergy bonus`() {
        val seed = track(1L, "Hi-Res Master", "Audiophile Artist", bitsPerSample = 24, sampleRateHz = 96000)
        val hiResCand = track(2L, "Track 2", "Audiophile Artist", bitsPerSample = 24, sampleRateHz = 96000)
        val stdCand = track(3L, "Track 3", "Audiophile Artist", bitsPerSample = 16, sampleRateHz = 44100)

        val recommendations = engine.findSimilarTracks(seed, listOf(stdCand, hiResCand))

        assertThat(recommendations.first()).isEqualTo(hiResCand)
    }
}

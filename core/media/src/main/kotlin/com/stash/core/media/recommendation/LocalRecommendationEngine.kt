package com.stash.core.media.recommendation

import com.stash.core.model.Track
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Stylistic genre and mood families for audio continuity.
 * Ensures recommendations and AI shuffles maintain a cohesive musical vibe
 * without jarring shifts (e.g., preventing rock/metal or upbeat dance from playing
 * immediately after a romantic/acoustic ballad).
 */
enum class StyleCluster {
    ROMANTIC_BALLAD_ACOUSTIC,
    ROCK_METAL_HARDCORE,
    FOLK_COUNTRY_ROOTS,
    HIPHOP_RAP_R_AND_B,
    ELECTRONIC_DANCE_CLUB,
    CLASSICAL_AMBIENT_LOFI,
    POP_UPBEAT_GENERAL,
    GENERAL,
}

/**
 * Precomputed metadata profile for O(N) feature extraction and fast similarity scoring.
 */
private class TrackProfile(
    val track: Track,
    val cluster: StyleCluster,
    val artistLower: String,
    val artistTokens: Set<String>,
    val albumLower: String,
    val titleKeywords: Set<String>,
)

/**
 * Enhanced, high-performance local music recommendation and vibe-continuity engine.
 *
 * Emulates Spotify-style intelligent next-song relevance on 100% on-device
 * music libraries without external network dependencies, heavy neural networks,
 * or slow web scrapers.
 *
 * Scoring signals:
 * 1. Stylistic / Genre Vibe affinity (+40) & strict incompatibility barrier (-120 penalty)
 * 2. Primary Artist match (+50) & Collaboration/Featuring artist match (+35)
 * 3. Album continuity (+25)
 * 4. Title / vibe lexical keyword similarity (+16)
 * 5. Duration & tempo proximity within ±15% (+15)
 * 6. High-resolution / audiophile bit-depth & sample-rate synergy (+10)
 * 7. User affinity boost (Liked / Saved / Highly played) (+18)
 */
@Singleton
class LocalRecommendationEngine @Inject constructor() {

    fun findSimilarTracks(
        seed: Track,
        pool: List<Track>,
        excludeIds: Set<Long> = emptySet(),
        limit: Int = 5,
    ): List<Track> {
        if (pool.isEmpty()) return emptyList()

        val seedProfile = profileOf(seed)

        val scored = pool.asSequence()
            .filter { candidate ->
                candidate.id != seed.id &&
                    candidate.id !in excludeIds &&
                    !candidate.title.equals(seed.title, ignoreCase = true)
            }
            .map { candidate ->
                val candProfile = profileOf(candidate)
                candidate to scorePair(seedProfile, candProfile)
            }
            // Disqualify strong incompatibilities (negative total score)
            .filter { it.second > 0.0 }
            .sortedByDescending { it.second }
            .map { it.first }
            .take(limit)
            .toList()

        // Fallback: If library has very few songs or scores were sparse, return remaining pool
        if (scored.isEmpty()) {
            return pool
                .filter { it.id != seed.id && it.id !in excludeIds }
                .shuffled()
                .take(limit)
        }

        return scored
    }

    /**
     * Sequences an entire pool of tracks into a smooth, vibe-coherent listening chain in O(N).
     * Precomputes track profiles once, partitions by stylistic cluster ordered by compatibility
     * from [seedTrack], and chains tracks within each cluster using a bounded lookahead window
     * so even 10,000+ track libraries complete in a few milliseconds without blocking the UI.
     */
    fun sequenceVibeChain(pool: List<Track>, seedTrack: Track? = null): List<Track> {
        if (pool.size <= 2) return pool

        // Deduplicate by id while preserving pool tracks safely
        val distinctPool = pool.distinctBy { it.id }
        if (distinctPool.size <= 2) return distinctPool

        val profiles = distinctPool.map { profileOf(it) }
        val firstProfile = (seedTrack?.let { s -> profiles.firstOrNull { it.track.id == s.id } }
            ?: profiles.firstOrNull { it.track.stashLikedAt != null }
            ?: profiles.first())

        val seedCluster = firstProfile.cluster
        val byCluster = profiles
            .filter { it.track.id != firstProfile.track.id }
            .groupBy { it.cluster }

        // Order clusters from most compatible with seedCluster to least compatible
        val orderedClusters = byCluster.keys.sortedByDescending { cluster ->
            if (cluster == seedCluster) 1000.0 else computeStyleAffinity(seedCluster, cluster)
        }

        val result = ArrayList<Track>(distinctPool.size)
        result.add(firstProfile.track)

        var current = firstProfile
        for (cluster in orderedClusters) {
            val bucket = byCluster[cluster]?.toMutableList() ?: continue
            // Shuffle bucket slightly before local chaining so repeat AI shuffles feel fresh
            bucket.shuffle()
            while (bucket.isNotEmpty()) {
                val windowSize = minOf(bucket.size, CHAIN_LOOKAHEAD_WINDOW)
                var bestIdx = 0
                var bestScore = Double.NEGATIVE_INFINITY
                for (i in 0 until windowSize) {
                    val s = scorePair(current, bucket[i])
                    if (s > bestScore) {
                        bestScore = s
                        bestIdx = i
                    }
                }
                val chosen = bucket.removeAt(bestIdx)
                result.add(chosen.track)
                current = chosen
            }
        }

        return result
    }

    private fun scorePair(seed: TrackProfile, candidate: TrackProfile): Double {
        var score = 0.0

        // 1. Stylistic Family Continuity & Compatibility Barrier
        score += computeStyleAffinity(seed.cluster, candidate.cluster)

        // 2. Primary Artist affinity & Collaboration matching
        if (seed.artistLower.isNotEmpty() && candidate.artistLower.isNotEmpty()) {
            if (seed.artistLower == candidate.artistLower) {
                score += 50.0
            } else if (
                seed.artistLower.contains(candidate.artistLower) ||
                candidate.artistLower.contains(seed.artistLower)
            ) {
                score += 35.0
            } else if (seed.artistTokens.isNotEmpty() && candidate.artistTokens.isNotEmpty()) {
                var sharedCount = 0
                for (t in seed.artistTokens) {
                    if (t in candidate.artistTokens) sharedCount++
                }
                if (sharedCount > 0) {
                    score += sharedCount * 35.0
                }
            }
        }

        // 3. Album context
        if (seed.albumLower.isNotEmpty() && candidate.albumLower.isNotEmpty() && seed.albumLower == candidate.albumLower) {
            score += 25.0
        }

        // 4. Keyword / title overlap
        if (seed.titleKeywords.isNotEmpty() && candidate.titleKeywords.isNotEmpty()) {
            var sharedKeywords = 0
            for (k in seed.titleKeywords) {
                if (k in candidate.titleKeywords) sharedKeywords++
            }
            if (sharedKeywords > 0) {
                score += sharedKeywords * 16.0
            }
        }

        // 5. Duration & tempo proximity
        val seedDuration = seed.track.durationMs
        val candDuration = candidate.track.durationMs
        if (seedDuration > 0 && candDuration > 0) {
            val ratio = abs(seedDuration - candDuration).toDouble() / seedDuration.toDouble()
            if (ratio <= 0.15) {
                score += 15.0
            } else if (ratio <= 0.30) {
                score += 8.0
            } else if (ratio > 1.2) {
                score -= 10.0
            }
        }

        // 6. Audiophile synergy (matching 24-bit / 96kHz or high quality FLAC)
        if (seed.track.bitsPerSample != null && seed.track.bitsPerSample == candidate.track.bitsPerSample) {
            score += 5.0
        }
        if (seed.track.sampleRateHz != null && seed.track.sampleRateHz == candidate.track.sampleRateHz) {
            score += 5.0
        }

        // 7. User affinity (prefer songs user likes or frequently plays)
        if (
            candidate.track.stashLikedAt != null ||
            candidate.track.spotifySavedAt != null ||
            candidate.track.ytMusicSavedAt != null
        ) {
            score += 18.0
        }
        score += (candidate.track.playCount * 0.5).coerceAtMost(12.0)

        // 8. Controlled pseudo-random jitter
        val jitter = ((candidate.track.id.hashCode() and 0x7FFFFFFF) % 7) * 0.5
        score += jitter

        return score
    }

    private fun profileOf(track: Track): TrackProfile = TrackProfile(
        track = track,
        cluster = detectStyleCluster(track),
        artistLower = track.artist.trim().lowercase(),
        artistTokens = extractArtistTokens(track.artist),
        albumLower = track.album.trim().lowercase(),
        titleKeywords = extractKeywords(track.title),
    )

    /**
     * Calculates compatibility score between style clusters.
     * Strict negative barriers prevent harsh jumps (e.g. Romantic -> Rock/Metal/Club).
     */
    fun computeStyleAffinity(seed: StyleCluster, candidate: StyleCluster): Double {
        if (seed == StyleCluster.GENERAL || candidate == StyleCluster.GENERAL) return 0.0
        if (seed == candidate) return 40.0

        return when (seed) {
            StyleCluster.ROMANTIC_BALLAD_ACOUSTIC -> when (candidate) {
                StyleCluster.ROCK_METAL_HARDCORE -> -120.0
                StyleCluster.ELECTRONIC_DANCE_CLUB -> -90.0
                StyleCluster.FOLK_COUNTRY_ROOTS -> -40.0
                StyleCluster.HIPHOP_RAP_R_AND_B -> -30.0
                StyleCluster.CLASSICAL_AMBIENT_LOFI -> 20.0
                StyleCluster.POP_UPBEAT_GENERAL -> 5.0
                else -> 0.0
            }
            StyleCluster.ROCK_METAL_HARDCORE -> when (candidate) {
                StyleCluster.ROMANTIC_BALLAD_ACOUSTIC -> -120.0
                StyleCluster.CLASSICAL_AMBIENT_LOFI -> -90.0
                StyleCluster.ELECTRONIC_DANCE_CLUB -> -20.0
                StyleCluster.FOLK_COUNTRY_ROOTS -> 10.0
                StyleCluster.HIPHOP_RAP_R_AND_B -> -40.0
                StyleCluster.POP_UPBEAT_GENERAL -> 10.0
                else -> 0.0
            }
            StyleCluster.FOLK_COUNTRY_ROOTS -> when (candidate) {
                StyleCluster.ELECTRONIC_DANCE_CLUB -> -100.0
                StyleCluster.HIPHOP_RAP_R_AND_B -> -80.0
                StyleCluster.ROCK_METAL_HARDCORE -> 10.0
                StyleCluster.ROMANTIC_BALLAD_ACOUSTIC -> -10.0
                StyleCluster.CLASSICAL_AMBIENT_LOFI -> 15.0
                else -> 0.0
            }
            StyleCluster.HIPHOP_RAP_R_AND_B -> when (candidate) {
                StyleCluster.FOLK_COUNTRY_ROOTS -> -80.0
                StyleCluster.ROCK_METAL_HARDCORE -> -40.0
                StyleCluster.ELECTRONIC_DANCE_CLUB -> 15.0
                StyleCluster.POP_UPBEAT_GENERAL -> 20.0
                else -> 0.0
            }
            StyleCluster.ELECTRONIC_DANCE_CLUB -> when (candidate) {
                StyleCluster.ROMANTIC_BALLAD_ACOUSTIC -> -90.0
                StyleCluster.FOLK_COUNTRY_ROOTS -> -100.0
                StyleCluster.CLASSICAL_AMBIENT_LOFI -> -50.0
                StyleCluster.POP_UPBEAT_GENERAL -> 25.0
                StyleCluster.HIPHOP_RAP_R_AND_B -> 15.0
                else -> 0.0
            }
            StyleCluster.CLASSICAL_AMBIENT_LOFI -> when (candidate) {
                StyleCluster.ROCK_METAL_HARDCORE -> -100.0
                StyleCluster.ELECTRONIC_DANCE_CLUB -> -60.0
                StyleCluster.ROMANTIC_BALLAD_ACOUSTIC -> 20.0
                else -> 0.0
            }
            StyleCluster.POP_UPBEAT_GENERAL -> when (candidate) {
                StyleCluster.ROCK_METAL_HARDCORE -> 10.0
                StyleCluster.ELECTRONIC_DANCE_CLUB -> 25.0
                StyleCluster.HIPHOP_RAP_R_AND_B -> 20.0
                else -> 0.0
            }
            StyleCluster.GENERAL -> 0.0
        }
    }

    /**
     * Classifies a track into a stylistic mood and genre cluster based on title,
     * artist, and album keywords.
     */
    fun detectStyleCluster(track: Track): StyleCluster {
        val text = "${track.title} ${track.artist} ${track.album}".lowercase()

        if (text.containsAny(ROMANTIC_WORDS)) return StyleCluster.ROMANTIC_BALLAD_ACOUSTIC
        if (text.containsAny(ROCK_WORDS)) return StyleCluster.ROCK_METAL_HARDCORE
        if (text.containsAny(FOLK_WORDS)) return StyleCluster.FOLK_COUNTRY_ROOTS
        if (text.containsAny(HIPHOP_WORDS)) return StyleCluster.HIPHOP_RAP_R_AND_B
        if (text.containsAny(ELECTRONIC_WORDS)) return StyleCluster.ELECTRONIC_DANCE_CLUB
        if (text.containsAny(AMBIENT_WORDS)) return StyleCluster.CLASSICAL_AMBIENT_LOFI
        if (text.containsAny(POP_WORDS)) return StyleCluster.POP_UPBEAT_GENERAL

        return StyleCluster.GENERAL
    }

    private fun String.containsAny(words: Array<String>): Boolean {
        for (w in words) {
            if (this.contains(w)) return true
        }
        return false
    }

    private fun extractArtistTokens(artistStr: String): Set<String> {
        if (artistStr.isBlank()) return emptySet()
        val clean = artistStr.lowercase()
            .replace(ARTIST_SPLIT_REGEX, ",")
            .replace('&', ',')
            .replace(';', ',')
            .replace('/', ',')
        return clean.split(',')
            .asSequence()
            .map { it.trim() }
            .filter { it.length > 1 }
            .toSet()
    }

    private fun extractKeywords(title: String): Set<String> {
        if (title.isBlank()) return emptySet()
        val clean = title.lowercase().replace(NON_ALNUM_REGEX, " ")
        return clean.split(WHITESPACE_REGEX)
            .asSequence()
            .filter { it.length > 2 && it !in STOP_WORDS }
            .toSet()
    }

    private companion object {
        const val CHAIN_LOOKAHEAD_WINDOW = 60

        val ARTIST_SPLIT_REGEX = Regex("(?i)\\b(feat\\.?|ft\\.?|with|featuring|vs\\.?|and)\\b")
        val NON_ALNUM_REGEX = Regex("[^a-z0-9\\s]")
        val WHITESPACE_REGEX = Regex("\\s+")
        val STOP_WORDS = setOf("the", "a", "an", "and", "or", "feat", "ft", "in", "on", "at", "to", "for", "of", "with")

        val ROMANTIC_WORDS = arrayOf(
            "romantic", "romance", "love", "ballad", "acoustic", "unplugged",
            "slow", "heart", "tender", "sweet", "piano version", "lullaby",
            "gentle", "soft", "intimate", "candle", "hug", "kiss", "lovers",
        )
        val ROCK_WORDS = arrayOf(
            "rock", "metal", "heavy", "punk", "grunge", "hardcore", "electric",
            "riff", "screamo", "distorted", "thrash", "iron", "metallica", "ac/dc",
            "slayer", "slipknot", "nirvana", "guitar solo",
        )
        val FOLK_WORDS = arrayOf(
            "folk", "country", "bluegrass", "americana", "banjo", "fiddle",
            "roots", "cowboy", "nashville", "western", "traditional",
        )
        val HIPHOP_WORDS = arrayOf(
            "rap", "hip hop", "hip-hop", "trap", "drill", "freestyle", "cypher",
            "r&b", "soul", "urban", "beats", "rhyme", "mc ", "dj ", "eminem", "drake",
        )
        val ELECTRONIC_WORDS = arrayOf(
            "edm", "electronic", "house", "techno", "dance", "club", "remix",
            "rave", "dubstep", "trance", "synthwave", "electro", "dj mix", "extended mix",
        )
        val AMBIENT_WORDS = arrayOf(
            "classical", "orchestra", "symphony", "ambient", "lofi", "lo-fi",
            "instrumental", "chillout", "meditation", "sleep", "study", "mozart",
            "beethoven", "bach", "chopin",
        )
        val POP_WORDS = arrayOf(
            "pop", "upbeat", "cheerful", "party", "summer", "dance pop", "synthpop",
        )
    }
}

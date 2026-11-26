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

        val seedCluster = detectStyleCluster(seed)
        val seedArtist = seed.artist.trim().lowercase()
        val seedArtistTokens = extractArtistTokens(seed.artist)
        val seedAlbum = seed.album.trim().lowercase()
        val seedKeywords = extractKeywords(seed.title)
        val seedDuration = seed.durationMs

        val scored = pool.asSequence()
            .filter { candidate ->
                candidate.id != seed.id &&
                candidate.id !in excludeIds &&
                !candidate.title.equals(seed.title, ignoreCase = true)
            }
            .map { candidate ->
                var score = 0.0
                val candCluster = detectStyleCluster(candidate)
                val candArtist = candidate.artist.trim().lowercase()
                val candArtistTokens = extractArtistTokens(candidate.artist)
                val candAlbum = candidate.album.trim().lowercase()

                // 1. Stylistic Family Continuity & Compatibility Barrier
                score += computeStyleAffinity(seedCluster, candCluster)

                // 2. Primary Artist affinity & Collaboration matching
                if (seedArtist.isNotEmpty() && candArtist.isNotEmpty()) {
                    if (seedArtist == candArtist) {
                        score += 50.0
                    } else if (seedArtist.contains(candArtist) || candArtist.contains(seedArtist)) {
                        score += 35.0
                    } else {
                        // Check featuring/collaborating sub-artists
                        val sharedArtists = seedArtistTokens.intersect(candArtistTokens)
                        if (sharedArtists.isNotEmpty()) {
                            score += sharedArtists.size * 35.0
                        }
                    }
                }

                // 3. Album context
                if (seedAlbum.isNotEmpty() && candAlbum.isNotEmpty() && seedAlbum == candAlbum) {
                    score += 25.0
                }

                // 4. Keyword / title overlap (e.g. "Acoustic", "Live", "Pt. 2", shared themes)
                val candKeywords = extractKeywords(candidate.title)
                val sharedKeywords = seedKeywords.intersect(candKeywords)
                if (sharedKeywords.isNotEmpty()) {
                    score += sharedKeywords.size * 16.0
                }

                // 5. Duration & tempo proximity
                if (seedDuration > 0 && candidate.durationMs > 0) {
                    val ratio = abs(seedDuration - candidate.durationMs).toDouble() / seedDuration.toDouble()
                    if (ratio <= 0.15) {
                        score += 15.0
                    } else if (ratio <= 0.30) {
                        score += 8.0
                    } else if (ratio > 1.2) {
                        score -= 10.0
                    }
                }

                // 6. Audiophile synergy (matching 24-bit / 96kHz or high quality FLAC)
                if (seed.bitsPerSample != null && seed.bitsPerSample == candidate.bitsPerSample) {
                    score += 5.0
                }
                if (seed.sampleRateHz != null && seed.sampleRateHz == candidate.sampleRateHz) {
                    score += 5.0
                }

                // 7. User affinity (prefer songs user likes or frequently plays)
                if (candidate.stashLikedAt != null || candidate.spotifySavedAt != null || candidate.ytMusicSavedAt != null) {
                    score += 18.0
                }
                score += (candidate.playCount * 0.5).coerceAtMost(12.0)

                // 8. Controlled pseudo-random jitter (avoids absolute monotony on repeat sessions)
                val jitter = ((candidate.id.hashCode() and 0x7FFFFFFF) % 7) * 0.5
                score += jitter

                candidate to score
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
     * Sequences an entire pool of tracks into a smooth, vibe-coherent listening chain.
     * Starts with [seedTrack] (or the highest affinity track if null) and iteratively
     * links the most musically compatible track next, eliminating jarring genre jumps.
     */
    fun sequenceVibeChain(pool: List<Track>, seedTrack: Track? = null): List<Track> {
        if (pool.size <= 2) return pool

        val remaining = pool.toMutableList()
        val result = ArrayList<Track>(pool.size)

        // Select starting seed: explicit seedTrack, or first liked track, or first track in pool
        val firstTrack = (seedTrack?.let { s -> remaining.firstOrNull { it.id == s.id } }
            ?: remaining.firstOrNull { it.stashLikedAt != null }
            ?: remaining.first())

        result.add(firstTrack)
        remaining.removeAll { it.id == firstTrack.id }

        var currentSeed = firstTrack
        while (remaining.isNotEmpty()) {
            val candidates = findSimilarTracks(currentSeed, remaining, limit = 3)
            val nextTrack = if (candidates.isNotEmpty()) {
                // Pick top candidate with slight temperature/variety
                candidates.first()
            } else {
                remaining.first()
            }
            result.add(nextTrack)
            remaining.removeAll { it.id == nextTrack.id }
            currentSeed = nextTrack
        }

        return result
    }

    /**
     * Calculates compatibility score between style clusters.
     * Strict negative barriers prevent harsh jumps (e.g. Romantic -> Rock/Metal/Club).
     */
    fun computeStyleAffinity(seed: StyleCluster, candidate: StyleCluster): Double {
        if (seed == StyleCluster.GENERAL || candidate == StyleCluster.GENERAL) return 0.0
        if (seed == candidate) return 40.0

        return when (seed) {
            StyleCluster.ROMANTIC_BALLAD_ACOUSTIC -> when (candidate) {
                StyleCluster.ROCK_METAL_HARDCORE -> -120.0 // Hard barrier: never jump romantic -> rock/metal
                StyleCluster.ELECTRONIC_DANCE_CLUB -> -90.0
                StyleCluster.FOLK_COUNTRY_ROOTS -> -40.0
                StyleCluster.HIPHOP_RAP_R_AND_B -> -30.0
                StyleCluster.CLASSICAL_AMBIENT_LOFI -> 20.0 // Compatible mellow vibe
                StyleCluster.POP_UPBEAT_GENERAL -> 5.0
                else -> 0.0
            }
            StyleCluster.ROCK_METAL_HARDCORE -> when (candidate) {
                StyleCluster.ROMANTIC_BALLAD_ACOUSTIC -> -120.0 // Hard barrier: never jump rock -> romantic
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

        // 1. Romantic / Ballad / Acoustic / Soft
        if (text.containsAny(
            "romantic", "romance", "love", "ballad", "acoustic", "unplugged",
            "slow", "heart", "tender", "sweet", "piano version", "lullaby",
            "gentle", "soft", "intimate", "candle", "hug", "kiss", "lovers"
        )) {
            return StyleCluster.ROMANTIC_BALLAD_ACOUSTIC
        }

        // 2. Rock / Metal / Hardcore
        if (text.containsAny(
            "rock", "metal", "heavy", "punk", "grunge", "hardcore", "electric",
            "riff", "screamo", "distorted", "thrash", "iron", "metallica", "ac/dc",
            "slayer", "slipknot", "nirvana", "guitar solo"
        )) {
            return StyleCluster.ROCK_METAL_HARDCORE
        }

        // 3. Folk / Country / Americana
        if (text.containsAny(
            "folk", "country", "bluegrass", "americana", "banjo", "fiddle",
            "roots", "cowboy", "nashville", "western", "traditional"
        )) {
            return StyleCluster.FOLK_COUNTRY_ROOTS
        }

        // 4. Hip-Hop / Rap / Trap / R&B
        if (text.containsAny(
            "rap", "hip hop", "hip-hop", "trap", "drill", "freestyle", "cypher",
            "r&b", "soul", "urban", "beats", "rhyme", "mc ", "dj ", "eminem", "drake"
        )) {
            return StyleCluster.HIPHOP_RAP_R_AND_B
        }

        // 5. Electronic / Dance / EDM / Club
        if (text.containsAny(
            "edm", "electronic", "house", "techno", "dance", "club", "remix",
            "rave", "dubstep", "trance", "synthwave", "electro", "dj mix", "extended mix"
        )) {
            return StyleCluster.ELECTRONIC_DANCE_CLUB
        }

        // 6. Classical / Ambient / Lo-Fi / Instrumental
        if (text.containsAny(
            "classical", "orchestra", "symphony", "ambient", "lofi", "lo-fi",
            "instrumental", "chillout", "meditation", "sleep", "study", "mozart",
            "beethoven", "bach", "chopin"
        )) {
            return StyleCluster.CLASSICAL_AMBIENT_LOFI
        }

        // 7. Pop / Upbeat
        if (text.containsAny(
            "pop", "upbeat", "cheerful", "party", "summer", "dance pop", "synthpop"
        )) {
            return StyleCluster.POP_UPBEAT_GENERAL
        }

        return StyleCluster.GENERAL
    }

    private fun String.containsAny(vararg words: String): Boolean {
        for (w in words) {
            if (this.contains(w)) return true
        }
        return false
    }

    private fun extractArtistTokens(artistStr: String): Set<String> {
        val clean = artistStr.lowercase()
            .replace(Regex("(?i)\\b(feat\\.?|ft\\.?|with|featuring|vs\\.?|and)\\b"), ",")
            .replace("&", ",")
            .replace(";", ",")
            .replace("/", ",")
        return clean.split(",")
            .map { it.trim() }
            .filter { it.length > 1 }
            .toSet()
    }

    private fun extractKeywords(title: String): Set<String> {
        val clean = title.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
        val stopWords = setOf("the", "a", "an", "and", "or", "feat", "ft", "in", "on", "at", "to", "for", "of", "with")
        return clean.split(Regex("\\s+"))
            .filter { it.length > 2 && it !in stopWords }
            .toSet()
    }
}

package com.jim.arrtv.data

import kotlinx.serialization.Serializable

/** What an interactive search is for. Sonarr can only search one episode or one season at a time. */
sealed interface ReleaseTarget {
    val kind: MediaKind

    data class Movie(val movieId: Int) : ReleaseTarget {
        override val kind get() = MediaKind.MOVIE
    }

    data class Episode(val episodeId: Int) : ReleaseTarget {
        override val kind get() = MediaKind.SHOW
    }

    data class Season(val seriesId: Int, val seasonNumber: Int) : ReleaseTarget {
        override val kind get() = MediaKind.SHOW
    }
}

/**
 * One release an indexer offered, from Radarr/Sonarr's interactive search. [rejections] say why it wouldn't be
 * grabbed automatically. It can still be grabbed by hand unless [downloadAllowed] is false.
 */
@Serializable
data class IndexerRelease(
    val guid: String,
    val indexerId: Int,
    val title: String,
    val size: Double = 0.0,
    val seeders: Int? = null,
    val leechers: Int? = null,
    val protocol: String? = null,
    val rejected: Boolean = false,
    val rejections: List<String> = emptyList(),
    val downloadAllowed: Boolean = true,
) {
    val key get() = "$indexerId-$guid"
}

/** The body for grabbing a release. Radarr/Sonarr find it in their cache from the last search. */
@Serializable
data class ReleaseGrab(val guid: String, val indexerId: Int)

/** Most seeders first. Usenet releases have no seeders, so they go last. */
fun List<IndexerRelease>.bySeeders() = sortedWith(compareByDescending<IndexerRelease> { it.seeders ?: -1 }.thenByDescending { it.size })

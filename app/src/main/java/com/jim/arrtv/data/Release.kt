package com.jim.arrtv.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Where a movie is in its release. Only [STREAMING] movies can actually be downloaded. */
enum class ReleaseStage { STREAMING, IN_CINEMAS, COMING, UNKNOWN }

/**
 * A movie's release, for the badge on its poster and the line on its page. [streaming] is the first digital
 * or physical release anywhere, which is when downloads turn up. Either date can be in the future.
 */
data class Release(val stage: ReleaseStage, val cinemas: Date? = null, val streaming: Date? = null) {
    /** A couple of words for the poster badge. */
    val badge: String
        get() = when (stage) {
            ReleaseStage.STREAMING -> "Streaming"
            ReleaseStage.IN_CINEMAS -> "In cinemas"
            ReleaseStage.COMING -> firstDate?.let { "Coming ${monthYear(it)}" } ?: "Coming soon"
            ReleaseStage.UNKNOWN -> "No date yet"
        }

    /** A sentence for the add and detail pages, with the dates. */
    val description: String
        get() = when (stage) {
            ReleaseStage.STREAMING -> streaming?.let { "On streaming since ${formatDate(it)}" } ?: "On streaming"
            ReleaseStage.IN_CINEMAS -> "In cinemas since ${cinemas?.let(::formatDate)} · " +
                (streaming?.let { "streaming from ${formatDate(it)}" } ?: "no streaming date yet")
            ReleaseStage.COMING -> listOfNotNull(
                cinemas?.let { "In cinemas ${formatDate(it)}" },
                streaming?.let { "streaming ${formatDate(it)}" } ?: "no streaming date yet",
            ).joinToString(" · ").replaceFirstChar { it.uppercase() }
            ReleaseStage.UNKNOWN -> "No release date yet"
        }

    private val firstDate get() = listOfNotNull(cinemas, streaming).minOrNull()

    companion object {
        /** Known to be out on streaming, without the dates (TMDB's "released on digital" filter says so). */
        val Streaming = Release(ReleaseStage.STREAMING)

        /**
         * Works out the stage from the dates. Like Radarr, a movie that's been in cinemas for [ASSUMED_DAYS]
         * with no streaming date counts as streaming: older movies often have no digital date recorded at all.
         */
        fun of(cinemas: Date?, streaming: Date?, now: Date = Date()): Release {
            val stage = when {
                streaming != null && !streaming.after(now) -> ReleaseStage.STREAMING
                cinemas != null && streaming == null && now.time - cinemas.time > ASSUMED_DAYS * DAY_MS -> ReleaseStage.STREAMING
                cinemas != null && !cinemas.after(now) -> ReleaseStage.IN_CINEMAS
                cinemas != null || streaming != null -> ReleaseStage.COMING
                else -> ReleaseStage.UNKNOWN
            }
            return Release(stage, cinemas, streaming)
        }

        private const val ASSUMED_DAYS = 90L
        private const val DAY_MS = 24L * 60 * 60 * 1000

        private fun monthYear(date: Date) = SimpleDateFormat("MMM yyyy", Locale.getDefault()).format(date)
    }
}

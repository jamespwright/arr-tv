package com.jim.arrtv.data

import java.util.Locale

/** How a download is doing, roughly in order of how much it needs attention. */
enum class QueueState {
    PROBLEM,
    DOWNLOADING,
    IMPORTING,
    WAITING,
}

/** One download, which for Sonarr can cover several episodes (a season pack). */
data class QueueItem(
    val kind: MediaKind,
    val key: String,
    /** Every queue record id in this download, so removing it removes all of them. */
    val ids: List<Int>,
    val title: String,
    val detail: String,
    val posterUrl: String?,
    val releaseTitle: String,
    val quality: String?,
    val source: String,
    val state: QueueState,
    val statusLabel: String,
    val progress: Float,
    val sizeLabel: String,
    val timeLeftLabel: String?,
    val messages: List<String>,
)

internal fun List<QueueRecord>.toQueueItems(kind: MediaKind): List<QueueItem> =
    groupBy { it.downloadId ?: "id-${it.id}" }.map { (downloadId, records) -> records.toQueueItem(kind, downloadId) }

private fun List<QueueRecord>.toQueueItem(kind: MediaKind, downloadId: String): QueueItem {
    val first = first()
    val media = first.movie ?: first.series
    val messages = flatMap { r -> r.statusMessages.flatMap { it.messages } + listOfNotNull(r.errorMessage) }
        .filter { it.isNotBlank() }
        .distinct()
    val (state, label) = first.describeStatus()
    return QueueItem(
        kind = kind,
        key = "${kind.name}-$downloadId",
        ids = map { it.id },
        title = media?.title ?: first.title ?: "Unknown",
        detail = when (kind) {
            MediaKind.MOVIE -> media?.year?.takeIf { it > 0 }?.toString() ?: ""
            MediaKind.SHOW -> episodeDetail(mapNotNull { it.episode })
        },
        posterUrl = media?.images?.posterUrl(),
        releaseTitle = first.title ?: "",
        quality = first.quality?.quality?.name,
        source = listOfNotNull(first.downloadClient, first.indexer).joinToString(" · "),
        state = state,
        statusLabel = label,
        progress = if (first.size > 0) (1 - first.sizeleft / first.size).toFloat().coerceIn(0f, 1f) else 0f,
        sizeLabel = sizeLabel(first.size - first.sizeleft, first.size),
        timeLeftLabel = first.timeleft?.takeIf { state == QueueState.DOWNLOADING }?.let(::timeLeftLabel),
        messages = messages,
    )
}

private fun QueueRecord.describeStatus(): Pair<QueueState, String> {
    val warning = trackedDownloadStatus == "warning" || trackedDownloadStatus == "error"
    return when {
        trackedDownloadState == "failed" || trackedDownloadState == "failedPending" || status == "failed" ->
            QueueState.PROBLEM to "Failed"
        trackedDownloadState == "importPending" || trackedDownloadState == "importBlocked" ->
            if (warning) QueueState.PROBLEM to "Can't import" else QueueState.IMPORTING to "Waiting to import"
        trackedDownloadState == "importing" -> QueueState.IMPORTING to "Importing"
        status == "downloadClientUnavailable" -> QueueState.PROBLEM to "Download client unavailable"
        status == "delay" -> QueueState.WAITING to "Delayed"
        status == "paused" -> QueueState.WAITING to "Paused"
        status == "queued" -> QueueState.WAITING to "Queued"
        warning -> QueueState.PROBLEM to "Stuck"
        else -> QueueState.DOWNLOADING to "Downloading"
    }
}

private fun episodeDetail(episodes: List<QueueEpisode>): String {
    if (episodes.isEmpty()) return ""
    if (episodes.size == 1) {
        val e = episodes.single()
        val code = "S%02dE%02d".format(Locale.ROOT, e.seasonNumber, e.episodeNumber)
        return listOfNotNull(code, e.title).joinToString(" · ")
    }
    val seasons = episodes.map { it.seasonNumber }.distinct().sorted()
    val where = if (seasons.size == 1) "Season ${seasons.single()}" else "Seasons ${seasons.joinToString(", ")}"
    return "$where · ${episodes.size} episodes"
}

private fun sizeLabel(done: Double, total: Double): String {
    if (total <= 0) return ""
    val gb = 1024.0 * 1024 * 1024
    return if (total >= gb) {
        "%.1f of %.1f GB".format(Locale.ROOT, done / gb, total / gb)
    } else {
        val mb = 1024.0 * 1024
        "%.0f of %.0f MB".format(Locale.ROOT, done / mb, total / mb)
    }
}

/** Turns Radarr/Sonarr's "d.hh:mm:ss" or "hh:mm:ss" into something like "2h 5m left". */
internal fun timeLeftLabel(timeleft: String): String? {
    val (days, clock) = if ('.' in timeleft.substringBefore(':')) {
        timeleft.substringBefore('.').toIntOrNull() to timeleft.substringAfter('.')
    } else {
        0 to timeleft
    }
    val parts = clock.split(':').map { it.substringBefore('.').toIntOrNull() ?: return null }
    if (days == null || parts.size < 2) return null
    val (hours, minutes) = parts
    return when {
        days > 0 -> "${days}d ${hours}h left"
        hours > 0 -> "${hours}h ${minutes}m left"
        minutes > 0 -> "${minutes}m left"
        else -> "Almost done"
    }
}

package dev.libreamp.player.data.db

enum class SortKey { MANUAL, TITLE, ARTIST, ALBUM, DURATION, DATE_ADDED, LAST_MODIFIED }

enum class GroupKey { NONE, ARTIST, ALBUM, MEDIA_TYPE }

/**
 * Applies the current sort/group choice to a flat list without touching persisted
 * manualOrderIndex values. Drag-reorder is only meaningful (and only allowed by the
 * UI) when group == NONE; grouping always re-buckets/re-sorts and is incompatible
 * with a single flat manual order.
 */
fun List<PlaylistEntryEntity>.sortedAndGrouped(
    sort: SortKey,
    group: GroupKey
): List<PlaylistEntryEntity> {
    val comparator: Comparator<PlaylistEntryEntity> = when (sort) {
        SortKey.MANUAL -> compareBy { it.manualOrderIndex }
        SortKey.TITLE -> compareBy { (it.title ?: it.displayName).lowercase() }
        SortKey.ARTIST -> compareBy { (it.artist ?: "").lowercase() }
        SortKey.ALBUM -> compareBy { (it.album ?: "").lowercase() }
        SortKey.DURATION -> compareBy { it.durationMs }
        SortKey.DATE_ADDED -> compareBy { it.dateAddedMs }
        SortKey.LAST_MODIFIED -> compareBy { it.lastModifiedMs }
    }

    if (group == GroupKey.NONE) {
        return sortedWith(comparator)
    }

    val groupKeyOf: (PlaylistEntryEntity) -> String = when (group) {
        GroupKey.ARTIST -> { e -> e.artist?.takeIf { it.isNotBlank() } ?: "Unknown Artist" }
        GroupKey.ALBUM -> { e -> e.album?.takeIf { it.isNotBlank() } ?: "Unknown Album" }
        GroupKey.MEDIA_TYPE -> { e -> e.mediaType.name }
        GroupKey.NONE -> { _ -> "" }
    }

    return groupBy(groupKeyOf)
        .toSortedMap()
        .values
        .flatMap { it.sortedWith(comparator) }
}

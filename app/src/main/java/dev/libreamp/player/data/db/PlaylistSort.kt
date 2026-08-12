package dev.libreamp.player.data.db

/**
 * Sort and group are **one-off operations**, not view modes: picking one rewrites
 * the stored manual order (see [PlaylistRepository.applySort]) and the list then
 * goes back to being displayed in plain manual order. Hence no MANUAL/NONE members
 * here — "manual" is simply the persisted state, not something you can sort by.
 */
enum class SortKey { TITLE, ARTIST, ALBUM, DURATION, DATE_ADDED, LAST_MODIFIED }

enum class GroupKey { ARTIST, ALBUM, MEDIA_TYPE }

fun List<PlaylistEntryEntity>.orderedBy(sort: SortKey): List<PlaylistEntryEntity> {
    val comparator: Comparator<PlaylistEntryEntity> = when (sort) {
        SortKey.TITLE -> compareBy { (it.title ?: it.displayName).lowercase() }
        SortKey.ARTIST -> compareBy { (it.artist ?: "").lowercase() }
        SortKey.ALBUM -> compareBy { (it.album ?: "").lowercase() }
        SortKey.DURATION -> compareBy { it.durationMs }
        SortKey.DATE_ADDED -> compareBy { it.dateAddedMs }
        SortKey.LAST_MODIFIED -> compareBy { it.lastModifiedMs }
    }
    return sortedWith(comparator)
}

/**
 * Buckets by [group] (buckets ordered alphabetically) while keeping each bucket's
 * entries in the order they already had, so grouping never silently re-sorts within
 * a bucket — apply a sort first if that is what you want.
 */
fun List<PlaylistEntryEntity>.groupedBy(group: GroupKey): List<PlaylistEntryEntity> {
    val groupKeyOf: (PlaylistEntryEntity) -> String = when (group) {
        GroupKey.ARTIST -> { e -> e.artist?.takeIf { it.isNotBlank() } ?: UNKNOWN_ARTIST }
        GroupKey.ALBUM -> { e -> e.album?.takeIf { it.isNotBlank() } ?: UNKNOWN_ALBUM }
        GroupKey.MEDIA_TYPE -> { e -> e.mediaType.name }
    }
    return groupBy(groupKeyOf)
        .toSortedMap(compareBy<String> { it.lowercase() })
        .values
        .flatten()
}

/** Matches free-text [query] against title/artist/album/filename; all terms must hit. */
fun PlaylistEntryEntity.matchesQuery(query: String): Boolean {
    val terms = query.trim().lowercase().split(WHITESPACE).filter { it.isNotEmpty() }
    if (terms.isEmpty()) return true
    val haystack = buildString {
        append(title.orEmpty()).append(' ')
        append(artist.orEmpty()).append(' ')
        append(album.orEmpty()).append(' ')
        append(displayName)
    }.lowercase()
    return terms.all { it in haystack }
}

private val WHITESPACE = Regex("\\s+")
private const val UNKNOWN_ARTIST = "Unknown Artist"
private const val UNKNOWN_ALBUM = "Unknown Album"

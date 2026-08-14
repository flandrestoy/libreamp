package dev.libreamp.player.data.db

import android.net.Uri
import java.util.Locale

/**
 * Sorting is a **one-off command**, not a view mode: it rewrites the stored order of one
 * container and the list then goes back to being displayed in plain manual order. Hence
 * no MANUAL/NONE member here — "manual" is the persisted state, not something to sort by.
 */
enum class SortKey { TITLE, ARTIST, ALBUM, DURATION, DATE_ADDED, LAST_MODIFIED }

/** Textual keys read a name; the rest read a number, and the two sort differently. */
private val SortKey.isTextual: Boolean
    get() = this == SortKey.TITLE || this == SortKey.ARTIST || this == SortKey.ALBUM

/**
 * What an auto-group command buckets by. Each bucket becomes a real, editable group.
 * [FOLDER] reads the file's own location rather than its tags — which is often the only
 * grouping that survives a library with patchy metadata, and matches how the files were
 * picked in the first place.
 */
enum class GroupKey { ARTIST, ALBUM, MEDIA_TYPE, FORMAT, FOLDER }

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
 * Sorts the top level, where groups and loose tracks compete for the same slots. A group
 * sorts on its own label for the textual keys — its name is the thing on screen, and
 * deriving a key from its contents instead would make identically-named groups jump
 * around as tracks move in and out.
 */
@JvmName("orderedItemsBy") // erases to the same signature as the entry overload above
fun List<PlaylistItem>.orderedBy(sort: SortKey): List<PlaylistItem> =
    if (sort.isTextual) sortedBy { it.textKey(sort) } else sortedBy { it.numericKey(sort) }

private fun PlaylistItem.textKey(sort: SortKey): String = when (this) {
    is PlaylistItem.Group -> group.label
    is PlaylistItem.LooseTrack -> when (sort) {
        SortKey.TITLE -> entry.title ?: entry.displayName
        SortKey.ARTIST -> entry.artist.orEmpty()
        SortKey.ALBUM -> entry.album.orEmpty()
        else -> ""
    }
}.lowercase()

/** Aggregates over [PlaylistItem.tracks], so a loose track is just the one-track case. */
private fun PlaylistItem.numericKey(sort: SortKey): Long = when (sort) {
    SortKey.DURATION -> tracks.sumOf { it.durationMs }
    SortKey.DATE_ADDED -> tracks.minOfOrNull { it.dateAddedMs } ?: 0L
    SortKey.LAST_MODIFIED -> tracks.maxOfOrNull { it.lastModifiedMs } ?: 0L
    else -> 0L
}

/**
 * The label a track would carry under [key]. Single source of truth: an auto-group
 * command copies this into the new group's [PlaylistGroupEntity.label] once, and from
 * then on the label is the user's, never recomputed. The old design derived bucket and
 * header text separately and they disagreed — grouping by AUDIO/VIDEO while captioning
 * by file extension.
 */
fun PlaylistEntryEntity.groupLabelFor(key: GroupKey): String = when (key) {
    GroupKey.ARTIST -> artist?.takeIf { it.isNotBlank() } ?: UNKNOWN_ARTIST
    GroupKey.ALBUM -> album?.takeIf { it.isNotBlank() } ?: UNKNOWN_ALBUM
    GroupKey.MEDIA_TYPE -> when (mediaType) {
        MediaType.AUDIO -> "Audio"
        MediaType.VIDEO -> "Video"
    }
    GroupKey.FORMAT -> displayName.substringAfterLast('.', "")
        .takeIf { it.isNotBlank() }?.uppercase(Locale.US) ?: UNKNOWN_FORMAT
    GroupKey.FOLDER -> parentFolderName() ?: UNKNOWN_FOLDER
}

/**
 * The name of the directory the file sits in, dug out of the stored Uri.
 *
 * Both shapes the pickers produce end in a path. A file:// Uri carries one directly; a SAF
 * document Uri carries its document id as the last segment — "primary:Music/Album/track.mp3"
 * — already percent-decoded by [Uri.getLastPathSegment]. The storage-root prefix ahead of
 * the colon is not part of the path, so it goes first; what remains is the path, and the
 * folder is the segment before the filename.
 *
 * Null for a file sitting at the root of its volume, which has no parent to name.
 */
private fun PlaylistEntryEntity.parentFolderName(): String? {
    val uri = Uri.parse(contentUri)
    val path = (if (uri.scheme == "file") uri.path else uri.lastPathSegment) ?: return null
    val segments = path.substringAfterLast(':').split('/').filter { it.isNotBlank() }
    return segments.getOrNull(segments.size - 2)
}

/** Buckets for an auto-group run, in the alphabetical order the new groups will take. */
fun List<PlaylistEntryEntity>.bucketBy(key: GroupKey): Map<String, List<PlaylistEntryEntity>> =
    groupBy { it.groupLabelFor(key) }.toSortedMap(compareBy { it.lowercase() })

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
private const val UNKNOWN_FORMAT = "Unknown Format"
private const val UNKNOWN_FOLDER = "Unknown Folder"

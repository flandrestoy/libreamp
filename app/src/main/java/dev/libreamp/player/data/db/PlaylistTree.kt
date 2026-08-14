package dev.libreamp.player.data.db

/**
 * The playlist is a tree exactly one level deep: a sequence of top-level items, each
 * either a loose track or a group holding tracks. Nesting is not represented here and
 * is refused at every edit site — a second level would double the drag rules and buy
 * very little for a hand-curated list.
 */
sealed class PlaylistItem {

    /** Position among top-level items. Groups and loose tracks share one number line. */
    abstract val orderIndex: Long

    /** This item's tracks in play order; a loose track is a run of one. */
    abstract val tracks: List<PlaylistEntryEntity>

    data class LooseTrack(val entry: PlaylistEntryEntity) : PlaylistItem() {
        override val orderIndex: Long get() = entry.manualOrderIndex
        override val tracks: List<PlaylistEntryEntity> get() = listOf(entry)
    }

    data class Group(
        val group: PlaylistGroupEntity,
        override val tracks: List<PlaylistEntryEntity>
    ) : PlaylistItem() {
        override val orderIndex: Long get() = group.orderIndex
    }
}

/**
 * Ties are possible only in the window between a gap running out and the renumber that
 * follows, but the order still has to be stable while it lasts — groups after tracks,
 * then by id, so the list never flickers between two equally valid arrangements.
 */
private val TOP_LEVEL_ORDER = compareBy<PlaylistItem>(
    { it.orderIndex },
    { if (it is PlaylistItem.Group) 1 else 0 },
    { if (it is PlaylistItem.Group) it.group.id else (it as PlaylistItem.LooseTrack).entry.id }
)

/**
 * Assembles the tree from the two flat tables. [entries] must already be ordered by
 * manualOrderIndex — that makes each group's members come out in order for free.
 */
fun buildTree(
    entries: List<PlaylistEntryEntity>,
    groups: List<PlaylistGroupEntity>
): List<PlaylistItem> {
    val known = groups.mapTo(HashSet()) { it.id }
    val members = HashMap<Long, MutableList<PlaylistEntryEntity>>()
    val loose = ArrayList<PlaylistEntryEntity>()
    for (entry in entries) {
        val groupId = entry.groupId
        // A groupId pointing at a group that no longer exists would hide the track
        // completely; showing it as loose loses a little placement, never the track.
        if (groupId != null && groupId in known) {
            members.getOrPut(groupId) { mutableListOf() } += entry
        } else {
            loose += entry
        }
    }

    val items = ArrayList<PlaylistItem>(groups.size + loose.size)
    loose.mapTo(items) { PlaylistItem.LooseTrack(it) }
    groups.mapTo(items) { PlaylistItem.Group(it, members[it.id].orEmpty()) }
    return items.sortedWith(TOP_LEVEL_ORDER)
}

/**
 * The play queue: every track in reading order, **including** those inside collapsed
 * groups. Collapsing is tidying, not filtering — if it changed what plays next, the
 * list would stop being safe to organise while listening.
 */
fun List<PlaylistItem>.flatten(): List<PlaylistEntryEntity> = flatMap { it.tracks }

/** The group holding [entryId], or null when it sits at the top level. */
fun List<PlaylistItem>.groupContaining(entryId: Long): PlaylistItem.Group? =
    filterIsInstance<PlaylistItem.Group>().firstOrNull { group ->
        group.tracks.any { it.id == entryId }
    }

package dev.libreamp.player.data.db

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * CRUD + ordering + persisted-SAF-permission bookkeeping for the single playlist.
 * Never touches MediaStore; all file identity comes from SAF content:// Uris.
 *
 * Every structural edit funnels through [writeTopLevel], which renumbers the whole
 * arrangement from scratch. That writes more rows than a targeted update would, but a
 * playlist is hundreds of rows and an edit happens once per gesture — cheap enough to
 * buy an invariant worth having: after any operation the stored indices are exactly the
 * arrangement on screen, with no gap-exhaustion path to reason about separately.
 */
class PlaylistRepository(context: Context) {

    private val db = AppDatabase.get(context)
    private val dao = db.playlistEntryDao()
    private val groupDao = db.playlistGroupDao()
    private val resolver: ContentResolver = context.applicationContext.contentResolver

    fun observeTree(): Flow<List<PlaylistItem>> =
        combine(dao.observeAll(), groupDao.observeAll()) { entries, groups ->
            buildTree(entries, groups)
        }

    /** Playback order: the tree flattened, collapsed groups included. */
    fun observeFlattened(): Flow<List<PlaylistEntryEntity>> = observeTree().map { it.flatten() }

    private suspend fun tree(): List<PlaylistItem> = buildTree(dao.getAll(), groupDao.getAll())

    // ---- adding and removing ----

    /** New files append at the top-level tail, loose — never swept into an existing group. */
    suspend fun addFiles(picked: List<PickedFile>): List<Long> {
        var next = dao.maxTopLevelOrderIndex() + MANUAL_ORDER_STEP
        val now = System.currentTimeMillis()
        val entries = picked.map { file ->
            // SAF grants (content://) need persisting; plain filesystem paths (file://) from the
            // in-app picker are already readable via storage permission, with nothing to persist.
            if (file.uri.scheme == "content") {
                resolver.takePersistableUriPermission(
                    file.uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            PlaylistEntryEntity(
                contentUri = file.uri.toString(),
                displayName = file.displayName,
                mediaType = file.mediaType,
                title = file.title,
                artist = file.artist,
                album = file.album,
                durationMs = file.durationMs,
                artPath = file.artPath,
                manualOrderIndex = next.also { next += MANUAL_ORDER_STEP },
                groupId = null,
                dateAddedMs = now,
                lastModifiedMs = file.lastModifiedMs
            )
        }
        return dao.insertAll(entries)
    }

    /**
     * Removing tracks leaves holes in the index sequence, which is harmless — indices only
     * ever need to be *ordered*, not contiguous. A group emptied this way is kept: the user
     * removed tracks, not the group.
     */
    suspend fun delete(entries: List<PlaylistEntryEntity>) {
        dao.deleteAll(entries)
        entries.forEach { entry -> entry.artPath?.let { java.io.File(it).delete() } }
    }

    // ---- arrangement ----

    /**
     * Persists a drag: [items] is the arrangement the list is already showing, so membership
     * and order are both read straight off it. The caller must pass the *whole* top level —
     * dragging is refused while the list is filtered for exactly this reason.
     */
    suspend fun applyArrangement(items: List<PlaylistItem>) = db.withTransaction {
        writeTopLevel(items)
    }

    /**
     * Assigns fresh evenly-spaced indices to [items] and to each group's members, and makes
     * [PlaylistEntryEntity.groupId] agree with where the track actually sits. This *is* the
     * new manual order — membership is written here and nowhere else.
     */
    private suspend fun writeTopLevel(items: List<PlaylistItem>) {
        val entries = mutableListOf<PlaylistEntryEntity>()
        val groups = mutableListOf<PlaylistGroupEntity>()
        items.forEachIndexed { position, item ->
            val index = (position + 1) * MANUAL_ORDER_STEP
            when (item) {
                is PlaylistItem.LooseTrack ->
                    entries += item.entry.copy(manualOrderIndex = index, groupId = null)

                is PlaylistItem.Group -> {
                    groups += item.group.copy(orderIndex = index)
                    item.tracks.forEachIndexed { slot, track ->
                        entries += track.copy(
                            manualOrderIndex = (slot + 1) * MANUAL_ORDER_STEP,
                            groupId = item.group.id
                        )
                    }
                }
            }
        }
        if (entries.isNotEmpty()) dao.updateAll(entries)
        if (groups.isNotEmpty()) groupDao.updateAll(groups)
    }

    /** One-off: reorders one container — the top level when [groupId] is null, else that group. */
    suspend fun applySort(sort: SortKey, groupId: Long?) = db.withTransaction {
        val items = tree()
        writeTopLevel(
            if (groupId == null) items.orderedBy(sort)
            else items.mapInGroup(groupId) { it.orderedBy(sort) }
        )
    }

    suspend fun reverseOrder(groupId: Long?) = db.withTransaction {
        val items = tree()
        writeTopLevel(
            if (groupId == null) items.reversed()
            else items.mapInGroup(groupId) { it.reversed() }
        )
    }

    private fun List<PlaylistItem>.mapInGroup(
        groupId: Long,
        transform: (List<PlaylistEntryEntity>) -> List<PlaylistEntryEntity>
    ): List<PlaylistItem> = map { item ->
        if (item is PlaylistItem.Group && item.group.id == groupId) {
            item.copy(tracks = transform(item.tracks))
        } else item
    }

    // ---- groups ----

    suspend fun renameGroup(groupId: Long, label: String) {
        val group = groupDao.getById(groupId) ?: return
        groupDao.update(group.copy(label = label))
    }

    /** Collapse is view state, but it is the user's view state, so it lives in the database. */
    suspend fun setCollapsed(groupId: Long, collapsed: Boolean) {
        val group = groupDao.getById(groupId) ?: return
        groupDao.update(group.copy(collapsed = collapsed))
    }

    /** Dissolves the group: its tracks stay exactly where they are and become loose. */
    suspend fun dissolveGroup(groupId: Long) = db.withTransaction {
        val rebuilt = tree().flatMap { item ->
            if (item is PlaylistItem.Group && item.group.id == groupId) {
                item.tracks.map { PlaylistItem.LooseTrack(it) }
            } else listOf(item)
        }
        writeTopLevel(rebuilt)
        groupDao.deleteByIds(listOf(groupId))
    }

    /** The other half of the pair: the group *and* everything in it. */
    suspend fun deleteGroupWithTracks(groupId: Long) {
        val members = dao.getByGroup(groupId)
        db.withTransaction {
            dao.deleteAll(members)
            groupDao.deleteByIds(listOf(groupId))
        }
        members.forEach { entry -> entry.artPath?.let { java.io.File(it).delete() } }
    }

    /** Collects [tracks] into one new group named [label], placed where the topmost one sat. */
    suspend fun createGroup(label: String, tracks: List<PlaylistEntryEntity>) = db.withTransaction {
        regroup(listOf(label to tracks.map { it.id }.toSet()))
    }

    /**
     * Buckets [scope] by [key] and turns every bucket into an ordinary group — same object a
     * hand-made group is, freely renameable and editable afterwards. There is no live rule
     * kept behind it: re-running the command is how newly added tracks get swept up, which
     * keeps "the user's edits win" true without a managed-vs-manual distinction.
     */
    suspend fun autoGroup(key: GroupKey, scope: List<PlaylistEntryEntity>) = db.withTransaction {
        regroup(scope.bucketBy(key).map { (label, tracks) -> label to tracks.map { it.id }.toSet() })
    }

    /**
     * Shared spine of [createGroup] and [autoGroup]: pull the named tracks out of wherever
     * they live, build a group per spec, and drop the new groups in at the position of the
     * topmost affected item so the operation reads as local rather than as a re-shuffle.
     *
     * Groups left empty *by this command* are deleted — they are husks nobody asked for.
     * A group the user empties by hand is kept, because there the group is the thing they
     * were working on.
     */
    private suspend fun regroup(specs: List<Pair<String, Set<Long>>>) {
        val claimed = specs.flatMapTo(HashSet()) { it.second }
        if (claimed.isEmpty()) return

        val items = tree()
        val reading = items.flatten()
        val created = specs.mapNotNull { (label, ids) ->
            // Reading order, not selection order: the group's contents should match how the
            // tracks were arranged on screen a moment ago.
            val tracks = reading.filter { it.id in ids }
            if (tracks.isEmpty()) return@mapNotNull null
            val id = groupDao.insert(PlaylistGroupEntity(label = label, orderIndex = 0))
            PlaylistItem.Group(PlaylistGroupEntity(id = id, label = label, orderIndex = 0), tracks)
        }
        if (created.isEmpty()) return

        val emptied = mutableListOf<Long>()
        val rebuilt = mutableListOf<PlaylistItem>()
        var placed = false
        for (item in items) {
            if (!placed && item.tracks.any { it.id in claimed }) {
                rebuilt += created
                placed = true
            }
            when (item) {
                is PlaylistItem.LooseTrack -> if (item.entry.id !in claimed) rebuilt += item
                is PlaylistItem.Group -> {
                    val kept = item.tracks.filterNot { it.id in claimed }
                    if (kept.isNotEmpty()) rebuilt += item.copy(tracks = kept)
                    else emptied += item.group.id
                }
            }
        }
        if (!placed) rebuilt += created

        writeTopLevel(rebuilt)
        if (emptied.isNotEmpty()) groupDao.deleteByIds(emptied)
    }

    /**
     * Cross-references persisted SAF permissions against stored entries at startup;
     * marks entries whose access was revoked (e.g. removed SD card) instead of
     * letting playback crash later.
     */
    suspend fun refreshAccessState() {
        val granted = resolver.persistedUriPermissions.map { it.uri }.toSet()
        val all = dao.getAll()
        val toUpdate = all.mapNotNull { entry ->
            val uri = entry.contentUri.toUri()
            // file:// entries come from the in-app picker and aren't SAF grants, so their
            // access is governed by the file still existing rather than a persisted permission.
            val stillGranted = if (uri.scheme == "content") uri in granted else java.io.File(uri.path ?: "").exists()
            if (entry.accessRevoked == stillGranted) {
                entry.copy(accessRevoked = !stillGranted)
            } else null
        }
        if (toUpdate.isNotEmpty()) dao.updateAll(toUpdate)
    }

    companion object {
        private const val MANUAL_ORDER_STEP = 1000L
    }
}

data class PickedFile(
    val uri: Uri,
    val displayName: String,
    val mediaType: MediaType,
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
    val artPath: String? = null,
    val lastModifiedMs: Long = 0L
)

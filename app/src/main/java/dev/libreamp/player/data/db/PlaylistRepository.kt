package dev.libreamp.player.data.db

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import kotlinx.coroutines.flow.Flow

/**
 * CRUD + ordering + persisted-SAF-permission bookkeeping for the single playlist.
 * Never touches MediaStore; all file identity comes from SAF content:// Uris.
 */
class PlaylistRepository(context: Context) {

    private val dao = AppDatabase.get(context).playlistEntryDao()
    private val resolver: ContentResolver = context.applicationContext.contentResolver

    fun observeAll(): Flow<List<PlaylistEntryEntity>> = dao.observeAll()

    /** New files are appended at the tail of manual order regardless of active sort mode. */
    suspend fun addFiles(picked: List<PickedFile>): List<Long> {
        var next = dao.maxManualOrderIndex() + MANUAL_ORDER_STEP
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
                dateAddedMs = now,
                lastModifiedMs = file.lastModifiedMs
            )
        }
        return dao.insertAll(entries)
    }

    suspend fun delete(entries: List<PlaylistEntryEntity>) {
        dao.deleteAll(entries)
        entries.forEach { entry -> entry.artPath?.let { java.io.File(it).delete() } }
    }

    /**
     * Persists a drag-drop: [moved] is the dragged entry, now sitting between
     * [before] and [after] in the visible list. Uses the average-of-neighbors gap
     * strategy so only this one row is rewritten; when the gap is exhausted (the
     * neighbours are adjacent integers) it falls back to a full renumbering.
     */
    suspend fun applyManualMove(
        moved: PlaylistEntryEntity,
        before: PlaylistEntryEntity?,
        after: PlaylistEntryEntity?
    ) {
        val newIndex = when {
            before == null && after == null -> MANUAL_ORDER_STEP
            before == null -> after!!.manualOrderIndex - MANUAL_ORDER_STEP
            after == null -> before.manualOrderIndex + MANUAL_ORDER_STEP
            else -> (before.manualOrderIndex + after.manualOrderIndex) / 2
        }
        if (before != null && newIndex <= before.manualOrderIndex ||
            after != null && newIndex >= after.manualOrderIndex
        ) {
            // No room left between the neighbours: rebuild the whole order with fresh
            // gaps, placing `moved` where the drag put it.
            val rest = dao.getAll().filter { it.id != moved.id }.toMutableList()
            val insertAt = when {
                before != null -> rest.indexOfFirst { it.id == before.id } + 1
                after != null -> rest.indexOfFirst { it.id == after.id }.coerceAtLeast(0)
                else -> 0
            }
            rest.add(insertAt.coerceIn(0, rest.size), moved)
            rewriteOrder(rest)
            return
        }
        dao.update(moved.copy(manualOrderIndex = newIndex))
    }

    /** Renumbers [ordered] with fresh evenly-spaced indices; this *is* the new manual order. */
    private suspend fun rewriteOrder(ordered: List<PlaylistEntryEntity>) {
        val renumbered = ordered.mapIndexed { i, entry ->
            entry.copy(manualOrderIndex = (i + 1) * MANUAL_ORDER_STEP)
        }
        if (renumbered.isNotEmpty()) dao.updateAll(renumbered)
    }

    /** One-off: reorders the stored playlist by [sort] and returns it to manual order. */
    suspend fun applySort(sort: SortKey) = rewriteOrder(dao.getAll().orderedBy(sort))

    /** One-off: re-buckets the stored playlist by [group], keeping order within a bucket. */
    suspend fun applyGroup(group: GroupKey) = rewriteOrder(dao.getAll().groupedBy(group))

    suspend fun reverseOrder() = rewriteOrder(dao.getAll().reversed())

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

package dev.libreamp.player.ui.playlist

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.libreamp.player.data.db.GroupKey
import dev.libreamp.player.data.db.PlaylistEntryEntity
import dev.libreamp.player.data.db.PlaylistItem
import dev.libreamp.player.data.db.PlaylistRepository
import dev.libreamp.player.data.db.SortKey
import dev.libreamp.player.data.db.flatten
import dev.libreamp.player.data.db.matchesQuery
import dev.libreamp.player.util.MediaProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlaylistViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = PlaylistRepository(application)

    val searchQuery = MutableStateFlow("")

    /**
     * The stored arrangement, with search as the only view-time transform: a filtered
     * group keeps its header so a match never appears to float free of its container,
     * and drops out entirely when nothing inside it matched.
     *
     * Sorting and grouping are absent here on purpose — they are commands that rewrite
     * the stored order, not lenses laid over it.
     */
    val visibleItems: StateFlow<List<PlaylistItem>> =
        combine(repository.observeTree(), searchQuery) { items, query ->
            if (query.isBlank()) items else items.mapNotNull { item ->
                when (item) {
                    is PlaylistItem.LooseTrack ->
                        item.takeIf { it.entry.matchesQuery(query) }

                    is PlaylistItem.Group -> {
                        val hits = item.tracks.filter { it.matchesQuery(query) }
                        // Force the group open: hiding matches behind a collapse would make
                        // the search look like it found nothing.
                        if (hits.isEmpty()) null
                        else item.copy(
                            group = item.group.copy(collapsed = false),
                            tracks = hits
                        )
                    }
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch { repository.refreshAccessState() }
    }

    fun setSearchQuery(query: String) { searchQuery.value = query }

    /** Every track in view order — what "no selection" means for a whole-list command. */
    fun allTracks(): List<PlaylistEntryEntity> = visibleItems.value.flatten()

    fun applySort(key: SortKey, groupId: Long? = null) {
        viewModelScope.launch { repository.applySort(key, groupId) }
    }

    fun reverseOrder(groupId: Long? = null) {
        viewModelScope.launch { repository.reverseOrder(groupId) }
    }

    fun createGroup(label: String, tracks: List<PlaylistEntryEntity>) {
        viewModelScope.launch { repository.createGroup(label, tracks) }
    }

    fun autoGroup(key: GroupKey, scope: List<PlaylistEntryEntity>) {
        viewModelScope.launch { repository.autoGroup(key, scope) }
    }

    fun renameGroup(groupId: Long, label: String) {
        viewModelScope.launch { repository.renameGroup(groupId, label) }
    }

    fun setCollapsed(groupId: Long, collapsed: Boolean) {
        viewModelScope.launch { repository.setCollapsed(groupId, collapsed) }
    }

    fun dissolveGroup(groupId: Long) {
        viewModelScope.launch { repository.dissolveGroup(groupId) }
    }

    fun deleteGroupWithTracks(groupId: Long) {
        viewModelScope.launch { repository.deleteGroupWithTracks(groupId) }
    }

    /** Persists a finished drag: the arrangement read straight off the rows on screen. */
    fun applyArrangement(items: List<PlaylistItem>) {
        viewModelScope.launch { repository.applyArrangement(items) }
    }

    fun addFiles(context: Context, uris: List<Uri>) {
        viewModelScope.launch {
            val picked = withContext(Dispatchers.IO) {
                // One unreadable/malformed file must not discard the rest of the batch —
                // adding hundreds of files at once is a normal thing to do here.
                uris.mapNotNull { uri -> runCatching { MediaProbe.probe(context, uri) }.getOrNull() }
            }
            if (picked.isNotEmpty()) repository.addFiles(picked)
        }
    }

    fun deleteEntries(entries: List<PlaylistEntryEntity>) {
        viewModelScope.launch { repository.delete(entries) }
    }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    PlaylistViewModel(application) as T
            }
    }
}

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
import dev.libreamp.player.data.db.PlaylistRepository
import dev.libreamp.player.data.db.SortKey
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
     * Always the stored manual order (sorting/grouping rewrite that order rather than
     * being applied as a lens here); the only view-time transform left is the search
     * filter.
     */
    val visibleEntries: StateFlow<List<PlaylistEntryEntity>> =
        combine(repository.observeAll(), searchQuery) { entries, query ->
            if (query.isBlank()) entries else entries.filter { it.matchesQuery(query) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch { repository.refreshAccessState() }
    }

    fun setSearchQuery(query: String) { searchQuery.value = query }

    fun applySort(key: SortKey) {
        viewModelScope.launch { repository.applySort(key) }
    }

    fun applyGroup(key: GroupKey) {
        viewModelScope.launch { repository.applyGroup(key) }
    }

    fun reverseOrder() {
        viewModelScope.launch { repository.reverseOrder() }
    }

    fun addFiles(context: Context, uris: List<Uri>) {
        viewModelScope.launch {
            val picked = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri -> MediaProbe.probe(context, uri) }
            }
            if (picked.isNotEmpty()) repository.addFiles(picked)
        }
    }

    fun deleteEntries(entries: List<PlaylistEntryEntity>) {
        viewModelScope.launch { repository.delete(entries) }
    }

    fun applyManualMove(moved: PlaylistEntryEntity, before: PlaylistEntryEntity?, after: PlaylistEntryEntity?) {
        viewModelScope.launch { repository.applyManualMove(moved, before, after) }
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

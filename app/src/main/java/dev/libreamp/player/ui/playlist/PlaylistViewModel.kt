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
import dev.libreamp.player.data.db.sortedAndGrouped
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

    val sort = MutableStateFlow(SortKey.MANUAL)
    val group = MutableStateFlow(GroupKey.NONE)

    val visibleEntries: StateFlow<List<PlaylistEntryEntity>> =
        combine(repository.observeAll(), sort, group) { entries, sortKey, groupKey ->
            entries.sortedAndGrouped(sortKey, groupKey)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch { repository.refreshAccessState() }
    }

    fun setSort(key: SortKey) { sort.value = key }
    fun setGroup(key: GroupKey) { group.value = key }

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

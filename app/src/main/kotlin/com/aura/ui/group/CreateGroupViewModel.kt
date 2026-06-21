package com.aura.ui.group

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aura.db.ContactDao
import com.aura.db.ContactEntity
import com.aura.db.PairState
import com.aura.group.GroupManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Backs the "New group" picker, launched from a 1:1 conversation's "Add people" menu (the peer
 * arrives as [preselect]) — or standalone. Only fully-paired (ACTIVE) contacts can be added, since
 * a member must share a verified ratchet to receive fan-out.
 */
@HiltViewModel
class CreateGroupViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    contactDao: ContactDao,
    private val groupManager: GroupManager
) : ViewModel() {

    /** The peer to pre-select (when launched from a two-person chat); empty otherwise. */
    val preselect: String = savedStateHandle.get<String>("peer").orEmpty()

    val contacts: StateFlow<List<ContactEntity>> =
        contactDao.observeAll()
            .map { list -> list.filter { it.pairState == PairState.ACTIVE } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Create the group and return its id (for navigation). */
    suspend fun create(name: String, memberIds: List<String>): String =
        groupManager.createGroup(name.trim().ifBlank { "New group" }, memberIds)
}

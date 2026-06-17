package com.aura.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aura.db.ContactDao
import com.aura.db.ContactEntity
import com.aura.db.MessageDao
import com.aura.db.MessageEntity
import com.aura.pairing.PairingCoordinator
import com.aura.security.AppLock
import com.aura.server.RendezvousServerController
import com.aura.streak.Streaks
import com.aura.ux.MessagePulse
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A contact plus its streak, unread count, and last message (for the home row). */
data class ContactRow(
    val contact: ContactEntity,
    val streak: Int,
    val unread: Int = 0,
    val lastMessage: MessageEntity? = null
)

/** A message-body search hit, with the matched message and its contact's name. */
data class MessageMatch(
    val message: MessageEntity,
    val contactName: String
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    val serverController: RendezvousServerController,
    contactDao: ContactDao,
    private val messageDao: MessageDao,
    appLockManager: AppLock,
    messagePulse: MessagePulse,
    private val pairingManager: PairingCoordinator
) : ViewModel() {
    /** Pending-pairing actions on the home list. */
    fun acceptPair(nodeIdHex: String) = viewModelScope.launch { pairingManager.acceptIncoming(nodeIdHex) }
    fun rejectPair(nodeIdHex: String) = viewModelScope.launch { pairingManager.rejectIncoming(nodeIdHex, block = false) }
    fun cancelPair(nodeIdHex: String) = viewModelScope.launch { pairingManager.cancelOutgoing(nodeIdHex) }
    /** Emits a sender's node-id when a fresh message lands — drives the contact-box glow. */
    val pulses: SharedFlow<String> = messagePulse.pulses
    // Decoy mode: show an empty contact list so the app looks unused.
    val contacts: StateFlow<List<ContactRow>> =
        combine(
            contactDao.observeAll(),
            appLockManager.decoyActive,
            messageDao.observeUnreadByContact(),
            messageDao.observeLatestPerContact()
        ) { list, decoy, unreadList, latestList ->
            if (decoy) {
                emptyList()
            } else {
                val unread = unreadList.associate { it.contactNodeIdHex to it.count }
                val latest = latestList.associateBy { it.contactNodeIdHex }
                val now = System.currentTimeMillis()
                val since = now - 60L * 24 * 60 * 60 * 1000  // 60-day window is plenty
                list.map { contact ->
                    val ts = messageDao.messageTimestamps(contact.nodeIdHex, since)
                    ContactRow(
                        contact = contact,
                        streak = Streaks.compute(ts, now),
                        unread = unread[contact.nodeIdHex] ?: 0,
                        lastMessage = latest[contact.nodeIdHex]
                    )
                }.sortedByDescending { it.lastMessage?.timestampMs ?: it.contact.createdAtMs }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ── Search ────────────────────────────────────────────────────────────────
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery
    fun setSearchQuery(q: String) { _searchQuery.value = q }

    /** Contacts whose name matches the query (empty while not searching). */
    val filteredContacts: StateFlow<List<ContactRow>> =
        combine(contacts, _searchQuery) { list, q ->
            val query = q.trim()
            if (query.isBlank()) emptyList()
            else list.filter { it.contact.displayName.contains(query, ignoreCase = true) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Text messages whose body matches the query, tagged with the contact name. */
    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    val messageMatches: StateFlow<List<MessageMatch>> =
        _searchQuery
            .debounce(200)
            .flatMapLatest { q ->
                val query = q.trim()
                if (query.length < 2) flowOf(emptyList())
                else flow { emit(messageDao.searchMessages(query)) }
            }
            .combine(contactDao.observeAll()) { msgs, contactList ->
                val nameBy = contactList.associate { it.nodeIdHex to it.displayName }
                msgs.mapNotNull { m -> nameBy[m.contactNodeIdHex]?.let { MessageMatch(m, it) } }
            }
            .combine(appLockManager.decoyActive) { matches, decoy -> if (decoy) emptyList() else matches }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

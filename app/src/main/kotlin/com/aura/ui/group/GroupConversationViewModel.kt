package com.aura.ui.group

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aura.crypto.toHex
import com.aura.db.ContactDao
import com.aura.db.ContactEntity
import com.aura.db.GroupDao
import com.aura.db.GroupEntity
import com.aura.db.GroupMemberEntity
import com.aura.db.MessageDao
import com.aura.db.MessageEntity
import com.aura.db.PairState
import com.aura.group.GroupControl
import com.aura.group.GroupManager
import com.aura.group.GroupMessageSender
import com.aura.identity.IdentityStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives a group conversation and its info/management screen (both keyed by the `groupId` nav arg).
 * Deliberately separate from [com.aura.ui.conversation.ConversationViewModel], which is dense with
 * 1:1-only concerns (pairing/verify, single-peer connection, media/voice/reactions). v1 group chat
 * is text only; media/voice/reactions/calls are intentionally absent here (engine not built for them).
 */
@HiltViewModel
class GroupConversationViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val groupDao: GroupDao,
    private val contactDao: ContactDao,
    private val messageDao: MessageDao,
    private val groupMessageSender: GroupMessageSender,
    private val groupManager: GroupManager,
    private val identityManager: IdentityStore
) : ViewModel() {

    val groupId: String = savedStateHandle.get<String>("groupId").orEmpty()

    private val _myNodeId = MutableStateFlow("")
    val myNodeId: StateFlow<String> = _myNodeId

    init { viewModelScope.launch { _myNodeId.value = identityManager.getOrCreate().nodeId.toHex() } }

    val group: StateFlow<GroupEntity?> = groupDao.observeGroup(groupId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val members: StateFlow<List<GroupMemberEntity>> = groupDao.observeMembers(groupId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val messages: StateFlow<List<MessageEntity>> = messageDao.observeConversation(groupId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** nodeId -> display name (members resolved via contacts; fallback to a short id). */
    val memberNames: StateFlow<Map<String, String>> =
        groupDao.observeMembers(groupId).combine(contactDao.observeAll()) { members, contacts ->
            val byId = contacts.associate { it.nodeIdHex to it.displayName }
            members.associate { m -> m.nodeId to (byId[m.nodeId] ?: shortId(m.nodeId)) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val isAdmin: StateFlow<Boolean> =
        combine(group, myNodeId) { g, me -> g != null && me.isNotEmpty() && g.createdByNodeId == me }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** ACTIVE contacts not already in the group — candidates for "Add people". */
    val addableContacts: StateFlow<List<ContactEntity>> =
        combine(contactDao.observeAll(), members) { contacts, mem ->
            val memberIds = mem.map { it.nodeId }.toSet()
            contacts.filter { it.pairState == PairState.ACTIVE && it.nodeIdHex !in memberIds }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun nameOf(nodeId: String): String = memberNames.value[nodeId] ?: shortId(nodeId)

    fun send(body: String) {
        if (body.isBlank()) return
        viewModelScope.launch { groupMessageSender.sendGroupMessage(groupId, body.trim()) }
    }

    fun markRead() { viewModelScope.launch { messageDao.markConversationRead(groupId) } }

    // ── admin / membership actions (group info screen) ──
    fun rename(name: String) { if (name.isNotBlank()) viewModelScope.launch { groupManager.rename(groupId, name.trim()) } }
    fun addMember(nodeId: String) { viewModelScope.launch { groupManager.addMember(groupId, nodeId) } }
    fun removeMember(nodeId: String) { viewModelScope.launch { groupManager.removeMember(groupId, nodeId) } }
    fun leave() { viewModelScope.launch { groupManager.leaveGroup(groupId) } }

    fun isAdminRole(role: String) = role == GroupControl.ROLE_ADMIN

    private fun shortId(nodeId: String) = if (nodeId.length > 8) nodeId.take(8) + "…" else nodeId
}

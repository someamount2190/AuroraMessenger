package com.aura.share

import android.content.Context
import android.content.Intent
import androidx.core.app.Person
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.aura.MainActivity
import com.aura.R
import com.aura.db.ContactDao
import com.aura.db.ContactEntity
import com.aura.security.AppLock
import dagger.hilt.android.qualifiers.ApplicationContext
import com.aura.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Publishes a direct-share shortcut per contact so they show up as faces in the
 * system share sheet (the way Signal / Messenger / Viber do). The shortcuts are
 * kept in sync with the contact list and are long-lived so the OS may surface
 * them as ranked share targets.
 */
@Singleton
class ShareShortcuts @Inject constructor(
    @ApplicationContext private val context: Context,
    private val contactDao: ContactDao,
    private val appLock: AppLock,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    private val scope = CoroutineScope(ioDispatcher)
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            // Real contact names go to OS-owned dynamic shortcuts (launcher long-press / share
            // sheet), which survive outside the app's locked UI. Publish them only while the app
            // is unlocked with the REAL PIN; clear them while locked or under the decoy PIN so a
            // coercer can't read the contact list off the launcher.
            combine(contactDao.observeAll(), appLock.locked, appLock.decoyActive) { contacts, locked, decoy ->
                if (locked || decoy) emptyList() else contacts
            }.collectLatest { publish(it) }
        }
    }

    private fun publish(contacts: List<ContactEntity>) {
        val max = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context).coerceAtMost(8)
        val shortcuts = contacts.take(max).map { contact ->
            val person = Person.Builder()
                .setName(contact.displayName)
                .setKey(contact.nodeIdHex)
                .build()
            ShortcutInfoCompat.Builder(context, contact.nodeIdHex)
                .setShortLabel(contact.displayName)
                .setLongLabel(contact.displayName)
                .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
                .setPerson(person)
                .setLongLived(true)
                .setCategories(setOf(CATEGORY))
                // Launcher long-press / fallback: open this conversation.
                .setIntent(
                    Intent(context, MainActivity::class.java).apply {
                        action = Intent.ACTION_VIEW
                        putExtra(EXTRA_CONTACT_NODE_ID, contact.nodeIdHex)
                    }
                )
                .build()
        }
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts) }
    }

    companion object {
        const val CATEGORY = "com.aura.directshare.category.CONTACT"
        const val EXTRA_CONTACT_NODE_ID = "contactNodeId"
    }
}

package com.aura.group

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Group membership snapshot framing + the epoch / admin-authority acceptance rule (pure JVM). */
class GroupControlTest {

    private fun snap(
        epoch: Long,
        admin: String = "A",
        members: List<GroupControl.Member> = listOf(
            GroupControl.Member("A", "admin"), GroupControl.Member("B", "member")
        )
    ) = GroupControl.Snapshot("g1", "Trip", epoch, admin, members)

    @Test fun syncRoundTrips() {
        val parsed = GroupControl.parseSync(GroupControl.buildSync(snap(3)))
        assertNotNull(parsed)
        assertEquals("g1", parsed.groupId)
        assertEquals("Trip", parsed.name)
        assertEquals(3L, parsed.epoch)
        assertEquals("A", parsed.admin)
        assertEquals(2, parsed.members.size)
        assertEquals("B", parsed.members[1].nodeId)
        assertEquals("member", parsed.members[1].role)
    }

    @Test fun parseRejectsMalformed() {
        assertNull(GroupControl.parseSync(JSONObject().put("ctl", "nope")))
        assertNull(GroupControl.parseSync(JSONObject().put("ctl", "grp_sync").put("groupId", "g").put("admin", "A").put("epoch", 0L)))
        assertNull(GroupControl.parseSync(JSONObject().put("ctl", "grp_sync").put("groupId", "g").put("admin", "A").put("epoch", 1L))) // no members
    }

    @Test fun acceptSync_newGroupOnlyFromNamedAdmin() {
        assertTrue(GroupControl.acceptSync("A", snap(1, admin = "A"), localAdmin = null, localEpoch = null))
        assertFalse(GroupControl.acceptSync("X", snap(1, admin = "A"), localAdmin = null, localEpoch = null))
    }

    @Test fun acceptSync_existingOnlyAdminAndForward() {
        assertTrue(GroupControl.acceptSync("A", snap(5, admin = "A"), localAdmin = "A", localEpoch = 4))   // newer, from admin
        assertFalse(GroupControl.acceptSync("A", snap(4, admin = "A"), localAdmin = "A", localEpoch = 4))  // not newer
        assertFalse(GroupControl.acceptSync("B", snap(9, admin = "A"), localAdmin = "A", localEpoch = 4))  // not the admin
    }
}

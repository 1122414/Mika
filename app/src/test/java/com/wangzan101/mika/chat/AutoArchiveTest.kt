package com.wangzan101.mika.chat

import com.wangzan101.mika.core.agent.Turn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AutoArchiveTest {
    @get:Rule val tmp = TemporaryFolder()

    private val day = 24L * 60 * 60 * 1000

    private fun summary(id: String, updatedAt: Long, pinnedAt: Long? = null, archivedAt: Long? = null, restoredAt: Long? = null) =
        ConversationSummary(id, id, updatedAt, pinnedAt = pinnedAt, archivedAt = archivedAt, restoredAt = restoredAt)

    /** 只挑没归档、没置顶、最后改动早于分界线的;在跑的和当前在看的留着。 */
    @Test fun candidatesOnlyTakeOldUnpinnedChats() {
        val now = 1_000_000_000_000L
        val cutoff = AutoArchive.cutoff(30, now)
        val all = listOf(
            summary("old", cutoff - 1),
            summary("edge", cutoff),
            summary("fresh", cutoff + 1),
            summary("pinned", cutoff - 5, pinnedAt = 1L),
            summary("archived", cutoff - 5, archivedAt = 2L),
            summary("running", cutoff - 5),
            summary("current", cutoff - 5),
        )
        assertEquals(listOf("old"), AutoArchive.candidates(all, cutoff, setOf("running", "current")))
    }

    /** 手动恢复的对话有 N 天宽限:刚恢复的不收,宽限期过了才可能再收走。 */
    @Test fun candidatesGiveRestoredChatsAGracePeriod() {
        val now = 1_000_000_000_000L
        val cutoff = AutoArchive.cutoff(30, now)
        val all = listOf(
            summary("restored-now", cutoff - 40 * day, restoredAt = now - day),
            summary("restored-long-ago", cutoff - 40 * day, restoredAt = cutoff - 1),
        )
        assertEquals(listOf("restored-long-ago"), AutoArchive.candidates(all, cutoff))
    }

    /** 按设置把老对话真的归档;新对话、置顶的、在跑的和当前在看的都不动。 */
    @Test fun runArchivesOldChatsAndSkipsTheKeptOnes() {
        val dir = tmp.newFolder()
        val store = ConversationStore(dir)
        val now = System.currentTimeMillis()
        listOf("old", "fresh", "running", "pinned", "current").forEach {
            store.append(it, Turn("第 $it 个", "好", "succeeded"))
        }
        store.setPinned("pinned", pinned = true)
        val old = now - 40 * day
        listOf("old", "running", "pinned", "current").forEach { File(dir, "$it.json").setLastModified(old) }

        assertEquals(
            1,
            AutoArchive.run(store, days = 30, running = listOf("running"), currentId = "current", now = now),
        )
        assertEquals(now, store.archivedAt("old"))
        assertNull(store.archivedAt("fresh"))
        assertNull(store.archivedAt("running"))
        assertNull(store.archivedAt("pinned"))
        assertNull(store.archivedAt("current"))
        // 已经归档的不会重复归档
        assertEquals(0, AutoArchive.run(store, days = 30, running = listOf("running"), currentId = "current", now = now + day))
    }

    /** 从归档里手动恢复的老对话,下次自动归档不会马上又收走。 */
    @Test fun runLeavesAJustRestoredChatAlone() {
        val dir = tmp.newFolder()
        val store = ConversationStore(dir)
        val now = System.currentTimeMillis()
        store.append("a", Turn("旧对话", "好", "succeeded"))
        File(dir, "a.json").setLastModified(now - 40 * day)
        store.setArchived("a", true, at = now - 2 * day)
        assertTrue(store.setArchived("a", false, at = now))

        assertEquals(0, AutoArchive.run(store, days = 30, now = now))
        assertNull(store.archivedAt("a"))
    }

    /** 关着(0 天)时一次都不跑。 */
    @Test fun runDoesNothingWhenOff() {
        val dir = tmp.newFolder()
        val store = ConversationStore(dir)
        store.append("old", Turn("很久以前", "好", "succeeded"))
        File(dir, "old.json").setLastModified(System.currentTimeMillis() - 400 * day)

        assertEquals(0, AutoArchive.run(store, days = 0))
        assertNull(store.archivedAt("old"))
        assertNotNull(ConversationStore(dir).list().singleOrNull { it.id == "old" })
    }
}

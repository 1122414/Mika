package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.context.*
import com.wangzan101.mika.core.verify.EvidenceLedger
import com.wangzan101.mika.core.observation.*
import org.junit.Assert.*
import org.junit.Test

class ReliableMemoryTest {
    @Test fun exactSourceVersionCorrectionAndEviction() {
        val ledger = EvidenceLedger(60)
        val m = TaskMemory(ledger::read)
        val a = ledger.add(Observation(ObsKind.Read, "Hotel A: walk 14 min"), 1)!!
        assertFalse(m.record("routeA", 0, a.id, "4 minutes"))
        assertTrue(m.record("routeA", 0, a.id, "walk 14 min"))
        val b = ledger.add(Observation(ObsKind.Read, "Hotel A: walk 12 min"), 2)!!
        assertFalse(m.record("routeA", 0, b.id, "walk 12 min"))
        assertTrue(m.record("routeA", 1, b.id, "walk 12 min"))
        assertEquals(2, m.active().single().version)
        assertTrue(m.steer()); assertTrue(m.active().isEmpty())
        assertTrue(m.record("routeA", 2, b.id, "walk 12 min"))
        ledger.add(Observation(ObsKind.Read, "X".repeat(99)), 3)
        assertNull(ledger.read(b.id))
        assertTrue("walk 12 min" in m.projection().orEmpty())
        assertTrue(m.steer("use a different destination", ledger.lastId))
        assertFalse(m.record("routeA", 3, b.id, "walk 12 min"))
        assertNull(m.projection())
        assertFalse(m.record("unknown", 0, 44, "guess"))
    }

    @Test fun sameNameAndTwoCorrectionsRequireTheNewSelectionSource() {
        val ledger = EvidenceLedger()
        val m = TaskMemory(ledger::read)
        fun seen(label: String) = ledger.add(Observation(ObsKind.Read, label, source = "contacts"), 1)!!
        val a = seen("张三 · 研发一组")
        val b = seen("张三 · 客服二组")
        assertTrue(m.record("recipient", 0, a.id, a.text))
        assertTrue(m.steer("改成客服二组的张三", ledger.lastId))
        assertFalse(m.record("recipient", 1, b.id, b.text))
        val fresh = seen(b.text)
        assertTrue(m.record("recipient", 1, fresh.id, fresh.text))
        assertTrue(m.steer("先不发送，改为只查看研发一组", ledger.lastId))
        assertTrue(m.active().isEmpty())
        val latest = seen(a.text)
        assertTrue(m.record("recipient", 2, latest.id, latest.text))
        assertEquals(listOf(1,2,3), m.state.facts.map { it.version })
        assertEquals("张三 · 研发一组", m.active().single().quote)
        assertEquals(2, m.state.steers.size)
    }

    @Test fun newMetricCannotCompletePreviousDimensions() {
        val plan = CompletionPlan(strict = true).apply(CompletionUpdate(listOf("compare", "book"), MetricUpdate("candidates", 3, 3)))
        assertFalse(plan.complete)
        val next = plan.apply(CompletionUpdate(metric = MetricUpdate("bookings", done = 0)))
        assertNull(next.metric!!.total)
        assertTrue(next.apply(CompletionUpdate(allCriteriaMet = true)).complete)
        assertFalse(next.apply(CompletionUpdate(allCriteriaMet = true)).apply(CompletionUpdate(criteria = listOf("new"))).complete)
    }

    @Test fun uncertainOrFailedWriteDoesNotAuthorizeAnotherDispatch() {
        val store = object : CheckpointStore {
            var current = TaskCheckpoint()
            var fail = false
            override fun load() = current
            override fun save(expected: Int, next: TaskCheckpoint): Boolean {
                if (fail || expected != current.revision) return false
                current = next; return true
            }
        }
        val m = TaskMemory({ null }, store)
        val stale = TaskMemory({ null }, store)
        assertTrue(m.beginEffect("order"))
        assertFalse(stale.beginEffect("order"))
        assertFalse(TaskMemory({ null }, store).beginEffect("order"))
        store.fail = true
        assertFalse(m.returned("order"))
        assertEquals("order", m.state.pendingEffect)
        store.fail = false
        assertTrue(m.returned("order"))
        assertEquals("order", store.load().lastEffect)
    }
}

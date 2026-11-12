package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.*
import com.wangzan101.mika.core.observation.Observation
import com.wangzan101.mika.core.observation.ObsKind
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.tool.FakeScreen
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class RequestPrefixTest {
    private fun wire(tools: String = "tool", system: String = "system", vararg history: String) = InferenceRequest(
        InferenceProtocol.Chat, buildJsonObject {
            putJsonArray("tools") { add(tools) }
            putJsonArray("messages") {
                addJsonObject { put("role", "system"); put("content", system) }
                history.forEach { addJsonObject { put("role", "user"); put("content", it) } }
            }
        }, "test")

    @Test fun breakStartsAtEarliestChangedZoneAndOmitsSameOrAppendedRequests() {
        val p = RequestPrefix()
        fun send(t: String, s: String, vararg ms: String, compact: Boolean = false) = p.observe(wire(t, s, *ms), compact)
        assertNull(send("a","s","1"))
        assertNull(send("a","s","1"))
        assertNull(send("a","s","1","2"))
        val tool = send("b","s","1","2")!!
        val system = send("b","t","1","2")!!
        val history = send("b","t","0","2")!!
        val compressed = send("b","t","new", compact = true)!!
        assertEquals(listOf("tools","system_prompt","history","compaction"), listOf(tool, system, history, compressed).map { it.reason })
        assertTrue(tool.suffixTokens > system.suffixTokens && system.suffixTokens > history.suffixTokens && history.suffixTokens > 0)
        assertEquals(0, send("b","t")!!.suffixTokens)
        assertNull(send("b","t", compact = true))
    }

    @Test fun mainLoopExportsBreaksFromRealAdapterEvenWithoutDetailedTrace() = runTest {
        val model = OpenAiCompatModel("http://localhost", null, "fixture", Capabilities(true, false, 1_000_000, false),
            OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body("""{"choices":[{"finish_reason":"stop","message":{"content":"ok"}}]}""".toResponseBody()).build()
            }.build())
        val events = mutableListOf<RunEvent>()
        val dispatcher = toolDispatcher(FakeScreen(), reliableContext = true)
        val caller = ModelCaller(model, dispatcher, null, SpeedFlags(reliableContext = true), RetryPolicy.NONE, events::add, { 0L })
        val one = Message(Role.User, listOf(Part.Text("one")), Author.User)
        val two = one.copy(parts = listOf(Part.Text("two")))
        caller.ask(listOf(one), dispatcher.definitions(), 1)
        caller.ask(listOf(one, two), dispatcher.definitions(), 2)
        caller.ask(listOf(two), dispatcher.definitions(), 3)
        val event = events.filterIsInstance<RunEvent.PrefixBreak>().single()
        assertEquals(3, event.step); assertEquals("history", event.reason); assertTrue(event.suffixTokens > 0)
        assertEquals(0, event.taskMemoryTokens)
        caller.reset("test")
        caller.ask(listOf(one), dispatcher.definitions(), 1)
        assertEquals(1, events.filterIsInstance<RunEvent.PrefixBreak>().size)
        dispatcher.ctx.beginRun()
        val obs = dispatcher.ctx.evidence.ledger.add(Observation(ObsKind.Read, "walk 8 min"))!!
        assertTrue(dispatcher.ctx.taskMemory!!.record("walk", 0, obs.id, "8 min"))
        caller.ask(listOf(one), dispatcher.definitions(), 2)
        caller.ask(listOf(one, two), dispatcher.definitions(), 3)
        val movedTail = events.filterIsInstance<RunEvent.PrefixBreak>().last()
        assertEquals("history", movedTail.reason)
        assertTrue(movedTail.taskMemoryTokens > 0)
        assertEquals(caller.taskMemoryTokens, movedTail.taskMemoryTokens)
    }
}

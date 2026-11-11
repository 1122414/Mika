package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.context.*
import com.wangzan101.mika.core.model.*
import com.wangzan101.mika.core.observation.*
import com.wangzan101.mika.core.usage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class IncrementalSummaryTest {
    private val rows = (1..40).map { Observation(ObsKind.Read, "record $it", id=it, step=it) }
    private fun answer(text: String) = ModelReply(listOf(Part.Text(text)), StopReason.EndTurn, Usage(100, 2))
    @Test fun incrementalAccountingNoToolsAndMergeAcrossWriters() = runTest {
        val m = TaskMemory({ id -> rows.find { it.id == id } })
        val first = FakeModel(answer("[1]"), answer("[11]"))
        val second = FakeModel(answer("[21]"), answer("[31]"))
        val sink = UsageSink { _,_ -> }
        val source = UsageSource("test","Test","model",UsagePurpose.Light)
        val tally = RunUsageTally()
        val job = IncrementalSummary()
        withContext(tally) {
            listOf(first, first, second, second).forEachIndexed { i, writer ->
                assertTrue(job.advance(m, rows, 15 + i*10, writer.metered(source,sink), "compare"))
                assertFalse(job.advance(m, rows, 15 + i*10, writer, "compare"))
            }
        }
        assertEquals(40, m.state.cursor)
        assertEquals(listOf(3), m.state.blocks.map { it.level })
        assertEquals(listOf(1,11,21,31), m.state.blocks.single().keep)
        assertTrue((first.offeredTools + second.offeredTools).all { it.isEmpty() })
        assertFalse("record 1\"" in first.requests[1].toString())
        assertEquals("summary", tally.event(0).groups.single().purpose)
        assertEquals(408L, tally.totalTokens())
    }

    @Test fun invalidCancelledOverBudgetOrStaleCandidateNeverMovesCursor() = runTest {
        for (writer in listOf(FakeModel(answer("bogus")), FakeModel(answer("[404]")), FakeModel(answer("[1,1]")), FakeModel(ModelError.Network()))) {
            val m = TaskMemory({ null })
            assertFalse(IncrementalSummary().advance(m, rows, 15, writer, "goal"))
            assertEquals(0, m.state.cursor)
        }
        val m = TaskMemory({ null })
        val stale = object : ChatModel by FakeModel() {
            override suspend fun complete(system:String,messages:List<Message>,tools:List<ToolSpec>):ModelReply {
                m.steer(); return answer("[1]")
            }
        }
        assertFalse(IncrementalSummary().advance(m, rows, 15, stale, "goal"))
        try { IncrementalSummary().advance(m, rows, 15, FakeModel(CancellationException()), "goal"); fail() } catch (_: CancellationException) {}
        val budget = RunUsageTally(10)
        budget.add(Usage(10, 0), 1)
        try { withContext(budget) { IncrementalSummary().advance(m, rows, 15, FakeModel(), "goal") }; fail() } catch (_: ModelError.TaskBudgetExceeded) {}
        assertEquals(0, m.state.cursor)
    }

    @Test fun onlyRedundantHistoricalScreensAreReplacedNeverUserImagesOrUniquePixels() {
        val original = (1..14).flatMap { i ->
            val owner = if (i == 3) Author.User else Author.Mika
            listOf(Message(Role.Assistant,listOf(Part.ToolCall("c$i","phone_observe","{}")),Author.Mika),
                Message(Role.Tool,listOf(Part.ToolResult("c$i","{}",false)),Author.Mika),
                Message(Role.User,listOf(Part.Image(if (i == 4) "unique" else "same", screenshotId=i)),owner))
        }
        val view = ReliableScreens.sameImages(original)
        assertEquals(original[2],view[2])
        assertTrue(view[5].parts.single() is Part.Text)
        assertEquals(original[8],view[8]); assertEquals(original[11],view[11])
        assertEquals(original.takeLast(9),view.takeLast(9))
        assertEquals(original.take(15),ReliableScreens.sameImages(original.take(15)))
    }

    @Test fun historicalTreeRestoresByteForByteWhileADiffBaseRemainsVisible() = runTest {
        val archive = ScreenArchive()
        val projection = ArchivedScreens(archive)
        fun screen(i:Int) = buildJsonObject {
            put("snapshotId", "s$i"); put("full", true); put("elements", JsonArray(listOf(JsonPrimitive("8 min"))))
            put("tree", JsonArray(listOf(JsonPrimitive("coordinates".repeat(300)))))
            if (i==10) put("base", "s1")
        }.toString()
        val raw = (1..11).flatMap { i -> listOf(
            Message(Role.Assistant,listOf(Part.ToolCall("c$i","phone_observe","{}")),Author.Mika),
            Message(Role.Tool,listOf(Part.ToolResult("c$i",screen(i),false)),Author.Mika)) }
        val view = projection.project(raw, TaskMemory({ null }))
        assertEquals(raw[1],view[1]); assertEquals(raw.last(),view.last())
        val p = (view[3].parts.single() as Part.ToolResult)
        val obj = Json.parseToJsonElement(p.content).jsonObject
        assertEquals("8 min", obj.getValue("elements").jsonArray.single().jsonPrimitive.content)
        val key = obj.getValue("archiveKey").jsonPrimitive.content
        var offset = 0
        val joined = buildString { do {
            val page = archive.page(key, offset)!!
            append(page.getValue("data").jsonPrimitive.content.removePrefix("<untrusted>\n").removeSuffix("\n</untrusted>"))
            offset = page["nextOffset"]?.jsonPrimitive?.int ?: -1
        } while(offset>=0) }
        assertEquals(screen(2), joined)
        val tools = com.wangzan101.mika.core.assembly.toolDispatcher(com.wangzan101.mika.core.tool.FakeScreen(), reliableContext = true)
        val model = FakeModel(answer("ok"), answer("ok"))
        val caller = ModelCaller(model, tools, null, SpeedFlags(reliableContext = true), RetryPolicy.NONE, {}, { 0L })
        caller.ask(raw, tools.definitions(), 12)
        assertTrue("archiveKey" in model.requests.last()[3].parts.filterIsInstance<Part.ToolResult>().single().content)
        caller.ask(raw, emptyList(), 13)
        assertEquals(screen(2), model.requests.last()[3].parts.filterIsInstance<Part.ToolResult>().single().content)
        assertNull(ScreenArchive().page(key,0))
        assertNull(archive.page(key,Int.MAX_VALUE))
        assertNull(archive.put("""{"secret":true,"text":"${"x".repeat(500)}"}"""))
    }
}

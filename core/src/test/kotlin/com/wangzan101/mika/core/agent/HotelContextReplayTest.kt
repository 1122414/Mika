package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.tool.FakeScreen
import kotlinx.coroutines.test.runTest
import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.testing.TOOLS
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 离线对照：只测固定轨迹的输入和可见证据。没有调用模型、手机或下单接口。 */
class HotelContextReplayTest {
    private val goal = Message(Role.User, listOf(Part.Text(
        "11月20日入住。比较杭州西湖附近好评优先的前三家非民宿酒店。用高德查到断桥的步行时间，选最近一家，报告地点、价格和步行时间。"
    )), Author.User)
    private val evidence = mutableSetOf<String>()
    private val history = mutableListOf(goal)
    private val listing = "携程"
    private val maps = "高德"

    private fun screen(app: String, vararg rows: String, keep: Boolean = false) {
        val id = "call-${history.size}"
        if (keep) evidence += id // 测试人工标注来源；不是产品已能自动识别的能力。
        val body = buildJsonObject {
            put("ok", true)
            putJsonObject("screen") {
                put("currentApp", app); put("full", true); put("snapshotId", "s${history.size}")
                putJsonArray("elements") {
                    rows.forEachIndexed { i, label ->
                        addJsonObject { put("ref", "@e${i+1}"); put("name", label); put("role", "text") }
                    }
                }
            }
        }
        history += Message(Role.Assistant, listOf(Part.ToolCall(id, "phone_tap", """{"ref":"@e1"}""")), Author.Mika)
        history += Message(Role.Tool, listOf(Part.ToolResult(id, body.toString(), false)), Author.Mika)
    }

    private fun fixture() {
        fun nav(app: String, vararg pages: String) = pages.forEach { screen(app, it, "搜索", "返回", "帮助中心", "更多选项") }
        nav(listing, "目的地", "杭州", "西湖", "入住日期", "排序")
        screen(listing, "杭州西湖附近", "入住：2026-11-20", "排序：好评优先",
            "民宿|样例小院|测试路1号|¥210", "酒店|样例甲店|测试路2号|¥380",
            "酒店|样例乙店|测试路3号|¥510", "酒店|样例丙店|测试路4号|¥420",
            "酒店|样例丁店|测试路5号|¥290", keep = true)
        for ((hotel, mins) in listOf("样例甲店" to 15, "样例乙店" to 8, "样例丙店" to 12)) {
            nav(maps, "搜索地点", "路线规划")
            screen(maps, "从：$hotel", "到：断桥", "驾车：3分钟", "步行：${mins}分钟", keep = true)
        }
        nav(listing, "返回酒店列表", "选择入住日期")
    }

    private data class Choice(val name: String, val address: String, val price: String, val minutes: Int)

    /** 只用该视图还有的同屏材料作答。缺排序、候选信息或任一路线就不能作选择。 */
    private fun choose(view: List<Message>): Choice? {
        val screens = view.flatMap { it.parts }.filterIsInstance<Part.ToolResult>().mapNotNull {
            val s = Json.parseToJsonElement(it.content).jsonObject["screen"] as? JsonObject ?: return@mapNotNull null
            s.getValue("currentApp").jsonPrimitive.content to (ReliableScreens.elements(s) ?: return@mapNotNull null).map { e ->
                e.jsonObject.getValue("name").jsonPrimitive.content
            }
        }
        val rows = screens.singleOrNull { (app, labels) -> app == listing &&
            labels.containsAll(listOf("杭州西湖附近", "入住：2026-11-20", "排序：好评优先")) }?.second ?: return null
        val candidates = rows.filter { it.startsWith("酒店|") }.take(3)
        if (candidates.size != 3) return null
        return candidates.map { row ->
            val (_, name, address, price) = row.split('|')
            val route = screens.singleOrNull { (app, labels) ->
                app == maps && "从：$name" in labels && "到：断桥" in labels
            }?.second ?: return null
            val walk = route.singleOrNull { it.startsWith("步行：") } ?: return null
            val minutes = walk.removePrefix("步行：").removeSuffix("分钟").toIntOrNull() ?: return null
            Choice(name, address, price, minutes)
        }.minBy { it.minutes }
    }

    @Test fun compareViewsBeforeBooking() {
        fixture()
        val expected = Choice("样例乙店", "测试路3号", "¥510", 8)
        val totals = IntArray(3)
        val tools = TOOLS
        for (end in 1..history.size step 2) { // 开局，以及每个完整往返之后的下一次请求
            val a = history.take(end)
            val b = HistoryPruner.prune(a, tools, HistoryPruner.Cut(5))
            val original = a.flatMap { it.parts }.filterIsInstance<Part.ToolResult>().associateBy { it.callId }
            val c = b.map { m -> m.copy(parts = m.parts.map { p ->
                if (p is Part.ToolResult && p.callId in evidence) original.getValue(p.callId) else p
            }) }
            listOf(a, b, c).forEachIndexed { index, view ->
                totals[index] += TokenEstimate.of(view)
                assertEquals(goal, view.first())
                assertEquals(a.flatMap { it.parts }.filterIsInstance<Part.ToolCall>().map { it.id },
                    view.flatMap { it.parts }.filterIsInstance<Part.ToolResult>().map { it.callId })
            }
            assertEquals(choose(a), choose(c))
            if (end == history.size) {
                assertEquals(expected, choose(a))
                assertEquals(expected, choose(c))
                println("hotel replay: cumulative estimated message tokens (full/window5/protected)=${totals.toList()}; fixed-window choice=${choose(b)}")
            }
        }
        assertTrue("Protected evidence should cost less than sending full history: ${totals.toList()}", totals[2] < totals[0])
    }

    @Test fun productProjectionRetainsEveryCandidateWithoutManualPins() {
        fixture()
        val source = history.toList()
        repeat(83) { screen(maps, "线路图", "返回", "换乘", "步行", "详细信息", "切换城市") }
        val baseline = ContextCompressor(TOOLS)
        val candidate = ContextCompressor(TOOLS, reliable = true)
        var a = 0L
        var b = 0L
        var prev = emptyList<Message>()
        val rewrites = mutableListOf<Int>()
        for (end in 1..history.size step 2) {
            val original = history.take(end)
            val before = baseline.compress(original, 1_000_000).messages
            val after = candidate.compress(original, 1_000_000).messages
            a += TokenEstimate.of(before); b += TokenEstimate.of(after)
            if ((end - 1) / 2 < 10) assertEquals(before, after)
            if (end >= source.size) assertEquals(Choice("样例乙店", "测试路3号", "¥510", 8), choose(after))
            val results = after.flatMap { it.parts }.filterIsInstance<Part.ToolResult>()
            val raw = original.flatMap { it.parts }.filterIsInstance<Part.ToolResult>()
            assertEquals(raw.map { it.callId }, results.map { it.callId })
            results.zip(raw).forEach { (v, r) ->
                fun elements(p: Part.ToolResult) = ReliableScreens.elements(Json.parseToJsonElement(p.content).jsonObject.getValue("screen").jsonObject)
                assertEquals(elements(r), elements(v))
            }
            if ((end - 1) / 2 in listOf(30, 100)) {
                println("product replay rounds=${(end-1)/2} A=$a B=$b")
                assertEquals(a, b)
            }
            if (after.take(prev.size) != prev) rewrites += (end - 1) / 2
            prev = after
        }
        assertEquals(emptyList<Int>(), rewrites)
        assertEquals(source, history.take(source.size))
    }

    /**
     * The 33 tool actions and the two tool errors follow the verified 34-request run. The export
     * has no bodies: page contents, trees and arguments below are a fixed, synthetic fixture.
     */
    @Test fun replayOrderPageAndPriceOnTheSame34Requests() = runTest { replay(34, 0.66255136 * 1.01) }
    @Test fun replayPastTheFortiethRound() = runTest { replay(45, 0.93615964 * 1.01) }

    private suspend fun replay(length: Int, ceiling: Double) {
        fixture()
        val evidencePages = history.flatMap { it.parts }.filterIsInstance<Part.ToolResult>()
            .filter { it.callId in evidence }.map { Json.parseToJsonElement(it.content).jsonObject.getValue("screen").jsonObject }
        val pages = listOf(12, 18, 23, 29).zip(evidencePages).toMap()
        val actions = (1 until length).associateWith { step -> when (step) {
            1 -> "phone_list_apps"; 2, 13, 30 -> "phone_launch_app"; 3 -> "task_progress"; 7 -> "do_steps"
            15, 20, 24, 28 -> "phone_type_text"; 19, 23, 27 -> "phone_clear_text"; else -> "phone_tap"
        } }
        val expected = Choice("样例乙店", "测试路3号", "¥510", 8)
        val raw = mutableListOf(goal)
        val tools = toolDispatcher(FakeScreen(), reliableContext = true)
        val model = FakeModel(ModelReply(listOf(Part.Text("fixture")), StopReason.EndTurn, null),
            repeatLast = true, contextTokens = 1_000_000)
        val caller = ModelCaller(model, tools, null, SpeedFlags(reliableContext = true, semanticSummaries = false),
            RetryPolicy.NONE, {}, { 0L })
        val wire = HotelReplayMeter()
        // Compare the representation of previously sent results on the product path.
        var previous = emptyMap<String, Pair<Boolean, Boolean>>()
        val changes = mutableMapOf<String, MutableList<Int>>()
        var selected: Choice? = null
        for (step in 1..length) {
            val specs = tools.definitions()
            caller.ask(raw, specs, step)
            val view = model.requests.last()
            wire.observe(step, view, specs)
            val shapes = view.flatMap { it.parts }.filterIsInstance<Part.ToolResult>().associate {
                it.callId to (("\"elementTables\"" in it.content) to ("\"archiveKey\"" in it.content))
            }
            if (previous.any { (id, old) -> shapes[id]?.first != old.first }) changes.getOrPut("tables") { mutableListOf() }.add(step)
            if (previous.any { (id, old) -> shapes[id]?.second != old.second }) changes.getOrPut("archive") { mutableListOf() }.add(step)
            previous = shapes
            if (step == length - 2) selected = choose(view)
            if (step == length) {
                val page = Json.parseToJsonElement((view.last().parts.single() as Part.ToolResult).content)
                    .jsonObject.getValue("screen").jsonObject
                assertEquals("携程", page.getValue("currentApp").jsonPrimitive.content)
                assertEquals("订单填写：${expected.name}；2026-11-20", page.getValue("title").jsonPrimitive.content)
                assertEquals(expected, choose(view))
                break
            }
            val id = "c$step"
            val isError = step == 3 || step == 7
            val response = when {
                isError -> """{"ok":false,"error":"unavailable"}"""
                step == 1 -> """{"apps":["携程","高德"]}"""
                else -> {
                    val source = pages[step]
                    val page = buildJsonObject {
                        put("currentApp", if (step in 13..29) maps else listing)
                        put("title", if (step == length - 1) "订单填写：${requireNotNull(selected).name}；2026-11-20" else "页面 $step")
                        put("full", true); put("snapshotId", "s$step")
                        putJsonArray("elements") {
                            source?.get("elements")?.jsonArray?.forEach(::add)
                            repeat(90) { n -> addJsonObject {
                                put("ref", "@n$n"); put("role", "text"); put("name", "导航 $step/$n"); put("clickable", false)
                            } }
                        }
                        put("tree", "screen $step: " + "layout bounds and accessibility node; ".repeat(550))
                    }
                    buildJsonObject { put("ok", true); put("screen", page) }.toString()
                }
            }
            raw += Message(Role.Assistant, listOf(Part.ToolCall(id, actions.getValue(step), "{}")), Author.Mika)
            raw += Message(Role.Tool, listOf(Part.ToolResult(id, response, isError)), Author.Mika)
        }
        assertEquals(expected, selected)
        println("$length-request hotel replay: $wire representationChanges=$changes")
        assertEquals(emptyList<Int>(), wire.rewrites)
        assertTrue(changes["tables"].isNullOrEmpty())
        assertTrue(changes["archive"].isNullOrEmpty())
        assertTrue("append-only replay exceeds $ceiling: $wire", wire.estimatedCost <= ceiling)
    }

    @Test fun cannotConfirmWinnerWithOneCompetitorsWalkMissing() {
        fixture()
        // 保留三家的起终点和驾车时间，只去掉非赢家甲店的步行时间；不能猜另外两家中最快的就是赢家。
        val incomplete = history.map { m -> m.copy(parts = m.parts.map { p ->
            if (p is Part.ToolResult) p.copy(content = p.content.replace("步行：15分钟", "步行：未查到")) else p
        }) }
        assertNull(choose(incomplete))
    }
}

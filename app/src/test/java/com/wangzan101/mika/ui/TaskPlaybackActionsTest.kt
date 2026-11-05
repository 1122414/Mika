package com.wangzan101.mika.ui

import com.wangzan101.mika.core.record.PlaybackMark
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.i18n.ChineseTexts
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import com.wangzan101.mika.record.RunStore

class TaskPlaybackActionsTest {
    @get:Rule val texts = ChineseTexts()

    @get:Rule val tmp = TemporaryFolder()
    private val store by lazy { RunStore(tmp.root, { null }, 30, { 1_760_000_000_000L }) { it } }

    private fun report(events: List<RunEvent>, images: Map<Int, String>): TaskReport {
        val h = store.open("查询帖子")
        images.forEach { (id, content) -> h.media(id, content.toByteArray()) }
        events.forEach(h::event)
        h.event(RunEvent.TaskEnded(99, "succeeded", "推荐这篇帖子：城市散步地图与路线推荐"))
        h.close()
        return TaskReportViewModel(store, h.runId).load()!!
    }

    @Test fun openingPostThenImmediatelyReturningPreservesDetailAndEvidenceNavigation() {
        val r = report(listOf(
            RunEvent.TaskPlayback(1, 10, "phone_tap", "open", 22, 23, PlaybackMark("tap", 253, 765)),
            RunEvent.Observed(2, 10, "城市散步地图与路线推荐", id = 9),
            RunEvent.Activity(3, 10, "act", "phone_tap", obsIds = listOf(9), callId = "open"),
            RunEvent.TaskPlayback(4, 11, "phone_back", "back", 25, 26),
        ), mapOf(22 to "list", 23 to "post detail", 25 to "post detail", 26 to "list"))
        // 两个列表之间必须能停在帖子详情，不能只断言有点击标记。
        assertEquals(listOf(22, 23, 25, 26), r.frames.map { it.frame.screenshotId })
        assertEquals("tap", r.frames.first().mark?.kind)
        assertTrue(r.frames.drop(1).all { it.mark == null })
        assertEquals(listOf(false, true, false, true), r.frames.map { it.resultOnly })
        assertEquals(0, r.missingFrames)
        val page = r.pages.single()
        assertEquals(23, page.screenshotId)
        assertEquals(1, TaskReportViewModel.framePosition(r.frames, page.screenshotId))
        val stage = r.stages.single { it.screenshotId == page.screenshotId }
        assertEquals(1, TaskReportViewModel.framePosition(r.frames, stage.screenshotId, stage.stepIndex))
    }

    @Test fun missingOriginalOrResultNeverTransfersCoordinatesOrCreatesBlankFrames() {
        val r = report(listOf(
            RunEvent.TaskPlayback(1, 1, "phone_tap", "tap", 1, 2, PlaybackMark("tap", 100, 200)),
            RunEvent.TaskPlayback(2, 1, "phone_swipe", "swipe", 3, 4, PlaybackMark("swipe", 500, 800, 500, 200)),
        ), mapOf(2 to "tap result", 3 to "swipe source"))
        assertEquals(listOf(2, 3), r.frames.map { it.frame.screenshotId })
        assertNull(r.frames.first().mark)
        assertEquals("swipe", r.frames.last().mark?.kind)
        assertEquals(2, r.missingFrames)
        assertEquals(1, TaskReportViewModel.framePosition(r.frames, 4, stepIndex = 1))
    }

    @Test fun sameRoundGesturesKeepEveryResultAndItsOwnMarker() {
        val r = report(listOf(
            RunEvent.TaskPlayback(1, 1, "phone_tap", "a", 1, 2, PlaybackMark("tap", 200, 300)),
            RunEvent.TaskPlayback(2, 1, "phone_swipe", "b", 3, 4, PlaybackMark("swipe", 500, 800, 500, 200)),
        ), (1..4).associateWith { "frame $it" })
        assertEquals(listOf(1, 2, 3, 4), r.frames.map { it.frame.screenshotId })
        assertEquals(listOf("tap", null, "swipe", null), r.frames.map { it.mark?.kind })
        assertTrue(TaskReportViewModel.frameDuration(r.frames[1]) >= 2_400L)
        assertTrue(TaskReportViewModel.frameDuration(r.frames[0]) < TaskReportViewModel.frameDuration(r.frames[1]))
    }

    @Test fun legacyScreensStayInOrderWithoutInventedResultsOrMarkers() {
        val r = report(listOf(RunEvent.Media(1, 7, 2, null), RunEvent.Media(2, 8, 2, null)),
            mapOf(7 to "legacy first", 8 to "legacy second"))
        assertEquals(listOf(7, 8), r.frames.map { it.frame.screenshotId })
        assertTrue(r.frames.all { it.mark == null && !it.resultOnly })
        assertEquals(0, TaskReportViewModel.framePosition(r.frames, null))
        assertEquals(0, TaskReportViewModel.framePosition(emptyList(), 7))
    }

    @Test fun errorsMatchExactStepToolAndCallRatherThanAdjacentBatchAction() {
        fun result(step: Int, id: String, code: String) = RunEvent.ToolResult(step.toLong(), step, "phone_type_text",
            """{"ok":false,"error":"$code"}""", false, null, callId = id)
        val events = listOf(
            result(1, "same", "ref_expired"),
            result(2, "neighbor", "operation_failed"),
            result(2, "same", "action_not_applied"),
            RunEvent.TaskPlayback(3, 2, "phone_type_text", "same", 1, 2, succeeded = false),
            RunEvent.TaskPlayback(4, 2, "phone_type_text", "missing", 3, 4, succeeded = false),
            RunEvent.TaskPlayback(5, 2, "phone_type_text", "same", 5, 6, succeeded = true),
        )
        val steps = TaskReportViewModel.playbackSteps(events)
        assertEquals("出错:字没输进去", steps[0].failure)
        assertEquals("出错：未记录具体原因。", steps[1].failure)
        assertNull(steps[2].failure)
    }

    @Test fun errorsUseKnownTranslationThenMessageThenCodeAndTolerateTruncation() {
        assertEquals("出错:字没输进去", RunTimelineViewModel.failureLabel("""{"ok":false,"error":"action_not_applied","message":"truncated"""))
        assertEquals("出错:连接已经断开", RunTimelineViewModel.failureLabel("""{"ok":false,"error":"custom_failure","message":"连接已经断开"}"""))
        assertEquals("出错:custom_failure", RunTimelineViewModel.failureLabel("""{"ok":false,"error":"custom_failure"}"""))
        assertNull(RunTimelineViewModel.failureLabel("""{"ok":true,"nested":{"ok":false,"error":"custom_failure"}}"""))
        assertNull(RunTimelineViewModel.failureLabel("broken record"))
    }
}

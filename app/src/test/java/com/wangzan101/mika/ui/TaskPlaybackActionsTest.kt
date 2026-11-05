package com.wangzan101.mika.ui

import com.wangzan101.mika.core.record.PlaybackMark
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.i18n.ChineseTexts
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TaskPlaybackActionsTest {
    @get:Rule val texts = ChineseTexts()

    private fun frames(steps: List<PlaybackStep>) = steps.flatMapIndexed { i, s ->
        s.frames.map { ReplayFrame(i, it, s.mark.takeIf { _ -> it.after == false }) }
    }

    @Test fun eachGestureKeepsItsOwnAnnotatedFrameAndFinalResultIsDistinct() {
        val steps = listOf(
            PlaybackStep(1, "phone_tap", 10, 11, PlaybackMark("tap", 200, 300)),
            PlaybackStep(2, "phone_swipe", 12, 13, PlaybackMark("swipe", 500, 800, 500, 200)),
        )
        val selected = TaskReportViewModel.actionFrames(steps, frames(steps))
        assertEquals(listOf(10, 12, 13), selected.map { it.frame.screenshotId })
        assertEquals(listOf("tap", "swipe"), selected.filterNot { it.resultOnly }.map { it.mark?.kind })
        assertTrue(selected.last().resultOnly)
        assertNull(selected.last().mark)
    }

    @Test fun missingOriginalFrameNeverTransfersCoordinatesToResultAndLegacyStaysHonest() {
        val step = PlaybackStep(1, "phone_tap", 1, 2, PlaybackMark("tap", 100, 200))
        val chosen = TaskReportViewModel.actionFrames(listOf(step), frames(listOf(step)).filter { it.frame.after == true })
        assertEquals(1, chosen.size); assertNull(chosen.single().mark)
        val legacy = PlaybackStep(2, null, 7, null, legacy = true)
        val old = TaskReportViewModel.actionFrames(listOf(legacy), frames(listOf(legacy)))
        assertEquals(1, old.size); assertNull(old.single().mark); assertFalse(old.single().resultOnly)
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

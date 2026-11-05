package com.wangzan101.mika.ui

import com.wangzan101.mika.core.record.PlaybackMark
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.record.RunStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TaskReportViewModelTest {
    @get:Rule val texts = ChineseTexts()
    @get:Rule val tmp = TemporaryFolder()
    private val store by lazy { RunStore(tmp.root, { null }, 30, { 1_760_000_000_000L }) { it } }

    @Test fun reportPreservesFullMarkdownAndOutcome() {
        val answer = "## 结果\n" + "完整回答".repeat(200) + "\n| 车次 | 票价 |\n| --- | --- |\n| G6042 | 302 |"
        val h = store.open("查询车票")
        h.event(RunEvent.TaskStarted(1, "查询车票", "m"))
        h.event(RunEvent.TaskEnded(2001, "succeeded", answer))
        h.event(RunEvent.OutcomeJudged(2002, "partial", "部分车次未查到"))
        h.close()
        val report = TaskReportViewModel(store, h.runId).load()!!
        assertEquals(answer, report.answer)
        assertEquals("只办成了一部分", report.header.outcome)
        assertTrue(report.playback.isEmpty())
        assertEquals(241, TaskReportViewModel.excerpt(answer).length)
        assertEquals("简短回答", TaskReportViewModel.excerpt("简短回答"))
    }

    @Test fun preservesBeforeAfterAndBatchActionsInTheSameStep() {
        val events = listOf(
            RunEvent.TaskPlayback(1, 4, "phone_tap", "a", 10, 11, PlaybackMark("tap", 900, 50)),
            RunEvent.TaskPlayback(2, 4, "phone_swipe", "b", 12, null, PlaybackMark("swipe", 500, 800, 500, 300), source = "batch"),
            RunEvent.Media(3, 11, 4, null),
        )
        val steps = TaskReportViewModel.playbackSteps(events)
        assertEquals(2, steps.size)
        assertEquals(listOf(PlaybackFrame(10, false), PlaybackFrame(11, true)), steps[0].frames)
        assertEquals(PlaybackFrame(null, true), steps[1].frames.last())
        assertEquals("swipe", steps[1].mark!!.kind)
    }

    @Test fun legacyRecordsNeverInventMarkersOrDuplicateBeforeAfter() {
        val steps = TaskReportViewModel.playbackSteps(listOf(
            RunEvent.Media(1, 8, 2, "com.x"), RunEvent.Media(2, 8, 2, "com.x"), RunEvent.Media(3, 9, 2, "com.x"),
        ))
        assertEquals(2, steps.size)
        assertTrue(steps.all { it.legacy && it.mark == null && it.frames.size == 1 && it.frames.single().after == null })
    }

    @Test fun invalidOrFailedMarkersAreHiddenAndMissingRecordsAreExplicit() {
        val steps = TaskReportViewModel.playbackSteps(listOf(
            RunEvent.TaskPlayback(1, 1, "phone_tap", "a", 10, 11, PlaybackMark("tap", -1, 50)),
            RunEvent.TaskPlayback(2, 2, "phone_tap", "b", 12, 13, PlaybackMark("tap", 500, 50), succeeded = false),
            RunEvent.TaskPlayback(3, 3, "phone_tap", "c", null, 14, PlaybackMark("tap", 500, 50)),
        ))
        assertTrue(steps.all { it.mark == null })
        assertNull(TaskReportViewModel(store, "../../other").load())
        val h = store.open("失败测试")
        h.event(RunEvent.TaskEnded(2, "failed", "请求受限")); h.close()
        val r = TaskReportViewModel(store, h.runId).load()!!
        assertEquals("请求受限", r.answer)
        assertNotEquals("办成了", r.header.outcome)
        assertNull(TaskReportViewModel(store, h.runId).screen(12))
    }
}

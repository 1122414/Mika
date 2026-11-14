package com.wangzan101.mika.ui

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
        assertEquals(241, TaskReportViewModel.excerpt(answer).length)
        assertEquals("简短回答", TaskReportViewModel.excerpt("简短回答"))
    }
}

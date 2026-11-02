package com.wangzan101.mika.ui

import com.wangzan101.mika.core.cap.web.AttachedPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 长按哪一条消息能分支:只有已答完的回答,且这个对话没有任务在跑。 */
class ChatBranchTest {
    private fun exchange(status: ExchangeStatus) = Exchange("问", emptyList(), status, live = false)

    @Test fun aFinishedAnswerCanBranch() {
        val exchanges = listOf(exchange(ExchangeStatus.Done("答", null)), exchange(ExchangeStatus.Working))
        assertEquals(0, branchableTurn(exchanges, "r0", active = false))
    }

    @Test fun failedAndCancelledTurnsCanBranch() {
        assertEquals(0, branchableTurn(listOf(exchange(ExchangeStatus.Failed("出错"))), "r0", active = false))
        assertEquals(0, branchableTurn(listOf(exchange(ExchangeStatus.Cancelled())), "r0", active = false))
    }

    @Test fun userMessagesSteersAndStatusCardsCannotBranch() {
        val exchanges = listOf(exchange(ExchangeStatus.Done("答", null)))
        assertNull(branchableTurn(exchanges, "u0", active = false))
        assertNull(branchableTurn(exchanges, "m0-steer", active = false))
        assertNull(branchableTurn(exchanges, "selectable-answer", active = false))
        assertNull(branchableTurn(exchanges, "r", active = false))
        assertNull(branchableTurn(exchanges, "r9", active = false))
    }

    @Test fun unfinishedTurnsCannotBranch() {
        assertNull(branchableTurn(listOf(exchange(ExchangeStatus.Working)), "r0", active = false))
        assertNull(branchableTurn(listOf(exchange(ExchangeStatus.WaitingUnlock)), "r0", active = false))
        assertNull(branchableTurn(listOf(exchange(ExchangeStatus.Queued("排队"))), "r0", active = false))
        assertNull(branchableTurn(listOf(exchange(ExchangeStatus.NeedsInput("问", null))), "r0", active = false))
        assertNull(branchableTurn(listOf(exchange(ExchangeStatus.Paused("稍等"))), "r0", active = false))
        assertNull(branchableTurn(listOf(exchange(ExchangeStatus.NeedsTakeover("付款", "请接管"))), "r0", active = false))
    }

    @Test fun aConversationWithARunningTaskCannotBranch() {
        val exchanges = listOf(exchange(ExchangeStatus.Done("答", null)))
        assertNull(branchableTurn(exchanges, "r0", active = true))
    }

    @Test fun onlyUserMessagesCanRetry() {
        val exchanges = listOf(exchange(ExchangeStatus.Done("答", null)))
        assertEquals(0, retryableTurn(exchanges, "u0", active = false))
        assertNull(retryableTurn(exchanges, "r0", active = false))
        assertNull(retryableTurn(exchanges, "m0-steer", active = false))
        assertNull(retryableTurn(exchanges, "u1", active = false))
        assertNull(retryableTurn(exchanges, "u", active = false))
        assertNull(retryableTurn(exchanges, "u0", active = true))
        assertNull(retryableTurn(listOf(exchange(ExchangeStatus.Done("答", null)).copy(goal = " ")), "u0", active = false))
    }

    @Test fun retryTextKeepsOnlyWhatTheUserWrote() {
        assertEquals("帮我改一下", retryText("帮我改一下"))
        assertEquals(
            "帮我改一下",
            retryText("> [Quoting your earlier reply]\n> 之前的话\n\n帮我改一下"),
        )
        assertEquals("", retryText("附件:/workspace/inbox/a.pdf(1 KB)"))
        assertEquals("", retryText("[[screen:s1]]"))
        val page = AttachedPage.compose(AttachedPage.Page("https://a.example/", "页", "选中的字"), "帮我改一下")
        assertEquals("帮我改一下", retryText(page))
        assertFalse(needsReattach(page))
        assertTrue(needsReattach("[[screen:s1]]\n帮我看看"))
        assertTrue(needsReattach("看这个\n附件:/workspace/inbox/a.pdf(1 KB)"))
        assertFalse(needsReattach("只是文字"))
    }
}

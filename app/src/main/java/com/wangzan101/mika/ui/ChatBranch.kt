package com.wangzan101.mika.ui

import com.wangzan101.mika.core.agent.QuoteMessage
import com.wangzan101.mika.core.cap.web.AttachedPage
import com.wangzan101.mika.core.userscreen.UserScreenMessage

/**
 * 长按的这条消息能不能作为分支点:只有 Mika 的回答(`r<轮次>`)、这一轮已经结束、对话没有任务在跑或排队时
 * 返回轮次(0 起),否则 null。用户的话、插话、状态卡、过程行都不算。
 */
internal fun branchableTurn(exchanges: List<Exchange>, key: String, active: Boolean): Int? {
    if (active) return null
    if (!key.startsWith("r")) return null
    val index = key.removePrefix("r").toIntOrNull() ?: return null
    if (index !in exchanges.indices) return null
    return index.takeIf { exchanges[it].status.branchable }
}

/** 这一轮已经结束(成功、失败、被停止都算);在跑、等你处理、排队的不算。 */
internal val ExchangeStatus.branchable: Boolean
    get() = when (this) {
        is ExchangeStatus.Done, is ExchangeStatus.Failed, is ExchangeStatus.Cancelled -> true
        else -> false
    }

/** 「从这里重新问」的结果:新对话编号、预填的文字、原消息是否带了要重新添加的附件/屏幕。 */
data class BranchRetry(val conversationId: String, val prefill: String, val needsReattach: Boolean)

/**
 * 长按的是哪条用户自己的话能「从这里重新问」:`u<轮次>`、轮次在范围内、这条话不是空的、对话没有任务在跑或排队。
 * 插话(`m…`)、回答、状态卡都不算。
 */
internal fun retryableTurn(exchanges: List<Exchange>, key: String, active: Boolean): Int? {
    if (active) return null
    if (!key.startsWith("u")) return null
    val index = key.removePrefix("u").toIntOrNull() ?: return null
    if (index !in exchanges.indices) return null
    if (exchanges[index].goal.isBlank()) return null
    return index
}

/** 原来那句话里能重新问的文字:去掉网页说明、引用、附件行和屏幕行;只发了附件/屏幕时为空。 */
internal fun retryText(raw: String): String =
    UserScreenMessage.parse(AttachmentMessage.parse(QuoteMessage.words(AttachedPage.words(raw))).words).words.trim()

/** 原话带了附件或屏幕:重新问时不会自动带上,界面据此提示重新添加。 */
internal fun needsReattach(raw: String): Boolean {
    val attached = AttachmentMessage.parse(QuoteMessage.words(AttachedPage.words(raw)))
    return attached.hasAttachments || UserScreenMessage.parse(attached.words).hasScreen
}

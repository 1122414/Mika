package com.wangzan101.mika.ui

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

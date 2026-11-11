# 历史引用的可读范围

**信号**：一个方案准备把工具结果换成 ID，假定同一任务稍后或进程重启后可以按 ID 找回。先区分「当前请求不再发送」「运行中还能查到」和「下一进程能读到同一份内容」。

| 来源 | 读回同一条材料的边界 |
| --- | --- |
| [ContextCompressor](../../../core/src/main/kotlin/com/wangzan101/mika/core/agent/ContextCompressor.kt) | 改的是某模型的发送视图，调用方的内存历史不变；这不提供落盘或模型可调用的查询接口。 |
| [HistoryRead](../../../core/src/main/kotlin/com/wangzan101/mika/core/general/HistoryRead.kt) | 读取当前对话开局带入的先前轮次。返回运行记录里已脱敏、截短的条目，不是本次运行的原始事件流。 |
| [Shots](../../../core/src/main/kotlin/com/wangzan101/mika/core/visual/Shots.kt) | 截图编号在任务内分配。回看查询内存队列；现有测试连读 25 次后，回看 5、6、25，返回 6 和 25，5 标为缺失。 |
| [EvidenceLedger](../../../core/src/main/kotlin/com/wangzan101/mika/core/verify/EvidenceLedger.kt) | 运行时核查与离线评测共用观察筛选规则。入账前脱敏、截短，相同地点和文字去重，总量超限淘汰早期条目。 |

**处理**：决定裁剪前，用实际读取接口检查任务归属、有效期和可回读的字段。对不能恢复的决定性信息，保留可用且允许保留的内容，或要求重新观察；不能把一个存在的编号当成仍有原件的证明。现有 [RunStore](../../../app/src/main/java/com/wangzan101/mika/record/RunStore.kt) 有独立的落盘和轮换策略，不能用它的存在推断上述接口的读取能力。

**验证范围**：在基线 `65b2ecf3` 核对源码，并运行 [ContextCompressorTest](../../../core/src/test/kotlin/com/wangzan101/mika/core/agent/ContextCompressorTest.kt)、[HistoryReadTest](../../../core/src/test/kotlin/com/wangzan101/mika/core/general/HistoryReadTest.kt)、[ToolDispatcherRecallTest](../../../core/src/test/kotlin/com/wangzan101/mika/core/tool/ToolDispatcherRecallTest.kt) 和 [EvidenceLedgerTest](../../../core/src/test/kotlin/com/wangzan101/mika/core/verify/EvidenceLedgerTest.kt)。本次没有验证真机重启恢复，也没有测量不同发送视图的成功率或费用。

**断点恢复的补充边界**：[Issue #340](https://github.com/wangzan101/Mika/issues/340) 描述了中断后继续的需求。当前 [RunRecovery](../../../app/src/main/java/com/wangzan101/mika/task/RunRecovery.kt) 可以从已记录的事件重建进度说明；[聊天页](../../../app/src/main/java/com/wangzan101/mika/ui/ChatScreen.kt) 的“从断点继续”会发送新的用户请求。[RunRecoveryTest](../../../app/src/test/java/com/wangzan101/mika/task/RunRecoveryTest.kt) 的 `interruptionIncludesPersistedProgressButNotificationStaysShort` 覆盖进度写回对话。这里能交接的是已记录的进度，不能据此重放一条结果不明的外部工具调用。

**跨 App 比较的合成回放**：[HotelContextReplayTest](../../../core/src/test/kotlin/com/wangzan101/mika/core/agent/HotelContextReplayTest.kt) 用排序页和三份路线页比较候选，期间穿插普通导航。逐轮调用现有裁剪器保留最近五轮时，预订前已无法仅从可见屏幕确定赢家。保住人工标注的四份来源后，每个决策点与完整历史的判定一致；任一候选缺步行时间，不能用驾车时间补足。复跑 `./gradlew :core:test --tests '*HotelContextReplayTest'`，报告位于 JUnit XML 的 `system-out`。这里测的是固定轨迹的消息估算与证据可见性，没有验证自动识别证据、模型决策或真实节省率。

**本分支的反例与边界**：只保留 `obsId` 仍不够：普通证据账本会按重复文本和容量淘汰条目。[TaskMemory](../../../core/src/main/kotlin/com/wangzan101/mika/core/context/TaskMemory.kt) 在提交活跃事实时固定独立的已脱敏来源；新指令会使旧事实失效。原始屏幕 JSON 的回读属于当次运行，不能从重启后的编号推断原文仍在；[RunRecovery](../../../app/src/main/java/com/wangzan101/mika/task/RunRecovery.kt) 只交接已落盘、能和来源对上的引文与动作状态。已用 [ReliableMemoryTest](../../../core/src/test/kotlin/com/wangzan101/mika/core/agent/ReliableMemoryTest.kt) 和 [IncrementalSummaryTest](../../../core/src/test/kotlin/com/wangzan101/mika/core/agent/IncrementalSummaryTest.kt) 覆盖活跃来源、窗口及摘要边界；这不能证明任意页面上的一次点击都能被识别为下单。

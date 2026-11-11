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

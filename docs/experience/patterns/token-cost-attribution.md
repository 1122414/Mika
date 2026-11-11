# 区分调用次数和单次输入的节省

**信号**：同一任务改动前后的总 token 差距很大，但两次走过的步骤不同。历史每多重发一次，就会多计一次输入；只比较总量，无法判断是少走了步骤，还是相同步骤的请求变短了。

**已知案例**：[上游 #223 的维护者对照](https://github.com/wangzan101/Mika/issues/223#issuecomment-6648888005) 分开报告了两组结果：同模型错误路由的只读样本从 20 步变为 7 步，输入约从 86.5 万降至 20.1 万；逐步对齐时，App 列表去重约少 3,000 输入 token。当前 [TaskRun](../../../app/src/main/java/com/wangzan101/mika/task/TaskRun.kt) 已跳过主、轻角色解析为同配置时的逐步路由。不要把整次运行的差值记为裁剪收益。该样本不是当前版本的复测或普遍节省率。

**怎么核对**：对齐相同动作和工具结果，测发送视图本身省下多少；再独立从同一起点跑到任务终点，计入成功、失败、额外步骤与回读。[RunMetrics](../../../core/src/main/kotlin/com/wangzan101/mika/core/record/RunMetrics.kt) 区分主循环回复、全部逻辑调用和 HTTP 尝试；缺 usage 的尝试单列。比较费用时还要区分缓存与未缓存输入。[RunTrace](../../../core/src/main/kotlin/com/wangzan101/mika/core/trace/RunTrace.kt) 能定位首个变动块，但仅凭前缀变动不能推出服务商实际收了多少钱。

**用量导出验证（一次）**：按 [RunStore 的导出字段](../../../app/src/main/java/com/wangzan101/mika/record/RunStore.kt) 和 [ModelCalls 的统计范围](../../../core/src/main/kotlin/com/wangzan101/mika/core/record/RunEvent.kt) 核对一份完整样本：逐步合计与运行汇总有差额，两者不能相加。用 `model_calls` 计运行总量，用 `model_reply` 定位相邻请求的输入增幅；工具名是该次回复提出的调用，导出不含工具结果正文，不能据此断定增量来自哪一块。运行结束后的记忆提取等调用不在该汇总内。

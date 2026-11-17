# 做了很多操作，目标计数却一直没变

信号：任务卡停在 0/N，但操作时间线里已经出现了可核实的结果。先比对同一次运行的 `criteria`、解析错误事件（`RunEvent.State.name == "task_progress_invalid"`）和模型回复。只有初始 `criteria`、没有解析错误，说明没有收到后续有效更新；不能由动作条数推算已完成的目标。聊天页折叠的「前面还有 N 步」指已经发生的动作。

一个跨应用查店任务里，回复先后报告已确认地址和店名，计数却只有开局的 0/3。旧协议会从对话历史剥离进度块，又没有在后续常规请求里带回清单。现在由 `TaskProgressTracker` 带回待确认编号，按附有证据的上报去重；`RunEvent.Criteria.confirmedIds` 可用于复查。计数器不验证证据内容，也不把未上报的结果补为完成。

回归入口：`AgentLoopTest.coffeeTaskConfirmsGoalsWithoutCountingClicksOrClosingOnFiveNotes` 检查模型实际收到的待办、1/3 → 2/3 → 3/3 以及子数量到达时仍可继续操作；`completedQuantityClosesOutInsteadOfRecheckingForever` 保留单纯计数任务的收尾行为。两者是脚本回放，不能证明所有模型都按时上报。

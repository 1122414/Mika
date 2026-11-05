# 截图回放必须保存实际动作的帧关联

## 信号与原因

Mika 的旧 `Media` 事件只给出步号和截图编号，同一循环轮次可以包含多个批次或脚本子动作。
`ModelReply` 是计划，不能据此把坐标画到同一步的任意截图上；有些画面是操作后状态。
工具坐标为 0–999，运行截图会压缩到宽度不超过 720 像素，不能直接使用原始设备像素位置。

## 处理与边界

- 在实际界面动作入口采集操作前帧；操作后复用本次调用已有的图像，否则在权限/防截屏核对后采集。
- 用 `RunEvent.TaskPlayback` 的 `callId`、`beforeId`、`afterId` 明确关联动作，不靠时间接近或步号猜测。
- 标记只出现在前帧；后帧缺失就显示缺失，不拿前帧冒充。图像异步切换时通过 Compose `key` 清空旧图，避免新标记叠在旧图上。
- 元素目标的中心先按原始屏幕宽高归一化；绘制时按实际显示图片的尺寸映射。
- 语义滚动不保证真实触点，箭头必须标为方向示意。旧记录仅展示画面，不编造标记。
- 本地记录图像不加入模型图片列表或上下文；记录数量增加仍可能增加截图延迟和磁盘占用，不能把“不增加模型图片”解释为零开销。

## 支持与验证

实现：[标记数据](../../../core/src/main/kotlin/com/wangzan101/mika/core/record/Playback.kt)、[动作采集](../../../core/src/main/kotlin/com/wangzan101/mika/core/uistate/Interfaces.kt)、[回放界面](../../../app/src/main/java/com/wangzan101/mika/ui/TaskReportScreen.kt)。
测试：`PlaybackTest`、`ToolDispatcherFrameTest.adaptiveVisionUsesElementsForReadableScreenAndKeepsRecordFrame`、`TaskReportViewModelTest`。
初次验证：2026-11-05，核心和 Android 单元测试通过。`TaskReportScreenTest` 仅编译通过，未在真实设备执行；页面自动变化与权限变化的真实设备边界仍需验收。

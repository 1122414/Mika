# 截图回放必须保存实际动作的帧关联

## 信号与原因

Mika 的旧 `Media` 事件只给出步号和截图编号，同一循环轮次可以包含多个批次或脚本子动作。
`ModelReply` 是计划，不能据此把坐标画到同一步的任意截图上；有些画面是操作后状态。
工具坐标为 0–999，运行截图会压缩到宽度不超过 720 像素，不能直接使用原始设备像素位置。

## 处理与边界

- 在实际界面动作入口采集操作前帧；操作后复用本次调用已有的图像，否则在权限/防截屏核对后采集。
- 用 `RunEvent.TaskPlayback` 的 `callId`、`beforeId`、`afterId` 明确关联动作，不靠时间接近或步号猜测。
- 标记只出现在前帧；回放不生成未采集的空帧；真实缺文件跳过并提示数量，不拿前帧冒充后帧。异步加载后将图像与其自身标记作为一个状态交叉淡入，避免新标记叠在旧图上。
- 元素目标的中心先按原始屏幕宽高归一化；绘制时按实际显示图片的尺寸映射。
- 语义滚动不保证真实触点，箭头必须标为方向示意。旧记录仅展示画面，不编造标记。
- 本地记录图像不加入模型图片列表或上下文；记录数量增加仍可能增加截图延迟和磁盘占用，不能把“不增加模型图片”解释为零开销。

## 支持与验证

实现：[标记数据](../../../core/src/main/kotlin/com/wangzan101/mika/core/record/Playback.kt)、[动作采集](../../../core/src/main/kotlin/com/wangzan101/mika/core/uistate/Interfaces.kt)、[回放界面](../../../app/src/main/java/com/wangzan101/mika/ui/TaskReportScreen.kt)。
测试：`PlaybackTest`、`ToolDispatcherFrameTest.adaptiveVisionUsesElementsForReadableScreenAndKeepsRecordFrame`、`TaskReportViewModelTest`。
初次验证：2026-11-05，核心和 Android 单元测试通过。`TaskReportScreenTest` 仅编译通过，未在真实设备执行；页面自动变化与权限变化的真实设备边界仍需验收。

## 真机反馈修正（2026-11-05）

验证状态：在一台 PKX110 手机上核对一次。任务记录中的 34 张已登记截图全部存在；两处 beforeId 为 null（启动与一次返回），旧界面把它们生成为空白帧，误报“未保存或已清理”。这不能推断所有缺图都不是落盘问题：实际文件缺失仍需单独检查。

处理：先区分“从未采集”“文件不存在”“解码失败”，只将实际可用帧放入连续播放序列。缺帧标记不得转移到结果帧。不要为了消除提示而改大队列、补造截图或从别的动作借帧。

关键页不等于均匀抽样的动作：通过 Observed.id → Activity.obsIds/callId → TaskPlayback.callId 建立关联，优先选报告正文实际引用的页面文字，按标题去重；没有证据时只使用“结束时页面”。同一轮多个子动作仍不得按轮次合并。导航阶段与完整步骤分开，保留明细入口。

回放画面必须与控件共用受约束的可用高度，画面按宽高比 fit，不能把整屏放进同一个长滚动列表。默认 2.4 秒停留从图片解码完成后开始；手动与自动使用同一路径，360 ms 过渡绑定图片和标记。布局、红圈、方向箭头、倍速、明细及实际导出流程已通过 ADB 真机检查；Compose 自动化因设备 InputManager.getInstance 缺失受阻，不能记为通过。

相关验收：[真机反馈修复](../../dev/2026-11-05_report-replay-phone-fixes.md)。

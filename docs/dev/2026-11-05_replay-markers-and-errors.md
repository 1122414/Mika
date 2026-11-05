# 回放操作标记与错误原因修复

基线：bc38a5d5，分支 codex/task-report-replay。

## 工作阶段

1. 修复播放项与具体错误关联，移除步骤明细，执行回归和手机检查后提交。
2. 版本升级为 preview.3 / 41；提交后构建同签名非调试 APK、覆盖安装并发布 fork 预发布。

## 问题与验收

| ID / 优先级 | 问题与证据 / 修复前行为 | 方案与预期 | 风险 / 状态 |
| --- | --- | --- | --- |
| M01 / P1 | 实际记录中点击/滑动都有坐标；前后帧拆成两项，后帧不画标记却仍叫“点击/滚动” | 每个动作一个播放项，标记绑定其原图；最后结果单独标为结束页面 | 不能将坐标搬到结果图，旧记录缺标记不补造；已修复并验证 |
| M02 / P1 | 两次输入失败已记录 action_not_applied 和 callId，但界面只读 succeeded | 按 step/tool/callId 查对应结果，复用本地化错误原因，未知错误保留安全的原因或错误码 | 批次与重复调用不得串错；已修复并验证 |
| M03 / P2 | 用户明确要求取消“步骤明细” | 移除按钮、弹窗与无用资源；阶段/进度/上下步仍可使用 | 不删除运行记录或原时间线；已修复并验证 |

目标检查：一次操作不再出现无标记重复项；缺前帧时不在后帧伪造位置；阶段和关键页仍对应正确动作；两次输入失败显示“出错：字没输进去”；未知/截断结果、同轮多动作不串错；手机按钮消失、原记录保留。

## 定向验收（2026-11-05）

| ID | 测试任务与步骤 / 命令 | 修复前 | 修复后预期 | 实际结果与证据 | 状态 |
| --- | --- | --- | --- | --- | --- |
| M01 | `TaskPlaybackActionsTest.eachGestureKeepsItsOwnAnnotatedFrameAndFinalResultIsDistinct`；ADB 打开原任务，查看点击、滚动 | 已观察：22 项中结果帧无标记但仍标为点击/滚动 | 每操作一项，最终结果独立命名 | 原记录为 12 次操作 + 1 张结束页面；第 2 项搜索位置红圈、第 7 项向上箭头均在对应原图，控件与完整图片同屏 | 通过 |
| M01 | `missingOriginalFrameNeverTransfersCoordinatesToResultAndLegacyStaysHonest` | 预测：旧选择方式多一张无标记结果帧；真正缺前图不能还原坐标 | 缺原图不借位置，旧记录不补造标记 | 单测覆盖仅后图和旧 Media；有效图片可回放，标记为空 | 通过 |
| M01 / M03 | ADB 上一步、自动播放至结束；检查页面控件 | 已观察：有“步骤明细”；前后图拆开 | 保留上下步/自动播放/阶段，去掉明细入口 | 第 13 项标为“结束时页面”，最终自动停止；全部检查页面均无“步骤明细” | 通过 |
| M02 | `errorsMatchExactStepToolAndCallRatherThanAdjacentBatchAction`；ADB 第 3、5 项 | 已观察：真实 trace 的 action_not_applied 只显示“出错了” | 展示同一调用的具体错误 | 两次失败均显示“出错:字没输进去”；单测验证重复 callId 跨轮次、相邻调用、无结果及成功结果 | 通过 |
| M02 | `errorsUseKnownTranslationThenMessageThenCodeAndTolerateTruncation` | 预测：未知或截断结果只剩泛化失败 | 已知译名 → 脱敏 message → error code；无原因明确说明未记录 | 未知错误、截断 JSON、嵌套失败但顶层成功、损坏记录均通过 | 通过 |
| M01–M03 | `./gradlew -PforkPreview :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:assemblePerf` | 原有 1,488 项单测通过 | 原回归与新增边界通过 | 1,492 项单测，0 失败/跳过；设备测试编译与 perf APK 构建通过 | 通过 |
| M01–M03 | `python3 tools/i18n/translations.py check`；`git diff --check` | 原有 1,992 项资源 | 文案跨语言一致，无空白错误 | 1,993 项资源 / 10 语言通过；差异检查通过 | 通过 |
| M01–M03 | `./gradlew -PforkPreview :app:lintPerf` | 已核实基线 32 Error | 不新增 Error | 仍为 32 Error：NewApi 8、RestrictedApi 1、ProduceStateDoesNotAssignValue 21、StateFlowValueCalledInComposition 1、ByteOrderMark 1 | 全项目检查受既有问题阻塞 |
| M01–M03 | `TaskReportScreenTest` 设备执行 | 已观察：本手机旧 Espresso 调用 InputManager.getInstance 时失败 | 自动化输入可执行 | 本次只编译；已知系统兼容阻塞未重跑，以 ADB 操作和截图验收 | 未执行，限制保留 |

## 发布与范围

版本 `0.1.37-report-preview.3`、versionCode 41；独立预览包名不变，覆盖升级保留历史任务。最终 APK 在提交后重新构建，发布流程核对签名与哈希。源码不包含手机截图、trace 或用户报告正文。

修复仅调整已有记录的展示，不补造未采集的坐标，不改采集协议，不重跑任务。语义滚动箭头仍是方向示意。原始前后截图保留，报告关键页继续使用结果截图；回放入口定位其对应的实际操作。横屏和大字体组合本次未作额外真机验收。

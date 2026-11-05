# 回放中间页面完整性修复

基线：17391baf，修复针对 preview.3 的动作单帧筛选。开始时用户手机暂不在手；完成单测后用户已重新连接手机，追加真机验收。

## 工作阶段

1. 修复连续画面、结果标识与证据页导航，补充场景回归；通过构建及检查后中文提交。
2. 手机重新连接后验证原任务，再从提交构建 preview.4 / 42 的同签名安装包并发布预发布。

## 问题与验收目标

| ID / 优先级 | 问题与证据 | 修复前行为 | 方案 | 预期 | 风险 | 状态 |
| --- | --- | --- | --- | --- | --- | --- |
| M04 / P1 | 本地 trace 和文件清单有点击后的详情截图；actionFrames 优先带标记前图、无标记返回选后图 | 点击列表→返回列表，中间详情被过滤；报告/阶段入口落到列表 | 保留可用前后帧时间顺序；标记只属于原图，结果帧明确称为操作结果；入口直接按截图编号定位 | 手动/自动均能显示详情；关键页跳到同一证据图；无前后按钮和明细按钮 | 不按相似度猜测删除画面，暂保留相邻重复图；缺图仍跳过且计数 | 代码修复、单测及本机 ADB 验收通过 |

定向验收：点击进入详情后立即返回，详情截图必须出现在序列中；有标记帧后跟无标记结果，不复制坐标；同轮多动作、缺前帧、缺后帧、旧记录仍可播放；报告缩略图和阶段入口指向同一截图。自动播放给动作提示较短停留、结果足够停留，手动逐帧可暂停。错误原因不丢失。单元测试覆盖真实缺陷场景，Compose 测试编译，手机重新连接后追加检查，结果见下表。

## 验收结果

| ID | 测试任务 / 步骤或命令 | 修复前预期或观察 | 修复后预期 | 实际结果与证据 | 状态 |
| --- | --- | --- | --- | --- | --- |
| M04 | `TaskPlaybackActionsTest.openingPostThenImmediatelyReturningPreservesDetailAndEvidenceNavigation` | 已观察：用户相邻两帧均是列表；本地 trace 选帧为 22→26 | 序列保留 22→23→25→26，详情可停留，报告和阶段精确到 23 | 测试从 RunStore 加载，全部断言通过；首次运行发现同首阶段冲突，改为证据阶段优先后通过 | 通过 |
| M04 | `missingOriginalOrResultNeverTransfersCoordinatesOrCreatesBlankFrames` | 预测：借原坐标会误标结果图 | 真实缺图跳过计数，不转移坐标 | 缺前图仅无标记结果；缺后图仅保留动作，缺图数为 2 | 通过 |
| M04 | `sameRoundGesturesKeepEveryResultAndItsOwnMarker` / `legacyScreensStayInOrderWithoutInventedResultsOrMarkers` | 预测：单帧筛选会丢中间结果；旧记录没有坐标 | 每次操作前后图有序，旧图不造标记 | 同轮双动作 4 图保留，各自标记只在原图；旧 Media 保留顺序；动作提示时长短于结果 | 通过 |
| M04 | `./gradlew -PforkPreview :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:assemblePerf` | 原单测只验证单帧选择结果，未证明页面完整 | 回归、场景与设备测试编译通过 | 1,494 项单测，0 失败/跳过；设备测试编译与 perf 构建通过 | 通过 |
| M04 | `python3 tools/i18n/translations.py check` / `git diff --check` | 原 1,993 项资源 | 新增结果文案同步各语言 | 1,994 项资源 / 10 语言一致；差异空白检查通过 | 通过 |
| M04 | 手机打开原任务，手动查看首个帖子、自动播放、点阶段与报告缩略图 | 已观察：preview.3 跳过详情 | 详情完整可见，入口到对应图片 | 用户随后连接 PKX110：已覆盖安装测试构建；14/22 点击列表→15/22 详情→16/22 返回前详情→17/22 返回后列表。自动播放捕获详情、暂停；阶段与报告入口均到 15/22，截图区域逐像素一致 | ADB 通过 |

## 取舍与兼容性

本次取消以有无标记筛掉画面的规则。相邻结果图与下一动作原图可能相似，暂时都保留以避免删除阅读过程；不恢复前后切换按钮或步骤明细。标记、具体错误和文件导出保持；失败结果帧也显示具体原因。

内部回放导航统一从步骤索引改为可空截图编号：null 表示从头，关键页传自身 screenshotId，MainActivity 和界面调用已同步。记录文件格式、采集和权限不变。普通前帧默认 1.2 秒、结果/旧图 2.4 秒，0.5×/1×/2× 与既有过渡保留。默认构建版本不变，fork 预览版升级为 0.1.37-report-preview.4 / 42。

用户重新连接手机后，已在原任务验证手动/自动播放与两个证据入口；未重跑模型任务。Compose 设备执行仍受前次发现的旧 Espresso / InputManager 系统兼容限制，本轮仅编译，ADB 验收单独记录，不等于 Compose 自动化通过。

Lint 检查：`:app:lintPerf` 仍失败于既有 32 Error，类别与基线一致（NewApi 8、RestrictedApi 1、ProduceStateDoesNotAssignValue 21、StateFlowValueCalledInComposition 1、ByteOrderMark 1），本次未新增 Error。未扩大范围处理上游问题。

# 回放红圈来源与图片手势修复

基线：99674a36。保留已记录的点击红圈、截图和结果图；增加图片滑动翻页。

| ID / 优先级 | 问题与证据 | 修复前行为 | 方案与预期 | 风险 | 状态 |
| --- | --- | --- | --- | --- | --- |
| M05 / P1 | 历史最后一次 ref 点击的元素 bounds 在搜索栏，结果却打开帖子；临时隐藏所有 ref 标记后，用户反馈点击红圈全部消失 | 把元素中心当作实际触点；临时修复又删除了正常红圈 | 恢复所有有效点击标记；普通元素点击标为位置示意，坐标/视觉点击不变。按 step/tool/callId 精确识别历史来源 | 不修复历史错误位置；旧记录不足以还原真实触点，截图与树不一致原因仍待采集证据 | 核心和应用单测通过；历史错位未还原 |
| M06 / P2 | 图片原本只能点开放大；用户要求手滑切换步骤 | 需按上下步或拖动进度条 | 左/上滑下一帧，右/下滑上一帧；开始拖动暂停播放；全屏同样支持，边界不循环 | 短拖、取消与点击放大可能冲突，需要边界验收 | 用户确认可以滑动；逻辑回归通过 |

验收：新旧记录的有效点击圈保留，元素中心注明估算；结果图不错误叠圈；坐标、视觉定位与滑动箭头不变；严格匹配动作、不删截图。四方向翻页、边界、暂停、轻点放大与全屏切换保持可用。检查后中文提交并发布 preview.5 / 43。

## 验证记录

| ID | 测试 / 操作 | 修复前 | 修复后预期 | 实际结果 | 状态 |
| --- | --- | --- | --- | --- | --- |
| M05 | `PlaybackTest.elementMarkerIsPreservedAsAnEstimateNotAnActualTouch`、`ToolDispatcherFrameTest` | 已观察：元素中心未注明估算；临时版本隐藏元素点击 | 元素位置标记保留并标为 approximate，坐标/方向不变 | 核心定向 19 项，0 失败/跳过 | 通过 |
| M05 | `oldElementMarkersRemainVisibleAndDistinguishEstimatedCoordinates` | 已观察：用户反馈点击圈全消失 | 跨轮同 callId、同轮相邻调用不串联；普通 ref 保留估算圈、坐标和 @v12 保留原标记；六张原图齐全 | 应用完整 1,496 项，0 失败/跳过 | 通过 |
| M06 | `imageSwipesUseDominantDirectionAndIgnoreShortDrags` | 已观察：只有点击放大 | 四方向、斜向主轴、短拖均按规则处理 | 应用完整 1,496 项，0 失败/跳过 | 通过 |
| M05–M06 | 翻译检查、差异空白检查、打包 | 预期：资源新增可能遗漏语言 | 全语言一致、可生成安装包 | 1,995 资源 / 10 语言及 git diff --check 通过；perf 构建通过 | 通过 |
| M06 | 真机手势 | 已观察：原版本无图片手势 | 图片滑动可切换帧 | 用户确认“可以滑动”；ADB 期间双方同时操作，不能据此声称全部边界通过 | 部分验证 |
| M05 | 恢复版本真机红圈 | 已观察：临时版本隐藏了所有元素点击圈 | 恢复点击红圈 | ADB 覆盖安装返回 Success；用户重新打开原回放确认“红圈已恢复” | 通过（用户真机验收） |

构建环境：应用模块不能引用 core 的 internal 常量，展示层按已存储的 @vN 格式识别；默认 Kotlin 守护进程出现 GC overhead limit exceeded，使用命令行单工作线程、in-process、临时 4GB JVM 堆，不改项目默认配置。新增六帧夹具曾瞬间超过生产四帧有界队列，改为等待每张夹具落盘，未改生产队列或放宽断言。

兼容性：记录协议及标记工厂签名不变；仅复用既有 approximate 字段区分元素估算。未修改节点点击执行或截图采集；图像与节点树不一致的底层原因缺少时间戳证据，不能声称已修复同步或还原旧触点。结果图按原规则不画前帧坐标。

最终代码验证命令（JDK 17 / Android SDK，临时 4GB in-process JVM）：

```sh
./gradlew --no-daemon --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8' -Pkotlin.compiler.execution.strategy=in-process -PforkPreview :core:test --tests '*PlaybackTest' --tests '*ToolDispatcherFrameTest' :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:assemblePerf --console=plain
python3 tools/i18n/translations.py check
git diff --check
```

最终定向核心测试 19 项、完整应用测试 1,496 项通过，设备界面测试仅编译通过。此前本轮完整核心 2,922 项（3 跳过）是在隐藏圈的中间版本运行，不能替代最终代码的完整核心回归结论。全项目 `:app:lintPerf` 仍失败，32 条既有 Error（NewApi 8、RestrictedApi 1、ProduceStateDoesNotAssignValue 21、StateFlowValueCalledInComposition 1、ByteOrderMark 1），类别和数量与此前基线一致。未修复无关模块或关闭检查。

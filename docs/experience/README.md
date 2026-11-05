# 项目经验索引

| 机制 | 适用信号 | 验证状态 | 记录 |
| --- | --- | --- | --- |
| 回放帧与实际动作的因果关联 | 同一步包含多个子动作、操作前后缺图、缩放后的坐标偏移 | 核心与 Android JVM 测试；一台真机验证，空帧误报已定位 | [截图回放](patterns/task-playback-frames.md) |
| 报告粗体边界与标准文件导出 | 中文粗体露出星号、正常片段被容错改坏、导出暂时零字节 | 单测与一台真机验证一次 | [报告 Markdown](patterns/report-markdown-boundaries.md) |

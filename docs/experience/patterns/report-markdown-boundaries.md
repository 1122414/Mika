# 报告 Markdown 粗体边界与导出

信号：中文结论中出现裸露的 `**`，尤其是标点紧贴结束符，或结束符前有多余空格。CommonMark 的分隔符规则不保证模型输出的所有粗体写法都能解析。

机制与处理：报告渲染前对成对粗体做有限的空白规范化，不修改持久化回答。不要先后对整串做相互独立的粗体替换：一次修复曾把前一段的闭合符与后一段的起始符误配，导致原本正常的粗体失效。改为一次处理完整配对片段，并通过 `TaskReportExportTest` 的正常/异常连续片段回归检查。代码块与行内代码保持字面内容，链接地址不注入加粗标记。

导出复用规范化后的 Markdown；Word 使用标准 OOXML 包和格式 run；TXT 使用 Markdown 模型转纯文本。写入系统文件选择器后应等流关闭，再检查文件，立即读取可能看到暂时的零字节。实际验证了手机生成的 MD/TXT、DOCX ZIP/XML，以及 macOS textutil 对 DOCX 的独立读取。

适用范围：报告展示层，不是完整 Markdown 修复器；不能推断所有语法错误的原意。只依据任务类型突出明确的日期、时间、金额和车次，不额外调用模型生成重点。验证状态：单元测试与一台真机验证一次。

代码：[TaskReportExport](../../../app/src/main/java/com/wangzan101/mika/ui/TaskReportExport.kt)。测试：[TaskReportExportTest](../../../app/src/test/java/com/wangzan101/mika/ui/TaskReportExportTest.kt)。

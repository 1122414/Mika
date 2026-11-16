# Compose 真机测试在输入初始化时失败

[个人助手回复复制 #644](https://github.com/wangzan101/Mika/issues/644) 的两条用例第一次运行时，都在 `Espresso.onIdle` 初始化阶段报 `NoSuchMethodException: android.hardware.input.InputManager.getInstance`。还没有点击或长按，不能将这个结果当作复制功能失败。

诊断时检查 **测试 APK** 解析出的 Espresso 版本。本次 Compose 测试间接引入的 3.5.0 在 SDK 37 真机上走了不可用的反射入口；[Espresso 3.7.0 的发布说明](https://developer.android.com/jetpack/androidx/releases/test#espresso-3.7.0)列出了对此的修复。只在 `androidTestImplementation` 指定修复版本，重建、覆盖安装测试 APK，再运行同一条用例；无需调整手机的隐藏 API 策略。

验证一次：同一台 SDK 37 设备、同一个 Dev 应用，仅覆盖测试包后，`PersonalReplyCopyTest` 从两条初始化失败变为两条通过，包括长按、拖选、全选和全文复制的剪贴板断言。

适用边界：上述堆栈出现在用例执行之前。界面已经显示但选区、全选范围或剪贴板断言不对时，应检查对应屏幕的回复组件，不能归因于此。

# 任务通知合格但没有显示流体云

在一加 13T（ColorOS 17 / API 37）上，Mika Dev 已获准发普通通知，升级后「流体云显示实时活动」仍是关闭的。运行 `TaskLiveUpdateDeviceTest`：开关关闭时，任务通知的 `hasPromotableCharacteristics()` 为真，但 `NotificationManager.canPostPromotedNotifications()` 为假，桌面只出现普通图标。用 `Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS` 和 `Settings.EXTRA_APP_PACKAGE` 打开本应用的系统页，启用后重跑同一用例，桌面顶部出现「正在执行」，随后原位变成「等你回答」。

诊断时分开看三件事：应用发出的通知是否符合[实时更新条件](https://developer.android.com/develop/ui/views/notifications/live-update)、系统是否允许这个应用提升通知、退出应用后系统界面是否真的显示。只看通知栏、权限声明或某一个系统标志，均不能代替最后一步。

验证范围：一台一加 13T 的 Dev 覆盖安装。不能据此推断其他机型的默认开关、入口名称，或 Android 15 及更早版本的厂商接口。

另一种情况是胶囊已出现，却长时间只显示「正在执行」。此时先检查从 `TaskState.Running` 到短文案的映射。等模型时 `tool` 为空，`note` 却可能还是上一步的动作；直接显示 `note` 不仅会报错阶段，也可能暴露任务内容。`RunProgress.Replay` 有当前步和总步数，`RunProgress.Batch` 只有批次长度，不能把模型轮数配上这个长度当作进度。`TaskChipLabelTest` 覆盖这些区别、快速切换和等待用户的优先级。

多状态版 `16e61108` 在同一台一加 13T 上覆盖安装后，`TaskLiveUpdateDeviceTest` 正式用例分别在「思考时退桌面」和「报出进度后退桌面」通过。桌面画面确认「正在思考」「进度2/5」「等你回答」及结束后撤下；这些阶段使用同一通知。首次观察时曾停在「正在思考」，原因未确定，未据此更改系统开关。此次没有逐一在真机观察读屏、手机操作和浏览网页文案。

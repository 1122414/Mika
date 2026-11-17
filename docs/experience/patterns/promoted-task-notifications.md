# 任务通知合格但没有显示流体云

在一加 13T（ColorOS 17 / API 37）上，Mika Dev 已获准发普通通知，升级后「流体云显示实时活动」仍是关闭的。运行 `TaskLiveUpdateDeviceTest`：开关关闭时，任务通知的 `hasPromotableCharacteristics()` 为真，但 `NotificationManager.canPostPromotedNotifications()` 为假，桌面只出现普通图标。用 `Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS` 和 `Settings.EXTRA_APP_PACKAGE` 打开本应用的系统页，启用后重跑同一用例，桌面顶部出现「正在执行」，随后原位变成「等你回答」。

诊断时分开看三件事：应用发出的通知是否符合[实时更新条件](https://developer.android.com/develop/ui/views/notifications/live-update)、系统是否允许这个应用提升通知、退出应用后系统界面是否真的显示。只看通知栏、权限声明或某一个系统标志，均不能代替最后一步。

验证范围：一台一加 13T 的 Dev 覆盖安装。不能据此推断其他机型的默认开关、入口名称，或 Android 15 及更早版本的厂商接口。

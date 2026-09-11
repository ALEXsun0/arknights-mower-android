Arknights Mower Android 0.2.0，内置最新 Mower alpha `eaa05aa1` 与已合并的 Android 兼容改动（打包提交 `d8cbb41b`，与 alpha `22a73d44` 文件树一致）、官方 Android MAA v6.17.5。APK 沿用原签名，可覆盖安装并保留配置。

- 修复后台连接超时后卡在“正在启动”的问题；后台启动改为自有启动器直连 AIDL，支持 Shizuku/Sui 与可选 Root。
- 原生设置增加操作录制/PIN 解锁、画中画、多点触控、720p、触点/FPS 监测、掉线停任务、屏保/物理熄屏、唤醒权限与日志导出。
- 无操作/返回主界面/退出游戏统一在原生设置选择，读写 Mower 原有配置并交给 Mower 执行；安卓版 WebUI 隐藏重复入口并避免自动保存覆盖新选择。
- 游戏预览默认只观察；“手动操作”才暂停调度。解锁记录加密保存在手机，不进入 WebUI、配置备份或日志导出。
- 设备异常可选复用 Mower 邮件配置；系统通知支持运行状态、FPS 和下一任务时间。小米超级岛授权集成尚未实现。
- Mower、MAA 和 Python 兼容接口继续独立热更新；Android 的 MAA 更新使用官方 ARM64 组件，暂不使用 Mirror酱。

附件包含 APK、对应 MAA Python 兼容 ZIP 和 android-release.json；不另发布 SHA256 文件。MAA 原包从官方获取。APK 正式版标签不改变内置 Mower alpha 属性。

需要 Android 8.1+、ARM64 和已授权的后台服务，建议预留至少 4 GB。首次解压耗时。录制解锁、Root/Sui、厂商熄屏与长期排班需要按设备进一步验证，详见仓库验证记录。

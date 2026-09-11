Arknights Mower Android 0.1.0 正式 APK，约 356 MiB，比此前约 580 MiB 减少 38.7%。ARM64，沿用已有签名，可覆盖安装并保留数据。内置 Mower 4.1.6-alpha.5（含 Android 兼容补丁）与官方 Android MAA v6.17.5。

- APK 内置 Python、Mower、MAA，使用整包 XZ 压缩并移除重复 OCR 模型、Git 和开发文件，首次启动无需额外下载环境。
- Mower 热更新使用主仓库的 Android ZIP，停止并重启服务后生效；兼容接口可单独拖拽导入。主仓库尚未发布 Android 包时，保持当前版本。
- MAA 核心和资源从官方更新；Android 暂时禁用 Mirror酱。MAA 更新完成后需重启服务。
- 原生设置集中提供屏幕唤醒、亮屏、静音、后台恢复、局域网、日志、APK 更新与恢复内置 Mower。
- 发布附件为 APK、MAA Python 兼容 ZIP、`android-release.json`。MAA 原包请从官方获取；校验值使用 GitHub asset digest。

需要 Android 8.1+、ARM64 和已启动的 Shizuku，建议预留至少 4 GB 空间。首次解压比后续启动耗时。当前主要验证三星 SM-G9880 / Android 13；长期排班及其他设备仍需继续验证。APK 正式版标签不改变内置 Mower 的 alpha 版本属性。

已通过宿主/前端测试、完整 Release lint、CI 构建，以及三星 SM-G9880 实机 WebUI、Mower 热更新/恢复与精简 MAA 资源加载检查。

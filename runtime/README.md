# Mower 运行时源码

这里保存 Android 发行使用的 Mower Python 后端、共享 WebUI 和 `mower_android` 宿主适配包。上游来源、分支和固定提交记录在 [UPSTREAM.json](../UPSTREAM.json)，许可证见 [LICENSE](LICENSE)。

- `arknights_mower/`：Mower 调度、识别、数据和回归测试。
- `ui/`：手机 WebView 与局域网浏览器共用的 WebUI。
- `mower_android/`：设备控制、MAA 桥接和 Android 更新接口。
- `server.py`：共享 Web 服务，由 Android 启动器初始化。

Android 构建统一使用仓库根目录的 `scripts/` 和 `.github/workflows/android-apk.yml`；此目录不独立运行桌面发布、Docker 发布或上游 Gitflow。保留部分桌面源文件及测试依赖，便于同步上游和检查共享代码兼容性；它们不会作为桌面程序打进 APK。

使用与构建说明见[主 README](../README.md)。上游文档见 [ArkMowers/arknights-mower](https://github.com/ArkMowers/arknights-mower/tree/alpha)。

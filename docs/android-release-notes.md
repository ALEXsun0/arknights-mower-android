Android 独立发行版，包含配套的 APK、兼容 MAA Python 接口包和官方 Android ARM64 MAA 核心包。附件校验值使用 GitHub 提供的 SHA256 digest，不另附校验文件。

- 安装 APK 即可使用本次构建内置的 Mower、后台游戏和 MAA，无需另装 Git 或进行源码部署。
- 兼容 Python ZIP 用于单独更新 Android MAA 桥接接口，可在 WebUI 选择或拖拽导入；不是官方 ctypes Python 包。
- 官方 MAAComponent tar.gz 可在 WebUI 选择或拖拽导入，校验后重启服务生效。MAA 资源仍可使用 Mirror酱更新。
- 普通发行包只提供正式、公测更新渠道，隐藏源码仓库和开发版设置。

完整说明见仓库 README 和 docs/android-update-packages.md。

屏幕唤醒、亮屏、静音、后台恢复和电池入口统一放在原生「软件设置」中；WebUI 沿用四端共用的软件更新页面，Android 隐藏进程操作。

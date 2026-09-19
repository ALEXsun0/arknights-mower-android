# Python 运行环境热更新

主仓的格式 2 Android Mower 包包含程序、WebUI 和 `python-runtime.zip.xz`，后者是配套的 ARM64 Linux Python 解释器、标准库、依赖与共享库。与只更新 `maa.py` 的 MAA Python 兼容接口包是两种不同组件。

首次启用需要 versionCode ≥ 29 的 APK。格式 1 旧包仍使用 APK 内置环境。格式 2 先下载、校验并存入独立版本目录；停止并启动服务时，由原生启动器选择程序和对应解释器，显示解压进度，完成后启动。已解压并完整的环境不会每次重新解压。Python 小版本由归档决定，原生桥协议变动才要求更新 APK。

原生启动器在解压/启动前记录未完成启动状态；未成功提供 WebUI 的新版本在下次启动时回退。配置和 MAA 不在更新包内，宿主适配代码始终由 APK 单独绑定。新版本 WebUI 就绪后，沿用 Mower 更新事务清理旧程序及其环境。手动恢复内置 Mower 同时恢复内置解释器；覆盖安装 APK 时仍应用 APK 的内置组件。

APK CI 消费主仓格式 2 包时复用其确切运行环境，不另选依赖版本。配套依赖变化不再计为宿主变化，不会单独触发 APK 更新提示。

MAA Python 兼容接口仅在 Android 的 MAA 页面接入联合检查，Windows、macOS、Linux 不请求该接口。两个检查并行、错误分别展示，接口根据代码摘要而非发行版号提示更新。保留自动检查、热加载和 WebUI 手动导入。

验证包括格式 1 兼容、清单和摘要拒绝、APK 版本门槛、Python 3.13 选择、回退/缓存及配置目录独立测试。真实归档可使用 `MOWER_TEST_FULL_UPDATE=/绝对路径/更新包.zip` 运行 `UpdatedPythonRuntimeTest` 进行原生完整解压测试；未提供归档时该项跳过。

本轮先保存在开发分支并提交主仓 PR，不发布 Android Release。首次公开启用需先提供符合最低版本的 APK。

# Android 更新包接口

APK 由本仓库构建发布。共享 WebUI 的软件更新检查 `ArkMowers/arknights-mower` 的 Android Mower 包，支持正式和公测渠道，不运行 Git/npm。APK 与 Mower 版本独立；需要更新宿主时从原生「APK 版本与更新」进入本仓库 Release，使用 Android 安装器覆盖安装，保留原签名和用户数据。

Mower 包包含根清单 `mower-android.json` 和 `mower/` 程序目录。宿主验证 `runtime_api=1`、Python 3.12、Android ARM64、版本和归档路径，将程序保存到独立目录，原子切换激活记录。停止并重启服务生效；未能正常显示 WebUI 的启动在下次启动回退。可通过原生「恢复内置 Mower」清除激活记录。包不覆盖 `mower_android`、Python 依赖、MAA 或用户配置；不兼容环境需要新版 APK。

在线下载验证 GitHub asset digest。手动导入在确认前只校验，确认后才切换；仅导入可信发布源的可执行程序包。主仓库尚未发布 Android 包时保持当前版本并显示说明。

MAA Python 包不是 Python 解释器，也不是官方 ctypes 封装。它只包含 Android 桥接兼容接口，不修改 APK 中的 JNI、Python 依赖、Mower源码或原生控制器：

```
maa-python.json
maa.py
```

JSON 示例：

```json
{"kind":"mower-maa-python","format":1,"version":"1.0.0","channel":"stable","bridge_protocol":1,"min_apk":5,"sha256":"<maa.py 内容的 SHA256>"}
```

`maa.py` 导出 `Asst`，提供Mower使用的load/get_version/connect/set_instance_option/append_task/set_task_params/start/running/stop/get_tasks_list。客户端在下一次创建实例时加载新模块，正在运行的对象仍引用旧模块；失败时恢复内置接口，也可手动恢复。上传包是可执行代码，只导入可信来源；校验值用于完整性和兼容性判断，不是作者签名。

生成：`python3 scripts/package_maa_python.py`。CI 与 APK 一并发布该 ZIP，使用 GitHub asset digest，不额外发布 SHA256 文件。

官方 Android 核心导入接受 `MAAComponent-v版本-android-arm64.tar.gz`，内置版本的官方哈希写入 `runtime/mower_android/maa-component-lock.json`，其他版本读取官方 Release asset 的 digest。核心和资源在下一次服务启动时加载，不在运行中的MaaCore进程强行替换动态库。

MAA 核心和资源只走官方源，Android 暂时禁用 Mirror酱。`android-release.json` 与 APK、Python 包一起发布，供主仓库识别同渠道的兼容宿主，无需每次重新构建 APK。

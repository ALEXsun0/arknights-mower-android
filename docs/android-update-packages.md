# Android 更新包接口

APK 由本仓库构建发布。共享 WebUI 的软件更新检查 `ArkMowers/arknights-mower` 的 Android Mower 包，支持正式和公测渠道，不运行 Git/npm。APK 与 Mower 版本独立；需要更新宿主时从原生「APK 版本与更新」检查并进入本仓库 Release，使用 Android 安装器覆盖安装，保留原签名和用户数据。

Mower 包包含根清单 `mower-android.json` 和 `mower/` 程序目录。宿主验证 `runtime_api=1`、Python 3.12、Android ARM64、版本和归档路径，将程序保存到独立目录，原子切换激活记录。停止并重启服务生效；未能正常显示 WebUI 的启动在下次启动回退。可通过 WebUI「内置 Mower 恢复」清除激活记录。包不覆盖 `mower_android`、Python 依赖、MAA 或用户配置；不兼容环境需要新版 APK。

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

## 自动发行与组件版本

`Android APK` 工作流每 30 分钟检查 Mower 和 MAA 的公开 Release，也支持手动 `publish=true` 及 `repository_dispatch`。公测渠道选择按发布时间最新的正式或 alpha/beta/rc 发行，排除 dev/nightly。上游 Release 资产尚未上传完成、缺少 GitHub digest、Android 更新包不兼容或 ABI 检查失败时停止发布，下一轮重新检查。一次检测中同时变化的组件合并到同一完整发行。

构建前生成固定的 `distribution.json`，ARM64 运行环境和 APK 两个作业都使用同一份快照。Mower 使用主仓库 Android ZIP；MAA 使用官方 Android ARM64 组件，并检查 Mower 调用的 Python 接口及桥接调用的 MaaCore 符号。MAA 的 OCR 资源继续按现有方式转换与裁剪，APK 不附带构建工具或源码部署工具。当前 alpha.5 没有 Android 附件，首发暂沿用已在实机验证的兼容快照；后续 Mower 包需要包含 #1032 的可选组件更新能力，避免热更新后入口消失。

每次发行均提供 APK、Python 兼容 ZIP、`android-release.json` 和构建快照 `distribution.json`。先创建草稿、上传全部附件，再公开。版本号和 versionCode 自动递增；组件版本及 Python 适用区间自动写入 Release 正文。不附加独立 SHA256 文件，上传完整性使用 GitHub digest。

Python 包按接口代码内容确定身份，ZIP 时间戳固定；接口未变时保持同一版本及同一包内容，不因 Mower/MAA 或 APK 发版而提示更新。兼容区间从本次发行起算，下一次接口变更时将之前 Release 正文中的区间闭合，旧附件不被重写。适用区间是发行兼容策略，检查到的接口和符号匹配不等于所有 MAA 任务及设备都已实测。

原生设置仅保留 APK 版本检查；Mower、MAA、Python 的更新设置、Python 自动检查与热更新、拖拽/文件导入，以及恢复内置组件均在 WebUI。Python 自动检查默认开启，服务运行时每 6 小时检查一次，只对新接口内容提醒一次。下载后验证发布清单、包摘要、接口协议与最低 APK 版本，下一次创建 MAA 实例时生效，不打断现有任务。手动回退 Mower 需明确确认；组件回退不通过 APK 降级完成。

APK 单独记录宿主内容标识与最低宿主 versionCode。只刷新内置 Mower、MAA 或可热更新 Python 接口的 Release 不提示升级 APK；包含原生功能或宿主运行依赖变化才提示。即使最新 Release 只是资源刷新，仍会正确提示使用更旧宿主的用户补齐之前的宿主更新。

# Android 更新包接口

Android 是独立 APK 发行版本。APK由 `ALEXsun0/arknights-mower-android` 仓库构建，不在手机运行 Git/npm，不以桌面 Source code ZIP 替换应用。普通发行包只检查该仓库，软件更新沿用共用的 Release 页面，仅支持正式与公测渠道。

APK 导入会验证 SHA256（上传后按内容寻址）、包名、当前安装签名与 versionCode，不允许降级。在线下载额外验证 GitHub Release asset 的 SHA256。包上传后不会自动安装，须在手机上的系统安装器确认。普通 release 与以前测试包沿用同一签名，以保留用户数据。

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

生成：`python3 scripts/package_maa_python.py`。CI 与 APK 一并输出该 ZIP 和 SHA256。

官方 Android 核心导入接受 `MAAComponent-v版本-android-arm64.tar.gz`，内置版本的官方哈希写入 `runtime/mower_android/maa-component-lock.json`，其他版本读取官方 Release asset 的 digest。核心和资源在下一次服务启动时加载，不在运行中的MaaCore进程强行替换动态库。

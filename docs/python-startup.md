# Android 资源准备与 Python 启动

APK 首次安装或覆盖更新后，宿主先完成 Python 环境及 MAA 资源的校验、解压，分别显示原生进度条，然后才启动 Python。MAA 的大批文件不再通过 PRoot 展开，也不占用 Python 启动超时。

MAA 解压目录与当前组件分开。完整解压并校验包摘要后，宿主记录 APK 安装代次和组件摘要；Python 只接受匹配的准备结果，保留配置并沿用原来的组件切换事务。中断的解压不会替换正在使用的组件，下次启动重新准备。备份仍在首次成功运行 MAA 任务后清理。

旧 APK 没有原生准备结果时，Python 兼容路径仍可展开组件：校验改用分块读取，归档路径预先检查，避免每个文件重复解析父目录，目录创建也在同次解压中复用。

Python 初始化报告当前阶段；兼容解压路径按 5% 报告进度。宿主在 60 秒无新进度时终止启动，总时限为 10 分钟。停滞时启动日志包含最后阶段，超过 45 秒的初始化还输出 Python 堆栈；WebUI 开始监听后停止堆栈计时器。

## 自动任务

Android 服务进程完成初始化并成功绑定 WebUI 端口后，读取一次“启动后自动开启任务”设置。启用时，服务直接启动任务，无需打开网页；关闭时，本次进程不会自动启动任务。之后更改设置在下次启动服务时生效。刷新网页、关闭后重新打开网页或切换前后台均不重新触发自动任务。停止并重新启动 Android 服务会创建新进程，再次按设置执行。

启用自动资源更新时，服务先检查并等待资源安装，再启动自动任务。资源检查或安装失败时，沿用现有资源启动；暂停任务或关闭服务会取消尚未开始的自动任务，之后手动开始也不会重新激活已取消的启动请求。WebUI 端口绑定失败时不启动任务。

宿主对内置和热更新的 Mower 使用同一启动入口，兼容 `hot_update` 和 `resource_update` 资源设置。`/status` 的 `auto_start_handled` 与 `desktop_startup_handled` 在整个进程内保持为 `true`，分别阻止共享 WebUI 再次自动启动任务和安装资源；后一个字段沿用桌面端名称以兼容已有 WebUI。

此启动行为由 Android 宿主提供，需要安装包含本次修复的新版 APK。仅更新 Mower 程序包不会更新旧 APK 的启动入口。

Python 每次服务运行使用独立的应用私有缓存目录作为 PRoot `/dev/shm`，权限为 0700；进程退出后清理。该目录支持 `multiprocessing` 的 POSIX 信号量与跨进程队列，不使用 Android 系统共享目录，也不改变 Mower 数据目录。启动检查包含 PRoot 依赖 `libandroid-shmem.so`，缺失时在启动前提示覆盖安装 APK。

`python3 scripts/check_android_ipc.py --serial SERIAL` 在可调试 APK 中运行临时 spawn/Queue 检查，不启动 Mower 或游戏、不挂载用户数据。旧 APK 缺少共享库时，可用 `--native-deps DIRECTORY` 临时提供 `scripts/engine-assets.lock.json` 固定版本的 `libandroid-shmem.so`；该检查会报告额外依赖，不代表旧 APK 本身已经修复。

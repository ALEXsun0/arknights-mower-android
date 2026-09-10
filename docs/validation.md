# 开发验证记录

日期：2026-09-11。用户提供的 MuMu ARM64 模拟器，Android 12 / API 32，1920×1080。

以下结果来自独立 `android/` 工程、官方 Android ARM64 MaaCore v6.17.5 与 NCNN 转换资源；不是早期嵌入完整 Meow 原型的结果。

## 已通过

- Gradle 编译与 APK 覆盖安装；WebUI 生产构建通过。
- 10 项 Python 回归测试：桥接帧与回调处理、导入桌面设置后的 Android 参数接管、固定 WebUI 会话参数、官方 Android 资产选择、拒绝错误平台资源。
- Shizuku 标准用户服务绑定；Root 启动时降为 shell 身份，创建 1920×1080 后台显示器。
- 官方 Android 库加载、NCNN OCR 资源加载、原生控制器连接；游戏处于后台显示器。
- MAA StartUp 从游戏启动页进入已登录首页，最终回调含 10002 和 3；没有运行刷关、充值或抽卡任务。
- Android 本机 CPython 3.12.14 / aarch64，以应用 UID 运行，AndroidDevice 获取真实截图；Mower `detect_index_scene()` 返回 true。一次最终检查识别出 47 个 OCR 区域，约 10.52 秒。PRoot 显示的 UID 0 是访客映射，不是 Python 获得 Root。
- 停止服务后 Python、PRoot 和 Shizuku 后台子服务均退出；重启后重新加载官方核心并再次完成任务。
- 原版 WebUI 的 Android 设置面板正常显示，设备连接参数由安卓接管。安卓不展示桌面托盘选项。
- 正常／深夜主题与安卓顶部栏同步；桌面启动入口切换到相应图标。两种图标共用 Mower 原 PNG，仅背景不同：正常白色、深夜 #18181c。
- HTTP 未认证配置请求返回 401；带令牌访问建立 HttpOnly cookie 后，静态 JS 可加载；不匹配来源的写请求返回 403。
- WebSocket 未认证握手返回 401，携带有效 cookie 返回 101。
- WebUI 官方更新检查实际访问 GitHub，选中 `MAAComponent-v6.17.5-android-arm64.tar.gz`；版本、平台 android、架构 arm64 显示正确。
- 使用提供的完整官方压缩包进行 Docker 内离线安装集成测试：真实流式 SHA-256 校验、解压、pnnx 模型转换、组件打包与替换；旧版备份、用户 config 和 Python 适配器得到保留。错误 SHA-256 被拒绝且不改变已安装版本。这项测试替换了远程版本发现和下载传输，保留实际安装逻辑。
- 开启局域网开关并重启后，服务监听所有网卡。认证 HTTP 和 WebSocket 已通过 ADB 转发验证。

## 测试中修复

- Root Shizuku 的显示器包名／UID 校验问题。
- 官方组件缺少 Android 核心所需 NCNN 模型：从该组件附带的 ONNX 转换，并在更新时重新准备。
- 动态更新目录中的原生控制器库需要预加载。
- GitHub asset.digest 中 SHA-256 的读取。
- Android 更新标题错误回退为 macOS；核心与资源更新文案均改为 Android。
- Android 12 捕获释放时 ImageReader 回调与销毁之间的死锁。

## 复现命令

```sh
docker run --rm --platform linux/arm64 -v "$PWD:/project" -w /project mower-android-runtime:dev python -m unittest discover -s tests -v
python3 scripts/smoke_android.py --serial DEVICE --launch --startup
python3 scripts/check_android_python.py --serial DEVICE
python3 scripts/check_android_web.py --serial DEVICE --update-check
```

离线更新安装测试：将官方包以只读方式挂载为 `/archive.tar.gz`，将项目挂载为 `/project`，设置 `PYTHONPATH=/project/runtime`，在一次性 Docker 容器中运行 `scripts/check_component_update.py`。该脚本固定验证 v6.17.5 官方包的 SHA-256。

## 尚未验证与限制

MuMu Wi-Fi 地址为 NAT 内部地址，电脑不能直接路由到该地址；不能将 ADB 转发测试称为真机局域网直连已通过。仍需物理手机测试无线调试 Shizuku、局域网浏览器和不同 ROM 的后台限制。

未进行多日基建排班、全部 MAA 任务、B 服、中文输入法、WebUI 文件导入导出、系统回收后的自动恢复或跨版本的在线整包升级长测。资源合并后的打包路径已实现，但未完成一次真实在线资源更新端到端测试。

服务内核和资源更新后必须手动停止并重新启动服务。当前 APK 为 debug 签名、targetSdk 28 的实验版本，尚非商店发行版。

测试凭证仅在内存或应用私有目录使用；日志和游戏截图存于 Git 忽略的 artifacts，不提交账号数据。

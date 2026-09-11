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

## GitHub Actions 构建

2026-09-11，提交 `a438bedeff7f5c1639f90386b8341b34a533ba62` 的[云端构建](https://github.com/ALEXsun0/arknights-mower-android/actions/runs/34543837481)完成以下检查：

- 私有仓库原生 `ubuntu-24.04-arm` runner 完成 WebUI 生产构建、Python 环境构建、10 项测试、官方 MAA v6.17.5 校验与 NCNN 模型准备、运行时打包。
- `ubuntu-24.04` x86_64 runner 校验两份运行时 ZIP，下载并校验 PRoot 依赖，使用 JDK 21、Android SDK 36、NDK 29 和固定开发签名编译成功。
- `apksigner verify` 通过，APK 证书 SHA-256 为 `ef5cdb073be86dc57c7e0a40aff7aef3d6cbdde2b69d63365b83d7f486205cd7`，与现有本地实验版相同。工作流在上传前强制核对该指纹。
- 最终 Artifact 包含 APK 与 SHA-256 文件，约 580 MiB，保留 7 天。此次验证覆盖云端构建链；前述模拟器运行结果来自本地构建的同一应用源码。

首次 CI 编译成功后，下载核对发现默认签名路径未使用预期密钥；该次不兼容的 APK Artifact 已删除。现已通过 `MOWER_DEBUG_KEYSTORE_PATH` 显式指定签名文件，并增加上传前的证书校验。

## 测试中修复

### 实机 CI WebUI 白屏

2026-09-11，在用户的 Android 13 实机上确认：后台服务与官方 MAA 正常，局域网 HTTP 和 WebSocket 可达，但浏览器仅显示侧栏，主页面未渲染。

原因是本地未跟踪的 `runtime/ui/.env` 未进入 CI；打包结果将 API 地址编译成 `undefined/conf`、`undefined/shop` 等，初始化请求返回 404。之前的接口检查与签名验证没有覆盖浏览器实际渲染，这两项通过不能说明 WebUI 可用。

`0.1.0-dev.3` 将生产构建 API 基地址固定为空字符串，使用当前页面所在的手机地址；开发服务器配置保持原样。新增 `scripts/check_webui_build.py` 检查入口资源与主页面初始化接口地址，并接入本地构建和 CI。该检查已针对旧 CI APK 验证可复现失败，修复构建通过。

使用本地修正版前端、只转发读取请求到同一实机的验证代理，浏览器已显示日志区、任务表、“开始执行”和“新增任务”；原白屏页面的 404 不再出现。验证代理拒绝写请求，因此不用于确认设置保存和任务执行。此结果不代表实机已安装新版 APK。

随后，[修复 CI](https://github.com/ALEXsun0/arknights-mower-android/actions/runs/34545330848)通过，下载其 APK 并再次校验 SHA-256、签名和包内 WebUI 后，通过无线 ADB 覆盖安装到三星 SM-G9880（Android 13）。已确认 versionCode 3 / versionName `0.1.0-dev.3`，手机安装的运行时摘要与 CI 包一致。直接访问手机局域网地址的浏览器已显示日志、任务表和执行按钮；手机屏幕也已显示自动专精页面。HTTP Cookie、WebSocket 认证和 Android 设置接管检查通过。原有四个配置文件均保留，排班、周计划和状态文件摘要不变；`conf.yml` 随正常页面初始化和会话更新重新保存。此次没有启动排班或游戏任务。

### 其他已修复问题

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

MuMu Wi-Fi 地址为 NAT 内部地址，电脑不能直接路由到该地址；模拟器的 ADB 转发测试与实机直连测试分别记录。三星 SM-G9880 / Android 13 已验证无线 ADB 安装、手机 WebView 和局域网浏览器基本显示；不同 ROM 的后台限制及实机长期调度仍需验证。

未进行多日基建排班、全部 MAA 任务、B 服、中文输入法、WebUI 文件导入导出、系统回收后的自动恢复或跨版本的在线整包升级长测。资源合并后的打包路径已实现，但未完成一次真实在线资源更新端到端测试。

服务内核和资源更新后必须手动停止并重新启动服务。当前 APK 为 debug 签名、targetSdk 28 的实验版本，尚非商店发行版。

测试凭证仅在内存或应用私有目录使用；日志和游戏截图存于 Git 忽略的 artifacts，不提交账号数据。

## 0.1.0 正式 APK（2026-09-11）

- APK `versionCode=8`，Release 构建、完整 `lintRelease` 通过；保留原签名，可覆盖安装。
- 本地 APK 373,099,133 字节（约 355.8 MiB），旧 APK 608,553,366 字节（约 580.4 MiB），减少 38.7%。CI 同版约 355.7 MiB。
- Python 环境从约 344 MiB 降至 176 MiB，采用整包 XZ；MAA 从约 234 MiB 降至 177 MiB，保留所有地区资源和原生库，去除已经转换的重复 OCR ONNX。
- 校验运行包 13,371 个文件及 MAA ZIP 的全部 CRC。默认 OCR 模型、WebUI、宿主和 Mower 仍在包内。
- 23 项宿主测试、43 项前端测试通过；生产 WebUI 构建与同源 API 检查通过。
- CI 34555626123 全部通过，实际生成并核对 CI APK 的 digest 与固定签名。
- 三星 SM-G9880 / Android 13 实机覆盖安装，完成 XZ 首次解压。真实局域网浏览器显示原版 WebUI、Mower 版本和 Release 更新入口。
- 用仅添加不可见验证标记的私有 Mower 测试包检查：预览不激活、确认后暂存、服务重启后从新程序目录加载。原生「恢复内置 Mower」后重启，验证标记消失；测试包不作为 Release 资产发布。
- 实机拒绝开发渠道与 Android Mirror 请求。重新导入官方 MAA v6.17.5，生成精简 NCNN 组件后重启，通过真实 `AsstLoadResource` 和 Android 控制器连接。
- 未以本次检查代替多日排班、所有 MAA 任务或其他品牌设备验证。


## 0.2.0 系统功能候选与最新 alpha（2026-09-11）

- 源码同步到 alpha `eaa05aa1`，包含已合并的 #1029，打包提交 `d8cbb41b` 与主仓库合并后的 alpha `22a73d44` 文件树完全一致。最新三项上游修复的六组 Python 测试 71 项通过；配置/宿舍候补/排班编辑前端测试 21 项通过，生产 WebUI 构建及同源检查通过。
- 此前 alpha `a991786a` 的 161 项 Python 测试通过；全量前端 250 项中 249 项首轮通过，主题检查因并发负载超过 5 秒而超时，单 worker 重跑该文件 16 项全部通过。
- 原生 84 项测试与 Release lint 通过，覆盖连接超时、父任务取消、重试、触点映射、录制序列化/回放、帧率判断和任务收尾状态。宿主全套 26 项测试通过，其中设备异常邮件使用模拟 sender 的 3 项测试，没有实际发邮件。
- 中间候选 APK 已覆盖安装三星 SM-G9880 / Android 13，保留数据。自有启动器成功拉起后台/Python，未再次打开 Shizuku 管理器。连接超时能恢复启动按钮的失败路径在前一中间包验证。
- 手机 WebView 与局域网 Playwright 页面实际可见，包括 alpha 配置备份入口；Android 隐藏 WebUI 收尾选择。官方 Android MAA v6.17.5 完成资源加载与控制器连接。
- 实机原生预览显示约 60 FPS，画中画显示游戏画面，系统记录为 pinned 模式；没有开始游戏任务或基建调度。
- 原生收尾入口改为原有 Mower 配置的三选项，WebUI 自动保存省略 Android 隐藏字段；其他三个平台仍提交原字段。最终 APK 已确认两个入口在原生设置顶部可见；收尾三选项可打开，但保存检查期间手机界面变化，未把该次检查计为通过。
- 解锁录制尚未完成实机验证：自动查找入口的滚动重试已停止。不会把代码实现或编码测试当成真实解锁验证。硬件熄屏、720p 实机坐标、Root/Sui、开机恢复、厂商实时通知和长期排班也尚未实测。

录制/PIN 不进入截图证据、配置备份或诊断 ZIP；所有手机会话凭证和测试输出位于 Git 忽略的 artifacts。

### 最终本地包覆盖安装（2026-09-11）

- Release assemble 和 lint 通过；APK 为 374,106,847 字节（约 356.8 MiB），versionCode 10 / versionName 0.2.0。固定签名验证通过，APK 全部 CRC、内置 liblauncher.so 与运行环境摘要均已核对。
- XZ 运行环境解压校验 13,375 个文件，打包 revision 为 d8cbb41b；与上游已合并 alpha 22a73d44 的文件树一致。
- 无线 ADB 覆盖安装三星 SM-G9880 成功。首次解压完成后，真实手机 WebView 显示从 49484c0 更新到 4.1.6-alpha.5+d8cbb41 的通知及 Mower 页面。认证接口报告 Android / MAA v6.17.5，调度为 stopped。
- 原生设置首屏可见局域网连接、诊断日志、任务结束后、解锁方式与操作录制入口。录制确认对话框可打开并取消，本次没有开始录制、锁屏或执行游戏任务。
- 收尾保存和最终局域网浏览器检查尚未记为通过：手机页面变化导致自动定位失败，浏览器旧测试会话也留有文件选择状态；已停止界面操作，没有以 HTTP 成功代替最终浏览器渲染验证。
- 本地 APK、MAA Python 1.0.0 ZIP 和 android-release.json 已准备；没有补传主仓库 alpha.5 的 Release 附件。

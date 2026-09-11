# Arknights Mower Android

在安卓本机运行 Mower Python 后端，共用原版 Mower WebUI，通过 Shizuku 创建后台明日方舟显示器，并调用 **MAA 官方 Android ARM64 核心**。无需电脑持续连接。

## 架构

```text
手机 WebView / 局域网浏览器
        ↓ 带访问凭证的 HTTP / WebSocket
安卓应用 UID 下的 Python + 原版 Mower（PRoot / Linux 用户态）
        ↓ Asst Python 兼容接口 / 本机认证桥接
Shizuku 后台服务：官方 Android MaaCore + 显示器、截图、触控
        ↓
1920×1080 后台明日方舟
```

Android `.so` 使用 Bionic，不能由 Linux 用户态 Python 直接通过 ctypes 加载。因此 Python 适配器保留 Mower 使用的 Asst 调用方式，由 Android 原生服务加载官方库并转发回调。它实现的是当前 Mower 所需接口，并非官方 Python API 的完整替代。

`MAAComponent-v6.17.5-android-arm64.tar.gz` 包含核心、原生控制器和资源，但没有 Python 绑定。该版 Android 核心使用 NCNN OCR，而发布包只有 ONNX 模型；构建和更新时使用固定版本 pnnx，将同一官方包中的模型转换为 FP32 NCNN。核心二进制保持原样。版本、来源和 SHA-256 见 [UPSTREAM.json](UPSTREAM.json) 与 [组件锁定文件](scripts/maa-android.lock.json)。

后台游戏能力参考 [MAA-Meow](https://github.com/Aliothmoon/MAA-Meow)，基于其中的后台显示、截图、预览与输入代码适配；本项目独立运行，并保留相关来源与许可证。

## 使用

1. 安装 ARM64 APK，建议预留至少 4 GB 空间。安装并启动 Shizuku，可通过无线调试或 Root 启动。
2. 点击顶部「启动服务」并授予 Shizuku 权限。首次启动需要解压 Python 和 MAA 环境。
3. 点击「游戏画面」进入后台显示器，登录明日方舟。进入手动预览会先暂停 Mower 和 MAA，返回后按需恢复调度。
4. 在原版 WebUI 中导入或编辑排班与任务。安卓自动管理 ADB、模拟器、截图方式和 MAA 路径；导入桌面配置会保留任务并覆盖设备连接参数。
5. 使用「停止服务」或常驻通知停止运行；「软件设置 → 诊断日志」显示启动错误。

界面和游戏预览保持横屏。图标直接使用 Mower 原图；正常模式为白色背景，深夜模式为深色背景，图案不变。图标随 WebUI 主题切换，桌面启动器可能需要刷新缓存。安卓设置隐藏桌面托盘选项。

## 局域网 WebUI

在「软件设置 → 局域网连接」中开启访问，停止并重新启动服务，再复制完整地址。其他设备连接同一局域网，通过该地址访问。访问令牌每次启动更新，页面静态资源和 WebSocket 同样需要认证。

默认仅监听本机。启用后监听所有网卡；局域网浏览器与手机共享同一个后端和任务状态。不要公开带令牌的地址。MuMu 使用虚拟网卡，其他设备能否直连取决于模拟器网络路由；已在三星 SM-G9880 / Android 13 上验证实际 Wi-Fi 访问及 WebUI 显示，其他设备仍需验证。

## MAA 更新

WebUI 保留 Mower 的官方 GitHub 稳定版／公测版更新入口，安卓版仅选择 `MAAComponent-…-android-arm64.tar.gz`，校验官方 SHA-256、解压并准备 NCNN 模型，再生成 Android 服务所用的组件包。Python 兼容接口位于应用运行时，不会被 MAA 包覆盖。

更新完成后必须停止并重新启动服务，才能释放已加载的原生库并启用新版。资源更新也会重新准备 OCR 模型并提示重启。更新转换可能持续数分钟；请保证空间和网络。Mower 本体使用主仓库的 Android 更新包，停止并重启服务后生效，无需更新 APK。Android 暂时禁用 Mirror酱，核心与资源均从官方获取。

## 构建

### GitHub Actions

推送到 `main` 会自动构建，也可以在 [Actions → Android APK](https://github.com/ALEXsun0/arknights-mower-android/actions/workflows/android-apk.yml) 手动构建。推送 `v*` 标签会在所有构建通过后自动发布 Release；带预发行标记的标签会发布为公测版。

每期 [Release](https://github.com/ALEXsun0/arknights-mower-android/releases) 同时提供三个配套附件：

| 附件 | 用途 |
| --- | --- |
| `mower-android-arm64.apk` | 完整 Android 应用，已内置当期运行时和 MAA |
| `mower-maa-python-版本.zip` | 本次 APK 对应的 Android MAA Python 兼容接口 |
| `android-release.json` | 供 Mower 主仓库自动附带兼容 APK/接口的版本与宿主协议清单 |

附件校验值使用 GitHub 自带的 SHA256 digest，不额外发布 `.sha256` 文件。CI 内部继续验证运行时、官方核心和 APK 签名。

CI 先在 ARM64 runner 构建 Python、WebUI 和官方 MAA 组件，再由 x86_64 runner 使用 Android SDK/NDK 打包签名 APK。临时安装包产物保留 7 天，运行时中间产物保留 1 天。

仓库 Secret `MOWER_DEBUG_KEYSTORE_BASE64` 保存固定的开发签名，当前与已安装的实验版相同，因此 CI APK 可以覆盖安装。CI 显式指定签名文件，并在上传前核对 APK 签名证书指纹。密钥只在签名步骤写入临时 runner，不进入源码、日志或产物；不要删除或更换，否则旧版不能直接覆盖升级。正式 APK 沿用此固定证书用于 GitHub 分发，不更换现有签名。首次在其他仓库使用时，应先将自己的 Android debug keystore 以 Base64 写入该 Secret，并更新工作流中的公开证书指纹。

### 本地构建

需要 JDK 21、Android SDK 36、NDK `29.0.13113456`、CMake `3.22.1`、Node.js 22+、Python 3 和 Docker（ARM64）。在 `android/local.properties` 设置 `sdk.dir`，或配置 `ANDROID_HOME`：

```sh
bash scripts/build.sh
```

输出：`artifacts/mower-android-arm64-debug.apk`，使用本机 Android debug 签名。Python 依赖快照见 `scripts/requirements-android.lock`；PRoot 及依赖的下载校验见 `scripts/engine-assets.lock.json`。

- `android/`：独立安卓入口、前台服务、官方核心加载器与背景显示代码。
- `runtime/`：固定版本的 Mower、原 WebUI 和 `mower_android/` 平台适配。
- `scripts/`：官方组件下载、模型准备、Python 运行包及 APK 构建、设备验证。
- `tests/`：桥接、设置接管与更新测试。
- [验证记录](docs/validation.md)：实际通过的检查与未验证范围。

## 限制与许可证

这是 Android 独立发行版，仅支持 ARM64。APK 版本独立于内置 Mower；0.1.0 内置 Mower 4.1.6-alpha.5 与 Android 兼容补丁。为运行内置 PRoot，当前 targetSdk 为 28、compileSdk 为 36，尚不适合作为 Play 商店发行包。需要允许后台运行；系统杀进程后的自动恢复、多日排班、不同品牌实机及 B 服尚未充分验证。手动文本输入暂限 ASCII。

整体采用 AGPL-3.0，保留 Mower 的 MIT 许可、后台游戏代码及各组件声明。参见 [LICENSE](LICENSE)、[runtime/LICENSE](runtime/LICENSE)、[第三方代码声明](docs/licenses/Meow-THIRD-PARTY-NOTICES.md) 和 [运行时声明](docs/third-party-runtime.md)。个人配置、凭证、游戏截图和日志不会提交到 Git。

### 后台与系统设置

手机顶栏「软件设置」可管理游戏静音、预览声音、后台窗口恢复、自动唤醒/无密码锁屏、前台亮屏和 CPU 保活。这些设置直接保存在手机，无需启动 Python 服务，不占用 WebUI 页面。安全锁屏需在手机上认证；游戏识别保持 1920×1080。

后台与系统的适配结果与未引入功能见 [后台与系统功能核对](docs/meow-system-feature-review.md)。

### Android 独立发行与导入

Android APK 由本仓库 CI 构建，WebUI 只提供正式版、公测版；普通发行包隐藏仓库与源码版本管理，与其他平台共用 Release 更新页面，仍不接受开发版更新。

「Mower 设置 → 软件更新」支持选择文件或全局拖拽：

- 主仓库 `arknights-mower_版本_android_arm64.zip`：校验兼容协议，独立保存版本并在重启服务后切换；启动失败可回退，原生设置可恢复内置 Mower。APK 更新请使用「软件设置 → APK 版本与更新」。
- 官方 `MAAComponent-v版本-android-arm64.tar.gz`：校验官方 SHA256，完成资源模型转换后暂存，重启服务后生效。内置版本可离线导入；其他版本需读取 GitHub 的校验值。
- 本仓库生成的 `mower-maa-python-版本.zip`：更新 Android 兼容 MAA Python 接口，校验协议、最低 APK 版本、完整性与接口结构；当前实例不变，新实例使用新接口，失败时回退内置接口。官方 ctypes Python 包不能直接用于 Android 桥接。

MAA 核心默认随 APK 提供，也可通过官方组件包独立更新。Android 暂时禁用 Mirror酱；MAA 核心与资源均使用官方来源。主仓库每次发布可从本仓库 Release 自动附带兼容的 APK 与接口 ZIP，不要求 APK 跟随 Mower 发版。

### APK 体积

运行环境使用整包 XZ 压缩；移除运行期不需要的 Git、头文件、测试、字节码缓存和当前 Mower 未使用的 OCR beta 模型。MAA 保留所有地区资源，将 OCR 转为 Android 所用的 NCNN 后不再重复打包对应 ONNX；其他 ONNX 推理模型与原生库保留。APK 仍内置 Python、Mower、MAA，首次启动无需额外下载运行环境。

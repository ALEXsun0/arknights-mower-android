# Mower Android

在安卓本机运行 Mower Python 后端，共用原版 Mower WebUI，通过 Shizuku 创建后台明日方舟显示器，并调用 **MAA 官方 Android ARM64 核心**。无需安装 Meow，也无需电脑持续连接。

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

完整 Meow 应用已经移除，仅保留背景显示、截图、预览、输入及相关 Android 接口实现，保留来源和许可证。不向 Meow 提交 PR。

## 使用

1. 安装 ARM64 APK，建议预留至少 4 GB 空间。安装并启动 Shizuku，可通过无线调试或 Root 启动。
2. 点击顶部「启动服务」并授予 Shizuku 权限。首次启动需要解压 Python 和 MAA 环境。
3. 点击「游戏画面」进入后台显示器，登录明日方舟。进入手动预览会先暂停 Mower 和 MAA，返回后按需恢复调度。
4. 在原版 WebUI 中导入或编辑排班与任务。安卓自动管理 ADB、模拟器、截图方式和 MAA 路径；导入桌面配置会保留任务并覆盖设备连接参数。
5. 使用「停止服务」或常驻通知停止运行；「诊断日志」显示启动错误。

界面和游戏预览保持横屏。图标直接使用 Mower 原图；正常模式为白色背景，深夜模式为深色背景，图案不变。图标随 WebUI 主题切换，桌面启动器可能需要刷新缓存。安卓设置隐藏桌面托盘选项。

## 局域网 WebUI

在顶部「局域网」中开启访问，停止并重新启动服务，再复制完整地址。其他设备连接同一局域网，通过该地址访问。访问令牌每次启动更新，页面静态资源和 WebSocket 同样需要认证。

默认仅监听本机。启用后监听所有网卡；局域网浏览器与手机共享同一个后端和任务状态。不要公开带令牌的地址。MuMu 使用虚拟网卡，其他设备能否直连取决于模拟器网络路由；手机上的实际 Wi-Fi 访问还需要真机验证。

## MAA 更新

WebUI 保留 Mower 的官方 GitHub 稳定版／公测版更新入口，安卓版仅选择 `MAAComponent-…-android-arm64.tar.gz`，校验官方 SHA-256、解压并准备 NCNN 模型，再生成 Android 服务所用的组件包。Python 兼容接口位于应用运行时，不会被 MAA 包覆盖。

更新完成后必须停止并重新启动服务，才能释放已加载的原生库并启用新版。资源更新也会重新准备 OCR 模型并提示重启。更新转换可能持续数分钟；请保证空间和网络。应用自身更新通过新版 APK。

## 构建

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

这是实验版，仅支持 ARM64。为运行内置 PRoot，当前 targetSdk 为 28、compileSdk 为 36，尚不适合作为 Play 商店发行包。需要允许后台运行；系统杀进程后的自动恢复、多日排班、物理手机及 B 服尚未充分验证。手动文本输入暂限 ASCII。

整体采用 AGPL-3.0，保留 Mower 的 MIT 许可、Meow 及各组件声明。参见 [LICENSE](LICENSE)、[runtime/LICENSE](runtime/LICENSE)、[保留的 Meow 声明](docs/licenses/Meow-THIRD-PARTY-NOTICES.md) 和 [运行时声明](docs/third-party-runtime.md)。个人配置、凭证、游戏截图和日志不会提交到 Git。

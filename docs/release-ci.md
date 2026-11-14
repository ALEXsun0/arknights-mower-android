# Android 独立发行

默认分支 main 是 Mower 主仓通知发行的宿主源码来源。手动构建使用 Run workflow 选定的分支；开发版使用 MowerRelease 已发布的 Nightly Android 完整运行包，普通版使用主仓已公开的正式／公测发行。MAA 使用官方已公开的正式／公测组件。

工作流只接受 Mower 主仓主动发送的 `repository_dispatch`（`mower-release`）和手动 `workflow_dispatch`。没有定时轮询、MAA 通知或分支推送入口。Mower 新版必须已有 Android 完整更新 ZIP，MAA 必须已有官方 Android ARM64 包和 GitHub 摘要。附件尚未就绪时终止；需要重新通知或手动重试。普通版以此前普通发行判断组件变更，开发版发布不会触发普通版重复发布。

收到有效触发且需要构建时，依次构建 ARM64 Python 运行环境、校验 Python 兼容接口、组装并校验沿用原签名的 Release APK。APK、Python 兼容 ZIP、发行元数据上传完毕后才公开 Release。只刷新内置组件时保留宿主更新标识，避免要求现有用户更新 APK。

Android 宿主回归测试由 [Android host tests](../.github/workflows/android-host-tests.yml) 在 PR、main 推送或手动触发时运行。Python 和 WebUI 测试使用该提交中保存的源码与依赖，不执行发行准备，也不下载替换为最新 Mower。这些测试用于验证 Android 仓库的改动；依赖上游内部函数、调度状态或测试替身的断言不作为新版 Mower 发行的阻断条件。

[Android APK](../.github/workflows/android-apk.yml) 和[本地构建脚本](../scripts/build.sh) 在应用上游发行快照后不运行上述回归测试，只检查实际包的兼容性与构建产物：更新包协议及摘要、Mower 调用的 MAA Python 接口、Android 使用的 MAA 原生符号、WebUI 组件更新支持及同源 API 地址。云端仍执行 Android 原生单元测试、Release lint、运行包摘要与 APK 签名校验。这些检查失败仍会停止构建；它们不代表已验证上游全部业务行为或手机实机任务。

Mower 主仓库只发布 Android 热更新 ZIP，正文链接到本仓库 Releases，不再转存 APK / Python 包。Mower 主仓主动通知需要有目标 Android 仓库权限的 `ANDROID_RELEASE_TOKEN`；缺少该 Secret 时使用 Android 手动入口。验证时运行 Android APK 的 workflow_dispatch 并保持 publish=false；不会公开新版本。

手动发布开发版时选择 `mower_channel=dev`、`publish=true`；`publish=false` 仅验证构建。开发版标记为 Pre-release、保留 Latest，沿用固定签名。普通版和开发版共享递增 versionCode。

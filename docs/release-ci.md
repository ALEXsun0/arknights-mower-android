# Android 独立发行

默认分支 main 是自动发行的宿主源码来源。已验证的原生修复必须进入 main，否则定时构建会继续使用旧宿主。Mower 和 MAA 使用上游已公开的正式／公测发行，不以主仓库 alpha HEAD 冒充发行版。

每小时第 17、47 分钟检查两边的公开 Release，GitHub 调度可能延迟。Mower 新版必须已有 Android 热更新 ZIP，MAA 必须已有官方 Android ARM64 包和 GitHub 摘要。附件尚未就绪时检查失败，后续调度重试；不把旧 Mower 标为新版本。两端组件未变化且接口内容未变化时不重复发布。

检测到上游新发行后依次构建 ARM64 Python 运行环境、校验 Python 兼容接口、组装并校验沿用原签名的 Release APK。APK、Python 兼容 ZIP、发行元数据上传完毕后才公开 Release。只刷新内置组件时保留宿主更新标识，避免要求现有用户更新 APK。

Mower 主仓库只发布 Android 热更新 ZIP，正文链接到本仓库 Releases，不再转存 APK / Python 包。无需主仓库配置跨仓库写权限。验证时运行 Android APK 的 workflow_dispatch 并保持 publish=false；不会公开新版本。

# 竖屏适配与断网诊断（2026-09-13）

## 实现

原生主页、软件设置和自动解锁设置解除强制横屏，并由现有 Activity 处理屏幕尺寸变化，保留 WebView、输入草稿和在途操作。主工具栏与欢迎步骤、设置快捷入口在窄屏重排；欢迎页减少左右留白，设置标题为按钮预留空间。WebView 使用设备宽度视口，并将 minimum-scale 设为 1，防止焦点输入框旋转后页面自动缩小；仍保留 Mower 自身界面缩放与用户放大能力，避让左右安全区域。游戏预览仍为横屏，后台游戏识别尺寸不变。

未新增 Android 专用 WebUI；复用 Mower 的 mobile 分支和底部导航。焦点输入框跨方向旋转时存在 Chromium 自动缩小行为，仅恢复默认宽视口设置不足以解决，因此在宿主页面加载完成时补充 minimum-scale。

新增 network.txt：记录服务启动/结束、系统默认网络连接/断开、Wi-Fi/移动网络/VPN、系统连通性验证，以及 Mower UID 的 blocked 状态。仅网络状态变更时记录，连续相同事件去重，历史约 32 KiB 后裁剪；不收集 SSID、地址、请求或 Token。导出同时包含当时亮屏、Doze、省电与流量节省状态。这个诊断不能证明游戏自身的网络连接或后台流量权限。

## 打包

本地 Release APK 0.2.5 / versionCode 25，尚未发布 GitHub Release。保留既有发行签名，非 debuggable、非 testOnly。

内置 Mower 快照 `1c5b788a0cd1fad7ede5bd9e44880335d0c59d07`，基于 alpha `dafe02b2`（含 #1046、#1047、#1048），保留 #1037 选人优化。MAA 6.17.5、Python 兼容接口 1.0.0 未变。要求与上一版相同，复用已构建的 ARM64 Python 依赖环境并重新打包 Mower/WebUI。

## 验证

- Android 157 项单元测试通过，Release Lint 与正式 APK 构建通过。
- 合并快照的确认框恢复、房间滚动、当前页选人、列表稳定性 53 项回归通过。
- 生产 WebUI 构建与同源 API 检查通过。
- MuMu Android 12 ARM64，1080×1920，440 dpi（约 393 dp 宽）：主页、原生设置及原版 WebUI 手机底部导航可见。
- 最终包在数字输入框保持焦点时完成竖→横→竖；字号不再缩小，仍在 Mower 设置页，输入值 500 和焦点保留。前后 ActivityRecord 均为 c5a3c45，未重建；输入类型为 0x6002（数字）。
- 原生局域网弹窗填写未保存端口 58001，旋转后弹窗与草稿保留，最终取消未保存。
- 使用 Shizuku 启动服务成功；运行目录的 git_revision 已核对为本次整合快照。Root 后端试启动未就绪，不列为已通过路径。
- 模拟器 Wi-Fi 断开/恢复测试记录了默认网络丢失、重新可用及 validated=false→true；Mower 服务保持运行。MuMu 的 ADB 通道也依赖该网络，恢复时使用模拟器界面重新开启 WLAN。无游戏任务运行。未测试荣耀实机的断网复现，也未执行锁屏或解锁录制。

## 用户日志的边界

日志来自荣耀 REP-AN00 / Android 15 / APK 0.2.4，未包含 #1046。

00:29–00:34 反复停在 Scene 8 二次确认，00:33:57 触发原 Recognizer.check_freeze()；#1046 针对弹窗识别及无界房间滚动的恢复路径。重复同一弹窗不等于反复发生多次互联网断线。

00:28、01:20、02:31 的 swipe Check failed 或 screencap sock.recv TimeoutError 属于本机 Android bridge 输入/截图链路，不能据此判断游戏服务器断线。02:02 的确认对话框也有成功识别记录。

background.log 存在 binderDied、DeadObjectException 和 DeadSystemException，但缺时间戳，无法判定是手机重启收尾还是系统服务故障，不能归因到 Mower。

另外三次“订单/设施倒计时识别失败”实际发生于宿舍，心情读数 23.92258 接近满格、计时 OCR 为空。缺原始画面，暂未扩大满心情判断阈值。

现有宿主代码未发现修改游戏网络、切换 Wi-Fi 或强制绑定网络的路径；Python DNS 同步只写自身运行环境。#1046 改善断网弹窗后的恢复，不代表解决网络掉线原因。下次需结合新 network.txt 与游戏弹窗时刻，区分系统网络切换、应用后台限制和游戏连接本身。

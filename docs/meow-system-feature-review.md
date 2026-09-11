# MAA-Meow 后台与系统功能核对（2026-09-11）

来源：[MAA-Meow 发布记录](https://github.com/Aliothmoon/MAA-Meow/releases)，共 73 条公开 Release，从 v0.0.4-alpha 到 v0.21.4，包含预发布版本。源码基线为 [`042e21b6a7c826f1dcd4ecef0004e5288f7601f2`](https://github.com/Aliothmoon/MAA-Meow/tree/042e21b6a7c826f1dcd4ecef0004e5288f7601f2)。该基线与先前保留的 Meow 来源一致，本次主要补齐未适配的能力。

本文核对公开 Release 正文与该基线相关源码；早期仅给出 compare 链接的 Release 没有逐条描述，不能据此声称逐个审查了所有历史提交或所有 ROM 的行为。附录保留相关发布条目的原文和永久链接。

## 功能映射

以下“已实现”指源码及本地构建；设备验证记录见 [validation.md](validation.md)，不代表全部 ROM 已实测。

| 范围 | 当前实现 | 验证或差异 |
| --- | --- | --- |
| 静音与恢复 | 单独保存并恢复游戏 PLAY_AUDIO；启动静音、预览恢复声音、服务退出恢复、失败记录重试 | 保留目标游戏原模式，不重置整包 AppOps；厂商是否实际静音需要听音验证 |
| 唤醒和解锁 | 滑动、数字 PIN、手动操作录制；Doze 唤醒等待、录制/测试、失败单次退出 | 录制最多 90 秒，支持点击/长按/滑动；PIN/轨迹以 AndroidKeyStore AES-GCM 加密存于 noBackupFilesDir，不进 WebUI 或导出 |
| 熄屏与屏保 | 黑色悬浮屏保、移动提示、长按退出、可选硬件熄屏、前台/退出恢复；保留任务前已亮屏状态 | 熄屏失败保留屏保，恢复失败保留待恢复标记；不同厂商显示服务需实测 |
| 后台游戏 | 720p/1080p、原生横屏、强制全屏、虚拟显示存活检查、游戏漂移自动拉回 | Mower 截图统一 1080p，输入映射到真实分辨率；MAA 使用实际分辨率 |
| 预览与输入 | 16:9 预览、系统画中画、可选自动进入、多点触控、自动/手动触点显示 | 默认仅观察；点“手动操作”才暂停任务；主屏解锁触点不进入游戏预览 |
| 连接与身份 | 自有启动器直连 AIDL、Shizuku/Sui、可选 Root、Android 14+ Root 身份处理、20 秒连接超时、Binder 死亡清理 | 避免依赖 Shizuku 管理器 UserService provider；不重新发送结果未知的输入 |
| 保活与定时 | CPU 唤醒锁、精确闹钟及无权限回退、电池权限入口、可选开机恢复服务 | 复用 Mower `/status` 的下一任务时间，Android 不建立第二套排班；冷启动是否启任务由 Mower 自身配置决定 |
| 收尾 | 原生三选项读写 Mower 原收尾配置（无操作/返回首页/退出游戏），由 Mower 执行；安卓版 WebUI 隐藏入口且不回写旧值。原生另提供可选锁屏和原亮屏状态保留 | 手动停止、冷启动不会误触发收尾；监测线程停止后再释放引擎 |
| 监控 | FPS、持续低帧率提醒、后台连接中断/游戏退出时停止任务，原生异常通知 | 不把静止画面当掉线，不复制 Meow 作战队列；每 5 秒采样 |
| 通知 | 常驻运行状态/FPS/下一任务时间、停止服务与恢复屏幕按钮、异常/建议独立渠道、Android 实时通知权限入口 | 任务通知仍由 Mower 管理；设备异常可选择复用其邮件配置和等级（每类限频 60 秒）。小米超级岛及其授权机制尚未移植 |
| 日志 | 原生查看、ZIP 分享，运行日志取末尾 2 MiB，过滤访问令牌，排除配置和解锁记录 | 不导出全机日志；服务启动失败也可查看 |
| 界面 | Android 原生软件设置，白底/深夜背景，横屏安全区，局域网和日志统一入口 | 不加入共享 WebUI；不复制 Meow 成就、皮肤、任务编辑器 |
| 更新 | 最新 Mower alpha 加兼容 PR，官方 Android MAA、独立 Python 接口、Mower ZIP 热更新 | 不移植 Meow 的 MAA 更新器；APK 与 Mower/MAA 独立发版 |

## 原生界面变化

| 原来 | 现在 |
| --- | --- |
| 只提供简单唤醒/滑动解锁 | 设置页顶部提供收尾选择和独立“自动解锁”入口；选择方式、录制、测试、清除；设置 PIN 时禁止截屏 |
| 进入游戏预览即暂停任务 | 默认观察继续调度，手动输入需点击“手动操作” |
| 基础亮屏和后台选项 | 分组管理预览、监测、任务收尾、屏保和保活，依赖未开启时禁用对应开关 |
| 启动超时后仍显示运行 | 显示明确失败原因，清理后恢复启动按钮 |

## 实现边界

设置只存于 Android 应用私有存储，无 `/android/settings` 或 `/android/action` HTTP 接口。共享配置导入不覆盖设备设置。解锁录制/凭证只通过原生 Binder 传递，不进入 Mower 的网络控制接口、触点预览或日志压缩包。

代码保留来源与许可证，参考基线不变。任务调度、任务外部通知、MAA 配置及热更新继续复用 Mower。本文保留尚未移植和未实测项，不将源码对齐等同于所有设备功能验证完成。

## 发布记录摘录

以下从全部公开Release正文筛出后台/系统相关条目；不相关的MAA任务策略和作战资源条目省略。预发布与正式版可能重复，保留原版本归属。

### [v0.21.4](https://github.com/Aliothmoon/MAA-Meow/releases/tag/v0.21.4)

- 统一更换主题面板添加按钮圆角 @Aliothmoon
- 任务名缩短为四字「更换主题」 @Aliothmoon
- 定时任务时间选择器改用自绘滚轮 @Aliothmoon
- 新增更换游戏主题任务 @Aliothmoon
- 按设定时间开始定时任务倒计时 @Aliothmoon
- Doze 唤醒后 PIN 注入过早无法解锁的问题 @Aliothmoon
- 定时任务不触发与冷启动解锁方式错误 @Aliothmoon
- 掉线中止后定时任务不关闭游戏 @Aliothmoon

### [v0.21.3](https://github.com/Aliothmoon/MAA-Meow/releases/tag/v0.21.3)

- 定时任务时间选择器改用自绘滚轮 @Aliothmoon
- 新增更换游戏主题任务 @Aliothmoon
- 按设定时间开始定时任务倒计时 @Aliothmoon
- Doze 唤醒后 PIN 注入过早无法解锁的问题 @Aliothmoon
- 定时任务不触发与冷启动解锁方式错误 @Aliothmoon
- 掉线中止后定时任务不关闭游戏 @Aliothmoon

### [v0.21.1](https://github.com/Aliothmoon/MAA-Meow/releases/tag/v0.21.1)

- 游戏静音前二次确认并建议改用系统音量 @Aliothmoon
- 肉鸽默认主题改为傀影 @Aliothmoon
- 预览宿主切换只重建 EGLSurface @Aliothmoon

### [v0.21.0](https://github.com/Aliothmoon/MAA-Meow/releases/tag/v0.21.0)

- 后台任务监控增强：游戏帧率监控与低帧率提示、任务实况通知升级（小米超级岛）、掉线升级为错误通知
- 连接稳定性：Shizuku 直连 AIDL 重构修复连接超时，新增多点触控
- 后台模式游戏帧率监控与低帧率提示 @Aliothmoon
- 任务实况通知优化（小米超级岛 / 实时更新 / 通知栏） @Aliothmoon
- 掉线提示升级为错误等级并支持外部通知，掉线即停整条队列 @Aliothmoon
- 多点触控 @Aliothmoon
- 后台任务日志页新增导出日志压缩包入口 @Aliothmoon
- 定时解锁手势选项更名「手动录制」并前移至 PIN 之前 @Aliothmoon
- 重构 Shizuku Bootstrap 流程，服务绑定改为直连 AIDL，修复部分设备连接超时 @Aliothmoon
- 修复不同服务器切换后干员识别失败 @Aliothmoon
- 动画优化和跨页效果调整 @Aliothmoon
- 压缩自动战斗页签选择器高度，窄屏自动单列 @Aliothmoon

### [v0.20.1-beta.6](https://github.com/Aliothmoon/MAA-Meow/releases/tag/v0.20.1-beta.6)

- 掉线提示升级为错误等级并支持外部通知 @Aliothmoon
- 后台任务日志页增加导出日志压缩包入口 @Aliothmoon
- 压缩自动战斗页签选择器高度，窄屏自动单列 @Aliothmoon
- 后台模式游戏帧率监控与低帧率提示 @Aliothmoon
- 调整 systemBars 自由窗口区域 @Aliothmoon
- 外部通知的开关判断收敛为统一发送入口 @Aliothmoon

### [v0.20.1-beta.5](https://github.com/Aliothmoon/MAA-Meow/releases/tag/v0.20.1-beta.5)

- 多点触控 @Aliothmoon
- 动画优化和跨页效果调整 @Aliothmoon
- 开始唤醒账号切换提示补充单账号留空说明 @Aliothmoon
- 修订首启引导文案，移除启动服务步骤 @Aliothmoon
- 重构 Shizuku Bootstrap 流程 @Aliothmoon
- Shizuku 服务绑定改为直连 AIDL，修复部分设备连接超时 @Aliothmoon

### [v0.20.1-beta.4](https://github.com/Aliothmoon/MAA-Meow/releases/tag/v0.20.1-beta.4)

- 动画优化和跨页效果调整 @Aliothmoon
- 开始唤醒账号切换提示补充单账号留空说明 @Aliothmoon
- 修订首启引导文案，移除启动服务步骤 @Aliothmoon
- 重构 Shizuku Bootstrap 流程 @Aliothmoon
- Shizuku 服务绑定改为直连 AIDL，修复部分设备连接超时 @Aliothmoon

### [v0.20.1-beta.3](https://github.com/Aliothmoon/MAA-Meow/releases/tag/v0.20.1-beta.3)

- 任务实况通知优化（小米超级岛 / 实时更新 / 通知栏） @Aliothmoon
- 定时解锁手势选项更名「手动录制」并前移至 PIN 之前 @Aliothmoon
- 不同服务器切换后干员识别失败 @Aliothmoon

### [v0.20.1-beta.2](https://github.com/Aliothmoon/MAA-Meow/releases/tag/v0.20.1-beta.2)

- 跟随上游移除掉线重连，掉线即停整条队列 @Aliothmoon

### [v0.20.1-beta.1](https://github.com/Aliothmoon/MAA-Meow/releases/tag/v0.20.1-beta.1)

- 跟随上游移除掉线重连，掉线即停整条队列 @Aliothmoon
- 定时任务全面增强：新增调度环境检查、权限引导、解锁手势录制
- 后台任务回桌面自动进入画中画，底部操作栏与快捷选项进一步优化
- 统一 App 动效语言，并跟随系统「减弱动画」设置

### [v0.20.0](https://github.com/Aliothmoon/MAA-Meow/releases/tag/v0.20.0)

- 定时任务全面增强：新增调度环境检查、权限引导、解锁手势录制
- 后台任务回桌面自动进入画中画，底部操作栏与快捷选项进一步优化
- 统一 App 动效语言，并跟随系统「减弱动画」设置
- 后台任务回桌面时自动进入画中画 @Aliothmoon
- 定时任务新增调度环境检查与权限引导 @Aliothmoon
- 定时任务支持录制解锁手势 @Aliothmoon
- 统一 App 动效语言并跟随系统减弱动画 @Aliothmoon
- 定时新增迁到顶栏，并补精确闹钟设置与提示 @Aliothmoon
- 后台任务底部操作栏合并开始/停止，⋮ 改为「快捷选项」 @Aliothmoon
- 解锁手势步骤列表改为只读 @Aliothmoon
- 定时亮屏失败后回退按键，避免误报系统不支持 @Aliothmoon
- 横屏更新提示按钮从标题行挪到底栏 @Aliothmoon
- 调整公告弹窗横竖屏底栏布局 @Aliothmoon
- 定时唤醒按熄屏挂机/滑动/PIN 三种用法放行 @Aliothmoon

### [v0.19.2](https://github.com/Aliothmoon/MAA-Meow/releases/tag/v0.19.2)

- 定时策略支持运行期间启用屏保 @Aliothmoon
- 定时熄屏新增「启动时已亮屏则不熄屏」 @Aliothmoon
- 并发定时触发不再互相拆掉前台服务 @Aliothmoon
- 定时闹钟重复投递不再记为跳过执行 @Aliothmoon

### [v0.19.1](https://github.com/Aliothmoon/MAA-Meow/releases/tag/v0.19.1)

- 完善锁屏跳过与解锁失败的提示文案 @Aliothmoon

### [v0.19.0](https://github.com/Aliothmoon/MAA-Meow/releases/tag/v0.19.0)

- **自动化与锁屏唤醒支持**：新增锁屏定时唤醒与自动解锁功能（支持全局滑动/PIN设置），定时策略支持任务结束后自动关闭游戏，并重构了统一启动流程与前台服务自停机制，显著提升定时任务的可靠性
- **交互细节与系统兼容性打磨**：优化设置页布局与页面自动缩放推荐算法；虚拟显示启动失败时能够智能识别 Root 授权 Shizuku 并提示切换模式，基建设施与设置项 UI 主题细节优化
- 定时策略支持任务结束后关闭游戏 @Aliothmoon
- 定时解锁改为全局滑动/PIN 设置 @Aliothmoon
- 统一启动流程并支持自动解锁 @Aliothmoon
- 锁屏定时唤醒功能 (#195) @dorkytiger @Aliothmoon
- 通知发送结果改为结构化 NotificationSendResult @Aliothmoon
- 定时策略编辑页分节间距过挤 @Aliothmoon
- 无锁屏设备解锁测试明确提示 @Aliothmoon
- 任务前台服务改为自停避免 startForeground 竞态崩溃 @Aliothmoon
- 虚拟显示启动失败时识别 Root 授权 Shizuku 并提示改用内置 Root 模式 @Aliothmoon
- 页面缩放自动推荐按系统大字体下压并整体收紧一档 @Aliothmoon
- 完善前台定时启动与前置校验 @Aliothmoon
- 基建设施全选/清除按钮统一主题圆角 @Aliothmoon
- 优化设置项说明与开关间距及主题区分隔 @Aliothmoon
- 精简唤醒解锁 API 命名与返回码归属 @Aliothmoon
- 设置页拆出任务设置并优化强制全屏文案 @Aliothmoon

### [v0.18.2](https://github.com/Aliothmoon/MAA-Meow/releases/tag/v0.18.2)

- 后台 VD 模式游戏漂移到主屏时自动拉回 (#194) @dorkytiger @Aliothmoon

### [v0.18.1](https://github.com/Aliothmoon/MAA-Meow/releases/tag/v0.18.1)

- 后台 VD 模式游戏漂移到主屏时自动拉回 (#194) @dorkytiger @Aliothmoon

### [v0.18.0](https://github.com/Aliothmoon/MAA-Meow/releases/tag/v0.18.0)

- 静音恢复改用 appops reset 兜底 @Aliothmoon
- RemoteService 连接状态机加锁、连接超时与死亡通知去竞态 @Aliothmoon
- 修复虚拟屏关闭后的静音状态 @Aliothmoon
- 简化游戏静音状态机 @Aliothmoon

### [v0.17.2](https://github.com/Aliothmoon/MAA-Meow/releases/tag/v0.17.2)

- 任务结束不再自动恢复游戏声音
- 修复重复静音导致游戏声音无法恢复
- 重构游戏静音实现

### [v0.17.1](https://github.com/Aliothmoon/MAA-Meow/releases/tag/v0.17.1)

- 修复重复静音导致游戏声音无法恢复
- 重构游戏静音实现

### [v0.17.0](https://github.com/Aliothmoon/MAA-Meow/releases/tag/v0.17.0)

- **作战体验与任务配置精细化**：自动战斗新增内置作业选择器，支持动态调整理智作战备选关卡；定时任务新增跳过锁屏检查选项，进一步提升自动化执行的流畅度。
- **UI 交互与视觉打磨**：底层通知组件由 Snackbar 全面切换为更现代的 Sonner Toast 设计，补充任务结束自动关闭游戏时的 Toast 提示，并优化了自动战斗布局与屏保表现。
- **系统优化与边缘问题修复**：调整硬件熄屏运行条件，应用启动时支持自动清理缓存 APK 释放空间；同时修复了主线关卡误判未开放、输入框焦点残留等历史遗留问题。
- 新增定时任务跳过锁屏检查选项
- 微调一下屏保表现
- 调整硬件熄屏运行条件

### [v0.17.0-beta.1](https://github.com/Aliothmoon/MAA-Meow/releases/tag/v0.17.0-beta.1)

- 新增定时任务跳过锁屏检查选项
- 微调一下屏保表现
- 调整硬件熄屏运行条件

### [v0.16.1](https://github.com/Aliothmoon/MAA-Meow/releases/tag/v0.16.1)

- **界面与交互焕新**：设置页面迎来全面重构与组件提取，新增**成就系统**，配合全新的 Tab 页切换动画，带来更丝滑流畅的视觉体验
- **Shizuku 深度优化**：加入 Shizuku 快捷入口与服务开关控制，按安卓系统版本智能切换运行身份，并统一了引导弹窗，设备兼容性与易用性大幅提升
- **体验打磨与架构优化**：优化后台静音逻辑，ViewModel 层架构优化，并修复了深色模式日志显示、未安装游戏死循环等边缘场景问题，整体运行更稳健
- 仅限 API 34+ 继承 root 权限
- 调整后台游戏静音实现，提高设备兼容性
- Tab页动画切换效果 (#154) @Blood-meow
- 设置页面改造与成就系统 (#153) @Blood-meow
- 增加 Shizuku 快捷入口与服务开关控制 (#147) @3265204
- 首次启动公告弹窗在矮屏/大字体设备上确认按钮显示补全
- 按安卓版本切换服务运行身份 (#148) @ClozyA
- 统一 Shizuku 就绪检测与引导弹窗 & 简化 Shizuku 快捷入口

### [v0.16.0](https://github.com/Aliothmoon/MAA-Meow/releases/tag/v0.16.0)

- **界面与交互焕新**：设置页面迎来全面重构与组件提取，新增**成就系统**，配合全新的 Tab 页切换动画，带来更丝滑流畅的视觉体验
- **Shizuku 深度优化**：加入 Shizuku 快捷入口与服务开关控制，按安卓系统版本智能切换运行身份，并统一了引导弹窗，设备兼容性与易用性大幅提升
- **体验打磨与架构优化**：优化后台静音逻辑，ViewModel 层架构优化，并修复了深色模式日志显示、未安装游戏死循环等边缘场景问题，整体运行更稳健
- 调整后台游戏静音实现，提高设备兼容性
- Tab页动画切换效果 (#154) @Blood-meow
- 设置页面改造与成就系统 (#153) @Blood-meow
- 增加 Shizuku 快捷入口与服务开关控制 (#147) @3265204
- 首次启动公告弹窗在矮屏/大字体设备上确认按钮显示补全
- 按安卓版本切换服务运行身份 (#148) @ClozyA
- 统一 Shizuku 就绪检测与引导弹窗
- 简化 Shizuku 快捷入口

### [v0.16.0-beta.1](https://github.com/Aliothmoon/MAA-Meow/releases/tag/v0.16.0-beta.1)

- 成就系统 (#141) @WhiteMoon319
- 修复任务执行通知实时性与重复触发问题
- 清理Android低版本残留进度通知 (#145) @WhiteMoon319

### [v0.14.3](https://github.com/Aliothmoon/MAA-Meow/releases/tag/v0.14.3)

- 修复部分设备定时任务无法正常启动

### [v0.14.2](https://github.com/Aliothmoon/MAA-Meow/releases/tag/v0.14.2)

- 补充服务启动日志
- 修复无精确闹钟权限设备上定时任务不触发
- **ui**: 添加强制横屏选项并优化横屏下对话框布局 (#131) @atemukesu
- **schedule**: 实现前台模式定时任务执行逻辑与悬浮球联动 (#130) @atemukesu
- 服务连接中时启动任务导致资源加载失败

### [v0.14.1](https://github.com/Aliothmoon/MAA-Meow/releases/tag/v0.14.1)

- **ui**: 添加强制横屏选项并优化横屏下对话框布局 (#131) @atemukesu
- **schedule**: 实现前台模式定时任务执行逻辑与悬浮球联动 (#130) @atemukesu
- 服务连接中时启动任务导致资源加载失败

### [v0.14.0](https://github.com/Aliothmoon/MAA-Meow/releases/tag/v0.14.0)

- **ui**: 添加强制横屏选项并优化横屏下对话框布局 (#131) @atemukesu
- **schedule**: 实现前台模式定时任务执行逻辑与悬浮球联动 (#130) @atemukesu
- 服务连接中时启动任务导致资源加载失败
- Revert "feat: 新增「游戏时不在后台中展示」设置项"

### [v0.13.3](https://github.com/Aliothmoon/MAA-Meow/releases/tag/v0.13.3)

- Revert "fix: 修复 Shizuku 服务连接卡在连接中的问题"
- 新增「游戏时不在后台中展示」设置项
- 修复 Shizuku 服务连接卡在连接中的问题
- 修复前台模式适配分辨率后悬浮窗入口检查失败
- 界面主题「出猎」& 增加怪猎联动二期 TD-6/7/8 等核心更新 详见 [v6.11.0](https://github.com/MaaAssistantArknights/MaaAssistantArknights/releases/tag/v6.11.0)

### [v0.13.2](https://github.com/Aliothmoon/MAA-Meow/releases/tag/v0.13.2)

- 新增「游戏时不在后台中展示」设置项
- 修复 Shizuku 服务连接卡在连接中的问题
- 修复前台模式适配分辨率后悬浮窗入口检查失败
- 界面主题「出猎」& 增加怪猎联动二期 TD-6/7/8 等核心更新 详见 [v6.11.0](https://github.com/MaaAssistantArknights/MaaAssistantArknights/releases/tag/v6.11.0)
- 修复定时启动时自定义基建时间轮换恒走首个班次
- 修复任务完成/停止后 Live Update 通知未关闭的问题

### [v0.13.1](https://github.com/Aliothmoon/MAA-Meow/releases/tag/v0.13.1)

- 新增「游戏时不在后台中展示」设置项
- 修复 Shizuku 服务连接卡在连接中的问题
- 修复前台模式适配分辨率后悬浮窗入口检查失败
- 界面主题「出猎」& 增加怪猎联动二期 TD-6/7/8 等核心更新 详见 [v6.11.0](https://github.com/MaaAssistantArknights/MaaAssistantArknights/releases/tag/v6.11.0)
- 修复定时启动时自定义基建时间轮换恒走首个班次
- 修复任务完成/停止后 Live Update 通知未关闭的问题

### [v0.13.0](https://github.com/Aliothmoon/MAA-Meow/releases/tag/v0.13.0)

- 界面主题「出猎」& 增加怪猎联动二期 TD-6/7/8 等核心更新 详见 [v6.11.0](https://github.com/MaaAssistantArknights/MaaAssistantArknights/releases/tag/v6.11.0)
- 修复定时启动时自定义基建时间轮换恒走首个班次
- 修复任务完成/停止后 Live Update 通知未关闭的问题

### [v0.12.2](https://github.com/Aliothmoon/MAA-Meow/releases/tag/v0.12.2)

- 允许 CustomWebhook 向本地 HTTP 服务器发送请求
- 自动战斗与小工具在后台模式下补充 VD 存活检查
- 新增虚拟屏全屏启动设置项
- 启动应用时时默认使用全屏flag
- 增大通知图标 (#115)
- 后台模式下检查游戏是否运行在目标vd上
- 后台模式及浮球模式下隐藏无障碍权限项

### [v0.12.1](https://github.com/Aliothmoon/MAA-Meow/releases/tag/v0.12.1)

- 新增虚拟屏全屏启动设置项
- 允许 CustomWebhook 向本地 HTTP 服务器发送请求
- 启动应用时时默认使用全屏flag
- 增大通知图标 (#115)
- 后台模式下检查游戏是否运行在目标vd上
- 后台模式及浮球模式下隐藏无障碍权限项

### [v0.12.0](https://github.com/Aliothmoon/MAA-Meow/releases/tag/v0.12.0)

- 允许 CustomWebhook 向本地 HTTP 服务器发送请求
- 启动应用时时默认使用全屏flag
- 增大通知图标 (#115)
- 后台模式下检查游戏是否运行在目标vd上
- 后台模式及浮球模式下隐藏无障碍权限项

### [v0.11.0](https://github.com/Aliothmoon/MAA-Meow/releases/tag/v0.11.0)

- 提高 Root 模式兼容性 &补充链路日志
- 修复横屏原生设备（如 AYN Odin2）后台模式显示竖屏问题
- 修复定时任务闹钟丢失及 Android 12 不触发问题

### [v0.10.7](https://github.com/Aliothmoon/MAA-Meow/releases/tag/v0.10.7)

- 修改熄屏挂机解锁避免全面屏手势冲突
- 主题模式新增「随系统」选项并设为默认

### [v0.10.6](https://github.com/Aliothmoon/MAA-Meow/releases/tag/v0.10.6)

- 修改熄屏挂机解锁避免全面屏手势冲突
- 主题模式新增「随系统」选项并设为默认
- 游戏静音重置失效

### [v0.10.5](https://github.com/Aliothmoon/MAA-Meow/releases/tag/v0.10.5)

- 修改熄屏挂机解锁避免全面屏手势冲突
- 主题模式新增「随系统」选项并设为默认
- 游戏静音重置失效

### [v0.10.3](https://github.com/Aliothmoon/MAA-Meow/releases/tag/v0.10.3)

- 移除后台全屏监控的渐入动画，修复平板透明遮罩问题
- 信用收支面板内联添加面板背景色适配主题
- 适配生息演算新主题「重启锚点」
- 优化后台管理机制
- 修复开关“开始唤醒”后服务器资源加载错误的问题
- 调整静音恢复逻辑，改为仅重置 `PLAY_AUDIO` 操作 (op)

### [v0.10.2](https://github.com/Aliothmoon/MAA-Meow/releases/tag/v0.10.2)

- 信用收支面板内联添加面板背景色适配主题
- 适配生息演算新主题「重启锚点」
- 优化后台管理机制
- 修复开关“开始唤醒”后服务器资源加载错误的问题
- 调整静音恢复逻辑，改为仅重置 `PLAY_AUDIO` 操作 (op)

### [v0.10.1](https://github.com/Aliothmoon/MAA-Meow/releases/tag/v0.10.1)

- 适配生息演算新主题「重启锚点」
- 优化后台管理机制
- 修复开关“开始唤醒”后服务器资源加载错误的问题
- 调整静音恢复逻辑，改为仅重置 `PLAY_AUDIO` 操作 (op)

### [v0.9.1](https://github.com/Aliothmoon/MAA-Meow/releases/tag/v0.9.1)

- 为通知添加Android 16 实时动态通知适配 @WhiteMoon319

### [v0.7.1](https://github.com/Aliothmoon/MAA-Meow/releases/tag/v0.7.1)

- 补充硬件熄屏状态管理
- 调整服务退出回调权限处理

### [v0.7.0](https://github.com/Aliothmoon/MAA-Meow/releases/tag/v0.7.0)

- 支持后台虚拟屏分辨率 720p/1080p 可选
- 任务结束时恢复自动熄屏
- 移除selinux域降级，修复部分Root设备无法正常启动任务

### [v0.6.1](https://github.com/Aliothmoon/MAA-Meow/releases/tag/v0.6.1)

- 新增间隔调度策略类型，支持指定开始时间和执行间隔
- 调整拉起Activity实现

### [v0.6.0](https://github.com/Aliothmoon/MAA-Meow/releases/tag/v0.6.0)

- 合并游戏声音控制
- 调整理智恢复通知格式
- 修复禁用唤醒后 clientType 回退为官服的问题
- 屏保"向上滑动解锁"提示随机偏移

### [v0.5.1](https://github.com/Aliothmoon/MAA-Meow/releases/tag/v0.5.1)

- 修复通知渠道失效问题

### [v0.5.0](https://github.com/Aliothmoon/MAA-Meow/releases/tag/v0.5.0)

- 支持系统通知
- 支持外部通知
- 支持定时任务强制启动
- 支持定时任务触发日志
- 完善应用后台运行限制
- 修复前台模式自定义基建文件崩溃
- 选用前台模式时初始化存在问题
- remove INTERACT_ACROSS_USERS_FULL from ShizukuProvider
- 优化快捷分辨率调整按钮样式

### [v0.4.1](https://github.com/Aliothmoon/MAA-Meow/releases/tag/v0.4.1)

- 修复熄屏悬浮窗仍显示状态栏的问题
- 修复定时周期遗漏的问题

### [v0.4.0](https://github.com/Aliothmoon/MAA-Meow/releases/tag/v0.4.0)

- 支持定时任务
- 支持暗黑模式 & 重构主题
- 增加触控预览显示
- 根据客户端类型自动调整分辨率
- 微调触控流程
- 调整心跳实现避免doze模式下调度延迟
- 修复root模式下的调试模式
- 服务关闭时自动恢复游戏静音
- 简化定时逻辑

### [v0.3.0](https://github.com/Aliothmoon/MAA-Meow/releases/tag/v0.3.0)

- 熄屏挂机功能优化
- 支持纯root模式 & 重构服务权限层级
- 定时检查热更资源变化，启动任务前按需重载
- disable SELinux permissive mode enforcement in root launcher
- split root service creation & use explicit IContentProvider bootstrap
- 修复非 Activity Context 打开外链崩溃
- 修复root权限启动检测
- 前台模式颜色主题修复
- 前台模式面板修复
- SUI 检测弹窗确认后持久跳过检查
- 优化触控注入实现
- 优化熄屏挂机 & 额外选项布局
- 拆分 RemoteServiceImpl 职责并实现 Root 模式的关闭清理
- **ui**: 统一颜色主题配置并移除硬编码颜色

### [v0.2.0](https://github.com/Aliothmoon/MAA-Meow/releases/tag/v0.2.0)

- 支持启动时关闭游戏声音
- 支持熄屏挂机
- 补充启动应用电池优化白名单
- 将运行模式默认值改为后台模式
- 调整抄作业按钮大小 & 修复开始唤醒无法滚动的问题
- 仅在后台模式显示一键长草编辑入口
- 修复熄屏挂机在高版本的异常崩溃

### [v0.1.9](https://github.com/Aliothmoon/MAA-Meow/releases/tag/v0.1.9)

- 后台模式全屏预览时支持触控
- 启动应用时排除后台任务列表

### [v0.1.8](https://github.com/Aliothmoon/MAA-Meow/releases/tag/v0.1.8)

- 调整后台模式分辨率
- 修复一些小问题- 细化权限申请，不启动多余的无障碍权限
- 修复前台模式无法滚动的问题- 修复初始化设置读取错误
- 简化 BackgroundTaskViewModel 的 Surface 生命周期管理
- 移除 ResourceInitService 中不必要的存储权限检查
- 调整后台运行时全屏展示逻辑

### [v0.1.7](https://github.com/Aliothmoon/MAA-Meow/releases/tag/v0.1.7)

- 修复Android11下后台模式无法正常预览小窗的问题


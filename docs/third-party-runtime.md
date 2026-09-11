# 附加运行时组件

保留的 Meow 来源和许可证见 `UPSTREAM.json`、`docs/licenses/`。完整 Meow 应用与任务界面不参与构建；`android/` 中的背景显示、输入及系统接口保留上游包名，另有少量 MAA C API 声明。修改包括独立生命周期、捕获释放顺序和 Android 核心服务。

| 组件 | 来源 | 许可证 |
| --- | --- | --- |
| Mower / 原图标 | https://github.com/ALEXsun0/arknights-mower | MIT，见 runtime/LICENSE |
| 官方 Android MaaCore | https://github.com/MaaAssistantArknights/MaaAssistantArknights | AGPL-3.0，含各依赖原许可证 |
| Meow 背景显示与输入 | https://github.com/Aliothmoon/MAA-Meow | AGPL-3.0，部分 scrcpy 代码 Apache-2.0 |
| Shizuku API/provider | https://github.com/RikkaApps/Shizuku-API | Apache-2.0 / MIT，按各模块声明 |
| JNA | https://github.com/java-native-access/jna | LGPL-2.1-or-later / Apache-2.0 |
| CPython 与 Debian 用户态 | Docker Official Image python:3.12-slim-bookworm | PSF、各 Debian 包原许可证 |
| PRoot | https://github.com/termux/proot | GPL-2.0-or-later |
| libtalloc | https://talloc.samba.org/ | LGPL-3.0-or-later |
| libandroid-shmem | https://github.com/termux/libandroid-shmem | 按上游许可证 |
| pnnx 20260526 | https://github.com/pnnx/pnnx | BSD-3-Clause 与所含依赖声明 |
| Python 依赖 | scripts/requirements-android.lock | 各包内保留原始许可证 |

Termux 打包源和构建规则：https://github.com/termux/termux-packages 。二进制版本和校验信息见 `scripts/engine-assets.lock.json`。Python 运行包保留包级许可证与 Debian copyright 文件。MAA OCR 模型从官方组件的 ONNX 转换为 NCNN FP32，不改变官方核心二进制；转换形状参考 Meow 对应固定提交的 `scripts/convert_ocr_ncnn.py` 文档。发布和再分发应继续附带这些许可证及各许可证要求的源码。

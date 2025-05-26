# Human Vehicle Monitor (人车监控系统)

一个使用设备摄像头和 TensorFlow Lite 进行实时人形和车辆检测的 Android 应用程序。该应用具备基于持续检测的可配置报警功能，目前正在积极开发中。

## 主要功能 (当前/计划中)

* 通过 CameraX 实现实时物体检测（人形、车辆等）。
* 在相机预览上通过边界框可视化检测到的物体。
* 可配置的检测参数：
    * 置信度阈值
    * 最大检测结果数量
    * 推理代理 (CPU/GPU)
    * 模型识别间隔
* 可定制的报警系统：
    * 报警模式 (仅人员、仅车辆、人员和车辆)
    * 可调节的持续检测时长以触发报警
* 使用 TensorFlow Lite (Task Vision Library) 在设备端进行推理。
* 基于 Jetpack Compose 构建的现代化用户界面。
* (注意：本项目仍在积极开发中，功能会持续迭代与完善。)

## 技术栈

* Kotlin
* Jetpack Compose
* CameraX
* TensorFlow Lite (Task Vision Library)
* Accompanist Permissions

## 当前状态

项目目前处于开发阶段。核心的检测、基本报警功能正在实现和优化中。

## 如何构建和运行 (基本步骤)

1.  在 Android Studio 中打开本项目。
2.  确保你的开发环境配置了 Android SDK 和 NDK (如果模型或依赖项需要)。
3.  在 Android 设备或模拟器上构建并运行 (需要相机权限)。

## 许可协议

本项目采用以下许可声明：

Copyright (c) 2025 xingwenjie. All rights reserved.

本项目代码允许用于非商业目的的自由使用、复制和修改。
任何形式的商业用途（包括但不限于：将本代码或其衍生部分用于商业产品、提供商业服务、或任何以盈利为主要目的的使用方式）均需事先获得版权持有人 xingwenjie (xgwnje@qq.com) 的书面授权。

如需商业授权，请联系：xgwnje@qq.com

详细信息请参阅项目根目录下的 `LICENSE.md` 文件。

## 作者与联系方式

* xingwenjie
* 邮箱: xgwnje@qq.com

---

**注意：** 由于项目正在开发，以上信息可能会随时更新。
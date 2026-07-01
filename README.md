# ArkPets Mobile

明日方舟桌面宠物 Android 版 —— 将 Spine 骨骼动画角色悬浮在手机桌面上，支持物理模拟和触摸交互。

## 功能

- **2200+ 角色模型** — 涵盖干员、敌人、动态立绘，来自《明日方舟》
- **Spine 骨骼动画** — 完整支持 Region/Mesh 附件，Idle / Walk 动画自动识别
- **物理模拟** — 重力掉落、惯性甩出、弹墙回弹
- **自动行走** — Idle 1-4s → Walk 3-8s 随机循环，碰壁掉头
- **重力感应** — 倾斜手机可拖拽/弹飞角色（可开关）
- **触摸交互** — 拖拽移动角色，点击触发特殊动作（可开关）
- **可调参数** — 行走速度、重力大小实时滑块调节

## 技术架构

| 组件 | 技术 |
|------|------|
| 渲染 | TextureView + EGL14 + OpenGL ES 2.0 |
| Spine | libGDX 1.12.1 + spine-libgdx 3.8.55 |
| 骨骼绘制 | PolygonSpriteBatch + SkeletonRenderer |
| 动画分类 | 正则模式匹配（参考 ArkPets Desktop `AnimClip.recognizeType`） |
| 悬浮窗 | WindowManager TYPE_APPLICATION_OVERLAY |
| 物理 | 独立线程 60fps 刚体模拟 |

## 构建

```bash
export ANDROID_HOME=/path/to/android/sdk
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

**要求**: Android SDK 34, JDK 17, Gradle 8.7

## 使用

1. 安装 APK，授予「悬浮窗」权限
2. 搜索/选择角色，点击「启动」
3. 角色出现在桌面，自动行走
4. 拉下通知栏可停止服务

### 触摸交互

- **拖拽**：按住角色拖动
- **点击**：触发特殊动作（Attack/Skill 等）
- **开关**：设置页「触摸交互」可完全关闭触摸窗

### 传感器

- **重力感应**开关控制传感器响应
- 手机倾斜 >2m/s² 时激活滑动/飞行
- 关闭时纯自动行走模式

## 模型来源

模型文件来自 ArkPets Desktop（[isHarryh/ArkPets](https://github.com/isHarryh/ArkPets)），版权归原游戏及原作者所有。

## 许可

本项目仅用于学习和技术研究。角色模型版权归鹰角网络（Hypergryph）所有。

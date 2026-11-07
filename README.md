# LDY-B5 小智客户端 —— 源码说明（ldyb5-client）

中国移动「和家小荷」LDY-B5 带屏智能音箱（**携住酒店定制款**）上的
小智（xiaozhi）语音助手客户端，包名 `cn.workbuddy.ldyb5app`。复用原厂云知声 Uni4mic 四麦通路 + 自建 sherpa-onnx KWS 双模唤醒。

设备实测：MediaTek **MT8167** / **Android 8.1.0（API 27）** / ≈1 GB RAM / 7 英寸触摸屏（物理 600×1024，横屏 1024×600）/ `armeabi-v7a` 32 位 / SELinux Permissive；`brand=Pandora`、`model=A2-CM`、`manufacturer=emdoor`，原厂 Launcher 版本 `v6.9.0.4-xiezhuCM`。

> 设备事实、四麦拾音与降噪实现、全部 44 个 MCP 工具、root 权限作用、安装与使用说明，见同级 `../README.md`（发布根说明）。本文件只讲这个源码包本身。

---

## 一、这是个什么工程

不依赖 Gradle 的纯手写 Android 工程，用 `aapt2 + d8 + jarsigner` 直接出 APK（目标是老设备 + 免现代 AGP）。`build-win.sh` 在 Windows / Git Bash 下即可一键构建，工具链已随包自带，无需 WSL / Gradle / Android Studio。

## 二、目录结构

```
ldyb5-client/
├── AndroidManifest.xml          包名 / 权限 / 组件声明（versionName=V1.0.1 / versionCode=63）
├── build-win.sh                 Windows 一键构建
├── build.sh                     通用构建（面向有 Android SDK 的环境，类同）
├── debug.keystore               签名用（debug）
├── src/cn/workbuddy/ldyb5app/   ← 全部 Java 源码（27 个文件）
│   ├── Ldyb5ClientService.java   前台服务：组件装配与生命周期
│   ├── AudioCapture.java         16kHz/16bit/mono 采集 + 环形预缓冲（preroll）
│   ├── OpusCodec.java            Opus 编解码 JNI 封装
│   ├── XiaoZhiWs.java            小智官方 WebSocket 协议客户端
│   ├── OtaClient.java            OTA 激活（拿 Bearer token）
│   ├── SessionController.java    会话状态机 + 上行调度（含「唤醒词不上传」开关逻辑）
│   ├── WakeupBridge.java         ★ 监听原厂云知声唤醒事件（msgcenter 总线）
│   ├── KwsEngine.java            ★ 自建 sherpa-onnx 流式 KWS + 原厂 HAL 双模
│   ├── TtsPlayer.java            AudioTrack 播放 + jitter buffer（起播水位 600ms）
│   ├── DeviceController.java      亮度 / 息屏 / 点亮 / root 命令封装
│   ├── SettingsUI.java           设置页（版本号、项目地址、唤醒词不上传开关等）
│   ├── ScreenUI.java             主界面（Launcher，拉起前台服务）
│   ├── ScreenBus.java / RobotIsland.java / IslandOverlay.java   灵动岛状态浮层
│   ├── MusicPlayer.java / KuwoController.java / RadioProxy.java / WeatherClient.java / TvController.java   MCP 工具后端
│   ├── MicProbe.java / PinyinKws.java / WakeScreenActivity.java / BootReceiver.java / SessionLog.java …   其余
│   └── com/unisound/jni/Uni4micHalJNI.java   四麦 HAL JNI 桥
├── libs/                        依赖 jar（sherpa-onnx、okhttp、media3/exoplayer、guava、orgjson 等）
├── jniLibs/armeabi-v7a/          native so（四麦 HAL JNI、sherpa-onnx JNI、opus、tinyalsa 等）
├── jni/r1opus.c                  Opus JNI 实现（文件名沿用旧工程，产物为 libld5opus.so）
├── assets/                      模型 / 配置（KWS 词表、sherpa 资源等）
├── res/                         Android 资源
└── build/
    ├── wintools/                构建工具链（aapt2.exe / r8.jar / android.jar / platform-tools）
    └── genstub/                构建期生成桩（R.java 占位等）
```

## 三、构建

Windows + Git Bash，工具链随包自带（exe），无需 WSL / Gradle / Android Studio：

```bash
cd ldyb5-client
bash build-win.sh V1.0.1 V1.0.1 63
# 输出 ldyb5-client-V1.0.1.apk（versionCode=63 / versionName=V1.0.1）
```

脚本流程：`aapt2 compile+link`（注入 assets）→ `javac`（JDK17，source/target 8）→ `D8`（classes + 依赖 jar 转 dex）→ Python 把 `classes.dex` 与 `jniLibs/armeabi-v7a/*.so` 合回 apk → `jarsigner` 用 `debug.keystore` 签名。

JDK17 / Python 路径在脚本顶部；换机器可用环境变量 `JDK17_BIN` / `PYTHON_EXE` 覆盖，不必改脚本。

构建产物与发布根目录 `LDY-B5_XiaoZhi_Client_V1.0.1.apk` 同源同字节。

## 四、其它文档

- `../README.md`：设备事实、四麦拾音与降噪、全部 MCP 工具、root 用途、安装与使用、从源码构建的完整说明（首选阅读）。
- `DEPLOY.md`：安装 / 首次设置 / 调试入口。
- `DESIGN.md`：架构与关键设计决策。

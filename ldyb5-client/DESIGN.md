# LDY-B5 小智客户端 —— 架构设计

## 组件与进程

- `Ldyb5ClientService`：前台服务，常驻，装配所有组件（麦克风 / KWS / WS / TTS / 设备控制 / MCP）。
- `ScreenUI`：Launcher 主界面，启动即拉起服务；也导出 HOME，可设为默认桌面。
- `SettingsUI`：设置页（唤醒词、灵敏度、服务器、唤醒词不上传开关、项目地址等）。
- `ScreenBus` / `RobotIsland` / `IslandOverlay`：灵动岛（状态浮层）UI。
- `BootReceiver`：开机自启（普通广播 `BOOT_COMPLETED`，无需 root）。

## 语音主链路

```
AudioRecord(16k/16bit/mono, 经 Uni4mic HAL)
   │ 每 60ms 帧
   ├──────────────▶ KwsEngine（sherpa-onnx 流式 + 原厂 HAL 双模，常驻）
   │
   └──────────────▶ SessionController（会话状态机 + 上行调度）
                         │ opus 16k/24kbps/60ms 裸包（无自定义帧头）
                         ▼
                      XiaoZhiWs（WebSocket）
                         │
                         ▼ 服务端 TTS(opus) → OpusCodec 解码
                      TtsPlayer（AudioTrack，起播水位 600ms + 1s 硬件缓冲）
```

## 双模唤醒

1. **本地 KWS**（`KwsEngine`，sherpa-onnx `KeywordSpotter`，默认词「小智小智」）：常驻采样，
   TTS 播放中也运行，支持「唤醒词打断」。
2. **原厂总线**（`WakeupBridge`）：监听云知声 msgcenter 总线上的「小讯小讯」唤醒事件。

命中后 `SessionController.handleWakeup` 建会话、开上行。默认唤醒词随指令一并上送、不发
`listen/detect`（故服务端不播问候 TTS）；设置页「唤醒词不上传」开启后，本地丢弃唤醒词缓冲、
不发 detect，唤醒后稍顿再开口（代价：唤醒词不进云端）。对话中打断走既有热会话路径，无缝、不受影响。

## 回声与自环抑制（无 AEC）

系统 `AcousticEchoCanceler` 默认关（会把近端人声一起压掉）；服务端 AEC 由官方服务器跑，
客户端 hello 不带 `features.aec`。防自环靠状态机：

- TTS 播放期上行全关（`suppressUplink=true`）；
- 句尾静音续播（`holdSilenceMs=4000`）：HAL 只在活跃播放流时启用回声抑制；
- `onTtsStop()` 装 `echoGate`（电平闸门，`echoRms=8000`，连续 8 帧低于阈值才开闸），
  并等 `TtsPlayer.isDrained()` 才恢复上行；
- 不发 `listen/detect`；barge-in 默认关；KWS 打断时 `TtsPlayer.abort()` + `preroll` 补发。

完整细节见 `../README.md` 第四章。

## MCP 工具

唯一注册点 `XiaoZhiWs.sendMcpToolsResult()`：经 `tools/list` 上报、`tools/call` 由客户端本地执行；
**所有参数在 `inputSchema` 里都是 `string` 类型**（枚举/范围只写在 description 文本里）。
共 44 个工具，分本地电台 / 音量 / 酷我 / 电视 / 屏幕设备程序 五组，完整表见 `../README.md` 第五章。

## 构建要点

纯 `aapt2 + d8 + jarsigner`，不依赖 Gradle。详见 `README.md` 第三节与 `../README.md` 第八章。

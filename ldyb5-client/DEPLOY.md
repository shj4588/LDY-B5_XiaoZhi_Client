# LDY-B5 小智客户端 —— 部署与验证

## 环境要求

- LDY-B5 设备已 root（SELinux Permissive），adb 可达（默认 TCP `192.168.2.16:5555`）。
- Windows 端 adb：`build/wintools/platform-tools/adb.exe`（或系统自带；注意本机旧 adb 可能占 5037 端口，必要时用 `-P 5038` 另起）。

## 安装

```bash
adb connect 192.168.2.16:5555
adb -s 192.168.2.16:5555 install -r ldyb5-client-V1.0.1.apk
adb -s 192.168.2.16:5555 shell am force-stop cn.workbuddy.ldyb5app
adb -s 192.168.2.16:5555 shell am start -n cn.workbuddy.ldyb5app/.ScreenUI
```

`ScreenUI` 是本机 Launcher（导出 HOME），启动即拉起前台服务 `Ldyb5ClientService`。
可用 `dumpsys activity services cn.workbuddy.ldyb5app` 确认 `Ldyb5ClientService isForeground=true`。

## 首次设置

打开「小智设置」配置唤醒词、灵敏度、服务器地址。设置页标题右侧显示当前 APK 版本号，
页底为项目地址（可点击打开）。

## 常用调试入口（不必真的说话）

```bash
# 模拟一次唤醒（跳过真喊唤醒词，直接走 KWS 命中后的会话流程）
adb shell am broadcast -a cn.workbuddy.ldyb5app.DEBUG_WAKE

# 本地设备工具（等价于对应 MCP 工具，便于无网调试）
adb shell am startservice -n cn.workbuddy.ldyb5app/.Ldyb5ClientService --es dev_tool self_screen_stay_on
adb shell am startservice -n cn.workbuddy.ldyb5app/.Ldyb5ClientService --es dev_tool self_screen_brightness --es dev_query '调到50%'
adb shell logcat -d | grep L5CLIENT-DEVDBG

# TTS 抗抖探针（纯静音、不出声，走完整播放链路）
adb shell am startservice -n cn.workbuddy.ldyb5app/.Ldyb5ClientService --es tts_sim 60 --es sim_jitter 200
adb shell logcat -d | grep -E "playback started|TTS drained"
```

日志过滤：`logcat -s L5CLIENT:V` / `grep -E "SessionController|Ldyb5ClientService|KwsEngine"`。

## root 用途

root 用于：注入 Cap4mic 使四麦 HAL 在第三方进程可用、锁屏、亮度/常亮的系统设置写入、
背光节点补写、酷我收藏整单播放、电视播放器重启 / HTTP 服务补起 / 强杀。
语音主链路（采集 / KWS / TTS / 回声闸门）与全部 MCP 工具**不开 root 也完整可用**。
详见 `../README.md` 第四章。

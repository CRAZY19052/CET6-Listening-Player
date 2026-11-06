# CET6 听力舱

一个完全离线的 Android 六级听力播放器。播放器面向 2020–2026 年六级听力整卷音频，支持后台/锁屏播放、倍速、题组跳转、断点续播、逐题组完整收听计次与本地学习统计。

> **这个仓库不包含六级真题音频或完整 APK。** 第三方听力录音的公开可访问性不等于再分发授权。构建自己的私有安装包时，请使用你已获得授权的音频素材。

## 功能

- 完全离线：运行时不申请 `INTERNET` 权限。
- 原生 Media3/ExoPlayer 播放内核，支持后台播放、锁屏媒体控制、音频焦点与耳机拔出处理。
- 0.75× / 1× / 1.25× / 1.5× / 2× 倍速。
- 整套连续播放，以及按长对话、篇章、讲座等题组跳转。
- 每个题组分别记录完整收听次数、累计实播进度和最后播放位置。
- 完整播到题组结尾才计 1 遍；拖动进度条跳到结尾不会计次。
- 今日收听分钟、累计学习时长、最近学习记录和题库完成度。
- 所有数据保存在本机，不使用账号、不上传服务器。

## 数据结构

- `app/src/main/assets/audio/*.m4a`：私有构建时注入的音频，已由 `.gitignore` 排除。
- `app/src/main/assets/catalog.json`：题库、时长和 7 个题组切点。
- `tools/audio-manifest.example.json`：本地音频清单示例。
- `tools/chapter-markers.example.json`：章节时间轴示例。
- `tools/chapter-markers.json`：27 套音频的 189 个题组切点。
- `tools/build_catalog.py`：把本地音频和切点生成到 App assets，不会联网下载素材。

每个题组区间必须连续覆盖整条音轨，应用会拒绝构建缺段、重叠或题组数量不为 7 的目录。

## 构建

要求：

- JDK 17
- Android SDK Platform 35
- Android Build-Tools 35.0.0
- Gradle 8.11.1（也可使用仓库中的 Gradle Wrapper）

准备本地素材：

1. 复制 `tools/audio-manifest.example.json`，填入你有权使用的本地音频路径与时长。
2. 准备 7 段连续章节切点；字段格式见 `tools/chapter-markers.example.json`。
3. 生成私有 assets：

```powershell
python tools\build_catalog.py `
  --manifest C:\path\to\audio-manifest.json `
  --sections C:\path\to\chapter-markers.json `
  --project .
```

4. 构建：

```powershell
$env:JAVA_HOME = "C:\path\to\jdk-17"
$env:ANDROID_HOME = "C:\path\to\android-sdk"
.\gradlew.bat assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`。

Windows 上也可以使用根目录的 `build-private-apk.ps1`：

```powershell
.\build-private-apk.ps1 `
  -Manifest C:\path\to\audio-manifest.json `
  -Sections C:\path\to\chapter-markers.json
```

## 计次规则

计次以题组为单位：应用累计当前播放会话在该题组内的实际播放时长，达到该题组时长的 80% 并继续播放到题组结束，才增加 1 次完整收听。暂停后继续不会丢失本次会话进度；主动拖动到其他位置会重置当前题组的本次计数进度。

## 隐私与权限

- 不需要网络权限。
- 需要媒体播放前台服务和通知权限，用于锁屏与后台播放。
- 应用关闭了系统备份，学习记录不会因 Android 云备份离开设备。

## 素材和权利

本仓库只提供播放器代码和数据结构，不提供历年真题音频。请勿将来源不明的录音、完整 APK 或二次打包素材直接发布到本仓库或 GitHub Releases。应用内题名仅用于用户自行整理已获授权的本地素材。

参见 [docs/CONTENT_POLICY.md](docs/CONTENT_POLICY.md)。

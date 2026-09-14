# 计时器

中文原生 Android 倒计时应用，Kotlin / Jetpack Compose / Material 3。

## 安装和使用

公开仓库：[GodBook/countdown-timer](https://github.com/GodBook/countdown-timer)。从 [最新版本](https://github.com/GodBook/countdown-timer/releases/latest) 下载 APK，或使用本地 `dist/countdown-timer-1.1.0.apk`。支持 Android 8.0（API 26）及以上，目标平台为 Android 16（API 36）。将 APK 发送到手机，通过文件管理器打开；如系统要求，允许该文件管理器安装应用。

首次打开允许通知。Android 12 还需按应用提示允许“闹钟和提醒”。输入时、分、秒，或选择 1／5／10／25 分钟，选择提醒方式，点击“开始计时”。下滑通知栏可查看剩余时间，或暂停、继续、取消。

- 同时运行一个倒计时，时长 1 秒至 99 小时 59 分 59 秒。
- 暂停保留剩余时间；重置恢复本次设定时长，等待重新开始；取消清除当前通知。
- 视觉提醒：应用内弹窗；后台通过系统结束通知提醒。横幅能否弹出由系统通知类别设置决定。
- 响铃：使用系统默认闹钟声音和闹钟音量，持续最多 1 分钟。可在应用或通知内提前停止。仅响铃模式也保留通知中的停止入口。
- 自动停止铃声后保留结束记录；点击“完成”清除。
- 熄屏、切换应用和正常进程回收不依赖界面继续计时；手机重启取消当前倒计时，包括暂停状态。
- 跟随系统深浅色；Android 12 及以上使用系统动态配色。

请保留通知权限，按需在系统中开启结束提醒类别的横幅。系统闹钟音量为零或系统勿扰设置可能影响响铃。部分品牌手机需要允许后台运行或自启动。用户在系统设置中强制停止应用后，系统不会继续派发其闹钟；重新打开应用后恢复检查状态。

计时功能可离线使用。应用无账号、广告或分析 SDK，不上传计时数据。不提供自定义铃声和振动设置。1.1.0 起联网仅用于 GitHub 更新检查和下载；网络服务提供方会收到正常连接所需的 IP、请求路径和应用版本 User-Agent。

## 应用内更新

主界面点击“检查更新”。打开应用时最多每天自动检查一次，失败静默，不影响倒计时；有新版本时按钮显示“发现新版本”。手动检查失败会显示可重试提示。

点击“下载更新”后在应用内显示进度，下载完成校验大小、SHA-256、包名、版本号和当前应用签名。点击“安装更新”后由 Android 显示安装确认；首次需要在系统设置中允许本应用安装未知应用，授权后返回再点击安装。安装期间会重启应用，因此运行／暂停的倒计时和正在响铃时不允许发起安装。

下载不使用常驻后台服务；切换界面时继续，但进程被系统回收或网络中断后需重新下载。临时 APK 存在应用私有目录中。安装权限被拒绝、取消安装均不会卸载或清除原应用数据。

**1.0.0 没有更新入口，需要先手动覆盖安装 1.1.0 一次。** 后续版本可以从应用内更新；无需 GitHub 账号。若网络无法访问 GitHub，计时功能照常使用，可另行下载官方 Release APK 后手动覆盖安装。

## 源码和构建

使用 JDK 17、Android SDK Platform 36、Gradle Wrapper 8.11.1、Android Gradle Plugin 8.10.1、Kotlin 2.1.21、Compose BOM 2025.05.00。通过 `ANDROID_HOME` 或未纳入版本控制的 `local.properties` 指定 SDK。

Windows 推荐执行：

```powershell
$env:JAVA_HOME = 'D:\dev\jdk-17'
.\scripts\build.ps1 -DebugOnly
```

构建发行版：

```powershell
.\scripts\build.ps1
```

脚本在中文目录下临时映射空闲盘符，避免 Windows Gradle 单元测试类路径乱码，构建结束后撤销映射。默认目录直接执行 `gradlew.bat` 也能生成 APK，但部分 Windows/JDK 组合的单元测试会因中文类路径失败。

运行设备测试（需已启动 Android 16 模拟器或已连接设备）：

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.awxds.countdowntimer.TimerIntegrationTest'
```

`TimerScenarioSetup` 是仅用于外部验收的测试入口，会故意保留计时状态；常规测试请使用上述类过滤器。

## 发行签名与更新

当前应用 ID 为 `com.awxds.countdowntimer`，版本 `1.1.0`，`versionCode=2`。发行 APK 使用专用 RSA 签名，调试签名与其不同。后续更新必须保留应用 ID、使用相同发行密钥，并提高 `app/build.gradle.kts` 中的 `versionCode`。

本机签名材料保存在 `%USERPROFILE%\.codex\signing\countdown-timer\`：

- `release.jks`：发行私钥。
- `signing.properties`：密钥路径、别名和密码备份。

工程根目录的 `signing.properties` 是构建配置副本。签名材料均被 Git 忽略，源码压缩包不包含密钥或密码。**请将整个签名目录复制到安全的离线备份中，不要随 APK 或源码公开发送。丢失私钥将无法覆盖更新已安装的发行版。**

在另一台开发机上恢复这份密钥，并在工程根目录配置：

```properties
storeFile=C:/private/countdown-timer/release.jks
storePassword=从私密备份填写
keyAlias=countdown-release
keyPassword=从私密备份填写
```

未配置签名时，发布脚本会报错，避免误发未签名 APK。不要为同一已发布应用重新生成私钥。

## 发布后续版本

修改版本号及 `release-notes.md`，提交源码并推送到 `main`，本机登录拥有仓库发布权限的 GitHub CLI 后执行：

```powershell
.\scripts\publish-release.ps1
```

脚本构建固定签名 APK、运行单元测试和静态检查、打包源码、生成 `update.json`，将附件全部上传到草稿 Release 后再公开发布为 latest。同名版本已存在时拒绝覆盖。GitHub Actions 只构建调试 APK 和检查源码，不保存发行私钥。

更新源固定为 `https://github.com/GodBook/countdown-timer/releases/latest/download/update.json`。格式为 `schemaVersion=1`、整数 `versionCode`、`versionName`、`minSdk`、官方 Release `apkUrl`、`sha256`、字节数 `size` 和文本 `notes`。每次正式发布必须同时上传对应 APK 与该清单，不将草稿或预发布版本设置为 latest。

## 实现与验证

`TimerState` 是不依赖 Android 的计时状态机。`TimerController` 负责持久化、精确闹钟和统一操作；`TimerNotifications` 使用系统倒计时 Chronometer，后台不按秒发送通知；`RingService` 仅在响铃期间运行，并限制播放时间。闹钟回调带有计时标识，旧回调不会结束新计时。

精确提醒使用 `setAlarmClock`，以避免短倒计时连续运行时受 while-idle 闹钟限频影响；系统可能显示下一次闹钟标记。剩余时间始终以单调时钟计算，调度时换算系统时间；系统时间或时区变化后重新调度。此选择优先保障用户主动设置的结束提醒，临近提醒时可能使系统退出休眠。

具体执行结果、环境和未验证项见 `verification/RESULTS.md`。Android 8～15 兼容性通过 API 级别分支及静态检查覆盖，尚不等同于这些版本逐一真机验收。

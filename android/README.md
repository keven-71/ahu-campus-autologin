# 安徽大学校园网 · 安卓版

这是独立的原生安卓应用，不需要在手机安装 Python、Termux 或 Root。返回[项目首页](../README.md)。

源码仓库不包含预编译 APK。通过下方构建脚本生成的安装包位于 `output/ahu-campus-android.apk`；如果维护者在 GitHub Releases 提供安装包，也可从对应版本下载。

## 手机上使用

1. 把 APK 传到手机并安装。系统要求时，允许当前文件管理器安装应用。
2. 打开“安大校园网”，填写网页认证使用的账号、密码，选择相同运营商。
3. Wi-Fi 名称保持 **`ahu.portal`**，控制器 IP 保持 **`172.16.253.1`**。这些值已按提供的手机认证页预填。
4. 点击“立即认证一次”，允许“使用应用期间”的**精确位置**权限，并在手机系统中开启定位。若提示未开启定位，打开应用内“系统定位设置”，开启后再试。
5. 手机需已经连接 `ahu.portal`。若系统提示此 Wi-Fi 暂时无互联网，选择保持连接。看到“校园网认证成功，已确认在线”或“校园网已认证，无需重复登录”后，点击“开启自动认证”。
6. 允许通知，在手机的电池/应用管理中允许本应用后台运行或设为不限制；厂商提供“自启动”选项时也可打开。应用内提供设置入口，各品牌菜单不同。

平时由安卓系统连接已保存的 Wi-Fi；应用负责连接后的网页登录认证。自动认证开启后可以返回桌面，通知栏显示状态并提供“停止”按钮。重启手机或更新应用后，点击“恢复校园网自动认证”通知，或者打开应用，即可继续监听。

## 为什么需要位置权限

本应用严格比较 Wi-Fi 名称，只有匹配保存的 `ahu.portal` 才会向学校提交凭据。Android 把 SSID 视为位置信息，读取它需要精确位置权限及系统定位；使用 `location` 前台服务让用户开启的监听在返回桌面后继续工作。应用没有请求 GPS 坐标，也没有申请“始终允许”的后台位置权限。

Android 限制从后台启动需要使用期间位置权限的前台服务，因此本版本开机时显示恢复通知，由用户点开应用恢复，而非直接在开机广播里启动。强行停止应用后，系统可能连开机通知也不再发送，需手动打开应用。

依据：[Wi-Fi 信息权限](https://developer.android.com/reference/android/net/wifi/WifiInfo)、[前台服务类型和位置服务限制](https://developer.android.com/develop/background-work/services/fgs/service-types)。

## 自动认证与凭据

- 监听 Wi-Fi 连接和地址变化，无需等待 Wi-Fi 已经可以访问互联网。认证 HTTP 请求通过 `Network.openConnection` 明确绑定到对应 Wi-Fi，并绕过 HTTP 代理，避免手机默认走移动数据导致认证失败。
- 每次重新读取学校的 `drcom/chkstatus`、`a79.htm`、`a41.js` 和网段配置；只适配配置中的 Portal 方法 1。
- 当前 IP 取自服务器并与所选 Wi-Fi 网卡 IPv4 核对，不会固定使用分享链接里的旧 IP。AC IP 使用 `172.16.253.1`；AC 名称为空。
- 只有 Wi-Fi 名称、网卡地址、学校认证页面和网段协议符合配置后，才提交账号密码。更换 Wi-Fi、停用认证或修改配置时，会在下一次 HTTP 请求前重新检查。
- 成功后约每分钟检查在线状态；临时故障逐步退避，间隔最多两分钟。账号拒绝或配置不兼容时暂停提交，修改后保存配置恢复。手动点击“立即认证一次”也会保存配置并允许重新尝试。
- 登录返回成功后再次查询在线状态，不直接把“IP 已在线”当作认证成功。一次认证期间短暂持有部分唤醒锁，最多两分钟，空闲时不持有；系统深度休眠仍可能使回调或重试延迟。
- 账号、密码、SSID、运营商、控制器 IP 作为一个整体使用 Android Keystore 的 AES-GCM 密钥加密保存；禁止系统备份。不读取、导入 Windows DPAPI 凭据。手机丢失密钥或清除应用数据后需要重新填写。
- 不输出认证 URL、账号密码或服务器原始错误，不上传到第三方服务器。应用仅允许访问 `172.16.253.3` 的 HTTP 80/801 端口，禁止重定向。
- 学校现有接口使用 HTTP；本地加密保存不改变学校接口的网络传输方式。

Wi-Fi 名称和页面特征不能提供 HTTPS 级别的服务器身份认证。当前协议与学校原有网页登录相同。

网络绑定实现依据：[Android Network.openConnection](https://developer.android.com/reference/android/net/Network#openConnection(java.net.URL,%20java.net.Proxy))。

## 检查与排查

当前已完成 18 项离线 Java 协议检查、APK 编译、签名和包清单校验，以及原 Windows 版本 22 项回归测试。**尚未连接安卓真机或模拟器进行安装和界面测试，也未验证该手机在校园网上的真实登录。** 构建使用 SDK 35，最低 Android 8，可在 Android 16 安装；Android 16 的后台权限和厂商省电行为需实机验证。

建议手机首次验证：

1. 连接 `ahu.portal`，填写配置并执行“立即认证一次”，确认学校在线状态。
2. 开启自动认证，断开 Wi-Fi 后重新连接，确认无需再次手点认证。已在线时跳过重复登录属于正常行为。
3. 返回桌面或锁屏后测试断开重连，观察通知状态；必要时调整厂商后台和电池设置。
4. 切换到其他 Wi-Fi，确认显示等待状态。关闭自动认证后确认常驻通知消失。
5. 重启手机，点击恢复通知，并确认服务恢复。

常见情况：

- **无法读取 Wi-Fi 名称**：允许精确位置、开启系统定位，再打开应用。只有“大致位置”不够。
- **等待连接指定 Wi-Fi**：检查手机已连上 `ahu.portal`，大小写和字符完全一致。
- **认证服务器不可达**：保持连接校园 Wi-Fi；VPN 开启“阻止未通过 VPN 的连接”时可能阻断绑定请求，可暂时关闭后排查。
- **认证未通过**：检查账号、密码、运营商、套餐或设备数限制，保存后重试。程序不会无限重复错误密码。
- **IP 不符或协议不兼容**：应用会停止提交凭据，请使用学校网页确认页面或网段是否已变更。
- **锁屏后不自动运行**：允许后台运行、关闭该应用的电池限制，保留常驻通知，确认系统没有强行停止它。前台服务无法保证绕过所有厂商的清理策略。
- **重启无通知**：确认允许通知；也可直接打开应用恢复。

## 本地构建

源码是 Java 和安卓平台 API，没有第三方运行依赖。可在 Android Studio 打开此目录，由 Gradle 同步构建；项目使用 Android Gradle Plugin 8.9.2、SDK 35，需要兼容的 Gradle 版本（8.11.1）及 JDK 17 或更高版本。也可使用已提供的 PowerShell 脚本，无需安装 Gradle：

```powershell
.\build-apk.ps1 -JavaHome '你的 JDK 目录' -SdkRoot '你的 Android SDK 目录'
```

SDK 需包含 `platforms;android-35` 和 `build-tools;35.0.0`。可以设置 `JAVA_HOME` 和 `ANDROID_HOME`，或通过参数指定它们。未传 `-SdkRoot` 时，脚本使用本项目 `.tooling` 下准备的官方工具；源码仓库不包含 SDK 或 JDK。

`.tooling` 的离线布局：

```text
.tooling/platform/android-35/android.jar
.tooling/build-tools/android-15/aapt2.exe
.tooling/build-tools/android-15/lib/d8.jar
.tooling/build-tools/android-15/lib/apksigner.jar
```

需要重新准备时，从 Google 官方 SDK 仓库下载 `platform-35_r02.zip` 和 `build-tools_r35_windows.zip` 并分别解压到上述目录，保留压缩包内的顶层目录。官方仓库元数据 SHA-1 为：

```text
platform-35_r02.zip       0bb560a90a7a2cbd0dd8348224d518b638fe7949
build-tools_r35_windows.zip af059bb67cf7786f45ee0db85e2d24985df1b4b6
```

来源：[Android SDK 官方仓库元数据](https://dl.google.com/android/repository/repository2-3.xml)。构建脚本不自动下载或安装工具。

安装包使用本地生成的个人开发签名，密钥在 `.signing/personal.keystore`，开发密码为 `android`。请保留该目录，以便后续覆盖更新；它是忽略文件，不要上传到代码仓库。本安装包用于个人侧载，正式发布应使用正式签名。Gradle 的默认 debug 签名与本脚本不同，切换构建方式可能需要卸载旧包并重新配置。

协议测试不需要手机；仅在 JVM 测试时使用 `org.json:json:20240303`（可从 Maven Central 下载到 `.tooling/json-20240303.jar`，APK 使用系统自带的 JSON 类，不包含该文件）：

```powershell
.\test-protocol.ps1 -JavaHome '你的 JDK 目录'
```

## 文件

- `app/src/main/java/cn/edu/ahu/autologin/PortalProtocol.java`：无安卓网络依赖的认证协议和参数解析。
- `WifiTransport.java`：Wi-Fi 网络绑定、响应大小限制、编码处理和错误脱敏。
- `AutoLoginService.java`：前台监听、SSID 核对、重试、暂停与在线确认。
- `Settings.java`：Keystore 凭据加密和本地状态。
- `MainActivity.java`：中文设置界面及权限入口。
- `BootReceiver.java`：重启/更新后的恢复提醒。
- `tests/ProtocolTest.java`：离线协议验证，不使用真实账号或密码。
- `build-apk.ps1`、`test-protocol.ps1`：构建与测试入口。

# 安徽大学校园网自动认证

面向安徽大学 Dr.COM 校园网的个人自动认证工具，包含 Windows 有线版和原生 Android Wi-Fi 版。网络由系统连接，程序负责连接后的账号认证。

本项目为个人工具，与学校及认证系统厂商无隶属关系。

## 选择版本

| 平台 | 场景 | 启动与恢复 | 使用说明 |
| --- | --- | --- | --- |
| Windows 10/11 | 网线连接后认证 | 登录 Windows 桌面后自动运行 | [Windows 使用说明](docs/windows.md) |
| Android 8 及以上 | 连接指定校园 Wi-Fi 后认证 | 首次手动开启监听，手机重启后点击恢复通知 | [Android 使用与构建](android/README.md) |

## 下载源码

在本仓库页面点击 **Code → Download ZIP**，解压到固定目录后使用。也可以通过 Git 下载：

```powershell
git clone https://github.com/keven-71/ahu-campus-autologin.git
cd ahu-campus-autologin
```

Windows 入口需要从解压后的文件夹运行；启用自启动后请保持这个目录的位置不变。Android 安装包需要按构建说明生成，仓库首页并不包含可直接安装的 APK。

## Windows 快速开始

需要包含 Tkinter 的 Python 3.9 或更高版本，无需安装第三方 Python 依赖。

1. 下载或克隆本仓库，插好校园网网线。
2. 在项目根目录双击 `start.bat`。
3. 在本地填写账号、密码，选择与网页登录一致的运营商。
4. 点击“立即连接”验证，再点击“启用开机自动认证”。

自动运行时机为登录 Windows 桌面后。关闭设置窗口不会停止已经启用的后台认证；关闭功能请使用“关闭开机自动认证”按钮。

如果双击后没有出现设置窗口，在项目根目录打开 PowerShell，运行 `python campus_login.py` 查看启动信息。程序不会帮你登录 Windows，也不需要填写 Windows 账户密码。

## Android 快速开始

使用原生 Java 和 Android 平台 API，无第三方应用运行依赖。

1. 按 [Android 构建说明](android/README.md#本地构建)生成 APK 并安装到手机。
2. 连接校园 Wi-Fi，在应用中填写账号、密码和运营商；默认 SSID 为 `ahu.portal`。
3. 允许所需权限，先执行“立即认证一次”，确认成功后开启自动认证。

源码仓库不包含 APK、SDK 或签名密钥。Android 重启后的恢复方式、后台权限及已知限制见版本说明；尚未完成真机验证。

Android 需要精确位置权限及系统定位来读取 Wi-Fi 名称，并需要允许通知和后台运行。手机系统负责连接已保存的 Wi-Fi，应用不会代替系统开启 Wi-Fi 或连接任意网络。

## 主要行为

- 从学校认证服务获取当前 IP 和网段参数，避免使用旧链接中固定的设备 IP。
- 已认证时跳过重复登录，临时故障退避重试，账号拒绝后暂停反复提交。
- 登录请求完成后再次查询学校在线状态。
- 账号密码在设备本地加密保存：Windows 使用 DPAPI，Android 使用 Keystore。
- 不向第三方服务上传凭据，日志不记录认证 URL 或密码。

学校现有认证接口使用 HTTP；本地加密保存不会改变其网络传输方式。代码适配当前学校 Portal 协议，其他校园网或协议变化需另行适配。

## 常见问题

| 情况 | 处理方式 |
| --- | --- |
| 无法访问认证服务器 | 先确认已经连接校园网，再检查网线、Wi-Fi、DHCP 和 VPN 路由。 |
| 提示认证未通过 | 核对平时网页登录使用的账号、密码和运营商；修改后保存配置再重试。 |
| 提示已经认证 | 属于正常状态，程序会跳过重复登录。 |
| Windows 不再需要自启动 | 在设置窗口点击“关闭开机自动认证”，再关闭程序。 |
| Android 重启后没有自动认证 | 点击恢复通知或打开应用恢复监听，同时检查通知、位置和后台权限。 |
| 换电脑或重装应用后不能读取配置 | 在新设备重新填写凭据，不要复制旧设备的加密配置或密钥。 |

提交问题时可以提供操作系统版本、使用平台和脱敏后的提示文字，不要附上真实账号密码、完整认证请求或本地签名文件。

## 开发与验证

Windows 离线测试，在项目根目录运行：

```powershell
python -m py_compile campus_core.py windows_support.py campus_login.py
python -X utf8 -m unittest discover -s tests -v
```

Android 离线协议测试，需要 JDK 和测试用 JSON JAR：

```powershell
.\android\test-protocol.ps1 -JavaHome '你的 JDK 目录'
```

依赖准备方式见 [Android 文档](android/README.md#本地构建)。GitHub Actions 配置位于 `.github/workflows/checks.yml`，仅运行离线测试，不提交真实校园网凭据。离线测试不能替代真实设备和校园网登录验证。

本地验证范围：Windows 的 22 项测试已分别在 Python 3.9 和 3.13 下通过，Android 的 18 项 JVM 协议检查已通过。Android 的真机安装、界面、后台省电行为及真实校园网认证仍需要设备验证；GitHub 上的检查结果以上传后 Actions 页面显示为准。

## 项目结构

```text
campus_core.py           Windows 认证协议与在线检查
campus_login.py          Windows 设置界面与后台入口
windows_support.py      Windows 加密、启动项与互斥锁
start.bat               Windows 双击入口
tests/                  Windows 离线测试
android/                原生 Android 源码、构建脚本与协议测试
docs/                   Windows 详细说明与 GitHub 上传指南
.github/workflows/      自动检查
```

Windows 源码保持在根目录，已有启动项可以继续使用。`data/`、`research/`、Android 工具链、构建产物和签名密钥仅保留在本地，由 `.gitignore` 排除。

## 放到自己的 GitHub 仓库

步骤和日常更新命令见 [GitHub 上传指南](docs/github.md)。当前尚未指定开源许可证，公开复用前可按需要选定并添加 `LICENSE`。

# Windows / 网线使用说明

返回[项目首页](../README.md)。

## 第一次使用

需要 Windows 10/11 和包含 Tkinter 的 Python 3.9 或更高版本，无第三方 Python 依赖。推荐使用受支持的 Python 版本；标准 Windows 安装通常已包含 Tkinter。

1. 插好校园网网线，保持 IPv4 自动获取（DHCP）。
2. 在项目根目录双击 `start.bat`，填写平时网页认证使用的账号、密码。
3. 运营商选择与网页登录页相同的一项：校园用户、校园电信、校园联通、校园移动。默认“校园用户”，请按自己的账户修改。
4. 点击“立即连接”，确认提示“校园网认证成功，已确认在线”。如果已经在线，程序会跳过重复认证。
5. 点击“启用开机自动认证”。程序会保存配置、启动后台并注册当前 Windows 账户的登录启动项，然后可以关闭设置窗口。

自动运行时机是**登录 Windows 桌面后**。不会要求 Windows 登录密码或管理员权限。请在任务管理器的“启动应用”中保持此启动项启用。移动项目目录或更换 Python 路径后，需要重新点击“启用开机自动认证”。

## 自动运行行为

- 从认证服务器读取当前校园网 IP，再按学校页面的 IPv4 网段配置选择认证方式，不使用旧链接中的 `wlanuserip`。
- 使用 Dr.COM Portal（`172.16.253.3:801/eportal/?c=Portal&a=login`）。遇到其他认证方式的网段会给出提示。
- 后台约每分钟查询一次学校的在线状态，只在未认证时提交凭据；网络未就绪时逐步重试，不修改网卡配置、不自动拨号。
- 服务器返回账号或密码错误等拒绝结果时暂停认证，修改并保存配置后恢复，避免反复提交错误凭据。
- 登录结果会通过学校的 `drcom/chkstatus` 再次确认。此状态表示校园网已认证，无法保证所有外部网站都可以访问。
- “关闭开机自动认证”移除本程序的启动项，后台在当前请求结束后退出，不会主动注销已经建立的校园网连接。

## 凭据与日志

`data/settings.json` 中的账号和密码整体通过 Windows 当前账户的 DPAPI 加密。其他 Windows 账户或换电脑后通常无法解密，应重新配置。同一 Windows 账户下运行的其他软件仍可能读取凭据，请保护好电脑账户。

日志位于 `data/campus.log`，自动轮转，不记录账号、密码、认证 URL 或服务器原始错误消息。程序仅访问 `172.16.253.3` 的 80/801 端口，禁用系统 HTTP 代理和自动重定向。

学校接口使用 HTTP，本地加密保存不会把认证网络传输变成 HTTPS。

`data/` 已被 Git 忽略。迁移代码到其他电脑时，应在新电脑重新填写账号密码。

## 检测和排查

在项目根目录运行：

```powershell
python campus_login.py --diagnose  # 只读检测，不提交认证凭据
python campus_login.py --once      # 使用已保存凭据连接一次
python -m unittest discover -s tests -v
```

无法连接认证服务器时先检查网线、校园网网卡的 DHCP，以及 VPN 的路由设置。密码正确但认证失败时，检查运营商是否与网页登录一致、账户状态是否正常。页面出现验证码、认证协议或网段匹配规则更改时需更新适配。

## 实现依据

依据 2026-10-20 读取的学校 `a79.htm`、`a41.js`、`a42.js` 与 `config.js` 实现，没有依赖第三方登录服务。采集的原始页面仅留在本地 `research/`，不加入代码仓库。

Windows 行为参考：[Microsoft DPAPI 文档](https://learn.microsoft.com/en-us/windows/win32/api/dpapi/nf-dpapi-cryptprotectdata)、[当前用户 Run 启动项文档](https://learn.microsoft.com/en-us/windows/win32/setupapi/run-and-runonce-registry-keys)。

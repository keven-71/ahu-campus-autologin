# 将项目放到个人 GitHub 仓库

返回[项目首页](../README.md)。

本仓库保存源码、说明和测试。账号配置、日志、抓取的网页、Android SDK、构建目录、APK 和签名密钥均被 `.gitignore` 忽略，本地文件不会因此被删除。

## 第一次提交

在项目根目录打开 PowerShell。首次使用 Git 时，设置提交署名和邮箱；可以使用 GitHub 账号设置中的 noreply 邮箱。这是提交信息，不是登录凭据。以下命令不带 `--global`，只作用于当前项目。

```powershell
# 仅当目录尚未初始化 Git 时执行
git init -b main

git config user.name "你的提交署名"
git config user.email "你的GitHub提交邮箱"

git add .
git diff --cached --stat
git diff --cached --name-only
git commit -m "Initial import of Windows and Android campus login tools"
```

检查暂存清单，确认没有 `data/`、`research/`、`.signing/`、`.tooling/`、APK、密钥或本机配置文件。

## 上传到空仓库

将下面的地址替换为自己的实际仓库地址。仅在远程仓库没有提交时使用这组命令：

```powershell
git remote add origin https://github.com/YOUR_USERNAME/YOUR_REPOSITORY.git
git push -u origin main
```

首次通过 HTTPS 推送时，按 Git Credential Manager 的提示在浏览器中登录。不要把 GitHub 密码或访问令牌写进源码或远程 URL。

## 远程仓库已有 README 或其他代码

先读取远程历史，再决定如何合并。不要直接强制推送覆盖已有提交。最直观的方法是把远程仓库克隆到另一个目录，将本项目准备发布的源码复制进去，再提交合并后的文件。

如果由助手代为接入，应先提供仓库链接，让助手检查远程内容后选择合适的导入方式。

## 以后更新

```powershell
git status
git add .
git diff --cached --stat
git commit -m "Describe the change"
git push
```

`.github/workflows/checks.yml` 会在推送和拉取请求时运行 Windows Python 测试，以及 Android 的 JVM 协议测试。测试使用虚构凭据，不会连接校园网或需要真实账号；Android 测试下载固定版本的 JSON 测试依赖并检查 SHA-256。工作流不自动发布或签名 APK，也不能替代真机验证。

## APK 和签名

需要提供安装包时，将确认过的 APK 作为 GitHub Release 附件上传，避免把二进制构建产物混入源码提交。Android 签名密钥请单独备份在私人位置，后续覆盖更新仍需相同密钥。

## 许可证

当前尚未指定开源许可证。如果准备公开并允许他人复用代码，可以选定许可证后再添加 `LICENSE`；无需仅为个人私有备份而先选择开源许可。

参考：[GitHub 本地代码上传指南](https://docs.github.com/en/migrations/importing-source-code/using-the-command-line-to-import-source-code/adding-locally-hosted-code-to-github)、[仓库许可证说明](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/licensing-a-repository)。

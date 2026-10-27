"""设置界面、命令行及开机后台入口。"""
import argparse
import logging
from logging.handlers import RotatingFileHandler
import queue
import subprocess
import sys
import threading
import time
import tkinter as tk
from tkinter import messagebox, ttk

from campus_core import (AuthenticationError, CampusClient, CampusError,
                         OPERATORS, TemporaryError, account_with_suffix, current_ip)
from windows_support import (DATA, ROOT, SETTINGS, Mutex, launch_command,
                             load_credentials, save_credentials, set_startup, startup_enabled)


def make_logger():
    DATA.mkdir(exist_ok=True)
    logger = logging.getLogger("ahu")
    if not logger.handlers:
        handler = RotatingFileHandler(DATA / "campus.log", maxBytes=256000, backupCount=2, encoding="utf-8")
        handler.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(message)s"))
        logger.addHandler(handler)
        logger.setLevel(logging.INFO)
    return logger


def connect_once(notify=lambda message: None):
    mutex = Mutex("authentication")
    if not mutex.acquire(1000):
        raise TemporaryError("另一个连接任务正在运行，稍后会重新检测。")
    try:
        username, password, operator = load_credentials()
        return CampusClient().ensure_online(username, password, operator, notify)
    finally:
        mutex.close()


def watch():
    logger = make_logger()
    mutex = Mutex("watcher")
    if not mutex.acquire():
        return 0
    logger.info("后台自动认证已启动。")
    delay, last_message, blocked_version = 5, None, None
    try:
        while startup_enabled():
            version = SETTINGS.stat().st_mtime_ns if SETTINGS.exists() else None
            if blocked_version is not None and blocked_version == version:
                time.sleep(5)
                continue
            try:
                message = connect_once()
                delay, blocked_version = 60, None
            except AuthenticationError as exc:
                message = str(exc) + " 已暂停认证，修改并保存配置后继续。"
                blocked_version, delay = version, 5
            except TemporaryError as exc:
                message = str(exc)
                delay = min(max(delay, 5) * 2, 60)
            except CampusError as exc:
                message, delay = str(exc), 60
            except Exception:
                # 不输出 traceback/异常对象，避免第三方或底层异常包含认证 URL。
                message, delay = "发生未预期错误，请打开设置窗口检查配置。", 60
            if message != last_message:
                logger.info(message)
                last_message = message
            # 分段等待以响应关闭自启；关闭后台后最多约五秒退出。
            for _ in range(max(1, int(delay / 5))):
                time.sleep(5)
                if not startup_enabled():
                    return 0
        return 0
    finally:
        mutex.close()
        logger.info("后台自动认证已停止。")


class App:
    def __init__(self, root):
        self.root = root
        self.events = queue.Queue()
        self.busy = False
        root.title("安徽大学 · 校园网自动认证")
        root.geometry("650x510")
        root.minsize(620, 490)
        style = ttk.Style()
        if "vista" in style.theme_names():
            style.theme_use("vista")
        style.configure("TLabel", font=("Microsoft YaHei UI", 10))
        style.configure("TButton", font=("Microsoft YaHei UI", 10), padding=7)
        frame = ttk.Frame(root, padding=24)
        frame.pack(fill="both", expand=True)
        frame.columnconfigure(1, weight=1)
        ttk.Label(frame, text="开机后，自动连上校园网", font=("Microsoft YaHei UI", 17, "bold")).grid(
            row=0, column=0, columnspan=3, sticky="w", pady=(0, 6))
        ttk.Label(frame, text="安徽大学 · 网线连接 · 登录 Windows 后运行").grid(
            row=1, column=0, columnspan=3, sticky="w", pady=(0, 20))
        self.username = tk.StringVar()
        self.password = tk.StringVar()
        self.operator = tk.StringVar(value="校园用户")
        for row, title in ((2, "校园网账号"), (3, "校园网密码"), (4, "运营商")):
            ttk.Label(frame, text=title).grid(row=row, column=0, sticky="w", padx=(0, 16), pady=7)
        ttk.Entry(frame, textvariable=self.username).grid(row=2, column=1, columnspan=2, sticky="ew")
        self.password_entry = ttk.Entry(frame, textvariable=self.password, show="●")
        self.password_entry.grid(row=3, column=1, sticky="ew")
        self.reveal = tk.BooleanVar()
        ttk.Checkbutton(frame, text="显示", variable=self.reveal,
                        command=lambda: self.password_entry.configure(show="" if self.reveal.get() else "●")).grid(
            row=3, column=2, padx=(10, 0))
        ttk.Combobox(frame, textvariable=self.operator, values=list(OPERATORS), state="readonly").grid(
            row=4, column=1, columnspan=2, sticky="ew")
        ttk.Label(frame, text="运营商请选择与校园网网页登录页相同的一项。").grid(
            row=5, column=1, columnspan=2, sticky="w", pady=(2, 16))
        self.buttons = []
        row1 = ttk.Frame(frame)
        row1.grid(row=6, column=0, columnspan=3, sticky="ew", pady=4)
        row2 = ttk.Frame(frame)
        row2.grid(row=7, column=0, columnspan=3, sticky="ew", pady=4)
        for parent, title, command in (
            (row1, "保存配置", self.save), (row1, "立即连接", self.login),
            (row1, "检测校园网", self.diagnose),
            (row2, "启用开机自动认证", self.enable), (row2, "关闭开机自动认证", self.disable)):
            button = ttk.Button(parent, text=title, command=command)
            button.pack(side="left", padx=(0, 8))
            self.buttons.append(button)
        self.startup_text = tk.StringVar()
        ttk.Label(frame, textvariable=self.startup_text).grid(row=8, column=0, columnspan=3, sticky="w", pady=(12, 5))
        self.status = tk.StringVar(value="先保存账号信息，再点击“立即连接”测试。")
        ttk.Label(frame, textvariable=self.status, wraplength=590, foreground="#1f5592").grid(
            row=9, column=0, columnspan=3, sticky="nw", pady=6)
        ttk.Separator(frame).grid(row=10, column=0, columnspan=3, sticky="ew", pady=(14, 10))
        ttk.Label(frame, text="凭据由 Windows 当前账户加密保存。日志不记录账号和密码。\n关闭设置窗口后，已启用的后台认证仍会运行。",
                  foreground="#666666").grid(row=11, column=0, columnspan=3, sticky="w")
        if SETTINGS.exists():
            try:
                username, password, operator = load_credentials()
                self.username.set(username)
                self.password.set(password)
                self.operator.set(operator)
                self.status.set("配置已加载，可以立即连接或启用开机自动认证。")
            except CampusError as exc:
                self.status.set(str(exc))
        self.refresh_startup()
        # 根据 Windows 字体/DPI 调整最小尺寸，避免底部按钮或说明被裁切。
        root.update_idletasks()
        width, height = max(650, frame.winfo_reqwidth()), max(510, frame.winfo_reqheight())
        root.geometry("%dx%d" % (width, height))
        root.minsize(width, height)
        root.after(100, self.poll)

    def refresh_startup(self):
        self.startup_text.set("开机自动认证：" + ("已启用" if startup_enabled() else "未启用"))

    def save(self):
        try:
            account_with_suffix(self.username.get(), self.operator.get())
            save_credentials(self.username.get(), self.password.get(), self.operator.get())
            self.status.set("配置已加密保存。")
            return True
        except (CampusError, OSError) as exc:
            self.status.set(str(exc) if isinstance(exc, CampusError) else "配置保存失败，请检查目录写入权限。")
            return False

    def run_worker(self, work):
        if self.busy:
            return
        self.busy = True
        for button in self.buttons:
            button.configure(state="disabled")

        def worker():
            try:
                result = work()
            except CampusError as exc:
                result = str(exc)
            except Exception:
                result = "操作失败，请检查网络连接或目录权限。"
            self.events.put(("done", result))

        threading.Thread(target=worker, daemon=True).start()

    def login(self):
        if self.save():
            self.status.set("正在检测并连接校园网，请稍候……")
            self.run_worker(lambda: connect_once(lambda text: self.events.put(("status", text))))

    def diagnose(self):
        self.status.set("正在读取校园网状态，不会提交认证信息……")

        def work():
            client = CampusClient()
            state = client.status()
            if str(state.get("result")) == "1":
                return "校园网已认证。当前校园网 IPv4：" + current_ip(state)
            if str(state.get("result")) != "0":
                raise TemporaryError("暂时无法确认校园网在线状态。")
            info = client.discover(state)
            return "认证服务器可达，当前未认证。IPv4：%s；认证方式：%s。" % (info.address, info.method)

        self.run_worker(work)

    def enable(self):
        if not self.save():
            return
        try:
            set_startup(True)
            subprocess.Popen(launch_command(), cwd=str(ROOT), creationflags=subprocess.CREATE_NO_WINDOW)
            self.refresh_startup()
            self.status.set("开机自动认证已启用，后台已启动。下次登录 Windows 后会自动连接。")
        except (OSError, CampusError):
            self.refresh_startup()
            self.status.set("自动启动设置未完成，请检查 Windows 启动应用设置或目录权限。")

    def disable(self):
        try:
            set_startup(False)
            self.refresh_startup()
            self.status.set("开机自动认证已关闭，后台会在当前操作结束后退出。")
        except OSError:
            self.status.set("无法关闭开机自动认证，请检查 Windows 启动应用设置。")

    def poll(self):
        try:
            while True:
                kind, text = self.events.get_nowait()
                self.status.set(text)
                if kind == "done":
                    self.busy = False
                    for button in self.buttons:
                        button.configure(state="normal")
        except queue.Empty:
            pass
        self.root.after(100, self.poll)


def main():
    parser = argparse.ArgumentParser(description="安徽大学校园网自动认证")
    parser.add_argument("--watch", action="store_true", help="开机后台检测，需已启用自动认证")
    parser.add_argument("--once", action="store_true", help="使用已保存配置连接一次")
    parser.add_argument("--diagnose", action="store_true", help="只检测认证服务器，不提交账号密码")
    args = parser.parse_args()
    if args.watch:
        return watch()
    if args.once or args.diagnose:
        try:
            if args.diagnose:
                client = CampusClient()
                state = client.status()
                print("校园网状态：" + ("在线" if str(state.get("result")) == "1" else "未认证"))
                print("当前 IPv4：" + current_ip(state))
                if str(state.get("result")) == "0":
                    info = client.discover(state)
                    print("网段认证方式：%s；页面脚本版本：%s" % (info.method, info.version))
            else:
                print(connect_once())
            return 0
        except CampusError as exc:
            print(str(exc))
            return 1
    root = tk.Tk()
    App(root)
    root.mainloop()
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception:
        # pythonw 没有控制台，启动阶段仍提供可操作的错误信息。
        if "--watch" not in sys.argv:
            messagebox.showerror("校园网自动认证", "程序启动失败，请使用终端运行 python campus_login.py 检查环境。")
        sys.exit(1)

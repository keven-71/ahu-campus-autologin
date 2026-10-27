"""Windows 当前账户密码加密、开机启动及进程互斥。"""
import base64
import ctypes
from ctypes import wintypes
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import winreg

from campus_core import CampusError, OPERATORS

ROOT = Path(__file__).resolve().parent
DATA = ROOT / "data"
SETTINGS = DATA / "settings.json"
RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"
RUN_NAME = "AHUCampusAutoLogin"


class Blob(ctypes.Structure):
    _fields_ = [("cbData", wintypes.DWORD), ("pbData", ctypes.POINTER(ctypes.c_ubyte))]


def crypt(data, decrypt=False):
    crypt32 = ctypes.WinDLL("crypt32", use_last_error=True)
    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel32.LocalFree.argtypes = [ctypes.c_void_p]
    kernel32.LocalFree.restype = ctypes.c_void_p
    buffer = ctypes.create_string_buffer(data)
    source = Blob(len(data), ctypes.cast(buffer, ctypes.POINTER(ctypes.c_ubyte)))
    output = Blob()
    if decrypt:
        function = crypt32.CryptUnprotectData
        function.argtypes = [ctypes.POINTER(Blob), ctypes.c_void_p, ctypes.c_void_p,
                             ctypes.c_void_p, ctypes.c_void_p, wintypes.DWORD, ctypes.POINTER(Blob)]
    else:
        function = crypt32.CryptProtectData
        function.argtypes = [ctypes.POINTER(Blob), wintypes.LPCWSTR, ctypes.c_void_p,
                             ctypes.c_void_p, ctypes.c_void_p, wintypes.DWORD, ctypes.POINTER(Blob)]
    function.restype = wintypes.BOOL
    if not function(ctypes.byref(source), None, None, None, None, 1, ctypes.byref(output)):
        raise CampusError("Windows 无法%s凭据，请使用保存配置时的 Windows 账户。" % ("解密" if decrypt else "加密"))
    try:
        return ctypes.string_at(output.pbData, output.cbData)
    finally:
        if decrypt:
            ctypes.memset(output.pbData, 0, output.cbData)
        kernel32.LocalFree(output.pbData)


def save_credentials(username, password, operator):
    if not username.strip() or not password or operator not in OPERATORS:
        raise CampusError("请填写账号、密码并选择运营商。")
    encrypted = crypt(json.dumps({"username": username.strip(), "password": password},
                                ensure_ascii=False).encode("utf-8"))
    settings = {"version": 1, "operator": operator,
                "credentials": base64.b64encode(encrypted).decode("ascii")}
    DATA.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=DATA,
                                         suffix=".tmp", delete=False) as stream:
            temporary = Path(stream.name)
            json.dump(settings, stream, ensure_ascii=False, indent=2)
        os.replace(temporary, SETTINGS)
    finally:
        if temporary is not None and temporary.exists():
            temporary.unlink()


def load_credentials():
    if not SETTINGS.exists():
        raise CampusError("尚未配置校园网账号，请双击 start.bat 打开设置。")
    try:
        value = json.loads(SETTINGS.read_text(encoding="utf-8"))
        if value.get("version") != 1 or value.get("operator") not in OPERATORS:
            raise ValueError()
        credentials = json.loads(crypt(base64.b64decode(value["credentials"], validate=True), True))
        if not isinstance(credentials.get("username"), str) or not isinstance(credentials.get("password"), str):
            raise ValueError()
        return credentials["username"], credentials["password"], value["operator"]
    except CampusError:
        raise
    except (ValueError, TypeError, KeyError):
        raise CampusError("本地配置文件格式异常，请重新保存配置。") from None


def launch_command():
    interpreter = Path(sys.executable).with_name("pythonw.exe")
    if not interpreter.exists():
        raise CampusError("找不到 pythonw.exe，请使用 Windows Python 安装运行程序。")
    return subprocess.list2cmdline([str(interpreter), str(ROOT / "campus_login.py"), "--watch"])


def startup_enabled():
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY) as key:
            value, _ = winreg.QueryValueEx(key, RUN_NAME)
        return value == launch_command()
    except FileNotFoundError:
        return False


def set_startup(enabled):
    with winreg.CreateKeyEx(winreg.HKEY_CURRENT_USER, RUN_KEY, 0, winreg.KEY_SET_VALUE) as key:
        if enabled:
            winreg.SetValueEx(key, RUN_NAME, 0, winreg.REG_SZ, launch_command())
        else:
            try:
                winreg.DeleteValue(key, RUN_NAME)
            except FileNotFoundError:
                pass


class Mutex:
    def __init__(self, purpose):
        key = hashlib.sha256(str(ROOT).lower().encode("utf-8")).hexdigest()[:16]
        self.name = "Local\\AHUCampusAutoLogin-" + key + "-" + purpose
        self.api = ctypes.WinDLL("kernel32", use_last_error=True)
        self.api.CreateMutexW.argtypes = [ctypes.c_void_p, wintypes.BOOL, wintypes.LPCWSTR]
        self.api.CreateMutexW.restype = wintypes.HANDLE
        self.api.WaitForSingleObject.argtypes = [wintypes.HANDLE, wintypes.DWORD]
        self.api.WaitForSingleObject.restype = wintypes.DWORD
        self.api.ReleaseMutex.argtypes = [wintypes.HANDLE]
        self.api.CloseHandle.argtypes = [wintypes.HANDLE]
        self.handle = None
        self.acquired = False

    def acquire(self, milliseconds=0):
        self.handle = self.api.CreateMutexW(None, False, self.name)
        if not self.handle:
            raise CampusError("无法创建程序互斥锁。")
        result = self.api.WaitForSingleObject(self.handle, milliseconds)
        self.acquired = result in (0, 0x80)
        if not self.acquired:
            self.api.CloseHandle(self.handle)
            self.handle = None
        return self.acquired

    def close(self):
        if self.handle:
            if self.acquired:
                self.api.ReleaseMutex(self.handle)
            self.api.CloseHandle(self.handle)
            self.handle = None
            self.acquired = False

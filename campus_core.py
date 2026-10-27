"""安徽大学 Dr.COM 有线认证。仅使用 Python 标准库。"""
import gzip
import ipaddress
import json
import re
import time
from dataclasses import dataclass
from urllib import error, parse, request

HOST = "172.16.253.3"
BASE = "http://" + HOST
PORTAL = BASE + "/a79.htm"
EPORTAL = BASE + ":801/eportal/"
AC_IP = "172.16.253.1"
OPERATORS = {"校园用户": "@xyw", "校园电信": "@dx", "校园联通": "@lt", "校园移动": ""}


class CampusError(Exception):
    """可以向用户展示、不含请求地址和凭据的错误。"""


class TemporaryError(CampusError):
    pass


class AuthenticationError(CampusError):
    pass


class NoRedirect(request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        # 登录 URL 含凭据，绝不允许自动跳转到其他地址。
        return None


def decode_body(body, charset=None):
    if body.startswith(b"\x1f\x8b"):
        body = gzip.decompress(body)
    for encoding in (charset, "utf-8-sig", "gb18030"):
        if encoding:
            try:
                return body.decode(encoding)
            except (UnicodeError, LookupError):
                continue
    raise TemporaryError("认证服务器返回了无法识别的文本编码。")


def parse_jsonp(text):
    text = text.strip().lstrip("\ufeff")
    match = re.fullmatch(r"[A-Za-z_$][\w$]*\s*\((.*)\)\s*;?", text, re.S)
    try:
        value = json.loads(match.group(1) if match else text)
    except (ValueError, TypeError):
        raise TemporaryError("认证服务器未返回有效的 JSON/JSONP 数据。") from None
    if not isinstance(value, dict):
        raise TemporaryError("认证服务器返回的数据结构异常。")
    return value


def js_string(text, name, default=""):
    match = re.search(r"\b" + re.escape(name) + r"\s*[:=]\s*(['\"])(.*?)\1", text, re.S)
    return match.group(2).strip() if match else default


def js_number(text, name, default=None):
    match = re.search(r"\b" + re.escape(name) + r"\s*=\s*(\d+)", text)
    return int(match.group(1)) if match else default


def current_ip(values):
    for name in ("v46ip", "ss5", "v4ip"):
        try:
            address = ipaddress.IPv4Address(str(values.get(name, "")).strip())
        except ipaddress.AddressValueError:
            continue
        if address.is_private and not address.is_unspecified and not address.is_loopback and not address.is_link_local:
            return str(address)
    raise TemporaryError("尚未获取校园网 IPv4 地址，请检查网线和 DHCP 设置。")


def select_method(config, address):
    # 与认证页 getMatchPage 一致：按序选择首个启用的 IPv4 范围。
    if js_number(config, "pageSetting") != 2:
        raise CampusError("认证页的网段匹配方式已变化，需要更新程序。")
    rows = {}
    for index, _, value in re.findall(r"ipPageAry\[(\d+)\]\s*=\s*(['\"])(.*?)\2", config):
        if value:
            rows[int(index)] = value.split("|")
    selected = rows.get(0)
    numeric_ip = int(ipaddress.IPv4Address(address))
    try:
        for index in sorted(rows):
            row = rows[index]
            if index == 0 or len(row) < 8 or row[5] != "1":
                continue
            starts, ends = row[1].split(";"), row[2].split(";")
            if len(starts) != len(ends):
                raise ValueError()
            if any(int(ipaddress.IPv4Address(start)) <= numeric_ip <= int(ipaddress.IPv4Address(end))
                   for start, end in zip(starts, ends)):
                selected = row
                break
        method = int(selected[-2]) if selected else -1
    except (ValueError, IndexError):
        raise CampusError("认证服务器的网段配置无法解析。") from None
    if method != 1:
        raise CampusError("当前网段不使用已适配的 Portal 认证方式，请使用学校网页登录。")
    return method


def account_with_suffix(username, operator):
    if operator not in OPERATORS:
        raise CampusError("请选择与网页登录页一致的运营商。")
    username = username.strip()
    suffix = OPERATORS[operator]
    if not username or not re.fullmatch(r"[A-Za-z0-9_.@-]+", username):
        raise CampusError("账号格式不正确，请填写校园网账号。")
    # 已填写完整后缀时保留，不重复添加，也不自动改写用户填写的账号。
    return username if "@" in username else username + suffix


@dataclass
class PortalInfo:
    address: str
    mac: str
    method: int
    version: str
    prefix: str
    ac_ip: str
    ac_name: str


class CampusClient:
    def __init__(self, timeout=8):
        # 校园网是内网服务，避免被系统代理/VPN 的 HTTP 代理带到外网。
        self.opener = request.build_opener(request.ProxyHandler({}), NoRedirect())
        self.timeout = timeout

    def get(self, url, params=None):
        target = parse.urlsplit(url)
        if target.scheme != "http" or target.hostname != HOST or target.port not in (None, 80, 801):
            raise CampusError("程序只允许访问已配置的安徽大学认证服务器。")
        if params:
            url += ("&" if "?" in url else "?") + parse.urlencode(params, quote_via=parse.quote)
        req = request.Request(url, headers={
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AHUAutoLogin/1.0",
            "Referer": PORTAL,
            "Cache-Control": "no-cache",
        })
        try:
            with self.opener.open(req, timeout=self.timeout) as response:
                body = response.read(2 * 1024 * 1024 + 1)
                if len(body) > 2 * 1024 * 1024:
                    raise TemporaryError("认证服务器返回的数据过大。")
                return decode_body(body, response.headers.get_content_charset())
        except error.HTTPError as exc:
            code = exc.code
            exc.close()
            raise TemporaryError("认证服务器返回 HTTP %s，请稍后重试。" % code) from None
        except (error.URLError, OSError, EOFError):
            # urllib 的原始异常可能含有密码所在的 URL，不能记录或展示。
            raise TemporaryError("暂时无法访问校园网认证服务器，请检查网线或稍后重试。") from None

    def status(self):
        return parse_jsonp(self.get(BASE + "/drcom/chkstatus", {
            "callback": "dr1001", "v": time.time_ns(),
        }))

    def discover(self, status):
        page = self.get(PORTAL)  # 不携带用户之前链接中可能已经过期的 wlanuserip。
        if "Dr.COMWebLoginID_" not in page:
            raise CampusError("目标网页不是已适配的 Dr.COM 认证页。")
        values = {name: js_string(page, name) for name in ("v46ip", "ss5", "v4ip")}
        address = current_ip(values)
        if address != current_ip(status):
            raise TemporaryError("校园网地址正在变化，稍后会重新获取。")
        file_version = js_string(page, "fileVersion")
        script = self.get(BASE + "/a41.js", {"version": file_version})
        program = js_string(script, "name")
        if not re.fullmatch(r"\d+", program):
            raise CampusError("认证页方案编号发生变化，需要更新程序。")
        if js_number(script, "enMd5", 0) or js_number(script, "enHttps", 0):
            raise CampusError("认证页的密码传输方式已变化，需要更新程序。")
        config = self.get(EPORTAL + "extern/" + program + "/config.js", {"version": file_version})
        prefix = ""
        if js_number(script, "accountPrefix", 0) == 1:
            prefix = ",a," if js_number(script, "customPerceive", 0) == 1 else ",0,"
        mac = str(status.get("ss4") or js_string(page, "ss4", "000000000000"))
        mac = re.sub(r"[:-]", "", mac)
        if not re.fullmatch(r"[0-9A-Fa-f]{12}", mac):
            raise TemporaryError("认证服务器返回的设备地址格式异常。")
        return PortalInfo(address, mac, select_method(config, address),
                          js_string(script, "jsVersion", "3.3.2"), prefix, AC_IP, "")

    def ensure_online(self, username, password, operator, notify=lambda message: None):
        state = self.status()
        if str(state.get("result")) == "1":
            return "校园网已认证，无需重复登录。"
        if str(state.get("result")) != "0":
            raise TemporaryError("暂时无法确认校园网在线状态。")
        info = self.discover(state)
        account = account_with_suffix(username, operator)
        if not password:
            raise CampusError("请先在设置窗口中填写密码并保存。")
        notify("已获取当前网段参数，正在提交校园网认证。")
        url = EPORTAL
        data = {"c": "Portal", "a": "login", "login_method": 1,
                "user_account": info.prefix + account, "user_password": password,
                "wlan_user_ip": info.address, "wlan_user_ipv6": "",
                "wlan_user_mac": info.mac, "wlan_ac_ip": info.ac_ip,
                "wlan_ac_name": info.ac_name, "jsVersion": info.version}
        data.update({"callback": "dr1003", "v": time.time_ns()})
        result = parse_jsonp(self.get(url, data))
        success = str(result.get("result")) in ("1", "ok")
        code = str(result.get("ret_code", ""))
        if not success and code != "2":
            if code in ("3", "6", "8"):
                raise TemporaryError("认证服务器繁忙或认证超时，稍后会重试。")
            # 不输出服务器原始 msg；部分服务器会在其中回显账号或请求参数。
            raise AuthenticationError("认证未通过（代码 %s）。请检查账号、密码、运营商或账户状态。"
                                      % (code if re.fullmatch(r"\d{1,4}", code) else "未知"))
        # 成功返回值或 ret_code=2 仍需确认实际在线，不把“IP已在线”直接当作成功。
        for delay in (1, 2, 3):
            time.sleep(delay)
            if str(self.status().get("result")) == "1":
                return "校园网认证成功，已确认在线。"
        raise TemporaryError("认证请求已完成，但暂未确认在线，稍后会重新检测。")

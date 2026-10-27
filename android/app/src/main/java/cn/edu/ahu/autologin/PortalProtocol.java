package cn.edu.ahu.autologin;

import org.json.JSONObject;
import java.net.URI;
import java.net.URLEncoder;
import java.util.*;
import java.util.regex.*;

/** Protocol only. No Android dependencies other than its built-in org.json. */
public final class PortalProtocol {
    public static final String HOST = "172.16.253.3";
    public static final String BASE = "http://" + HOST;
    public static final String PORTAL = BASE + "/a79.htm";
    public static final String EPORTAL = BASE + ":801/eportal/";
    public static final String[] OPERATORS = {"校园用户", "校园电信", "校园联通", "校园移动"};
    private static final String[] SUFFIXES = {"@xyw", "@dx", "@lt", ""};

    public static class Failure extends Exception {
        public final boolean temporary;
        public final boolean rejected;
        public Failure(String message, boolean temporary, boolean rejected) {
            super(message); this.temporary = temporary; this.rejected = rejected;
        }
    }
    public static Failure temporary(String message) { return new Failure(message, true, false); }
    public static Failure configuration(String message) { return new Failure(message, false, false); }
    public interface Transport { String get(String url, Map<String, String> params) throws Failure; }
    public interface Pause { void sleep(long milliseconds) throws InterruptedException; }
    private final Transport http;
    private final Pause pause;
    public PortalProtocol(Transport http, Pause pause) { this.http = http; this.pause = pause; }

    public static Map<String, String> params(String... pairs) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put(pairs[i], pairs[i + 1]);
        return result;
    }
    public static String query(Map<String, String> params) {
        StringBuilder result = new StringBuilder();
        try {
            for (Map.Entry<String, String> entry : params.entrySet()) {
                if (result.length() > 0) result.append('&');
                result.append(URLEncoder.encode(entry.getKey(), "UTF-8")).append('=')
                    .append(URLEncoder.encode(entry.getValue(), "UTF-8").replace("+", "%20"));
            }
        } catch (java.io.UnsupportedEncodingException impossible) { throw new AssertionError(); }
        return result.toString();
    }
    public static void checkUrl(String value) throws Failure {
        try {
            URI uri = new URI(value);
            if (!"http".equals(uri.getScheme()) || !HOST.equals(uri.getHost()) || uri.getUserInfo() != null
                    || uri.getFragment() != null || !(uri.getPort() == -1 || uri.getPort() == 80 || uri.getPort() == 801))
                throw configuration("只允许访问安徽大学认证服务器。");
        } catch (java.net.URISyntaxException invalid) { throw configuration("认证地址格式无效。"); }
    }
    public static JSONObject jsonp(String source) throws Failure {
        String value = source.trim();
        if (value.startsWith("\ufeff")) value = value.substring(1).trim();
        Matcher callback = Pattern.compile("[A-Za-z_$][\\w$]*\\s*\\((.*)\\)\\s*;?", Pattern.DOTALL).matcher(value);
        if (callback.matches()) value = callback.group(1);
        try {
            // JSONTokener's complete-consumption check also rejects trailing JavaScript.
            org.json.JSONTokener tokens = new org.json.JSONTokener(value);
            Object parsed = tokens.nextValue();
            if (!(parsed instanceof JSONObject) || tokens.nextClean() != 0) throw new Exception();
            return (JSONObject) parsed;
        } catch (Exception invalid) { throw temporary("认证服务器未返回有效的 JSON/JSONP 数据。"); }
    }
    public static String jsString(String source, String key, String fallback) {
        Matcher match = Pattern.compile("\\b" + Pattern.quote(key) + "\\s*[:=]\\s*(['\"])(.*?)\\1", Pattern.DOTALL).matcher(source);
        return match.find() ? match.group(2).trim() : fallback;
    }
    public static int jsNumber(String source, String key, int fallback) {
        Matcher match = Pattern.compile("\\b" + Pattern.quote(key) + "\\s*=\\s*(\\d+)").matcher(source);
        try { return match.find() ? Integer.parseInt(match.group(1)) : fallback; }
        catch (NumberFormatException invalid) { return fallback; }
    }
    public static long ip(String address) throws Failure {
        if (address == null || !address.matches("\\d{1,3}(\\.\\d{1,3}){3}")) throw configuration("IPv4 地址格式异常。");
        long result = 0;
        for (String part : address.split("\\.")) {
            int number = Integer.parseInt(part);
            if (number > 255) throw configuration("IPv4 地址格式异常。");
            result = (result << 8) | number;
        }
        return result;
    }
    public static String currentIp(JSONObject values) throws Failure {
        for (String key : new String[]{"v46ip", "ss5", "v4ip"}) {
            String value = values.optString(key, "").trim();
            try {
                long address = ip(value);
                if ((address >>> 24) == 10 || (address >>> 20) == 0xac1 || (address >>> 16) == 0xc0a8) return value;
            } catch (Failure ignored) { }
        }
        throw temporary("尚未获取校园 Wi-Fi 的 IPv4 地址。");
    }
    public static int selectMethod(String config, String address) throws Failure {
        if (jsNumber(config, "pageSetting", -1) != 2) throw configuration("学校网段匹配规则已变化，需要更新应用。");
        TreeMap<Integer, String[]> rows = new TreeMap<>();
        Matcher match = Pattern.compile("ipPageAry\\[(\\d+)\\]\\s*=\\s*(['\"])(.*?)\\2").matcher(config);
        try {
            while (match.find()) if (!match.group(3).isEmpty()) rows.put(Integer.parseInt(match.group(1)), match.group(3).split("\\|", -1));
            String[] selected = rows.get(0);
            long numeric = ip(address);
            for (Map.Entry<Integer, String[]> entry : rows.entrySet()) {
                String[] row = entry.getValue();
                if (entry.getKey() == 0 || row.length < 8 || !"1".equals(row[5])) continue;
                String[] starts = row[1].split(";"), ends = row[2].split(";");
                if (starts.length != ends.length) throw new IllegalArgumentException();
                boolean found = false;
                for (int i = 0; i < starts.length; i++) if (ip(starts[i]) <= numeric && numeric <= ip(ends[i])) found = true;
                if (found) { selected = row; break; }
            }
            if (selected == null || selected.length < 8) throw new IllegalArgumentException();
            int method = Integer.parseInt(selected[selected.length - 2]);
            if (method != 1) throw configuration("此网段使用其他认证协议，请使用学校网页认证。");
            return method;
        } catch (RuntimeException invalid) { throw configuration("学校网段配置无法解析。"); }
    }
    public static String account(String username, int operator) throws Failure {
        username = username.trim();
        if (!username.matches("[A-Za-z0-9_.@-]+") || operator < 0 || operator >= SUFFIXES.length)
            throw configuration("请填写有效的校园网账号并选择运营商。");
        return username.contains("@") ? username : username + SUFFIXES[operator];
    }
    private JSONObject status() throws Failure {
        return jsonp(http.get(BASE + "/drcom/chkstatus", params("callback", "dr1001", "v", String.valueOf(System.nanoTime()))));
    }
    public String ensureOnline(String username, String password, int operator, String acIp,
                               Set<String> wifiAddresses) throws Failure, InterruptedException {
        JSONObject state = status();
        String result = state.optString("result", "");
        if ("1".equals(result)) return "校园网已认证，无需重复登录。";
        if (!"0".equals(result)) throw temporary("暂时无法确认校园网在线状态。");
        String address = currentIp(state);
        if (!wifiAddresses.contains(address)) throw temporary("认证服务器返回的 IP 与当前 Wi-Fi 不符，已停止提交。");
        String page = http.get(PORTAL, Collections.emptyMap());
        if (!page.contains("Dr.COMWebLoginID_")) throw configuration("当前页面不是已适配的学校认证页。");
        JSONObject pageAddresses = new JSONObject();
        try { for (String key : new String[]{"v46ip", "ss5", "v4ip"}) pageAddresses.put(key, jsString(page, key, "")); }
        catch (org.json.JSONException impossible) { throw configuration("认证页面地址无法解析。"); }
        if (!address.equals(currentIp(pageAddresses))) throw temporary("Wi-Fi 地址正在变化，稍后重新获取。");
        String version = jsString(page, "fileVersion", "");
        String script = http.get(BASE + "/a41.js", params("version", version));
        String program = jsString(script, "name", "");
        if (!program.matches("\\d+")) throw configuration("学校认证方案已变化，需要更新应用。");
        if (jsNumber(script, "enMd5", 0) != 0 || jsNumber(script, "enHttps", 0) != 0)
            throw configuration("学校认证加密方式已变化，需要更新应用。");
        String config = http.get(EPORTAL + "extern/" + program + "/config.js", params("version", version));
        selectMethod(config, address);
        String mac = state.optString("ss4", jsString(page, "ss4", "000000000000")).replace(":", "").replace("-", "");
        if (!mac.matches("[0-9A-Fa-f]{12}")) throw temporary("认证服务器返回的设备地址格式异常。");
        String prefix = jsNumber(script, "accountPrefix", 0) == 1 ? (jsNumber(script, "customPerceive", 0) == 1 ? ",a," : ",0,") : "";
        if (password.isEmpty()) throw configuration("请先填写校园网密码。");
        ip(acIp);
        Map<String, String> data = params("c", "Portal", "a", "login", "login_method", "1",
                "user_account", prefix + account(username, operator), "user_password", password,
                "wlan_user_ip", address, "wlan_user_ipv6", "", "wlan_user_mac", mac,
                "wlan_ac_ip", acIp, "wlan_ac_name", "", "jsVersion", jsString(script, "jsVersion", "3.3.2"),
                "callback", "dr1003", "v", String.valueOf(System.nanoTime()));
        JSONObject reply = jsonp(http.get(EPORTAL, data));
        String code = reply.optString("ret_code", "");
        String success = reply.optString("result", "");
        if (!"1".equals(success) && !"ok".equals(success) && !"2".equals(code)) {
            if (Arrays.asList("3", "6", "8").contains(code)) throw temporary("认证服务器繁忙，稍后重试。");
            throw new Failure("认证未通过（代码 " + (code.matches("\\d{1,4}") ? code : "未知")
                    + "）。请检查账号、密码、运营商或账户状态。", false, true);
        }
        for (int delay : new int[]{1, 2, 3}) {
            pause.sleep(delay * 1000L);
            if ("1".equals(status().optString("result", ""))) return "校园网认证成功，已确认在线。";
        }
        throw temporary("已提交认证，但暂未确认在线，稍后重新检测。");
    }
}

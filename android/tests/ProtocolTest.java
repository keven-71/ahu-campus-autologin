package cn.edu.ahu.autologin;

import java.util.*;
import org.json.JSONObject;

/** Run with -ea. No Android device or real credentials; fake transport only. */
public final class ProtocolTest {
    static int cases;
    static final String OFFLINE = "dr1001({\"result\":0,\"v46ip\":\"172.29.3.107\",\"ss4\":\"00:11:22:33:44:55\"});";
    static final String PAGE = "<!--Dr.COMWebLoginID_0.htm-->v46ip='172.29.3.107';fileVersion='123';";
    static final String SCRIPT = "var page={name:'20180816'};var enMd5=0;var enHttps=0;var accountPrefix=0;var jsVersion='3.3.2';";
    static final String CONFIG = "var pageSetting=2;"
        + "ipPageAry[0]='default|0.0.0.0|255.255.255.255|||1|1|';"
        + "ipPageAry[1]='disabled|172.29.0.0|172.29.255.255|||0|8|admin';"
        + "ipPageAry[2]='wireless|172.28.0.0;172.29.0.0|172.28.255.255;172.29.255.255|||1|1|admin';"
        + "ipPageAry[3]='guest|10.4.1.2|10.4.1.254|||1|8|admin';";
    static final Set<String> LOCAL = Collections.singleton("172.29.3.107");
    interface Checked { void run() throws Exception; }
    static void check(String label, Checked test) throws Exception { test.run(); cases++; System.out.println("PASS " + label); }
    static void failure(boolean temporary, boolean rejected, Checked task) throws Exception {
        try { task.run(); throw new AssertionError("Expected sanitized failure"); }
        catch (PortalProtocol.Failure result) {
            assert result.temporary == temporary;
            assert result.rejected == rejected;
            assert !result.getMessage().contains("fake-secret");
        }
    }
    static class Fake implements PortalProtocol.Transport {
        final List<String> requests = new ArrayList<>();
        Map<String,String> submitted;
        String page = PAGE, script = SCRIPT, config = CONFIG, login = "{\"result\":1}";
        String status = OFFLINE;
        boolean confirmed = true;
        public String get(String url, Map<String,String> params) throws PortalProtocol.Failure {
            PortalProtocol.checkUrl(url);
            requests.add(url);
            if (url.endsWith("chkstatus")) return submitted != null && confirmed ? "{\"result\":1}" : status;
            if (url.equals(PortalProtocol.PORTAL)) { assert !params.containsKey("wlanuserip"); return page; }
            if (url.endsWith("a41.js")) return script;
            if (url.endsWith("config.js")) return config;
            assert url.equals(PortalProtocol.EPORTAL);
            submitted = new HashMap<>(params); return login;
        }
        String connect() throws Exception {
            return new PortalProtocol(this, delay -> {}).ensureOnline("student", "fake-secret &+=中文", 1, "172.16.253.1", LOCAL);
        }
    }
    public static void main(String[] arguments) throws Exception {
        if (!ProtocolTest.class.desiredAssertionStatus()) throw new AssertionError("Run with -ea");
        check("parse JSON and JSONP", () -> {
            assert PortalProtocol.jsonp("\ufeff dr1001({\"result\":1}); ").getInt("result") == 1;
            assert PortalProtocol.jsonp("{\"result\":0}").getInt("result") == 0;
        });
        check("reject trailing JavaScript and non-object data", () -> {
            for (String source : new String[]{"alert(1)", "dr1001({\"result\":1});alert(1)", "[]", "null", "{};alert(1)"})
                failure(true, false, () -> PortalProtocol.jsonp(source));
        });
        check("private IPv4 only", () -> {
            for (String address : new String[]{"172.29.3.107", "10.40.1.2", "192.168.2.3"})
                assert address.equals(PortalProtocol.currentIp(new JSONObject().put("v46ip", address)));
            for (String address : new String[]{"0.0.0.0", "127.0.0.1", "169.254.0.1", "8.8.8.8", "172.32.1.2", "999.1.1.1"})
                failure(true, false, () -> PortalProtocol.currentIp(new JSONObject().put("v46ip", address)));
        });
        check("wireless subnet, disabled row, inclusive boundaries", () -> {
            for (String address : new String[]{"172.29.3.107", "172.29.0.0", "172.29.255.255", "172.28.6.1"})
                assert PortalProtocol.selectMethod(CONFIG, address) == 1;
            failure(false, false, () -> PortalProtocol.selectMethod(CONFIG, "10.4.1.2"));
            failure(false, false, () -> PortalProtocol.selectMethod(CONFIG.replace("pageSetting=2", "pageSetting=3"), "172.29.3.107"));
        });
        check("operator suffixes", () -> {
            assert "student@xyw".equals(PortalProtocol.account(" student ", 0));
            assert "student@dx".equals(PortalProtocol.account("student@dx", 1));
            assert "student".equals(PortalProtocol.account("student", 3));
            failure(false, false, () -> PortalProtocol.account("student&password=x", 1));
        });
        check("allow only fixed school host and HTTP ports", () -> {
            for (String url : new String[]{"http://example.com/", "https://172.16.253.3/", "http://172.16.253.3:802/", "http://name@172.16.253.3/", "http://172.16.253.3/#x"})
                failure(false, false, () -> PortalProtocol.checkUrl(url));
        });
        check("query encoding preserves special password characters", () -> {
            String query = PortalProtocol.query(PortalProtocol.params("user_password", "a &+=中文"));
            assert query.equals("user_password=a%20%26%2B%3D%E4%B8%AD%E6%96%87");
        });
        check("already online skips login and page discovery", () -> {
            Fake fake = new Fake(); fake.status = "{\"result\":1}";
            assert fake.connect().contains("无需重复"); assert fake.requests.size() == 1; assert fake.submitted == null;
        });
        check("dynamic wireless IP, controller and MAC parameters", () -> {
            Fake fake = new Fake(); assert fake.connect().contains("已确认在线");
            assert fake.submitted.get("wlan_user_ip").equals("172.29.3.107");
            assert fake.submitted.get("wlan_ac_ip").equals("172.16.253.1");
            assert fake.submitted.get("wlan_user_mac").equals("001122334455");
            assert fake.submitted.get("user_account").equals("student@dx");
            assert fake.submitted.get("user_password").equals("fake-secret &+=中文");
            assert fake.requests.size() == 6;
        });
        check("unknown status never submits credentials", () -> {
            Fake fake = new Fake(); fake.status = "{}"; failure(true, false, fake::connect); assert fake.submitted == null;
        });
        check("server IP must belong to selected Wi-Fi network", () -> {
            Fake fake = new Fake(); fake.status = OFFLINE.replace("172.29.3.107", "172.29.3.108");
            failure(true, false, fake::connect); assert fake.requests.size() == 1; assert fake.submitted == null;
        });
        check("changing IP prevents credential submission", () -> {
            Fake fake = new Fake(); fake.page = PAGE.replace("172.29.3.107", "172.29.3.108");
            failure(true, false, fake::connect); assert fake.submitted == null;
        });
        check("unknown portal and changed crypto prevent submission", () -> {
            Fake fake = new Fake(); fake.page = "other portal";
            failure(false, false, fake::connect); assert fake.submitted == null;
            fake = new Fake(); fake.script = SCRIPT.replace("enMd5=0", "enMd5=1");
            failure(false, false, fake::connect); assert fake.submitted == null;
        });
        check("account prefix matches school script", () -> {
            Fake fake = new Fake(); fake.script = SCRIPT.replace("accountPrefix=0", "accountPrefix=1");
            fake.connect(); assert fake.submitted.get("user_account").equals(",0,student@dx");
        });
        check("server rejection pauses and never echoes raw message", () -> {
            Fake fake = new Fake(); fake.login = "{\"result\":0,\"ret_code\":1,\"msg\":\"fake-secret\"}";
            failure(false, true, fake::connect);
        });
        check("busy server is retryable", () -> {
            Fake fake = new Fake(); fake.login = "{\"result\":0,\"ret_code\":6}";
            failure(true, false, fake::connect);
        });
        check("IP already online still requires status confirmation", () -> {
            Fake fake = new Fake(); fake.login = "{\"result\":0,\"ret_code\":2}"; fake.confirmed = false;
            failure(true, false, fake::connect); assert fake.requests.size() == 8;
        });
        check("success reply still requires status confirmation", () -> {
            Fake fake = new Fake(); fake.confirmed = false; failure(true, false, fake::connect);
        });
        System.out.println("Passed " + cases + " protocol checks.");
    }
}

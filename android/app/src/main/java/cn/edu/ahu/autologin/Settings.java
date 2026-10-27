package cn.edu.ahu.autologin;

import android.content.*;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.UUID;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

final class Settings {
    private static final String KEY = "ahu-campus-credentials-v1";
    final SharedPreferences prefs;
    Settings(Context context) { prefs = context.getSharedPreferences("campus", Context.MODE_PRIVATE); }
    static final class Credentials {
        final String username, password, ssid, acIp, revision;
        final int operator;
        Credentials(String username, String password, int operator, String ssid, String acIp, String revision) {
            this.username = username; this.password = password; this.operator = operator;
            this.ssid = ssid; this.acIp = acIp; this.revision = revision;
        }
    }
    private javax.crypto.SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(KEY)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(KEY, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        return (javax.crypto.SecretKey) store.getKey(KEY, null);
    }
    void save(String username, String password, int operator, String ssid, String acIp) throws Exception {
        PortalProtocol.account(username, operator);
        PortalProtocol.ip(acIp);
        if (password.isEmpty() || ssid.trim().isEmpty()) throw new Exception("请填写密码和校园 Wi-Fi 名称。");
        JSONObject data = new JSONObject();
        data.put("username", username.trim()).put("password", password).put("operator", operator)
                .put("ssid", ssid.trim()).put("acIp", acIp.trim());
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        String encrypted = Base64.encodeToString(cipher.doFinal(data.toString().getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
        String iv = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP);
        if (!prefs.edit().putString("encrypted", encrypted).putString("iv", iv)
                .putString("revision", UUID.randomUUID().toString()).remove("blocked").commit())
            throw new Exception("配置保存失败，请检查手机存储空间。");
    }
    Credentials load() throws Exception {
        String encrypted = prefs.getString("encrypted", "");
        if (encrypted.isEmpty()) throw new Exception("请先保存校园网账号。");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(prefs.getString("iv", ""), Base64.NO_WRAP)));
        JSONObject data = new JSONObject(new String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), StandardCharsets.UTF_8));
        return new Credentials(data.getString("username"), data.getString("password"), data.getInt("operator"),
                data.getString("ssid"), data.getString("acIp"), prefs.getString("revision", ""));
    }
    boolean enabled() { return prefs.getBoolean("enabled", false); }
    void enabled(boolean enabled) { prefs.edit().putBoolean("enabled", enabled).commit(); }
    void status(String message) {
        prefs.edit().putString("status", message).putLong("statusTime", System.currentTimeMillis()).apply();
    }
    String status() { return prefs.getString("status", "填写账号并保存，然后开启自动认证。"); }
    boolean blocked(String revision) { return revision.equals(prefs.getString("blocked", "")); }
    void block(String revision) { prefs.edit().putString("blocked", revision).commit(); }
    void clearBlock() { prefs.edit().remove("blocked").commit(); }
}

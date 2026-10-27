package cn.edu.ahu.autologin;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.InputType;
import android.view.*;
import android.widget.*;

public final class MainActivity extends Activity {
    private static final int GREEN = Color.rgb(23, 107, 91), INK = Color.rgb(29, 47, 43);
    private cn.edu.ahu.autologin.Settings settings;
    private EditText username, password, ssid, acIp;
    private Spinner operator;
    private TextView status, badge;
    private Button auto;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int pending; // 1 = persistent monitoring; 2 = one check
    private final Runnable refresh = new Runnable() {
        @Override public void run() { updateStatus(); handler.postDelayed(this, 1000); }
    };
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private GradientDrawable background(int color, int radius) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d;
    }
    private TextView text(String value, int size, int color) {
        TextView result = new TextView(this); result.setText(value); result.setTextSize(size); result.setTextColor(color);
        result.setLineSpacing(dp(3), 1); return result;
    }
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        settings = new cn.edu.ahu.autologin.Settings(this);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(245, 246, 242));
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(28), dp(24), dp(28));
        scroll.addView(root);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(scroll);
        TextView mark = text("AHU  /  CAMPUS NETWORK", 11, GREEN);
        mark.setLetterSpacing(.13f); mark.setTypeface(null, Typeface.BOLD); root.addView(mark);
        TextView title = text("连上校园网，\n认证自动完成。", 30, INK);
        title.setTypeface(null, Typeface.BOLD); add(root, title, 14, 18);
        badge = text("", 13, GREEN); badge.setPadding(dp(14), dp(10), dp(14), dp(10));
        badge.setBackground(background(Color.rgb(225, 238, 231), 12)); add(root, badge, 0, 18);

        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(20), dp(20), dp(20)); card.setBackground(background(Color.WHITE, 18));
        root.addView(card, new LinearLayout.LayoutParams(-1, -2));
        TextView accountTitle = text("校园网账号", 19, INK); accountTitle.setTypeface(null, Typeface.BOLD); card.addView(accountTitle);
        username = field(card, "账号", "填写网页认证使用的账号", false);
        password = field(card, "密码", "填写校园网密码", true);
        add(card, text("运营商", 13, INK), 14, 6);
        operator = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, PortalProtocol.OPERATORS);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); operator.setAdapter(adapter);
        card.addView(operator, new LinearLayout.LayoutParams(-1, dp(48)));
        ssid = field(card, "仅在此 Wi-Fi 上认证", "ahu.portal", false); ssid.setText("ahu.portal");
        acIp = field(card, "认证控制器 IP", "172.16.253.1", false); acIp.setText("172.16.253.1");
        acIp.setInputType(InputType.TYPE_CLASS_PHONE);
        Button save = button("保存配置", false); add(card, save, 16, 0);
        save.setOnClickListener(view -> { if (save()) { toast("配置已加密保存"); if (settings.enabled()) begin(1); } });

        auto = button("开启自动认证", true); add(root, auto, 18, 0);
        auto.setOnClickListener(view -> {
            if (settings.enabled()) {
                settings.enabled(false); stopService(new Intent(this, AutoLoginService.class));
                settings.status("自动认证已关闭。"); updateStatus();
            } else if (save()) begin(1);
        });
        Button once = button("立即认证一次", false); add(root, once, 8, 0);
        once.setOnClickListener(view -> { if (save()) begin(2); });
        status = text("", 14, INK); status.setPadding(dp(2), dp(14), dp(2), dp(14)); add(root, status, 4, 0);

        TextView explanation = text("读取 Wi-Fi 名称需要精确位置权限和系统定位。本应用不读取 GPS 坐标。\n\n开启后请保留通知，并在手机设置中允许后台运行；重启后点恢复通知继续。", 12, Color.rgb(98, 112, 106));
        add(root, explanation, 4, 12);
        Button permissions = button("权限与应用设置", false); root.addView(permissions);
        permissions.setOnClickListener(view -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))));
        Button location = button("系统定位设置", false); add(root, location, 4, 0);
        location.setOnClickListener(view -> startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)));
        Button battery = button("后台省电设置", false); add(root, battery, 4, 0);
        battery.setOnClickListener(view -> {
            try { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); }
            catch (RuntimeException unavailable) { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); }
        });
        add(root, text("仅访问学校认证服务器 172.16.253.3。\n密码在手机本地加密保存；学校认证接口使用 HTTP。", 11, Color.rgb(98, 112, 106)), 20, 0);
        try {
            cn.edu.ahu.autologin.Settings.Credentials data = settings.load();
            username.setText(data.username); password.setText(data.password); operator.setSelection(data.operator);
            ssid.setText(data.ssid); acIp.setText(data.acIp);
        } catch (Exception unavailable) {
            if (settings.prefs.contains("encrypted")) settings.status("保存的凭据无法解密，请重新填写账号密码并保存。");
        }
        updateStatus();
    }
    private void add(LinearLayout parent, View view, int top, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(top); params.bottomMargin = dp(bottom); parent.addView(view, params);
    }
    private EditText field(LinearLayout parent, String label, String hint, boolean secret) {
        add(parent, text(label, 13, INK), 16, 5);
        EditText edit = new EditText(this); edit.setTextSize(16); edit.setTextColor(INK); edit.setHint(hint);
        edit.setHintTextColor(Color.rgb(141, 153, 146)); edit.setSingleLine(true); edit.setPadding(dp(12), dp(10), dp(12), dp(10));
        edit.setBackground(background(Color.rgb(245, 247, 243), 8));
        edit.setInputType(InputType.TYPE_CLASS_TEXT | (secret ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));
        edit.setSaveEnabled(false); edit.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        parent.addView(edit, new LinearLayout.LayoutParams(-1, dp(50))); return edit;
    }
    private Button button(String title, boolean filled) {
        Button button = new Button(this); button.setText(title); button.setAllCaps(false); button.setTextSize(15);
        button.setTextColor(filled ? Color.WHITE : GREEN); button.setMinHeight(dp(52));
        button.setPadding(dp(12), dp(10), dp(12), dp(10));
        button.setBackground(background(filled ? GREEN : Color.rgb(229, 237, 230), 12)); return button;
    }
    private boolean save() {
        try {
            settings.save(username.getText().toString(), password.getText().toString(), operator.getSelectedItemPosition(),
                    ssid.getText().toString(), acIp.getText().toString());
            return true;
        } catch (PortalProtocol.Failure invalid) { toast(invalid.getMessage()); return false; }
        catch (Exception invalid) { toast("保存失败，请检查账号、密码、Wi-Fi 名称、控制器 IP 和手机存储状态。"); return false; }
    }
    private void begin(int action) {
        pending = action;
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            new AlertDialog.Builder(this).setTitle("允许识别校园 Wi-Fi")
                    .setMessage("安卓将 Wi-Fi 名称列为位置信息。请选择允许使用期间访问，并开启精确位置；应用只用它核对校园 Wi-Fi 名称。")
                    .setPositiveButton("继续", (dialog, which) -> requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION}, 10))
                    .setNegativeButton("取消", (dialog, which) -> pending = 0).show(); return;
        }
        continueStart();
    }
    private void continueStart() {
        if (pending == 0) return;
        if (!AutoLoginService.permissionsReady(this)) {
            toast("请允许精确位置权限，并在系统设置中开启定位，然后再点击开启。"); pending = 0; return;
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !settings.prefs.getBoolean("askedNotification", false)) {
            settings.prefs.edit().putBoolean("askedNotification", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 11); return;
        }
        boolean monitoring = pending == 1;
        pending = 0;
        if (monitoring) settings.enabled(true);
        settings.clearBlock();
        Intent service = new Intent(this, AutoLoginService.class);
        if (!monitoring) service.setAction(AutoLoginService.ONCE);
        try { startForegroundService(service); getSystemService(NotificationManager.class).cancel(2); }
        catch (RuntimeException unavailable) { settings.enabled(false); settings.status("监听启动失败，请检查位置权限和系统后台运行设置。"); }
        updateStatus();
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == 10 || code == 11) continueStart();
    }
    private void updateStatus() {
        boolean enabled = settings.enabled();
        badge.setText(enabled ? "●  自动认证已开启 · 等待 / 监听 Wi-Fi" : "○  自动认证未开启");
        auto.setText(enabled ? "关闭自动认证" : "开启自动认证");
        String time = "";
        long timestamp = settings.prefs.getLong("statusTime", 0);
        if (timestamp > 0) time = "\n" + android.text.format.DateFormat.format("MM-dd HH:mm:ss", timestamp);
        status.setText(settings.status() + time);
    }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
    @Override public void onResume() {
        super.onResume(); handler.post(refresh);
        if (settings.enabled() && AutoLoginService.permissionsReady(this) && pending == 0) {
            try { startForegroundService(new Intent(this, AutoLoginService.class)); getSystemService(NotificationManager.class).cancel(2); }
            catch (RuntimeException unavailable) { settings.status("请点击关闭后重新开启自动认证。"); }
        }
    }
    @Override public void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
}

package cn.edu.ahu.autologin;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.location.LocationManager;
import android.net.*;
import android.net.wifi.*;
import android.os.*;
import java.net.Inet4Address;
import java.util.*;
import java.util.concurrent.*;

public final class AutoLoginService extends Service {
    static final String ONCE = "cn.edu.ahu.autologin.ONCE";
    static final String STOP = "cn.edu.ahu.autologin.STOP";
    static final String CHANNEL = "campus_connection";
    static final int NOTIFICATION = 1;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Map<Network, NetworkCapabilities> networks = new ConcurrentHashMap<>();
    private Settings settings;
    private ConnectivityManager connectivity;
    private WifiManager wifi;
    private ConnectivityManager.NetworkCallback callback;
    private volatile boolean stopped;
    private boolean checking, repeat;
    private volatile boolean once;
    private int retrySeconds = 5;
    private final Runnable checkTask = () -> check();

    static boolean permissionsReady(Context context) {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return false;
        LocationManager manager = context.getSystemService(LocationManager.class);
        if (Build.VERSION.SDK_INT >= 28) return manager.isLocationEnabled();
        return manager.isProviderEnabled(LocationManager.GPS_PROVIDER) || manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
    }
    static void channel(Context context) {
        context.getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, "校园网自动认证", NotificationManager.IMPORTANCE_LOW));
    }
    static PendingIntent openApp(Context context) {
        return PendingIntent.getActivity(context, 0, new Intent(context, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    @Override public void onCreate() {
        super.onCreate();
        settings = new Settings(this);
        connectivity = getSystemService(ConnectivityManager.class);
        wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        channel(this);
        Notification initial = notification("正在启动 Wi-Fi 监听…");
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, initial, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            else startForeground(NOTIFICATION, initial);
        } catch (RuntimeException unavailable) {
            settings.status("无法启动监听，请允许精确位置权限、开启系统定位，再打开应用恢复。");
            stopped = true; stopSelf(); return;
        }
        callback = Build.VERSION.SDK_INT >= 31 ? new Callback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) : new Callback();
        try {
            connectivity.registerNetworkCallback(new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED).build(), callback);
        } catch (RuntimeException unavailable) {
            update("无法监听网络变化，请重新打开应用。"); stopped = true; stopSelf();
        }
    }
    private final class Callback extends ConnectivityManager.NetworkCallback {
        Callback() { super(); }
        Callback(int flags) { super(flags); }
        @Override public void onAvailable(Network network) { schedule(1500); }
        @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
            networks.put(network, capabilities); schedule(1500);
        }
        @Override public void onLinkPropertiesChanged(Network network, LinkProperties properties) { schedule(1500); }
        @Override public void onLost(Network network) { networks.remove(network); schedule(1500); }
    }
    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (stopped) return START_NOT_STICKY;
        if (intent != null && STOP.equals(intent.getAction())) {
            settings.enabled(false); update("自动认证已关闭。"); stopSelf(); return START_NOT_STICKY;
        }
        once = intent != null && ONCE.equals(intent.getAction()) && !settings.enabled();
        if (!once && !settings.enabled()) { stopSelf(); return START_NOT_STICKY; }
        schedule(700);
        return once ? START_NOT_STICKY : START_STICKY;
    }
    private void schedule(long milliseconds) {
        main.post(() -> {
            if (stopped) return;
            main.removeCallbacks(checkTask);
            main.postDelayed(checkTask, milliseconds);
        });
    }
    private String ssid(Network network) {
        NetworkCapabilities caps = networks.get(network);
        if (caps == null) caps = connectivity.getNetworkCapabilities(network);
        if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return "";
        WifiInfo info = null;
        if (Build.VERSION.SDK_INT >= 29 && caps.getTransportInfo() instanceof WifiInfo) info = (WifiInfo) caps.getTransportInfo();
        if (info == null) info = wifi.getConnectionInfo();
        if (info == null) return "";
        String result = info.getSSID();
        if (result == null || WifiManager.UNKNOWN_SSID.equals(result)) return "";
        return result.startsWith("\"") && result.endsWith("\"") ? result.substring(1, result.length() - 1) : result;
    }
    private Set<String> addresses(Network network) {
        Set<String> addresses = new HashSet<>();
        LinkProperties properties = connectivity.getLinkProperties(network);
        if (properties != null) for (LinkAddress link : properties.getLinkAddresses())
            if (link.getAddress() instanceof Inet4Address) addresses.add(link.getAddress().getHostAddress());
        return addresses;
    }
    private void check() {
        if (stopped) return;
        if (!settings.enabled() && !once) { stopSelf(); return; }
        if (checking) { repeat = true; return; }
        if (!permissionsReady(this)) { finishIdle("需要精确位置权限并开启系统定位，才能确认校园 Wi-Fi 名称。"); return; }
        final Settings.Credentials credentials;
        try { credentials = settings.load(); }
        catch (Exception invalid) { finishIdle("无法读取已保存账号，请重新填写并保存配置。"); return; }
        if (settings.blocked(credentials.revision)) { finishIdle("认证已暂停，请检查账号、密码和运营商后重新保存。"); return; }
        Network candidate = null;
        boolean hidden = false;
        for (Network network : connectivity.getAllNetworks()) {
            String name = ssid(network);
            if (credentials.ssid.equals(name)) { candidate = network; break; }
            NetworkCapabilities caps = connectivity.getNetworkCapabilities(network);
            if (name.isEmpty() && caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) hidden = true;
        }
        if (candidate == null) {
            finishIdle(hidden ? "暂时无法读取 Wi-Fi 名称，请确认精确位置权限和系统定位已开启。" : "等待连接指定校园 Wi-Fi…");
            return;
        }
        final Network network = candidate;
        final Set<String> localAddresses = addresses(network);
        if (localAddresses.isEmpty()) { finishIdle("校园 Wi-Fi 正在获取 IPv4 地址…"); return; }
        checking = true; repeat = false;
        update("已连接校园 Wi-Fi，正在检测认证状态…");
        final PowerManager.WakeLock wakeLock = getSystemService(PowerManager.class)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AHUCampus:authentication");
        wakeLock.acquire(120000);
        worker.submit(() -> {
            String message; boolean temporary = false; boolean blocked = false;
            try {
                WifiTransport transport = new WifiTransport(network, () -> !stopped && (settings.enabled() || once)
                        && credentials.revision.equals(settings.prefs.getString("revision", ""))
                        && permissionsReady(this) && credentials.ssid.equals(ssid(network))
                        && addresses(network).equals(localAddresses));
                message = new PortalProtocol(transport, Thread::sleep).ensureOnline(credentials.username, credentials.password,
                        credentials.operator, credentials.acIp, localAddresses);
                // Ask Android to re-evaluate a portal network after the school confirms authentication.
                connectivity.reportNetworkConnectivity(network, true);
            } catch (PortalProtocol.Failure failure) {
                message = failure.getMessage(); temporary = failure.temporary; blocked = !failure.temporary;
            } catch (InterruptedException cancelled) { return; }
            catch (Exception unexpected) { message = "认证检测失败，请重新打开应用检查权限与配置。"; temporary = true; }
            finally { if (wakeLock.isHeld()) wakeLock.release(); }
            final String result = message;
            final boolean retry = temporary, pause = blocked;
            main.post(() -> {
                checking = false;
                if (stopped) return;
                if (pause) settings.block(credentials.revision);
                update(result + (pause ? " 已暂停，修改配置后重试。" : ""));
                if (once || !settings.enabled()) { stopSelf(); return; }
                retrySeconds = retry ? Math.min(retrySeconds * 2, 120) : 60;
                schedule(repeat ? 1500 : retrySeconds * 1000L);
            });
        });
    }
    private void finishIdle(String message) {
        update(message);
        if (once) stopSelf(); else schedule(60000);
    }
    private Notification notification(String message) {
        Intent stop = new Intent(this, AutoLoginService.class).setAction(STOP);
        PendingIntent pending = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_status)
                .setContentTitle("安大校园网 · 自动认证").setContentText(message).setStyle(new Notification.BigTextStyle().bigText(message))
                .setContentIntent(openApp(this)).setOngoing(true).setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null, "停止", pending).build()).build();
    }
    private void update(String message) {
        settings.status(message);
        getSystemService(NotificationManager.class).notify(NOTIFICATION, notification(message));
    }
    @Override public void onDestroy() {
        stopped = true;
        main.removeCallbacksAndMessages(null);
        if (callback != null) try { connectivity.unregisterNetworkCallback(callback); } catch (RuntimeException ignored) { }
        worker.shutdownNow();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}

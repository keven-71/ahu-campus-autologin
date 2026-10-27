package cn.edu.ahu.autologin;

import android.app.*;
import android.content.*;

public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!(Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction()))) return;
        Settings settings = new Settings(context);
        if (!settings.enabled()) return;
        // Android restricts starting a location FGS at boot without background location.
        // Resume from a user-visible Activity instead; no background location permission is requested.
        settings.status("手机已重启或应用已更新，请打开应用恢复自动认证。");
        AutoLoginService.channel(context);
        context.getSystemService(NotificationManager.class).notify(2, new Notification.Builder(context, AutoLoginService.CHANNEL)
                .setSmallIcon(R.drawable.ic_status).setContentTitle("恢复校园网自动认证")
                .setContentText("点击恢复，之后连接 ahu.portal 会自动认证。")
                .setContentIntent(AutoLoginService.openApp(context)).setAutoCancel(true).build());
    }
}

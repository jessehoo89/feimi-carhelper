package com.feimi.car.proxy;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.session.MediaController;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * 音源代理前台服务：把目标 App 的播放状态持续翻译给车机媒体中心。
 *
 * 生命周期：
 *   onCreate  → 初始化 EAS 桥接 + 订阅 MediaSession
 *   周期任务  → 每 20s 重订阅一次（通知使用权可能后授予；目标 App 可能重建 session）
 *   停止      → 注销会话订阅，桥接残留由下次 start 覆盖
 *
 * 之所以要前台服务：进程被系统回收后方向盘按键就没人接了；前台服务能显著降低被回收概率。
 */
public class ProxyService extends Service {

    public static final String ACTION_START = "com.feimi.car.proxy.START";
    public static final String ACTION_STOP = "com.feimi.car.proxy.STOP";

    /** 供界面显示"接入服务"状态。 */
    public static volatile boolean running = false;

    private static final String CH_ID = "car_proxy";
    private static final int CH_NOTI_ID = 0x9A01;
    private static final long RESCAN_MS = 20000L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean rescanRunning;

    private final Runnable rescanTask = new Runnable() {
        @Override
        public void run() {
            try {
                SessionWatcher.rescan();
                MediaController c = SessionWatcher.controller();
                ProxyConfig.log("心跳: controller=" + (c == null ? "无" : c.getPackageName())
                        + " ready=" + MediaCenterBridge.isReady());
            } catch (Throwable t) {
                ProxyConfig.log("心跳异常: " + t);
            }
            main.postDelayed(this, RESCAN_MS);
        }
    };

    public static void start(Context ctx) {
        Intent i = new Intent(ctx, ProxyService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }

    public static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, ProxyService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        ProxyConfig.init(this);
        ProxyConfig.log("ProxyService.onCreate，目标=" + ProxyConfig.targetPkg());
        startForegroundSafely();
        try {
            MediaCenterBridge.init(this);
            SessionWatcher.attach(this);
        } catch (Throwable t) {
            ProxyConfig.log("服务初始化失败: " + t);
        }
        if (!rescanRunning) {
            rescanRunning = true;
            main.postDelayed(rescanTask, RESCAN_MS);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(a)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        // 车机用 startService 形式发"恢复上次音源"时走这里
        if (MediaCenterBridge.ACTION_RECOVER.equals(a)) {
            ProxyConfig.log("[恢复] 服务收到 RECOVER，转发 play");
            ControlForwarder.action("play");
        }
        // 每次被拉起都重挂一次，避免"服务活着但订阅掉了"
        try {
            SessionWatcher.attach(this);
        } catch (Throwable ignored) {
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        ProxyConfig.log("ProxyService.onDestroy");
        running = false;
        rescanRunning = false;
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ------------------------------------------------------------------ 前台通知

    private void startForegroundSafely() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26 && nm != null) {
                NotificationChannel ch = new NotificationChannel(CH_ID, "音源代理", NotificationManager.IMPORTANCE_MIN);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
            Intent open = new Intent(this, com.feimi.car.MainActivity.class);
            PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(this, CH_ID)
                    : new Notification.Builder(this);
            b.setContentTitle("音源代理运行中")
                    .setContentText("目标：" + ProxyConfig.targetLabel())
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setOngoing(true)
                    .setContentIntent(pi);
            startForeground(CH_NOTI_ID, b.build());
        } catch (Throwable t) {
            ProxyConfig.log("前台通知失败: " + t);
        }
    }
}

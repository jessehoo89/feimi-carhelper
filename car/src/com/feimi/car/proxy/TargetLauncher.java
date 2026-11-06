package com.feimi.car.proxy;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/**
 * 「随时能点」——目标 App 被退出之后，媒体中心的播放键要能把 App 拉起来并接上播放。
 *
 * 背景：落雪是 RN 应用，没有常驻播放服务（也没有 MediaBrowserService、没有可用的
 * 后台播放 Service）。进程被退出后它的 MediaSession 消失：
 *   - TransportControls 没有 controller 可发；
 *   - 媒体键也没有接收方（force-stop 之后广播接收器处于 stopped 状态，按键直接丢弃）。
 * 所以"退出后按播放没反应"只能靠**拉起 App 本体**来解决 —— 落雪自己配了
 * 「启动时继续上次播放」，起来之后会自己接着播；这里再补一次 play 作为兜底
 * （某些情况下它起来是暂停态，或要等播放列表加载完）。
 *
 * 后台启动 Activity 的限制：Android 10+ 默认禁止后台 startActivity，但本应用
 * 持有 SYSTEM_ALERT_WINDOW（悬浮球）、并且注册了 NotificationListenerService，
 * 两者都在豁免名单里，实测可以直接拉起。
 */
public final class TargetLauncher {

    /** 拉起后等 session 出现的轮询间隔 / 上限 */
    private static final long POLL_MS = 500L;
    private static final long WAIT_MS = 20000L;
    /** 冷却：避免用户连点把 Activity 反复拽到前台 */
    private static final long COOLDOWN_MS = 8000L;

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static Context appCtx;

    /** 代次：新的拉起请求会作废旧轮询 */
    private static int gen = 0;
    private static long lastLaunchAt = 0L;
    private static String pending = null;

    private TargetLauncher() {
    }

    static void attach(Context c) {
        if (c != null) appCtx = c.getApplicationContext();
    }

    /** 目标 App 的会话是否彻底没了（= 进程被退出）——只有这种情况才需要拉 Activity。 */
    static boolean sessionGone() {
        return SessionWatcher.controller() == null && !SessionWatcher.sessionAlive();
    }

    /**
     * 拉起目标 App，等它的 session 出现后补一次动作。
     *
     * @param action "play" / "next" / "previous"
     */
    static void launchAndRun(String action) {
        final Context ctx = appCtx;
        if (ctx == null) {
            ProxyConfig.log("[拉起] 无 Context，跳过");
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (lastLaunchAt > 0 && now - lastLaunchAt < COOLDOWN_MS) {
            ProxyConfig.log("[拉起] " + (now - lastLaunchAt) + "ms 前刚拉过，忽略（防连点）");
            return;
        }

        final String pkg = ProxyConfig.targetPkg();
        Intent li;
        try {
            li = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
        } catch (Throwable t) {
            li = null;
        }
        if (li == null) {
            ProxyConfig.log("[拉起] 拿不到 " + pkg + " 的启动意图（包不可见？）");
            return;
        }
        li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);

        lastLaunchAt = now;
        pending = action;
        final int g = ++gen;
        try {
            ctx.startActivity(li);
            ProxyConfig.log("[拉起] 已启动 " + pkg + "（component=" + li.getComponent()
                    + "），等待 session 后补动作=" + action);
        } catch (Throwable t) {
            ProxyConfig.log("[拉起] startActivity 失败: " + t);
            return;
        }
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                poll(g, 0);
            }
        }, 600L);
    }

    /** 轮询等待目标 session 出现；出现后再补动作。 */
    private static void poll(final int g, final int step) {
        if (g != gen) return;
        if (step * POLL_MS > WAIT_MS) {
            ProxyConfig.log("[拉起] 等了 " + WAIT_MS + "ms 仍无 session，放弃补动作");
            pending = null;
            return;
        }
        try {
            SessionWatcher.rescan();
        } catch (Throwable ignored) {
        }
        if (!SessionWatcher.sessionAlive()) {
            main.postDelayed(new Runnable() {
                @Override
                public void run() {
                    poll(g, step + 1);
                }
            }, POLL_MS);
            return;
        }

        final String a = pending;
        pending = null;
        ProxyConfig.log("[拉起] session 已出现（约 " + (step * POLL_MS) + "ms），准备补动作=" + a);

        // 落雪起来后要加载播放列表/恢复进度，给 1.2s 缓冲再判断，避免它刚起播就被打断
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    nudge(a);
                } catch (Throwable t) {
                    ProxyConfig.log("[拉起] 补动作失败: " + t);
                }
            }
        }, 1200L);
    }

    private static void nudge(String a) {
        if ("next".equals(a) || "previous".equals(a)) {
            ProxyConfig.log("[拉起] 补动作 " + a);
            ControlForwarder.action(a);
            return;
        }
        if (SessionWatcher.isPlaying()) {
            ProxyConfig.log("[拉起] App 已自行接续播放，无需补 play");
            MediaCenterBridge.pushState();
            return;
        }
        ProxyConfig.log("[拉起] App 起来是暂停态 → 补一次 play");
        ControlForwarder.action("play");

        // 再兜一层：2.5s 后还没播，回灌媒体键（此时 App 已在前台，媒体键能被它收到）
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (SessionWatcher.isPlaying()) return;
                ProxyConfig.log("[拉起] 补 play 仍未生效，回灌媒体键 PLAY");
                ControlForwarder.rawKey(android.view.KeyEvent.KEYCODE_MEDIA_PLAY);
            }
        }, 2500L);
    }
}

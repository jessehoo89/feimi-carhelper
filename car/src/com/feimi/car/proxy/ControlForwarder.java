package com.feimi.car.proxy;

import android.content.Context;
import android.media.AudioManager;
import android.media.session.MediaController;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;

/**
 * 控制方向：媒体中心回调 → 目标 App 的 TransportControls；拿不到 controller 时回退标准媒体键。
 *
 * 回退通道很关键：落雪是 RN 应用，session 偶尔会短暂消失（切歌/重建播放器），
 * 此时 TransportControls 不可用，但它的 MediaButtonReceiver 仍然在，回灌媒体键能让按键不丢。
 */
public final class ControlForwarder {

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static Context appCtx;

    /**
     * 最近一次「经由本入口下发暂停」的时刻（elapsedRealtime）。
     *
     * 用途：区分"用户在本音源里按了暂停"与"被别的音源挤掉导致的暂停"。
     * 前者才需要事后补播放焦点；后者补焦会把焦点抢回来，导致用户切到 QQ 后
     * 点下一首又跳回落雪。
     */
    static volatile long lastPauseAt = 0L;

    private ControlForwarder() {
    }

    static void attach(Context ctx) {
        if (ctx != null) appCtx = ctx.getApplicationContext();
        TargetLauncher.attach(appCtx);
    }

    /** 由 NativeMusicClient 在 Binder 线程调用，统一切到主线程。 */
    public static void action(final String a) {
        ProxyConfig.log("媒体中心动作: " + a);
        main.post(new Runnable() {
            @Override
            public void run() {
                doAction(a);
            }
        });
    }

    private static void doAction(String a) {
        MediaController c = SessionWatcher.controller();

        // 强杀 App 时 onSessionDestroyed 不一定及时到，controller 可能是个死引用。
        // 先自愈一次：重扫活跃 session（目标不在列表里 → pick() 会把 controller 清掉）。
        if (c != null && !SessionWatcher.sessionAlive()) {
            SessionWatcher.rescan();
            c = SessionWatcher.controller();
        }

        // ---------------------------------------------------------------
        // 目标 App 已经被退出（session 彻底消失）的情况。
        // 此时 TransportControls 和媒体键都没有接收方，按播放键等于石沉大海，
        // 唯一可行的做法是把 App 拉起来 —— 它会自己接续播放（+ 我们兜底补 play）。
        // 注意：必须在下面的"重复 pause 当切换"逻辑之前拦截，否则会被翻成 pause。
        // ---------------------------------------------------------------
        if (ProxyConfig.mode() != 1 && c == null && TargetLauncher.sessionGone()) {
            if ("play".equals(a) || "next".equals(a) || "previous".equals(a)
                    || "pause".equals(a)) {
                if ("pause".equals(a)) {
                    ProxyConfig.log("目标 App 已退出且本就未在播，pause 无需处理");
                    return;
                }
                ProxyConfig.log("目标 App 已退出（无 session）→ 拉起 App 并补动作: " + a);
                TargetLauncher.launchAndRun(a);
                return;
            }
        }

        MediaController.TransportControls tc = c == null ? null : c.getTransportControls();
        try {
            if (a.startsWith("seek:")) {
                long p = Long.parseLong(a.substring(5));
                if (tc != null) {
                    tc.seekTo(p);
                } else {
                    ProxyConfig.log("无 controller，seek 丢弃");
                }
                return;
            }
            if ("play".equals(a) || "pause".equals(a)) {
                boolean wantPlay = "play".equals(a);
                boolean wasPlaying = SessionWatcher.isPlaying();

                // 有的车型方向盘中间键是"暂停键"而不是切换键 —— 已经在暂停态还收到 pause，
                // 就按切换处理（否则按第二下什么都不会发生，正是"能停不能续"的成因）。
                if (wantPlay == wasPlaying) {
                    if (!wantPlay) {
                        ProxyConfig.log("收到重复 pause（当前本就暂停），按切换处理 → play");
                        wantPlay = true;
                    } else {
                        ProxyConfig.log("已是播放态，忽略重复 play");
                        return;
                    }
                }
                // 不做乐观赋值：状态一律由目标 App 的 session 回调来定，
                // 否则动作失败时车机会一直以为在播放，下次按键方向就反了。
                // 记录"这是本音源内的用户暂停"，供补焦点逻辑判断（被别的音源挤掉的暂停不算）。
                if (!wantPlay) {
                    if (MediaCenterBridge.focusIsSelf()) {
                        lastPauseAt = SystemClock.elapsedRealtime();
                    } else {
                        ProxyConfig.log("焦点不在本音源，本次 pause 不记为用户暂停");
                    }
                }
                if (tc != null) {
                    if (wantPlay) {
                        tc.play();
                    } else {
                        tc.pause();
                    }
                } else {
                    key(wantPlay ? KeyEvent.KEYCODE_MEDIA_PLAY : KeyEvent.KEYCODE_MEDIA_PAUSE);
                }
                verify(wantPlay, wasPlaying);
            } else if ("next".equals(a)) {
                if (tc != null) {
                    tc.skipToNext();
                } else {
                    key(KeyEvent.KEYCODE_MEDIA_NEXT);
                }
            } else if ("previous".equals(a)) {
                if (tc != null) {
                    tc.skipToPrevious();
                } else {
                    key(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
                }
            } else {
                ProxyConfig.log("未知动作: " + a);
                return;
            }
            MediaCenterBridge.pushState();
        } catch (Throwable t) {
            ProxyConfig.log("执行动作失败(" + a + "): " + t);
        }
    }

    /**
     * 动作后校验：TransportControls 偶尔会静默失败（session 被重建/处于非激活态）。
     * 约 700ms 后状态没变，就换媒体键通道再试一次，并把 controller 重新挂一遍。
     */
    private static void verify(final boolean wantPlay, final boolean wasPlaying) {
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    boolean nowPlaying = SessionWatcher.isPlaying();
                    if (wantPlay && wasPlaying == nowPlaying) {
                        ProxyConfig.log("play 未生效，回灌媒体键");
                        key(KeyEvent.KEYCODE_MEDIA_PLAY);
                        SessionWatcher.rescan();
                    } else if (!wantPlay && wasPlaying == nowPlaying) {
                        ProxyConfig.log("pause 未生效，回灌媒体键");
                        key(KeyEvent.KEYCODE_MEDIA_PAUSE);
                        SessionWatcher.rescan();
                    }
                } catch (Throwable t) {
                    ProxyConfig.log("校验失败: " + t);
                }
            }
        }, 700L);
    }

    /** 供 TargetLauncher 用：直接回灌媒体键（App 已在前台时它能收到）。 */
    static void rawKey(int code) {
        key(code);
    }

    /** 回退通道：把动作当标准媒体键回灌，由目标 App 的 MediaButtonReceiver 消费。 */
    private static void key(int code) {
        try {
            if (appCtx == null) return;
            AudioManager am = (AudioManager) appCtx.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
            ProxyConfig.log("媒体键回灌: " + code);
        } catch (Throwable t) {
            ProxyConfig.log("媒体键回灌失败: " + t);
        }
    }
}

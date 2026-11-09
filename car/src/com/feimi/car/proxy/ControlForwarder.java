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

    /**
     * 暂停补刀（事件驱动，不做定时轰炸）。
     *
     * 落雪（RNTP）切歌时会销毁旧播放器再建新的，这几秒里下发的 pause 经常被旧
     * session 吃掉。实车表现为「点暂停后音乐还要放 2~4 秒，图标也跟着滞后」。
     *
     * 两个必须同时修的点：
     *   1) 旧代码用 SessionWatcher.isPlaying() 直接读 session 判断是否「已经在暂停」，
     *      切歌过渡期读到 NONE 就误判，把用户的 pause 翻成 play，还回灌了 MEDIA_PLAY。
     *      现在改用 NowPlaying.playing（切歌加载窗口里刻意保持为 true）。
     *   2) 旧 verify() 只在 700ms 查一次就放弃。现在改为：在收到明确的 STATE_PAUSED
     *      之前保持「暂停待确认」，由新播放器上报 PLAYING 时立即补发一次 pause，
     *      外加一笔 1.2s 的单次兜底；用 250ms 节流，避免同一次切歌里连发十几笔。
     */
    private static final long PAUSE_RESEND_MIN_MS = 250L;
    private static final long PAUSE_FALLBACK_MS = 1200L;
    private static final long PAUSE_EXPIRE_MS = 8000L;
    private static volatile boolean pausePending = false;
    private static volatile long lastPauseSentAt = 0L;
    private static int pauseResendCount = 0;

    /** 补发一笔 pause：TransportControls + 媒体键双通道，两者都是幂等的。 */
    private static final Runnable pauseResend = new Runnable() {
        @Override
        public void run() {
            if (!pausePending) return;
            long now = SystemClock.elapsedRealtime();
            if (now - lastPauseSentAt < PAUSE_RESEND_MIN_MS) return;
            if (!MediaCenterBridge.focusIsSelf()) {
                ProxyConfig.log("[暂停] 焦点已不在本音源，撤销补发");
                pausePending = false;
                return;
            }
            MediaController c = SessionWatcher.controller();
            MediaController.TransportControls tc = c == null ? null : c.getTransportControls();
            if (tc != null) {
                try {
                    tc.pause();
                } catch (Throwable ignored) {
                }
            }
            key(KeyEvent.KEYCODE_MEDIA_PAUSE);
            lastPauseSentAt = now;
            pauseResendCount++;
            ProxyConfig.log("[暂停] 切歌后补发 pause #" + pauseResendCount);
        }
    };

    /** 8 秒后若仍没拿到 STATE_PAUSED，按落雪真实状态回滚乐观上报的暂停态。 */
    private static final Runnable pauseExpire = new Runnable() {
        @Override
        public void run() {
            if (!pausePending) return;
            pausePending = false;
            boolean real = SessionWatcher.isPlaying();
            NowPlaying.playing = real;
            MediaCenterBridge.pushState();
            ProxyConfig.log("[暂停] 超时未确认，按落雪实际状态回滚 playing=" + real);
        }
    };

    private ControlForwarder() {
    }

    static void attach(Context ctx) {
        if (ctx != null) appCtx = ctx.getApplicationContext();
        TargetLauncher.attach(appCtx);
    }

    /** 由 NativeMusicClient 在 Binder 线程调用，统一切到主线程。 */
    public static void action(final String a) {
        ProxyConfig.log("媒体中心动作: " + a);
        // 先记下乐观赋值之前的播放态，doAction 的「重复 pause 当切换」判定必须用原值。
        final boolean wasPlayingAtEntry = NowPlaying.playing;
        if ("play".equals(a)) {
            // 用户主动恢复播放：撤销尚未完成的暂停补刀，避免把刚点开的播放又按停。
            pausePending = false;
            main.removeCallbacks(pauseResend);
            main.removeCallbacks(pauseExpire);
        } else if ("pause".equals(a)) {
            pausePending = true;
            pauseResendCount = 0;
            main.removeCallbacks(pauseResend);
            main.removeCallbacks(pauseExpire);
            main.postDelayed(pauseResend, PAUSE_FALLBACK_MS);
            main.postDelayed(pauseExpire, PAUSE_EXPIRE_MS);
        }
        // 车机按约 50ms 周期主动 pull getPlaybackStatus()，该 getter 直接读
        // NowPlaying.playing。在这里（Binder 回调入口）就改字段，图标随点击切换，
        // 不必等主线程 doAction 走完一串 Binder 调用（实测可省约 200ms）。
        // pause 由 pausePending + pauseExpire 兜底回滚，play 由 verify() 回灌兜底。
        if ("pause".equals(a)) NowPlaying.playing = false;
        else if ("play".equals(a)) NowPlaying.playing = true;
        main.post(new Runnable() {
            @Override
            public void run() {
                doAction(a, wasPlayingAtEntry);
            }
        });
    }

    /** 由 SessionWatcher 在收到明确的 STATE_PAUSED / STATE_ERROR 时调用。 */
    public static void onPaused() {
        pausePending = false;
        main.removeCallbacks(pauseResend);
        main.removeCallbacks(pauseExpire);
    }

    public static boolean isPausePending() {
        return pausePending;
    }

    /** session 重建 / 重新上报 PLAYING 时调用：立刻补发一次。 */
    public static void onSessionRebound() {
        if (!pausePending) return;
        main.post(pauseResend);
    }

    private static void doAction(String a, boolean wasPlayingAtEntry) {
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
                // 用 action() 在乐观赋值之前记下的播放态。不能在这里读
                // NowPlaying.playing（已被乐观改写），也不能直接读 session：
                // 切歌重建播放器时 getPlaybackState() 会短暂报 NONE，据此判定会把
                // 「播放中点暂停」误判成「本就暂停」，进而翻成 play。
                boolean wasPlaying = wasPlayingAtEntry;

                // 有的车型方向盘中间键是"暂停键"而不是切换键 —— 已经在暂停态还收到 pause，
                // 就按切换处理（否则按第二下什么都不会发生，正是"能停不能续"的成因）。
                if (wantPlay == wasPlaying) {
                    if (!wantPlay) {
                        ProxyConfig.log("收到重复 pause（当前本就暂停），按切换处理 → play");
                        wantPlay = true;
                        pausePending = false;
                        main.removeCallbacks(pauseResend);
                        main.removeCallbacks(pauseExpire);
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
                        lastPauseSentAt = SystemClock.elapsedRealtime();
                        // 切歌重建播放器时 sessionAlive() 会短暂为 false，此时
                        // TransportControls 多半落在即将销毁的旧 session 上。立刻用
                        // 媒体键补一发，不必等兜底定时器。
                        if (!SessionWatcher.sessionAlive()) {
                            key(KeyEvent.KEYCODE_MEDIA_PAUSE);
                        }
                    }
                } else {
                    key(wantPlay ? KeyEvent.KEYCODE_MEDIA_PLAY : KeyEvent.KEYCODE_MEDIA_PAUSE);
                    if (!wantPlay) lastPauseSentAt = SystemClock.elapsedRealtime();
                }
                // 乐观赋值已在 action() 入口完成，这里只做指令下发与失败兜底。
                verify(wantPlay);
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
     *
     * pause 不在这里处理：单次校验在切歌过渡期会读到 NONE 并误判为「已暂停」，
     * 正是暂停延迟的成因；改由 pauseResend 在明确的播放态回调时补发。
     */
    private static void verify(final boolean wantPlay) {
        if (!wantPlay) return;
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    // 播放态是乐观写的，不能用它判断指令是否生效；直接读目标 App 的 session。
                    if (!SessionWatcher.isPlaying()) {
                        ProxyConfig.log("play 未生效，回灌媒体键");
                        key(KeyEvent.KEYCODE_MEDIA_PLAY);
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

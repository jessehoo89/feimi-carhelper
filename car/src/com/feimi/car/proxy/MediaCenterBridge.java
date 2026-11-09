package com.feimi.car.proxy;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.session.MediaController;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

/**
 * 媒体中心桥接（落雪版）。
 *
 * 链路：
 *   ProxyService.onCreate → init()
 *     → NativeFull.init()           连 EAS Framework，取 IMediaCenterSvc / IStateRecoverApiSvc
 *        → NativeFull.register()    registerInMusic + updateZoneId + registerEx 拿 token
 *        → requestPlay（可配）+ updateCurrentSourceType(6=在线音乐)
 *        → updateMediaSourceTypeList({6})  ← 不声明的话音源列表里没有这一项
 *        → NativeFull.setupRecovery()      ← 车机"恢复上次音源"
 *   状态来源：SessionWatcher（MediaSessionManager，需通知使用权）→ NowPlaying
 *   上行：NativeUplink 每 1 秒补一次进度（车机进度条不会自己走）
 *
 * 全链路只使用 Android 公开 API（IBinder/Parcel），不依赖任何预编译组件。
 */
public final class MediaCenterBridge {

    public static final String ACTION_RECOVER = "com.feimi.car.proxy.RECOVER";

    private static Context appCtx;
    /** 服务端下发的 IMediaCenterClientToken Binder。 */
    private static volatile IBinder token;
    private static volatile boolean ready;

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static boolean tickerRunning;

    /** 注册看护：媒体中心切换音源时会注销非当前音源的客户端，本地 ready/token 不会同步失效。 */
    private static volatile long lastRegCheckAt = 0L;
    private static volatile long lastReRegisterAt = 0L;
    private static volatile String lastFocusWho = null;
    private static final long REG_CHECK_MS = 3000L;
    private static final long RE_REGISTER_COOLDOWN_MS = 800L;
    /** 注册会让媒体中心立刻回一次"焦点=本音源"，这次回调要吃掉，避免自激重注册。 */
    private static volatile boolean expectFocusCallback = false;

    private static long posAnchorMs;
    private static long posAnchorTs;
    private static float speed = 1f;
    private static boolean nativeInitStarted;

    /**
     * 最近一次"媒体中心把播放焦点切给别的音源"的时刻（elapsedRealtime）。
     * 从落雪切到 QQ/蓝牙时服务端会推 onMediaCenterFocusChanged(别人的包名)，
     * 在这之后的一段时间内我们不能再抢焦点、也不能再转发控制动作。
     */
    private static volatile long lastForeignFocusAt = 0L;

    private MediaCenterBridge() {
    }

    /** 桥接底座是否可用：底层 Binder 是否已就绪。 */
    private static boolean usable() {
        return NativeFull.svc() != null;
    }

    public static String pkg() {
        return appCtx == null ? "com.feimi.car" : appCtx.getPackageName();
    }

    /**
     * 注册给媒体中心的身份包名。
     *
     * 媒体中心靠包名把"音源条目"和"已注册的播放客户端"对上；条目包名与本身份不一致时，
     * 界面会变成"当前暂无播放内容"。所以这里统一用目标 App 的包名。
     */
    public static String proxyPkg() {
        String t = ProxyConfig.targetPkg();
        if (t != null && t.length() > 0) return t;
        return pkg();
    }

    public static android.content.Context context() {
        return appCtx;
    }

    /** 是否已注册成功（界面显示"媒体中心注册"）。 */
    public static boolean isReady() {
        return ready;
    }

    /**
     * 目标 App 变了：注册身份要换成新包名，否则媒体中心的音源条目和已注册客户端对不上
     * （表现就是"当前暂无播放内容"）。
     *
     * 这里直接再注册一次新身份 —— 旧客户端仍是我们自己的实现，回调行为完全一致，
     * 不会造成状态错乱；音源列表里旧条目由 CarMusicSource 按「上一任包名」清掉。
     */
    public static synchronized void retarget() {
        if (appCtx == null || !usable()) {
            ProxyConfig.log("[retarget] 桥接未初始化，等下次服务启动时按新身份注册");
            return;
        }
        ready = false;
        token = null;
        prepareLauncher();
        register();
        pushState();
        ProxyConfig.log("[retarget] 已按新目标重新注册: " + proxyPkg());
    }

    // ------------------------------------------------------------------ 初始化

    public static synchronized void init(Context ctx) {
        if (ctx == null) return;
        if (appCtx == null) {
            appCtx = ctx.getApplicationContext();
            ProxyConfig.init(appCtx);
            ControlForwarder.attach(appCtx);
            prepareLauncher();
        }
        if (nativeInitStarted) return;
        nativeInitStarted = true;

        // 连 EAS Framework 要在后台做（bindService 最长等 3 秒），完成后回主线程注册。
        ProxyConfig.log("初始化：连接媒体中心服务");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean ok = NativeFull.init();
                ProxyConfig.log("[nfull] init=" + ok);
                if (!ok) return;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        register();
                    }
                });
            }
        }, "carproxy-nfull").start();
    }

    /** 卡片/内容行点击后要能打开目标 App。 */
    private static void prepareLauncher() {
        try {
            String target = ProxyConfig.targetPkg();
            PackageManager pm = appCtx.getPackageManager();
            Intent li = pm.getLaunchIntentForPackage(target);
            if (li == null) {
                li = pm.getLaunchIntentForPackage(appCtx.getPackageName());
            }
            ProxyConfig.log("[launch] 目标=" + target + " 启动意图="
                    + (li == null ? "null（包不可见？）" : li.getComponent() + " action=" + li.getAction()));
            if (li != null) {
                NowPlaying.launchIntent = PendingIntent.getActivity(appCtx, 0, li,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            }
            // 卡片上的音源图标：必须用我们自己 APK 里的资源。
            // 指向目标 App 的 icon 时拿到的是 adaptive icon（XML），车机进程解不出来会退化成安卓机器人。
            int myIcon = appCtx.getResources().getIdentifier("ic_target", "drawable", appCtx.getPackageName());
            if (myIcon == 0) myIcon = appCtx.getApplicationInfo().icon;
            NowPlaying.appIcon = "android.resource://" + appCtx.getPackageName() + "/" + myIcon;
        } catch (Throwable t) {
            ProxyConfig.log("准备启动意图失败: " + t);
        }
    }

    private static void register() {
        register(true);
    }

    /** grabFocus=false 用于静默自愈重注册：不抢焦点，避免把用户切到别的音源时又拉回落雪。 */
    private static void register(boolean grabFocus) {
        if (ready || !usable()) return;
        try {
            IBinder tok = NativeFull.register(proxyPkg());
            ProxyConfig.log("[nfull] register token=" + tok + " grabFocus=" + grabFocus);
            if (tok == null) {
                ProxyConfig.log("[nfull] token 为空 —— 服务端拒绝注册");
                return;
            }
            token = tok;
            ready = true;
            lastReRegisterAt = System.currentTimeMillis();
            expectFocusCallback = true;

            if (grabFocus && ProxyConfig.requestPlay()) {
                ProxyConfig.log("[nfull] requestPlay=" + NativeFull.requestPlay());
            }
            NativeFull.updateCurrentSourceType(ProxyConfig.SOURCE_ONLINE);
            NativeFull.updateMediaSourceTypeList(new int[]{ProxyConfig.SOURCE_ONLINE});

            setupRecovery();
            startTicker();
            pushState();

            CarMusicSource.install(appCtx);
            CarMusicSource.startKeepAlive();
            CarMusicSource.dumpConfig();
        } catch (Throwable t) {
            ProxyConfig.log("[nfull] register 失败: " + t);
        }
    }

    private static void setupRecovery() {
        NativeFull.setupRecovery(1, ACTION_RECOVER, pkg());
    }

    // ------------------------------------------------------------------ 状态锚点（供进度推算）

    static void onPositionAnchor(long posMs, float sp) {
        posAnchorMs = posMs;
        posAnchorTs = SystemClock.elapsedRealtime();
        speed = sp > 0 ? sp : 1f;
    }

    static void onControllerReady(MediaController c) {
        ProxyConfig.log("controller 就绪: " + (c == null ? "null" : c.getPackageName()));
        pushState();
    }

    static void onControllerLost() {
        ProxyConfig.log("controller 丢失，回退媒体键通道");
        pushState();
    }

    /**
     * 媒体中心的播放焦点现在是否在本音源。
     *
     * 用于两道拦截：①暂停后是否补焦；②是否把媒体中心下发的控制动作转给落雪。
     * 查不到就返回 true（放行），宁可维持老行为也不要因为查询失败把功能关掉。
     */
    static boolean focusIsSelf() {
        if (!ready || !usable() || token == null) return true;
        String who;
        try {
            who = NativeFull.queryCurrentFocusClient();
        } catch (Throwable t) {
            return true;
        }
        if (who == null || who.length() == 0) return true;
        return who.equals(proxyPkg()) || who.equals(pkg());
    }

    /**
     * 媒体中心是不是刚把播放焦点切给了别的音源（8 秒内）。
     *
     * 实测：从落雪切到 QQ 音乐后，媒体中心仍会把「下一首」派给本客户端。
     * 不拦的话就会跳回落雪并播落雪的下一首。
     */
    public static boolean foreignFocusRecently() {
        long now = SystemClock.elapsedRealtime();
        return lastForeignFocusAt > 0L && now - lastForeignFocusAt < 8000L;
    }

    /**
     * 暂停后重新申请播放焦点。
     *
     * 现象：方向盘中间键第一下能暂停，但暂停之后再按就完全没有回调 ——
     * 车机不再把这个键派给我们。这里在暂停后补一次 requestPlay 把焦点拿回来。
     */
    static void reacquireFocus() {
        if (!ready || !usable() || token == null) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastForeignFocusAt < 6000L) {
            ProxyConfig.log("[焦点] 焦点 " + (now - lastForeignFocusAt) + "ms 前已切给别的音源，不补焦");
            return;
        }
        if (!focusIsSelf()) {
            ProxyConfig.log("[焦点] 当前焦点不是本音源，不补焦（避免把用户切走的音源抢回来）");
            return;
        }
        try {
            boolean ok = Boolean.TRUE.equals(NativeFull.requestPlay());
            ProxyConfig.log("[焦点] 暂停后重新 requestPlay=" + ok);
            main.postDelayed(new Runnable() {
                @Override
                public void run() {
                    ProxyConfig.log("[焦点] 补焦后状态 playing=" + SessionWatcher.isPlaying()
                            + "（应为 false，若变成 true 说明 requestPlay 会误触发播放）");
                }
            }, 1200L);
        } catch (Throwable t) {
            ProxyConfig.log("[焦点] requestPlay 失败: " + t);
        }
    }

    private static long lastReacquire = 0;

    /** mode3：暂停期间定期补焦点。 */
    static void maybeKeepFocus() {
        if (ProxyConfig.mode() != 3) return;
        if (NowPlaying.playing) return;
        long now = System.currentTimeMillis();
        if (now - lastReacquire < 3000) return;
        lastReacquire = now;
        reacquireFocus();
    }

    /**
     * 注册看护（ticker 每秒调用，内部 3s 节流）。
     *
     * 切换到别的音源时媒体中心会把非当前音源的客户端注销，此后所有上行都被拒，
     * 切回落雪就一直"加载内容"直到"当前暂无播放内容"；而本进程 ready/token 仍是旧值。
     * 这里跨进程自检当前焦点归属，仅用于观察与日志。
     */
    private static synchronized void ensureRegistered() {
        if (!usable()) return;
        long now = System.currentTimeMillis();
        if (now - lastRegCheckAt < REG_CHECK_MS) return;
        lastRegCheckAt = now;
        if (token == null) return;
        String who = null;
        try {
            who = NativeFull.queryCurrentFocusClient();
        } catch (Throwable t) {
            ProxyConfig.log("[注册] 焦点自检异常: " + t);
            return;
        }
        String prev = lastFocusWho;
        boolean changed = (who == null) ? prev != null : !who.equals(prev);
        lastFocusWho = who;
        if (who == null || who.length() == 0) return;
        if (changed) {
            ProxyConfig.log("[注册] 媒体中心当前焦点=" + who + "（上一个=" + prev + "，本音源=" + proxyPkg() + "）");
        }
        // 焦点自检只用于观察，绝不据此自动恢复：焦点抖动时自动 requestPlay
        // 会把用户切到 QQ/蓝牙又弹回落雪。恢复统一由用户显式选择音源触发。
    }

    /** 供音源切换回调强制自检一次（绕过节流）。 */
    static void checkRegistrationNow() {
        lastRegCheckAt = 0L;
        ensureRegistered();
    }

    /** 重新注册：清掉本地旧 token，重新走一遍注册。注册本身会让媒体中心把焦点给本音源。 */
    private static synchronized void reRegister(String reason) {
        long now = System.currentTimeMillis();
        if (now - lastReRegisterAt < RE_REGISTER_COOLDOWN_MS) return;
        lastReRegisterAt = now;
        ProxyConfig.log("[注册] " + reason + " -> 重新注册 " + proxyPkg());
        ready = false;
        token = null;
        register(false);
    }

    /**
     * 切回本音源的自愈：重新注册 -> 抢回焦点 -> 立即及分次补推完整状态。
     * 切换瞬间推的列表/播放信息会被媒体中心以
     * "please register first" / "focus:[别的音源]" 拒收，必须补推才能填上内容页。
     */
    public static void recoverRegistration(String reason) {
        ProxyConfig.log("[注册] 恢复音源自愈: " + reason);
        reRegister(reason);
        if (!ready || !usable() || token == null) return;
        try {
            boolean ok = Boolean.TRUE.equals(NativeFull.requestPlay());
            ProxyConfig.log("[注册] 恢复后 requestPlay=" + ok);
        } catch (Throwable t) {
            ProxyConfig.log("[注册] 恢复后 requestPlay 失败: " + t);
        }
        pushState();
        pushLyric();   // 重注册后媒体中心会清歌词，必须补推
        final long[] delays = {600L, 1600L, 3200L, 6000L};
        for (int i = 0; i < delays.length; i++) {
            final long d = delays[i];
            main.postDelayed(new Runnable() {
                @Override public void run() {
                    if (ready && usable() && token != null) {
                        ProxyConfig.log("[注册] 延迟补推 +" + d + "ms");
                        pushState();
                        pushLyric();
                    }
                }
            }, d);
        }
    }

    /**
     * 用户在媒体中心音源列表里选中本音源（onSourceSelected）。
     * 实测切到别的音源再切回来：媒体中心把本客户端注销、内容列表清空，
     * 却不发恢复 Intent，只有 onSourceSelected 会到。
     * 因此在这里复刻 RecoverReceiver 的完整恢复链路（重注册 -> 抢焦点 ->
     * 分次补推 -> 让目标 App 自动播放）。
     */
    public static void onUserSelectSource(int sourceType) {
        ProxyConfig.log("[音源] 用户选中本音源 type=" + sourceType + " -> 完整恢复");
        recoverRegistration("用户选择本音源 type=" + sourceType);
        ControlForwarder.action("play");
    }

    /** 焦点回调只记录，绝不自动抢焦点，否则用户切到 QQ/蓝牙会被弹回落雪。 */
    static synchronized void onMediaFocus(String who) {
        ProxyConfig.log("[焦点] 媒体中心焦点变化: " + who + " ready=" + ready);
        if (who == null || !proxyPkg().equals(who) && !pkg().equals(who)) {
            // 焦点已被别的音源拿走：记下时刻，供补焦与控制转发做拦截判断。
            lastForeignFocusAt = SystemClock.elapsedRealtime();
            checkRegistrationNow();
            return;
        }
        if (expectFocusCallback) {
            expectFocusCallback = false;
            ProxyConfig.log("[焦点] 注册自身触发的回调，跳过重注册");
            return;
        }
        ProxyConfig.log("[焦点] 指向本音源，仅记录（恢复由 onSourceSelected 触发）");
    }

    /** 车机进度条不会自己走：每秒补一次。 */
    private static void startTicker() {
        if (tickerRunning) return;
        tickerRunning = true;
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    ensureRegistered();
                    maybeKeepFocus();
                    // 目标 App 退出后 session 会变 NONE：必须立刻停止进度推算，
                    // 否则媒体中心会出现"音乐停了、歌词和进度条还在走"。
                    // 切歌加载窗口里 session 会长时间停在 NONE，这是重建播放器而不是退出，
                    // 此时推暂停态会让媒体中心图标闪回「播放」再翻回来。
                    if (NowPlaying.playing && SessionWatcher.sessionReallyGone()
                            && !SessionWatcher.inTrackLoadWindow()) {
                        ProxyConfig.log("[ticker] 会话已失效，停止推算并推暂停态");
                        NowPlaying.playing = false;
                        pushState();
                    }
                    if (ready && token != null) {
                        long pos = posAnchorMs;
                        if (NowPlaying.playing) {
                            pos += (long) ((SystemClock.elapsedRealtime() - posAnchorTs) * speed);
                        }
                        if (NowPlaying.durationMs > 0 && pos > NowPlaying.durationMs) {
                            pos = NowPlaying.durationMs;
                        }
                        NowPlaying.positionMs = pos;
                        NativeUplink.pushProgress(pos);
                    }
                } catch (Throwable ignored) {
                }
                main.postDelayed(this, 1000L);
            }
        }, 1000L);
    }

    // ------------------------------------------------------------------ 推送

    private static volatile String lastPushedUuid = "";

    public static void pushState() {
        if (!ready || !usable() || token == null) return;
        String u = NowPlaying.uuid();
        boolean ch = !u.equals(lastPushedUuid);
        lastPushedUuid = u;
        NativeUplink.pushState();
        if (ch) {
            ProxyConfig.log("[push] 曲目=" + (NowPlaying.title.length() == 0 ? "(空)" : NowPlaying.title)
                    + " 歌手=" + NowPlaying.artist + " uuid=" + u
                    + " 歌词=" + (NowPlaying.lyric == null ? 0 : NowPlaying.lyric.length()) + "B");
        }
    }

    /** 取出已注册 token 的 Binder。 */
    public static IBinder tokenBinder() {
        return token;
    }

    public static void pushLyric() {
        if (!ready || !usable() || token == null) return;
        NativeUplink.pushLyric();
    }

    /** 目标 App 切歌/退出时清空，避免卡片残留上一首。 */
    public static void clear() {
        NowPlaying.reset();
        pushState();
    }
}

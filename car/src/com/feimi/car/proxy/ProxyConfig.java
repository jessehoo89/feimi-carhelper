package com.feimi.car.proxy;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * 音源代理配置 + 环形日志。
 *
 * 设计原则：目标 App 包名可配置，默认落雪音乐；不写死、不蹭别人的包名。
 */
public final class ProxyConfig {

    public static final String TAG = "CarProxy";

    /** 默认目标：落雪音乐（lx-music-mobile） */
    public static final String DEFAULT_TARGET = "cn.toside.music.mobile";

    /** AbstractMusicPlaybackInfo.SOURCE_TYPE_ONLINE */
    public static final int SOURCE_ONLINE = 6;

    private static final String PREF = "car_proxy";

    private static SharedPreferences sp;

    private ProxyConfig() {
    }

    public static void init(Context c) {
        if (sp == null && c != null) {
            sp = c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
        }
    }

    public static String targetPkg() {
        String v = sp == null ? null : sp.getString("target_pkg", null);
        return (v == null || v.length() == 0) ? DEFAULT_TARGET : v;
    }

    public static void setTargetPkg(String pkg) {
        if (sp == null) return;
        String old = sp.getString("target_pkg", null);
        if (old != null && old.length() > 0 && !old.equals(pkg)) {
            // 记住上一任，注入音源条目时要把它的旧条目清掉，否则列表里会留一条死的
            sp.edit().putString("prev_target_pkg", old).apply();
        }
        sp.edit().putString("target_pkg", pkg).apply();
    }

    /** 上一任目标 App 包名（切换目标后清理旧音源条目用）。 */
    public static String prevTargetPkg() {
        String v = sp == null ? null : sp.getString("prev_target_pkg", null);
        return v == null ? "" : v;
    }

    public static String targetLabel() {
        String v = sp == null ? null : sp.getString("target_label", null);
        return (v == null || v.length() == 0) ? "落雪音乐" : v;
    }

    public static void setTargetLabel(String label) {
        if (sp != null) sp.edit().putString("target_label", label).apply();
    }

    private static int modeCache = -1;
    private static long modeAt = 0;
    private static int onlineLyricCache = -1;
    private static long onlineLyricAt = 0;

    /**
     * 歌词在线兜底开关，默认关闭。
     *
     * 整篇歌词一律从落雪 MediaSession 的 "android.media.metadata.LYRICS" 读取，
     * 本 App 默认不对外发任何歌词请求。只有在落雪确实没给完整 LRC（旧版落雪、
     * 其他音源、或系统丢掉了自定义 key）且需要兼容时，才手动打开：
     *   adb shell settings put global carproxy_lyric_online 1
     */
    static boolean onlineLyricFallback() {
        long now = System.currentTimeMillis();
        if (onlineLyricCache >= 0 && now - onlineLyricAt < 1000) return onlineLyricCache == 1;
        int v = 0;
        try {
            v = android.provider.Settings.Global.getInt(
                    MediaCenterBridge.context().getContentResolver(), "carproxy_lyric_online", 0);
        } catch (Throwable ignored) {
        }
        onlineLyricCache = (v == 1) ? 1 : 0;
        onlineLyricAt = now;
        return onlineLyricCache == 1;
    }

    /**
     * 运行时实验开关：adb shell settings put global carproxy_mode N
     *
     * 0 = 默认（转发动作 + 回调返回 true）
     * 1 = onPlay/onPause 返回 false，交回车机/系统
     * 2 = 暂停时仍上报"播放中"，让车机继续把这个键派给我们
     * 3 = 暂停期间每 3 秒重新 requestPlay 抢焦点
     */
    static int mode() {
        long now = System.currentTimeMillis();
        if (modeCache >= 0 && now - modeAt < 1000) return modeCache;
        int v = 0;
        try {
            v = android.provider.Settings.Global.getInt(
                    MediaCenterBridge.context().getContentResolver(), "carproxy_mode", 0);
        } catch (Throwable ignored) {
        }
        modeCache = v;
        modeAt = now;
        return v;
    }

    /** 手动指定底层传输事务码：adb shell settings put global carproxy_eas_call_code N（0=自发现）。 */
    static int easCallCode() {
        try {
            return android.provider.Settings.Global.getInt(
                    MediaCenterBridge.context().getContentResolver(), "carproxy_eas_call_code", 0);
        } catch (Throwable t) {
            return 0;
        }
    }

    public static boolean enabled() {
        return sp == null || sp.getBoolean("enabled", true);
    }

    public static void setEnabled(boolean b) {
        if (sp != null) sp.edit().putBoolean("enabled", b).apply();
    }

    /**
     * 「万物全屏」悬浮球是否常驻（默认关闭）。
     *
     * 悬浮球会一直浮在屏幕上，属于"显式要才给"的功能，所以默认不启用；
     * 用户打开后写进偏好，开机也能按这个偏好自启。
     */
    public static boolean ballEnabled() {
        return sp != null && sp.getBoolean("ball_enabled", false);
    }

    public static void setBallEnabled(boolean b) {
        if (sp != null) sp.edit().putBoolean("ball_enabled", b).apply();
    }

    /**
     * 是否已适配。
     *
     * 目前只针对落雪音乐（LX Music）做过适配与实测：它没有 MediaBrowserService，
     * 只能走通知使用权读 MediaSession；歌名藏在 artist 字段里、title 是当前歌词句；
     * 没有 android:icon 需要自带音源图标。其他音乐 App 的 session 结构、metadata 约定
     * 都不一样，接上去能显示但歌名/歌词/封面可能不对，也无法保证按键行为。
     */
    public static final String ADAPTED_PKG = "cn.toside.music.mobile";

    public static boolean isAdapted(String pkg) {
        return ADAPTED_PKG.equals(pkg);
    }

    /**
     * 是否调用 requestPlay 抢媒体焦点。
     * 落雪本身从不向车机媒体中心注册，不抢焦点的话方向盘按键没有归属方 —— 故默认开启。
     */
    public static boolean requestPlay() {
        return sp == null || sp.getBoolean("request_play", true);
    }

    public static void setRequestPlay(boolean b) {
        if (sp != null) sp.edit().putBoolean("request_play", b).apply();
    }

    // ------------------------------------------------------------------ 日志

    private static final int RING_SIZE = 80;
    private static final String[] RING = new String[RING_SIZE];
    private static int ringPos = 0;

    public static void log(String s) {
        try {
            Log.i(TAG, s);
        } catch (Throwable ignored) {
        }
        synchronized (RING) {
            RING[ringPos % RING_SIZE] = System.currentTimeMillis() + "  " + s;
            ringPos++;
        }
    }

    public static String dumpLog() {
        synchronized (RING) {
            StringBuilder sb = new StringBuilder();
            int start = ringPos < RING_SIZE ? 0 : ringPos - RING_SIZE;
            for (int i = start; i < ringPos; i++) {
                String line = RING[i % RING_SIZE];
                if (line != null) sb.append(line).append('\n');
            }
            return sb.toString();
        }
    }
}

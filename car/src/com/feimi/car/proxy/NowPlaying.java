package com.feimi.car.proxy;

import android.app.PendingIntent;
import android.net.Uri;

/**
 * 归一化后的"正在播放"模型。
 *
 * 只存放推给媒体中心的数据，不持有 Context，避免泄漏。
 */
public final class NowPlaying {

    public static volatile String title = "";
    public static volatile String artist = "";
    public static volatile String album = "";
    public static volatile long durationMs = 0L;
    public static volatile long positionMs = 0L;
    public static volatile boolean playing = false;
    public static volatile String artwork = null;
    public static volatile String lyric = null;
    /** 目标 App 自己推过来的"当前歌词句"（落雪把它塞在 title 里），优先级高于本地 LRC 推算。 */
    public static volatile String lyricLine = null;
    public static volatile String appIcon = null;
    public static volatile PendingIntent launchIntent = null;

    private NowPlaying() {
    }

    /**
     * 媒体中心用 uuid 区分"当前曲目"，切歌必须变 —— 但同一首歌里必须**保持稳定**。
     * title 现在存的是歌名（歌词行单独放在 lyricLine），所以这里不会每秒抖动。
     */
    public static String uuid() {
        String t = title;
        String a = artist;
        if (t == null || t.length() == 0) return "proxy-empty";
        return Integer.toHexString((t + '|' + a + '|' + album).hashCode());
    }

    public static Uri artworkUri() {
        String s = artwork;
        if (s == null || s.length() == 0) return null;
        try {
            return Uri.parse(s);
        } catch (Throwable t) {
            return null;
        }
    }

    public static void reset() {
        title = "";
        artist = "";
        album = "";
        durationMs = 0L;
        positionMs = 0L;
        playing = false;
        artwork = null;
        lyric = null;
        lyricLine = null;
    }
}

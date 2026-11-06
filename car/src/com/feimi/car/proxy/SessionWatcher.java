package com.feimi.car.proxy;

import android.content.ComponentName;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.List;

/**
 * 订阅目标 App 的 MediaSession。
 *
 * 落雪音乐（React Native + react-native-track-player）**没有导出 MediaBrowserService**，
 * 所以只能走 MediaSessionManager.getActiveSessions()，而该接口要求调用方是
 * 已启用的通知监听器（BIND_NOTIFICATION_LISTENER_SERVICE）。
 *
 * 因此本类是 M1 的硬前提：没有通知使用权 → 完全看不到落雪。
 */
public class SessionWatcher extends NotificationListenerService {

    private static final String TAG = "CarProxy";

    /** 落雪 fork 版 RNTP 输出整篇 LRC 用的自定义 key；framework 无对应常量。 */
    private static final String LX_LYRIC_KEY = "android.media.metadata.LYRICS";

    private static SessionWatcher instance;

    private static MediaSessionManager msm;
    private static MediaController controller;
    private static ComponentName self;
    private static Handler main;

    private static MediaSessionManager.OnActiveSessionsChangedListener sessionsListener;

    /**
     * 「会话真的结束了吗」的宽限判定用。
     *
     * 落雪用的是 RNTP，**切歌时会先销毁旧 MediaSession 再建新的**，这一两百毫秒里
     * getActiveSessions() 里查不到它、getPlaybackState() 也会短暂报 NONE/STOPPED。
     * 早期实现一判到 NONE 就置 playing=false 并推暂停态，结果就是：音乐已经在放、
     * 封面歌词都到位了，媒体中心的播放键还要等好几秒才变成暂停键。
     */
    private static volatile long lastMetadataAt = 0L;
    private static volatile long lastAliveAt = 0L;
    private static final long GONE_GRACE_MS = 3000L;

    /**
     * 切歌加载窗口。
     *
     * 落雪（RNTP）切下一首时会先销毁旧播放器再建新的，期间 PlaybackState 依次报
     * NONE(0) → BUFFERING(6) → PLAYING(3)，实车约 3 秒。这段它并没有被暂停，
     * 但如果照实上报，媒体中心的暂停图标会先翻成播放图标、几秒后再翻回来。
     *
     * 真正的用户暂停一定会报 STATE_PAUSED(2)，可以直接采信；NONE/STOPPED/BUFFERING
     * 只要落在「新曲目 metadata 到达后 5 秒内」就当作加载中，保持播放态不动。
     */
    private static volatile boolean trackLoadArmed = false;
    private static volatile long trackLoadUntil = 0L;
    private static final long TRACK_LOAD_MS = 5000L;
    private static String lastTrackUuid = "";

    // ------------------------------------------------------------------ 生命周期

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        ProxyConfig.log("SessionWatcher.onCreate");
    }

    /** 通知使用权就绪 —— 这是唯一可靠的"权限已授予"信号。 */
    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        instance = this;
        ProxyConfig.log("通知使用权已生效（onListenerConnected）");
        attach(this);
    }

    @Override
    public void onListenerDisconnected() {
        ProxyConfig.log("通知使用权被撤销（onListenerDisconnected）");
        super.onListenerDisconnected();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        // 目标 App 起播时若 session 尚未注册，靠通知事件触发一次重扫
        if (sbn != null && ProxyConfig.targetPkg().equals(sbn.getPackageName())) {
            rescan();
        }
    }

    // ------------------------------------------------------------------ 订阅

    /** 由 ProxyService / SessionWatcher 自身调用。可重复调用，内部去重。 */
    public static void attach(Context ctx) {
        if (ctx == null) return;
        final Context app = ctx.getApplicationContext();
        if (main == null) main = new Handler(Looper.getMainLooper());
        if (msm == null) {
            msm = (MediaSessionManager) app.getSystemService(Context.MEDIA_SESSION_SERVICE);
        }
        if (msm == null) {
            ProxyConfig.log("MediaSessionManager == null，本机不支持");
            return;
        }
        if (self == null) self = new ComponentName(app, SessionWatcher.class);

        if (sessionsListener == null) {
            sessionsListener = new MediaSessionManager.OnActiveSessionsChangedListener() {
                @Override
                public void onActiveSessionsChanged(List<MediaController> controllers) {
                    pick(controllers);
                }
            };
        }

        try {
            msm.removeOnActiveSessionsChangedListener(sessionsListener);
        } catch (Throwable ignored) {
        }
        try {
            msm.addOnActiveSessionsChangedListener(sessionsListener, self, main);
            ProxyConfig.log("已订阅 activeSessions 变化");
        } catch (SecurityException e) {
            ProxyConfig.log("没有通知使用权，无法订阅（SecurityException）: " + e.getMessage());
            return;
        } catch (Throwable t) {
            ProxyConfig.log("订阅失败: " + t);
            return;
        }
        rescan();
    }

    public static void rescan() {
        if (msm == null) return;
        try {
            pick(msm.getActiveSessions(self));
        } catch (SecurityException e) {
            ProxyConfig.log("getActiveSessions 被拒（无通知使用权）");
        } catch (Throwable t) {
            ProxyConfig.log("getActiveSessions 失败: " + t);
        }
    }

    /** 从活跃 session 里挑出目标 App 的那一个并挂上回调。 */
    private static void pick(List<MediaController> controllers) {
        if (controllers == null) {
            ProxyConfig.log("活跃 session 为空");
            return;
        }
        String target = ProxyConfig.targetPkg();
        MediaController hit = null;
        StringBuilder names = new StringBuilder();
        for (MediaController c : controllers) {
            if (c == null) continue;
            String p = c.getPackageName();
            if (names.length() > 0) names.append(',');
            names.append(p);
            if (target.equals(p)) hit = c;
        }
        ProxyConfig.log("活跃 session: [" + names + "] 目标命中=" + (hit != null));

        if (hit == null) {
            // 目标 App 被退出/杀掉时，session 会消失，但可能收不到 onSessionDestroyed。
            // 不在这里清掉 playing，进度推算就会一直跑（表现为"音乐停了、界面还在滚"）。
            // 但切歌瞬间目标也会短暂从活跃列表里消失，所以要先过宽限判定。
            if (controller != null || NowPlaying.playing) {
                if (!sessionReallyGone()) {
                    ProxyConfig.log("[session] 目标暂时不在活跃列表（切歌重建中），保持播放态");
                    return;
                }
                ProxyConfig.log("[session] 目标会话已消失 → 标记为已停止");
                controller = null;
                NowPlaying.playing = false;
                MediaCenterBridge.onControllerLost();
            }
            return;
        }
        if (controller != null && hit.getSessionToken().equals(controller.getSessionToken())) {
            return; // 同一个 session，不用重挂
        }
        bind(hit);
    }

    private static void bind(MediaController c) {
        try {
            if (controller != null) {
                try {
                    controller.unregisterCallback(callback);
                } catch (Throwable ignored) {
                }
            }
            controller = c;
            controller.registerCallback(callback, main);
            ProxyConfig.log("已绑定 session: " + c.getPackageName() + " / " + c.getSessionToken());
            MediaCenterBridge.onControllerReady(c);
            onMetadata(c.getMetadata());
            onPlaybackState(c.getPlaybackState());
        } catch (Throwable t) {
            ProxyConfig.log("绑定 session 失败: " + t);
        }
    }

    private static final Runnable reacquire = new Runnable() {
        @Override
        public void run() {
            MediaCenterBridge.reacquireFocus();
        }
    };

    private static final MediaController.Callback callback = new MediaController.Callback() {
        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            onMetadata(metadata);
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            onPlaybackState(state);
        }

        @Override
        public void onSessionDestroyed() {
            // 切歌时 RNTP 会销毁旧 session 再建新的，这里立刻判死会让播放键闪回"暂停"。
            // 交给宽限判定：3 秒后还没恢复才认定为真的结束。
            ProxyConfig.log("目标 session 已销毁 → 延迟确认是否真结束");
            main.removeCallbacks(goneCheck);
            main.postDelayed(goneCheck, GONE_GRACE_MS);
        }
    };

    private static final Runnable goneCheck = new Runnable() {
        @Override
        public void run() {
            if (!sessionReallyGone()) {
                ProxyConfig.log("[session] 销毁后已恢复，忽略");
                return;
            }
            ProxyConfig.log("[session] 确认已结束 → 标记为已停止");
            controller = null;
            NowPlaying.playing = false;
            MediaCenterBridge.onControllerLost();
        }
    };

    // ------------------------------------------------------------------ 状态解析

    private static void onMetadata(MediaMetadata m) {
        if (m == null) return;
        lastMetadataAt = SystemClock.elapsedRealtime();
        try {
            String rawTitle = m.getString(MediaMetadata.METADATA_KEY_TITLE);
            String rawArtist = m.getString(MediaMetadata.METADATA_KEY_ARTIST);
            if (rawArtist == null) rawArtist = m.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);

            // 落雪的 title 是"当前歌词句"而不是歌名，歌名藏在 artist 的 "歌名 - 歌手" 里。
            // 不还原的话卡片标题会变成滚动歌词、歌词也用歌词去搜，永远不会命中。
            String[] meta = TargetMeta.parse(rawTitle, rawArtist);
            NowPlaying.title = meta[0];
            NowPlaying.artist = meta[1];
            NowPlaying.lyricLine = meta[2];

            String al = m.getString(MediaMetadata.METADATA_KEY_ALBUM);
            if (al != null) NowPlaying.album = al;

            long d = m.getLong(MediaMetadata.METADATA_KEY_DURATION);
            if (d > 0) NowPlaying.durationMs = d;

            String art = m.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI);
            if (art == null) art = m.getString(MediaMetadata.METADATA_KEY_ART_URI);
            if (art == null) art = m.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI);
            if (art != null) NowPlaying.artwork = art;

            // 整篇歌词优先取落雪自己的「显示完整蓝牙歌词」通道，助手默认不联网。
            LyricFetcher.onTrack(NowPlaying.title, NowPlaying.artist);
            String full = m.getString(LX_LYRIC_KEY);
            if (full == null || full.length() == 0) full = scanLyricKey(m);
            dumpLyricKeys(m);
            if (full != null && full.length() > 0) {
                LyricFetcher.applyLocal(full, NowPlaying.title, NowPlaying.artist);
            }

            ProxyConfig.log("曲目: " + NowPlaying.title + " / " + NowPlaying.artist
                    + " / " + NowPlaying.album + " dur=" + NowPlaying.durationMs
                    + (NowPlaying.lyricLine == null ? "" : " | 歌词行=" + NowPlaying.lyricLine));

            // 曲目变了就打开加载窗口：接下来几秒里的 NONE/BUFFERING 都算"新歌在缓冲"，
            // 不是暂停（真正暂停是 STATE_PAUSED，不受影响）。
            String uuid = NowPlaying.uuid();
            if (!uuid.equals(lastTrackUuid)) {
                lastTrackUuid = uuid;
                if (NowPlaying.playing) {
                    trackLoadArmed = true;
                    trackLoadUntil = SystemClock.elapsedRealtime() + TRACK_LOAD_MS;
                    ProxyConfig.log("[状态] 新曲目 " + uuid + " → 开启 "
                            + (TRACK_LOAD_MS / 1000) + "s 加载窗口，过渡态不报暂停");
                }
            }

            MediaCenterBridge.pushState();
            // 默认不再联网抓词；仅显式打开 carproxy_lyric_online 且落雪没给 LRC 时兜底
            if (full == null || full.length() == 0) {
                LyricFetcher.fetch(NowPlaying.title, NowPlaying.artist);
            }
        } catch (Throwable t2) {
            ProxyConfig.log("解析 metadata 异常: " + t2);
        }
    }

    private static String lastLyricKeys = null;

    /**
     * 实车诊断：metadata 里所有与 lyric 相关的 key。
     * 打开落雪「显示完整蓝牙歌词」后应能看到 android.media.metadata.LYRICS；
     * 若始终为空，说明自定义 key 没跨进程保留 / 开关未生效。
     * 只在 key 集合变化时打印，避免逐句刷新刷屏。
     */
    private static void dumpLyricKeys(MediaMetadata m) {
        StringBuilder ks = new StringBuilder();
        int total = 0;
        try {
            for (String k : m.keySet()) {
                total++;
                if (k != null && k.toLowerCase().contains("lyric")) {
                    if (ks.length() > 0) ks.append(", ");
                    ks.append(k);
                }
            }
        } catch (Throwable ignored) {
        }
        String sig = total + "|" + ks;
        if (sig.equals(lastLyricKeys)) return;
        lastLyricKeys = sig;
        ProxyConfig.log("[歌词] metadata key 诊断: 共 " + total
                + " 个 key, lyric 相关=[" + ks + "]");
    }

    /** 兼容 ROM / 落雪版本改了 key 名：扫描含 lyric 的自定义 key，取第一份像 LRC 的文本。 */
    private static String scanLyricKey(MediaMetadata m) {
        try {
            for (String k : m.keySet()) {
                if (k == null || k.equals(LX_LYRIC_KEY)) continue;
                if (!k.toLowerCase().contains("lyric")) continue;
                String v = m.getString(k);
                if (v != null && v.length() > 16 && v.indexOf('[') >= 0 && v.indexOf(']') > 0) {
                    ProxyConfig.log("[歌词] 兼容 key: " + k + " (" + v.length() + "B)");
                    return v;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void onPlaybackState(PlaybackState s) {
        if (s == null) return;
        try {
            int st = s.getState();
            long now = SystemClock.elapsedRealtime();
            boolean playing = st == PlaybackState.STATE_PLAYING;

            if (st == PlaybackState.STATE_PAUSED || st == PlaybackState.STATE_ERROR) {
                // 用户明确暂停 / 播放失败 —— 采信，并关掉加载窗口。
                trackLoadArmed = false;
                trackLoadUntil = 0L;
            } else if (!playing && trackLoadArmed && now < trackLoadUntil) {
                // 切歌过渡：NONE(0)/STOPPED(1)/BUFFERING(6) 都不代表暂停。
                // 保持播放态，只把进度归零（新歌从头开始），避免媒体中心图标闪一下。
                ProxyConfig.log("[状态] 切歌加载中 state=" + st + " pos=" + s.getPosition()
                        + " → 保持播放态（不推暂停）");
                NowPlaying.positionMs = 0L;
                MediaCenterBridge.onPositionAnchor(0L, 1f);
                return;
            } else if (playing) {
                trackLoadArmed = false;
                trackLoadUntil = 0L;
            }

            NowPlaying.playing = playing;
            NowPlaying.positionMs = s.getPosition();
            MediaCenterBridge.onPositionAnchor(NowPlaying.positionMs,
                    s.getPlaybackSpeed() > 0 ? s.getPlaybackSpeed() : 1f);
            ProxyConfig.log("播放态: state=" + st + " playing=" + NowPlaying.playing
                    + " pos=" + NowPlaying.positionMs + " actions=" + s.getActions());
            MediaCenterBridge.pushState();

            // 暂停后补一次播放焦点，否则车机不再把方向盘中间键派给我们（第二下没反应）。
            // 但**只在本音源内用户主动按暂停**时才补：
            // 如果这次暂停是"用户切到 QQ/蓝牙音源"造成的，补焦会把播放焦点抢回落雪，
            // 之后媒体中心的「下一首」就会派给落雪 —— 实测表现为"在 QQ 里点下一首，
            // 直接跳回落雪并播落雪的下一首"。
            if (st == PlaybackState.STATE_PAUSED) {
                long sincePause = SystemClock.elapsedRealtime() - ControlForwarder.lastPauseAt;
                if (sincePause >= 0 && sincePause < 2500L) {
                    main.removeCallbacks(reacquire);
                    main.postDelayed(reacquire, 800L);
                } else {
                    ProxyConfig.log("[焦点] 非用户主动暂停（" + sincePause + "ms 前无 pause 动作），不补焦");
                }
            }
        } catch (Throwable t) {
            ProxyConfig.log("解析 playbackState 异常: " + t);
        }
    }

    // ------------------------------------------------------------------ 供控制侧使用

    public static MediaController controller() {
        return controller;
    }

    /** 权威播放状态：优先直接读 session，读不到才用缓存值。 */
    public static boolean isPlaying() {
        MediaController c = controller;
        if (c == null) return false;      // 会话没了就是没在播，别再信缓存值
        try {
            PlaybackState st = c.getPlaybackState();
            if (st != null) return st.getState() == PlaybackState.STATE_PLAYING;
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * 会话是否还"活着"（播放中/暂停都算，只有 NONE / STOPPED / ERROR 或读不到才算没了）。
     * 进度推算必须看这个：App 被退出时 session 会变 NONE，此时不能继续走进度。
     */
    public static boolean sessionAlive() {
        MediaController c = controller;
        if (c == null) return false;
        PlaybackState st;
        try {
            st = c.getPlaybackState();
        } catch (Throwable t) {
            return false;
        }
        if (st == null) return false;
        int s = st.getState();
        boolean ok = s != PlaybackState.STATE_NONE
                && s != PlaybackState.STATE_STOPPED
                && s != PlaybackState.STATE_ERROR;
        if (ok) lastAliveAt = SystemClock.elapsedRealtime();
        return ok;
    }

    /**
     * 会话是否**真的**结束了（而不是切歌重建的一瞬）。
     *
     * 判死条件必须同时满足：当前读不到活状态，且最近 3 秒内既没有新 metadata、
     * 也没有读到过活状态。真正退出 App 时会晚 3 秒才把界面置为暂停，可接受；
     * 切歌则完全不会被误判。
     */
    public static boolean sessionReallyGone() {
        long now = SystemClock.elapsedRealtime();
        if (sessionAlive()) return false;
        if (now - lastMetadataAt < GONE_GRACE_MS) return false;
        if (now - lastAliveAt < GONE_GRACE_MS) return false;
        return true;
    }

    public static boolean hasPermission() {
        return msm != null && controller != null;
    }
}

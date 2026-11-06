package com.feimi.car.proxy;

import android.os.IBinder;

import java.util.ArrayList;
import java.util.List;

/**
 * 数据上行（uplink）—— 只用 Android 公开 API。
 *
 * 职责：把 NowPlaying 的曲目 / 进度 / 歌词 / 列表持续上行给媒体中心，
 * 由 MediaCenterBridge 在状态变化与每秒 ticker 中调用。
 */
public final class NativeUplink {

    /** 与 ProxyConfig.SOURCE_ONLINE 一致，媒体中心用它区分音源。 */
    private static final int SOURCE = ProxyConfig.SOURCE_ONLINE;
    private static final String LIST_ID = "feimi-online";

    private static MediaCenterSvcProxy svc;
    private static IBinder token;
    private static NativeMusicClient client;
    private static boolean logged;

    private NativeUplink() {
    }

    private static boolean ensure() {
        IBinder tok = MediaCenterBridge.tokenBinder();
        if (tok == null) {
            if (!logged) {
                logged = true;
                ProxyConfig.log("[nup] 还没有可用 token，跳过自写上行");
            }
            return false;
        }
        if (svc == null || !svc.alive()) {
            IBinder b = EasNative.mediaCenterBinder();
            if (b == null) {
                if (!logged) {
                    logged = true;
                    ProxyConfig.log("[nup] 拿不到 IMediaCenterSvc，跳过自写上行");
                }
                return false;
            }
            svc = new MediaCenterSvcProxy(b);
        }
        if (client == null) client = new NativeMusicClient();
        if (tok != token) {
            token = tok;
            ProxyConfig.log("[nup] 自写上行已接入 token alive=" + tok.isBinderAlive());
        }
        return true;
    }

    /** 一次完整上行：音源列表 → 当前音源 → 播放状态 → 媒体列表 → 播放列表 → 内容 → 当前列表 → 进度 → 歌词。 */
    public static boolean pushState() {
        if (!ensure()) return false;
        try {
            svc.updateMediaSourceTypeList(token, new int[]{SOURCE});
            svc.updateCurrentSourceType(token, SOURCE);

            Boolean state = svc.updateMusicPlaybackState(token, client.infoBinder());

            List<NativeMedia.Item> items = items();
            svc.updateMediaList(token, SOURCE, 0, items);
            svc.updatePlaylist(token, SOURCE, items);

            List<NativeMedia.Content> contents = new ArrayList<NativeMedia.Content>();
            NativeMedia.Content c = new NativeMedia.Content();
            c.id = LIST_ID;
            c.title = "在线音乐";
            c.pendingIntent = NowPlaying.launchIntent;
            contents.add(c);
            Boolean content = svc.updateMediaContent(token, contents);

            NativeMedia.MediaList ml = new NativeMedia.MediaList();
            ml.title = "在线音乐";
            ml.sourceType = SOURCE;
            ml.mediaListId = LIST_ID;
            ml.mediaListType = SOURCE;
            ml.mediaList = items;
            ml.pendingIntent = NowPlaying.launchIntent;
            svc.updateMediaPlayList(token, ml);

            svc.updateCurrentProgress(token, NowPlaying.positionMs);
            if (NowPlaying.lyric != null && NowPlaying.lyric.length() > 0) {
                svc.updateCurrentLyric(token, NowPlaying.lyric);
            }

            ProxyConfig.log("[nup] push 完成 state=" + state + " content=" + content
                    + " 曲目=" + (NowPlaying.title.length() == 0 ? "(空)" : NowPlaying.title)
                    + " 列表条数=" + items.size()
                    + " 歌词=" + (NowPlaying.lyric == null ? 0 : NowPlaying.lyric.length()) + "B");
            return Boolean.TRUE.equals(state);
        } catch (Throwable t) {
            ProxyConfig.log("[nup] push 失败: " + t);
            return false;
        }
    }

    public static void pushProgress(long pos) {
        if (!ensure()) return;
        try {
            svc.updateCurrentProgress(token, pos);
        } catch (Throwable ignored) {
        }
    }

    public static void pushLyric() {
        if (!ensure()) return;
        if (NowPlaying.lyric == null || NowPlaying.lyric.length() == 0) return;
        try {
            svc.updateCurrentLyric(token, NowPlaying.lyric);
        } catch (Throwable ignored) {
        }
    }

    private static List<NativeMedia.Item> items() {
        List<NativeMedia.Item> out = new ArrayList<NativeMedia.Item>();
        if (NowPlaying.title == null || NowPlaying.title.length() == 0) return out;
        out.add(item());
        return out;
    }

    private static NativeMedia.Item item() {
        NativeMedia.Item it = new NativeMedia.Item();
        it.uuid = NowPlaying.uuid();
        it.title = NowPlaying.title;
        it.artist = NowPlaying.artist;
        it.album = NowPlaying.album;
        it.duration = NowPlaying.durationMs;
        it.sourceType = SOURCE;
        it.mediaId = NowPlaying.uuid();
        it.playingMediaListId = LIST_ID;
        it.playingMediaListType = SOURCE;
        it.positionInQueue = 0;
        it.lyricContent = NowPlaying.lyric;
        it.artWork = NowPlaying.artworkUri();
        it.categoryStr = "";
        it.subCategoryStr = "";
        it.mediaCp = "";
        it.targetType = "";
        return it;
    }
}

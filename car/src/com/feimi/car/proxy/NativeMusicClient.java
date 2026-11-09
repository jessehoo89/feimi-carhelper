package com.feimi.car.proxy;

import android.app.PendingIntent;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.List;

/**
 * IMusicClient / IMusicPlaybackInfo Binder 实现 —— 只用 Android 公开 API。
 *
 * 媒体中心注册音源时只拿到对端给的一个 Binder，之后两个方向都在这个 Binder 上：
 *   1) 服务端主动来取数据（pull）：IMusicClient.getMusicPlaybackInfo() 返回
 *      另一个 Binder，服务端再在它上面逐个调 getter 拿曲目/进度/歌词。
 *   2) 服务端下发控制（cmd）：onPlay / onPause / onSourceSelected / onExit …
 *
 * 本类手写 onTransact 提供这两个 Binder，只用 IBinder/Parcel 公开 API；
 * 接口 descriptor 与 transaction code 属于跨进程接口规格，实现为独立编写。
 *
 * reply 布局（与标准 AIDL 生成代码一致）：
 *   void    -> writeNoException()
 *   boolean -> writeNoException() + writeInt(0/1)
 *   int     -> writeNoException() + writeInt
 *   long    -> writeNoException() + writeLong
 *   String  -> writeNoException() + writeString
 *   Uri / PendingIntent / Parcelable -> writeNoException()
 *              + writeInt(0)                      // null
 *              或 writeInt(1) + writeToParcel(reply, 1)
 *   IBinder 接口 -> writeNoException() + writeStrongBinder
 *   List    -> writeNoException() + writeTypedList
 *   int[]   -> writeNoException() + writeIntArray
 *
 * 已知取舍：onMediaSelected(IMedia) / onPlayRecommend(IRecommend) 的参数是设备侧的
 * Parcelable/Binder 对象，这里不解析（不读参数直接拒绝）；
 * 这类回调当前链路用不到。
 */
public final class NativeMusicClient {

    /** ecarx.xsf.mediacenter.IMusicClient */
    public static final String CLIENT_DESCRIPTOR = "ecarx.xsf.mediacenter.IMusicClient";
    /** ecarx.xsf.mediacenter.IMusicPlaybackInfo */
    public static final String INFO_DESCRIPTOR = "ecarx.xsf.mediacenter.IMusicPlaybackInfo";

    // ---- IMusicClient.transaction（服务端 → 我们）
    private static final int C_ON_PLAY = 1;
    private static final int C_ON_PAUSE = 2;
    private static final int C_ON_NEXT = 3;
    private static final int C_ON_PREVIOUS = 4;
    private static final int C_ON_FORWARD = 5;
    private static final int C_ON_REWIND = 6;
    private static final int C_ON_LOOP_MODE_CHANGE = 7;
    private static final int C_ON_SOURCE_SELECTED = 8;
    private static final int C_ON_MEDIA_SELECTED = 9;
    private static final int C_GET_MUSIC_PLAYBACK_INFO = 10;
    private static final int C_GET_MEDIA_SOURCE_TYPE_LIST = 11;
    private static final int C_GET_CURRENT_SOURCE_TYPE = 12;
    private static final int C_GET_CURRENT_PROGRESS = 13;
    private static final int C_GET_PLAYLIST = 14;
    private static final int C_ON_COLLECT = 15;
    private static final int C_ON_DOWNLOAD = 16;
    private static final int C_ON_SOURCE_CHANGED = 17;
    private static final int C_ON_REPLAY = 18;
    private static final int C_ON_PLAY_RECOMMEND = 19;
    private static final int C_ON_CANCEL_RECOMMEND = 20;
    private static final int C_ON_MEDIA_SELECTED_PLAY = 21;
    private static final int C_ON_MEDIA_FORWARD = 22;
    private static final int C_ON_MEDIA_REWIND = 23;
    private static final int C_ON_MEDIA_QUALITY_CHANGE = 24;
    private static final int C_ON_MEDIA_CENTER_FOCUS_CHANGED = 25;
    private static final int C_ON_EXIT = 26;
    private static final int C_SELECT_LIST_MEDIA_PLAY = 27;
    private static final int C_GET_CONTENT_LIST = 28;
    private static final int C_GET_MULTI_MEDIA_LIST = 29;
    private static final int C_CTRL_PLAY_MEDIA_LIST = 30;
    private static final int C_CTRL_PAUSE_MEDIA_LIST = 31;
    private static final int C_CTRL_COLLECT = 32;
    private static final int C_OPERATION_TYPE = 33;
    private static final int C_CTRL_COLLECT_BY_UUID = 34;

    // ---- IMusicPlaybackInfo.transaction（服务端 → 我们，逐个 getter）
    private static final int I_GET_LAUNCH_INTENT = 1;
    private static final int I_GET_TITLE = 2;
    private static final int I_GET_ARTIST = 3;
    private static final int I_GET_ALBUM = 4;
    private static final int I_GET_RADIO_FREQUENCY = 5;
    private static final int I_GET_RADIO_STATION_NAME = 6;
    private static final int I_GET_DURATION = 7;
    private static final int I_GET_POSITION_IN_QUEUE = 8;
    private static final int I_GET_SOURCE_TYPE = 9;
    private static final int I_GET_MEDIA_PATH = 10;
    private static final int I_GET_PLAYBACK_STATUS = 11;
    private static final int I_GET_LYRIC_CONTENT = 12;
    private static final int I_GET_LYRIC = 13;
    private static final int I_GET_CURRENT_LYRIC_SENTENCE = 14;
    private static final int I_GET_PREVIOUS_ARTWORK = 15;
    private static final int I_GET_ARTWORK = 16;
    private static final int I_GET_NEXT_ARTWORK = 17;
    private static final int I_GET_LOOP_MODE = 18;
    private static final int I_GET_RADIO_MODE = 19;
    private static final int I_IS_SUPPORT_COLLECT = 20;
    private static final int I_IS_COLLECTED = 21;
    private static final int I_IS_SUPPORT_DOWNLOAD = 22;
    private static final int I_IS_DOWNLOADED = 23;
    private static final int I_GET_UUID = 24;
    private static final int I_GET_APP_NAME = 25;
    private static final int I_GET_APP_ICON = 26;
    private static final int I_GET_PACKAGE_NAME = 27;
    private static final int I_IS_SUPPORT_LOOP_MODE_SWITCH = 28;
    private static final int I_IS_SUPPORT_VR_CTRL_PLAY_STATUS = 29;
    private static final int I_GET_PLAYING_MEDIA_LIST_ID = 30;
    private static final int I_GET_VIP = 31;
    private static final int I_GET_PLAYING_MEDIA_LIST_TYPE = 32;
    private static final int I_GET_PLAYER_INTENT = 33;

    /**
     * 播放状态枚举 —— 这是媒体中心自己的取值，**不是** MediaSession.PlaybackState：
     * PAUSED=0、PLAYING=1、INTERRUPT=2、PREPARE=3。
     *
     * 特别容易踩的坑：这里如果按 MediaSession 的 2/3 去填，2 会变成「被中断」、
     * 3 会变成「准备中」，车机据此认为音源还没准备好 —— 表现就是 HUD 一直显示
     * 「正在加载歌词」、方向盘与扶手箱的播放键全部失效。
     */
    private static final int STATUS_PAUSED = 0;
    private static final int STATUS_PLAYING = 1;

    /** 仅供日志：车机 pull 到的播放态发生变化时打一条，便于量图标刷新延迟。 */
    private static volatile int lastReportedStatus = -1;

    private static volatile int pullCount;
    private static volatile int cmdCount;
    private static volatile String lastCmd = "";

    private final Info info = new Info();
    private final Client client = new Client();

    /** 传给 IMediaCenterSvc.registerMusic(1) 的 Binder。 */
    public IBinder asBinder() {
        return client;
    }

    /** 传给 IMediaCenterSvc.updateMusicPlaybackState(6) 的播放信息 Binder。 */
    public IBinder infoBinder() {
        return info;
    }

    public static int pullCount() {
        return pullCount;
    }

    public static int cmdCount() {
        return cmdCount;
    }

    public static String lastCmd() {
        return lastCmd;
    }

    private static void notePull(String what) {
        int n = pullCount + 1;
        pullCount = n;
        // 服务端轮询很密，只在头几次和每 50 次打一条，避免刷爆日志
        if (n <= 6 || n % 50 == 0) {
            ProxyConfig.log("[nmc] 服务端取数据(" + n + "): " + what);
        }
    }

    private static void noteCmd(int code, String what) {
        cmdCount++;
        lastCmd = what;
        ProxyConfig.log("[nmc] 服务端下发动作 code=" + code + " " + what);
    }

    // ------------------------------------------------------------------ 工具

    private static void writeBool(Parcel reply, boolean v) {
        reply.writeInt(v ? 1 : 0);
    }

    private static void writeUri(Parcel reply, String s) {
        Uri u = null;
        if (s != null && s.length() > 0) {
            try {
                u = Uri.parse(s);
            } catch (Throwable ignored) {
            }
        }
        if (u == null) {
            reply.writeInt(0);
        } else {
            reply.writeInt(1);
            u.writeToParcel(reply, 1);
        }
    }

    private static void writePending(Parcel reply, PendingIntent p) {
        if (p == null) {
            reply.writeInt(0);
        } else {
            reply.writeInt(1);
            p.writeToParcel(reply, 1);
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    // ------------------------------------------------------------------ IMusicPlaybackInfo

    private static final class Info extends Binder {

        @Override
        public String getInterfaceDescriptor() {
            return INFO_DESCRIPTOR;
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code == INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString(INFO_DESCRIPTOR);
                return true;
            }
            if (data != null) {
                try {
                    data.enforceInterface(INFO_DESCRIPTOR);
                } catch (Throwable ignored) {
                }
            }
            if (reply == null) return true;
            notePull("playback#" + code);
            switch (code) {
                case I_GET_LAUNCH_INTENT:
                    reply.writeNoException();
                    writePending(reply, NowPlaying.launchIntent);
                    return true;
                case I_GET_TITLE:
                    reply.writeNoException();
                    reply.writeString(nz(NowPlaying.title));
                    return true;
                case I_GET_ARTIST:
                    reply.writeNoException();
                    reply.writeString(nz(NowPlaying.artist));
                    return true;
                case I_GET_ALBUM:
                    reply.writeNoException();
                    reply.writeString(nz(NowPlaying.album));
                    return true;
                case I_GET_RADIO_FREQUENCY:
                case I_GET_RADIO_STATION_NAME:
                    reply.writeNoException();
                    reply.writeString("");
                    return true;
                case I_GET_DURATION:
                    reply.writeNoException();
                    reply.writeLong(NowPlaying.durationMs);
                    return true;
                case I_GET_POSITION_IN_QUEUE:
                case I_GET_RADIO_MODE:
                case I_GET_VIP:
                case I_GET_PLAYING_MEDIA_LIST_TYPE:
                    reply.writeNoException();
                    reply.writeInt(0);
                    return true;
                case I_GET_SOURCE_TYPE:
                    reply.writeNoException();
                    reply.writeInt(ProxyConfig.SOURCE_ONLINE);
                    return true;
                case I_GET_MEDIA_PATH:
                case I_GET_LYRIC:
                case I_GET_PREVIOUS_ARTWORK:
                case I_GET_NEXT_ARTWORK:
                    reply.writeNoException();
                    reply.writeInt(0);
                    return true;
                case I_GET_PLAYBACK_STATUS:
                    reply.writeNoException();
                    int reported = NowPlaying.playing ? STATUS_PLAYING : STATUS_PAUSED;
                    if (reported != lastReportedStatus) {
                        lastReportedStatus = reported;
                        ProxyConfig.log("[状态上行] getPlaybackStatus → " + reported
                                + (reported == STATUS_PLAYING ? " (PLAYING)" : " (PAUSED)"));
                    }
                    reply.writeInt(reported);
                    return true;
                case I_GET_LYRIC_CONTENT:
                    reply.writeNoException();
                    reply.writeString(nz(NowPlaying.lyric));
                    return true;
                case I_GET_CURRENT_LYRIC_SENTENCE:
                    reply.writeNoException();
                    reply.writeString(nz(LyricFetcher.currentSentence()));
                    return true;
                case I_GET_ARTWORK:
                    reply.writeNoException();
                    writeUri(reply, NowPlaying.artwork);
                    return true;
                case I_GET_LOOP_MODE:
                    reply.writeNoException();
                    reply.writeInt(0);
                    return true;
                case I_IS_SUPPORT_COLLECT:
                case I_IS_COLLECTED:
                case I_IS_SUPPORT_DOWNLOAD:
                case I_IS_DOWNLOADED:
                case I_IS_SUPPORT_LOOP_MODE_SWITCH:
                case I_IS_SUPPORT_VR_CTRL_PLAY_STATUS:
                    reply.writeNoException();
                    writeBool(reply, false);
                    return true;
                case I_GET_UUID:
                    reply.writeNoException();
                    reply.writeString(NowPlaying.uuid());
                    return true;
                case I_GET_APP_NAME:
                    reply.writeNoException();
                    reply.writeString(nz(ProxyConfig.targetLabel()));
                    return true;
                case I_GET_APP_ICON:
                    reply.writeNoException();
                    reply.writeString(nz(NowPlaying.appIcon));
                    return true;
                case I_GET_PACKAGE_NAME:
                    reply.writeNoException();
                    reply.writeString(nz(MediaCenterBridge.proxyPkg()));
                    return true;
                case I_GET_PLAYING_MEDIA_LIST_ID:
                    reply.writeNoException();
                    // 必须与 updateMediaPlayList 上传的 id 一致，否则服务端定位不到
                    // 当前播放在哪个列表，就取不到列表项的歌词，HUD 会一直「加载歌词」。
                    reply.writeString(NativeUplink.LIST_ID);
                    return true;
                case I_GET_PLAYER_INTENT:
                    reply.writeNoException();
                    writePending(reply, NowPlaying.launchIntent);
                    return true;
                default:
                    // 车机固件的接口版本可能比我们已知的更新（实测出现过 34/35）。
                    // 记下来便于适配，避免静默失败看不出原因。
                    ProxyConfig.log("[nmc] 未知 playback code=" + code);
                    return false;
            }
        }
    }

    // ------------------------------------------------------------------ IMusicClient

    private final class Client extends Binder {

        @Override
        public String getInterfaceDescriptor() {
            return CLIENT_DESCRIPTOR;
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code == INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString(CLIENT_DESCRIPTOR);
                return true;
            }
            if (data != null) {
                try {
                    data.enforceInterface(CLIENT_DESCRIPTOR);
                } catch (Throwable ignored) {
                }
            }
            if (reply == null) {
                // oneway 场景：动作照做，但没有 reply 可写
                handleNoReply(code, data);
                return true;
            }
            switch (code) {
                // ------------------------------------------------ 服务端取数据
                case C_GET_MUSIC_PLAYBACK_INFO:
                    notePull("getMusicPlaybackInfo");
                    reply.writeNoException();
                    reply.writeStrongBinder(info);
                    return true;
                case C_GET_MEDIA_SOURCE_TYPE_LIST:
                    notePull("getMediaSourceTypeList");
                    reply.writeNoException();
                    reply.writeIntArray(new int[]{ProxyConfig.SOURCE_ONLINE});
                    return true;
                case C_GET_CURRENT_SOURCE_TYPE:
                    notePull("getCurrentSourceType");
                    reply.writeNoException();
                    reply.writeInt(ProxyConfig.SOURCE_ONLINE);
                    return true;
                case C_GET_CURRENT_PROGRESS: {
                    notePull("getCurrentProgress");
                    reply.writeNoException();
                    reply.writeLong(NowPlaying.positionMs);
                    return true;
                }
                case C_GET_PLAYLIST: {
                    try {
                        data.readInt();
                    } catch (Throwable ignored) {
                    }
                    notePull("getPlaylist");
                    reply.writeNoException();
                    reply.writeTypedList(new ArrayList<Parcelable>());
                    return true;
                }
                case C_GET_CONTENT_LIST:
                    notePull("getContentList");
                    reply.writeNoException();
                    reply.writeTypedList(new ArrayList<Parcelable>());
                    return true;
                case C_GET_MULTI_MEDIA_LIST: {
                    try {
                        data.createIntArray();
                    } catch (Throwable ignored) {
                    }
                    notePull("getMultiMediaList");
                    reply.writeNoException();
                    reply.writeInt(0);   // IMediaLists = null
                    return true;
                }

                // ------------------------------------------------ 服务端下发动作
                case C_ON_PLAY:
                    noteCmd(code, "onPlay");
                    ControlForwarder.action("play");
                    reply.writeNoException();
                    writeBool(reply, true);
                    return true;
                case C_ON_PAUSE:
                    noteCmd(code, "onPause");
                    ControlForwarder.action("pause");
                    reply.writeNoException();
                    writeBool(reply, true);
                    return true;
                case C_ON_NEXT:
                    noteCmd(code, "onNext");
                    if (MediaCenterBridge.foreignFocusRecently()) {
                        ProxyConfig.log("[nmc][拦截] 焦点已切给别的音源，忽略 onNext");
                        reply.writeNoException();
                        writeBool(reply, false);
                        return true;
                    }
                    ControlForwarder.action("next");
                    reply.writeNoException();
                    writeBool(reply, true);
                    return true;
                case C_ON_PREVIOUS:
                    noteCmd(code, "onPrevious");
                    if (MediaCenterBridge.foreignFocusRecently()) {
                        ProxyConfig.log("[nmc][拦截] 焦点已切给别的音源，忽略 onPrevious");
                        reply.writeNoException();
                        writeBool(reply, false);
                        return true;
                    }
                    ControlForwarder.action("previous");
                    reply.writeNoException();
                    writeBool(reply, true);
                    return true;
                case C_ON_REPLAY:
                    noteCmd(code, "onReplay");
                    ControlForwarder.action("play");
                    reply.writeNoException();
                    writeBool(reply, true);
                    return true;
                case C_ON_FORWARD:
                case C_ON_MEDIA_FORWARD:
                    noteCmd(code, "onForward");
                    if (MediaCenterBridge.foreignFocusRecently()) {
                        ProxyConfig.log("[nmc][拦截] 焦点已切给别的音源，忽略 onForward");
                        reply.writeNoException();
                        writeBool(reply, false);
                        return true;
                    }
                    ControlForwarder.action("next");
                    reply.writeNoException();
                    writeBool(reply, true);
                    return true;
                case C_ON_REWIND:
                case C_ON_MEDIA_REWIND:
                    noteCmd(code, "onRewind");
                    if (MediaCenterBridge.foreignFocusRecently()) {
                        ProxyConfig.log("[nmc][拦截] 焦点已切给别的音源，忽略 onRewind");
                        reply.writeNoException();
                        writeBool(reply, false);
                        return true;
                    }
                    ControlForwarder.action("previous");
                    reply.writeNoException();
                    writeBool(reply, true);
                    return true;
                case C_ON_SOURCE_SELECTED: {
                    int type = -1;
                    try {
                        type = data.readInt();
                    } catch (Throwable ignored) {
                    }
                    noteCmd(code, "onSourceSelected type=" + type);
                    // 用户手动切回本音源：走已验证的完整恢复链
                    MediaCenterBridge.onUserSelectSource(type);
                    reply.writeNoException();
                    writeBool(reply, true);
                    return true;
                }
                case C_ON_SOURCE_CHANGED: {
                    int type = -1;
                    String id = null;
                    try {
                        type = data.readInt();
                        id = data.readString();
                    } catch (Throwable ignored) {
                    }
                    noteCmd(code, "onSourceChanged type=" + type + " id=" + id);
                    reply.writeNoException();
                    writeBool(reply, true);
                    return true;
                }
                case C_ON_MEDIA_CENTER_FOCUS_CHANGED: {
                    String who = null;
                    try {
                        who = data.readString();
                    } catch (Throwable ignored) {
                    }
                    noteCmd(code, "onMediaCenterFocusChanged " + who);
                    // 焦点变化必须交给桥接层：切到别的音源后要靠它拦截误派过来的控制动作
                    MediaCenterBridge.onMediaFocus(who);
                    reply.writeNoException();
                    return true;
                }
                case C_ON_LOOP_MODE_CHANGE:
                case C_ON_MEDIA_QUALITY_CHANGE:
                case C_OPERATION_TYPE: {
                    try {
                        data.readInt();
                    } catch (Throwable ignored) {
                    }
                    noteCmd(code, "int 参数回调");
                    reply.writeNoException();
                    if (code != C_OPERATION_TYPE) writeBool(reply, true);
                    return true;
                }
                case C_CTRL_PLAY_MEDIA_LIST: {
                    try {
                        data.readInt();
                    } catch (Throwable ignored) {
                    }
                    noteCmd(code, "ctrlPlayMediaList");
                    ControlForwarder.action("play");
                    reply.writeNoException();
                    writeBool(reply, true);
                    return true;
                }
                case C_CTRL_PAUSE_MEDIA_LIST: {
                    try {
                        data.readInt();
                    } catch (Throwable ignored) {
                    }
                    noteCmd(code, "ctrlPauseMediaList");
                    ControlForwarder.action("pause");
                    reply.writeNoException();
                    writeBool(reply, true);
                    return true;
                }
                case C_ON_MEDIA_SELECTED_PLAY: {
                    try {
                        data.readInt();
                        data.readString();
                    } catch (Throwable ignored) {
                    }
                    noteCmd(code, "onMediaSelectedPlay");
                    reply.writeNoException();
                    writeBool(reply, true);
                    return true;
                }
                case C_SELECT_LIST_MEDIA_PLAY: {
                    try {
                        data.readInt();
                        data.readInt();
                        data.readString();
                    } catch (Throwable ignored) {
                    }
                    noteCmd(code, "selectListMediaPlay");
                    reply.writeNoException();
                    writeBool(reply, true);
                    return true;
                }
                case C_ON_EXIT:
                    // 不真的退出：媒体中心切源会发这个，退出代理就没有音源了
                    noteCmd(code, "onExit（忽略，不退出代理）");
                    reply.writeNoException();
                    writeBool(reply, false);
                    return true;
                case C_CTRL_COLLECT_BY_UUID: {
                    try {
                        data.readInt();
                        data.readString();
                        data.readInt();
                    } catch (Throwable ignored) {
                    }
                    noteCmd(code, "ctrlCollectByUUID");
                    reply.writeNoException();
                    return true;
                }
                case C_CTRL_COLLECT: {
                    try {
                        data.readInt();
                        data.readInt();
                    } catch (Throwable ignored) {
                    }
                    noteCmd(code, "ctrlCollect");
                    reply.writeNoException();
                    reply.writeInt(-1);
                    return true;
                }
                case C_ON_COLLECT:
                case C_ON_DOWNLOAD: {
                    try {
                        data.readInt();
                        data.readInt();
                    } catch (Throwable ignored) {
                    }
                    noteCmd(code, "onCollect/onDownload");
                    reply.writeNoException();
                    writeBool(reply, false);
                    return true;
                }
                case C_ON_MEDIA_SELECTED:
                case C_ON_PLAY_RECOMMEND:
                case C_ON_CANCEL_RECOMMEND:
                    // 参数是设备侧的 Parcelable/Binder 数据对象，这里不解析
                    noteCmd(code, "旧式媒体对象回调（未解析，返回 false）");
                    reply.writeNoException();
                    writeBool(reply, false);
                    return true;
                default:
                    ProxyConfig.log("[nmc] 未知 transaction code=" + code);
                    return false;
            }
        }

        private void handleNoReply(int code, Parcel data) {
            switch (code) {
                case C_ON_PLAY:
                    noteCmd(code, "onPlay(oneway)");
                    ControlForwarder.action("play");
                    break;
                case C_ON_PAUSE:
                    noteCmd(code, "onPause(oneway)");
                    ControlForwarder.action("pause");
                    break;
                case C_ON_NEXT:
                    noteCmd(code, "onNext(oneway)");
                    ControlForwarder.action("next");
                    break;
                case C_ON_PREVIOUS:
                    noteCmd(code, "onPrevious(oneway)");
                    ControlForwarder.action("previous");
                    break;
                default:
                    noteCmd(code, "oneway#");
                    break;
            }
        }
    }
}

package com.feimi.car.proxy;

import android.os.IBinder;
import android.os.Parcel;

import java.util.List;

/**
 * IMediaCenterSvc 的 AIDL Proxy 实现 —— 只用 Android 公开 API。
 *
 * 通过 EasNative.callBinder("mediacenter","MediaCenterAPI","getMainBinder",...)
 * 拿到设备的 IMediaCenterSvc Binder 后，本类用标准 AIDL Parcel 布局直接调用它，
 * 负责把上层调用编码成标准 AIDL Parcel 发给媒体中心服务。
 *
 * 契约（接口 descriptor 与 transaction code）属于跨进程接口规格；
 * 实现为独立编写，不复制第三方代码。
 *
 * 调用模式：writeInterfaceToken(DESCRIPTOR) + [writeStrongBinder(token) + 参数]
 *           + transact(code, data, reply, 0) + readException() + 读返回值。
 *
 * 数据对象分两类（2026-11-06 由字节码确认）：
 *   1) IMusicPlaybackInfo 是 Binder 接口 —— 上行只写 asBinder()，服务端回调其 33 个 getter；
 *   2) IMedia / IContent / IMediaList 是 Parcelable —— 上行由 NativeMedia 按字段布局写入。
 * 因此数据层不需要复刻全部 bean，只需要这三份写实现。
 */
public final class MediaCenterSvcProxy {

    public static final String DESCRIPTOR = "ecarx.xsf.mediacenter.IMediaCenterSvc";

    // 实际用到的 transaction code（完整 44 项见本地协议笔记，不进公开仓库）
    public static final int T_REGISTER_MUSIC = 1;
    public static final int T_REGISTER_IN_MUSIC = 19;
    public static final int T_UNREGISTER = 4;
    public static final int T_REQUEST_PLAY = 5;
    public static final int T_UPDATE_MUSIC_PLAYBACK_STATE = 6;
    public static final int T_UPDATE_MEDIA_SOURCE_TYPE_LIST = 7;
    public static final int T_UPDATE_CURRENT_SOURCE_TYPE = 8;
    public static final int T_UPDATE_PLAYLIST = 9;
    public static final int T_UPDATE_CURRENT_PROGRESS = 10;
    public static final int T_UPDATE_CURRENT_LYRIC = 14;
    public static final int T_UPDATE_MEDIA_LIST = 29;
    public static final int T_GET_STATE_BINDER = 31;
    public static final int T_GET_MEDIACONTROLCLIENT_API = 32;
    public static final int T_GET_MEDIACONTROLLER_API = 33;
    public static final int T_UPDATE_MEDIA_CONTENT = 38;
    public static final int T_UPDATE_MEDIA_PLAY_LIST = 40;
    public static final int T_QUERY_CURRENT_FOCUS_CLIENT = 42;

    private final IBinder remote;

    public MediaCenterSvcProxy(IBinder b) {
        remote = b;
    }

    public boolean alive() {
        return remote != null && remote.isBinderAlive();
    }

    private interface Filler {
        void fill(Parcel d);
    }

    private interface Reader<T> {
        T read(Parcel r);
    }

    private static final Reader<IBinder> READ_BINDER = new Reader<IBinder>() {
        @Override
        public IBinder read(Parcel r) {
            return r.readStrongBinder();
        }
    };

    private static final Reader<String> READ_STRING = new Reader<String>() {
        @Override
        public String read(Parcel r) {
            return r.readString();
        }
    };

    private static final Reader<Boolean> READ_BOOL = new Reader<Boolean>() {
        @Override
        public Boolean read(Parcel r) {
            return r.readInt() != 0;
        }
    };

    private <T> T transact(int code, Filler filler, Reader<T> reader) {
        if (!alive()) return null;
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            d.writeInterfaceToken(DESCRIPTOR);
            if (filler != null) filler.fill(d);
            if (!remote.transact(code, d, r, 0)) return null;
            r.readException();
            return reader == null ? null : reader.read(r);
        } catch (Throwable t) {
            ProxyConfig.log("[mcsvc] transact code=" + code + " 失败: " + t);
            return null;
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    private static Filler tokenOnly(final IBinder token) {
        return new Filler() {
            @Override
            public void fill(Parcel d) {
                d.writeStrongBinder(token);
            }
        };
    }

    // ------------------------------------------------------------ 无参只读（本轮验证用）

    public IBinder getStateBinder() {
        return transact(T_GET_STATE_BINDER, null, READ_BINDER);
    }

    public IBinder getMediaControllerApi() {
        return transact(T_GET_MEDIACONTROLLER_API, null, READ_BINDER);
    }

    public IBinder getMediaControlClientApi() {
        return transact(T_GET_MEDIACONTROLCLIENT_API, null, READ_BINDER);
    }

    // ------------------------------------------------------------ 注册 / 注销

    /**
     * registerMusic(IMusicClient) → IMediaCenterClientToken（code=1）。
     * 这条路径不需要 zoneId、也不需要 registerEx，是最干净的自写注册入口。
     */
    public IBinder registerMusicClient(final IBinder client) {
        if (client == null) return null;
        return transact(T_REGISTER_MUSIC, new Filler() {
            @Override
            public void fill(Parcel d) {
                d.writeStrongBinder(client);
            }
        }, READ_BINDER);
    }

    /**
     * registerInMusic(String pkg, IMusicClient) → IMediaCenterClientToken（code=19）。
     * 注册入口：包名从这里带进去，服务端据此把音源条目与客户端对上。
     */
    public IBinder registerInMusic(final String pkg, final IBinder client) {
        if (pkg == null || client == null) return null;
        return transact(T_REGISTER_IN_MUSIC, new Filler() {
            @Override
            public void fill(Parcel d) {
                d.writeString(pkg);
                d.writeStrongBinder(client);
            }
        }, READ_BINDER);
    }

    /** unregister(IMediaCenterClientToken) → boolean（code=4）。 */
    public Boolean unregister(final IBinder token) {
        if (token == null) return null;
        return transact(T_UNREGISTER, new Filler() {
            @Override
            public void fill(Parcel d) {
                d.writeStrongBinder(token);
            }
        }, READ_BOOL);
    }

    // ------------------------------------------------------------ 带 token 的方法

    public String queryCurrentFocusClient(IBinder token) {
        if (token == null) return null;
        return transact(T_QUERY_CURRENT_FOCUS_CLIENT, tokenOnly(token), READ_STRING);
    }

    public Boolean requestPlay(IBinder token) {
        if (token == null) return null;
        return transact(T_REQUEST_PLAY, tokenOnly(token), READ_BOOL);
    }

    public void updateCurrentSourceType(IBinder token, final int type) {
        if (token == null) return;
        transact(T_UPDATE_CURRENT_SOURCE_TYPE, new Filler() {
            @Override
            public void fill(Parcel d) {
                d.writeStrongBinder(token);
                d.writeInt(type);
            }
        }, null);
    }

    public void updateCurrentLyric(IBinder token, final String lyric) {
        if (token == null) return;
        transact(T_UPDATE_CURRENT_LYRIC, new Filler() {
            @Override
            public void fill(Parcel d) {
                d.writeStrongBinder(token);
                d.writeString(lyric);
            }
        }, null);
    }

    public void updateCurrentProgress(IBinder token, final long pos) {
        if (token == null) return;
        transact(T_UPDATE_CURRENT_PROGRESS, new Filler() {
            @Override
            public void fill(Parcel d) {
                d.writeStrongBinder(token);
                d.writeLong(pos);
            }
        }, null);
    }

    public void updateMediaSourceTypeList(IBinder token, final int[] types) {
        if (token == null) return;
        transact(T_UPDATE_MEDIA_SOURCE_TYPE_LIST, new Filler() {
            @Override
            public void fill(Parcel d) {
                d.writeStrongBinder(token);
                d.writeIntArray(types);
            }
        }, null);
    }

    // ------------------------------------------------------------ 数据上行（push）

    /**
     * updateMusicPlaybackState(token, IMusicPlaybackInfo)（code=6）。
     * 第二个参数是 Binder 接口，服务端拿到后会回调我们 Info 的 33 个 getter，
     * 所以上行播放信息不需要任何 Parcelable。
     */
    public Boolean updateMusicPlaybackState(final IBinder token, final IBinder info) {
        if (token == null || info == null) return null;
        return transact(T_UPDATE_MUSIC_PLAYBACK_STATE, new Filler() {
            @Override
            public void fill(Parcel d) {
                d.writeStrongBinder(token);
                d.writeStrongBinder(info);
            }
        }, READ_BOOL);
    }

    /** updateMediaList(token, int listType, int position, List&lt;IMedia&gt;)（code=29）。 */
    public void updateMediaList(IBinder token, final int listType, final int position,
                                final List<NativeMedia.Item> list) {
        if (token == null) return;
        transact(T_UPDATE_MEDIA_LIST, new Filler() {
            @Override
            public void fill(Parcel d) {
                d.writeStrongBinder(token);
                d.writeInt(listType);
                d.writeInt(position);
                NativeMedia.writeItemList(d, list);
            }
        }, null);
    }

    /** updatePlaylist(token, int type, List&lt;IMedia&gt;)（code=9）。 */
    public void updatePlaylist(IBinder token, final int type, final List<NativeMedia.Item> list) {
        if (token == null) return;
        transact(T_UPDATE_PLAYLIST, new Filler() {
            @Override
            public void fill(Parcel d) {
                d.writeStrongBinder(token);
                d.writeInt(type);
                NativeMedia.writeItemList(d, list);
            }
        }, null);
    }

    /** updateMediaContent(token, List&lt;IContent&gt;)（code=38）。 */
    public Boolean updateMediaContent(IBinder token, final List<NativeMedia.Content> list) {
        if (token == null) return null;
        return transact(T_UPDATE_MEDIA_CONTENT, new Filler() {
            @Override
            public void fill(Parcel d) {
                d.writeStrongBinder(token);
                NativeMedia.writeContentList(d, list);
            }
        }, READ_BOOL);
    }

    /** updateMediaPlayList(token, IMediaList)（code=40），nullable Parcelable 走 writeInt(0/1)。 */
    public void updateMediaPlayList(IBinder token, final NativeMedia.MediaList list) {
        if (token == null) return;
        transact(T_UPDATE_MEDIA_PLAY_LIST, new Filler() {
            @Override
            public void fill(Parcel d) {
                d.writeStrongBinder(token);
                if (list == null) {
                    d.writeInt(0);
                } else {
                    d.writeInt(1);
                    list.writeTo(d, 0);
                }
            }
        }, null);
    }
}

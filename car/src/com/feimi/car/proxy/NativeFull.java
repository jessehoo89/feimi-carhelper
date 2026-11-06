package com.feimi.car.proxy;

import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;

/**
 * 媒体中心全链路：注册 / 焦点 / 恢复回调 —— 只用 Android 公开 API。
 *
 * 与 NativeUplink 的分工：本类负责注册、焦点与恢复回调，NativeUplink 负责数据上行。
 *
 * 注册流程实为三步（2026-11-06 实车确认）：
 *   1) IMediaCenterSvc.registerInMusic(19)(pkg, IMusicClient) -> token
 *      注意包名是从这里带进去的：服务端用它把「音源条目」和「已注册客户端」对上，
 *      换成 registerMusic(1)（不带包名）会让身份变成我们自己的包名，音源首页因此
 *      显示「当前暂无播放内容」。
 *   2) updateZoneId(token, 0) 走 EAS Framework 通用 RPC：
 *      service="mediacenter" module="MediaCenterAPI" method="updateZoneId"
 *      param="0".getBytes() attach=new byte[0]，extra=token
 *   3) registerEx(同通道, param=pkg) 携带一个 IExCallback Binder（收藏/下载等扩展动作）
 *
 * 恢复回调在 IStateRecoverApiSvc（IMediaCenterSvc.getStateBinder(31) 返回）：
 *   1=registerMusicRecoveryIntent(token,int,String JSON) 5=setMusicRecoveryCallback(token,listener)

 */
public final class NativeFull {

    private static final String SVC = "mediacenter";
    private static final String MODULE = "MediaCenterAPI";

    public static final String STATE_DESCRIPTOR =
            "ecarx.xsf.mediacenter.staterecover.IStateRecoverApiSvc";
    public static final String LISTENER_DESCRIPTOR =
            "ecarx.xsf.mediacenter.staterecover.IMusicRecoveryListener";
    public static final String EX_DESCRIPTOR = "com.ecarx.eas.xsf.mediacenter.IExCallback";

    /** IStateRecoverApiSvc 的 transaction code（其余 2=注销意图 3/4=取恢复数据 6=完成）。 */
    private static final int S_REGISTER_RECOVERY_INTENT = 1;
    private static final int S_SET_RECOVERY_CALLBACK = 5;

    private static volatile MediaCenterSvcProxy svc;
    private static volatile IBinder stateSvc;
    private static volatile IBinder token;
    private static volatile NativeMusicClient client;
    private static volatile IBinder exCb;
    private static volatile IBinder listener;

    private static final Handler main = new Handler(Looper.getMainLooper());

    private NativeFull() {
    }

    // ---------------------------------------------------------------- 初始化

    /** 连上 EAS Framework 并取 IMediaCenterSvc / IStateRecoverApiSvc；可重复调用。 */
    public static boolean init() {
        if (svc != null && svc.alive()) return true;
        IBinder b = EasNative.mediaCenterBinder();
        if (b == null) {
            ProxyConfig.log("[nfull] 拿不到 IMediaCenterSvc Binder，自写链路不可用");
            return false;
        }
        MediaCenterSvcProxy p = new MediaCenterSvcProxy(b);
        svc = p;
        try {
            stateSvc = p.getStateBinder();
        } catch (Throwable ignored) {
        }
        String sd = null;
        try {
            sd = stateSvc == null ? null : stateSvc.getInterfaceDescriptor();
        } catch (Throwable ignored) {
        }
        ProxyConfig.log("[nfull] 已就绪 svcAlive=" + p.alive() + " stateBinder=" + sd);
        return true;
    }

    public static boolean ready() {
        return svc != null && svc.alive() && token != null;
    }

    public static IBinder token() {
        return token;
    }

    public static MediaCenterSvcProxy svc() {
        return svc;
    }

    // ---------------------------------------------------------------- 注册

    /**
     * 自写注册：registerInMusic(pkg) + updateZoneId + registerEx。
     * 成功返回服务端下发的 token Binder（IMediaCenterClientToken）。
     */
    public static IBinder register(String pkgName) {
        if (!init()) return null;
        if (pkgName == null || pkgName.length() == 0) return null;
        if (client == null) client = new NativeMusicClient();

        IBinder tok = svc.registerInMusic(pkgName, client.asBinder());
        if (tok == null) {
            ProxyConfig.log("[nfull] registerInMusic(19) 未返回 token —— 服务端拒绝自写注册");
            return null;
        }
        token = tok;
        String desc = null;
        try {
            desc = tok.getInterfaceDescriptor();
        } catch (Throwable ignored) {
        }
        ProxyConfig.log("[nfull] registerInMusic(19) 成功 token=" + desc + " pkg=" + pkgName);

        // zoneId 固定 0
        boolean zone = EasNative.asyncBinderCall(SVC, MODULE, "updateZoneId",
                "0".getBytes(), tok);
        ProxyConfig.log("[nfull] updateZoneId(0) 已发送 -> " + zone);

        if (exCb == null) exCb = new ExCallback();
        boolean ex = EasNative.asyncBinderCall(SVC, MODULE, "registerEx",
                pkgName.getBytes(), exCb);
        ProxyConfig.log("[nfull] registerEx 已发送 -> " + ex);
        return tok;
    }

    /** 注销（主链路不调用，供自愈/退出场景使用）。 */
    public static Boolean unregister() {
        IBinder tok = token;
        if (svc == null || tok == null) return null;
        token = null;
        return svc.unregister(tok);
    }

    // ---------------------------------------------------------------- 焦点 / 音源

    public static Boolean requestPlay() {
        IBinder tok = token;
        if (svc == null || tok == null) return null;
        return svc.requestPlay(tok);
    }

    public static void updateCurrentSourceType(int type) {
        IBinder tok = token;
        if (svc == null || tok == null) return;
        svc.updateCurrentSourceType(tok, type);
    }

    public static void updateMediaSourceTypeList(int[] types) {
        IBinder tok = token;
        if (svc == null || tok == null) return;
        svc.updateMediaSourceTypeList(tok, types);
    }

    public static String queryCurrentFocusClient() {
        IBinder tok = token;
        if (svc == null || tok == null) return null;
        return svc.queryCurrentFocusClient(tok);
    }

    // ---------------------------------------------------------------- 恢复回调

    /**
     * setMusicRecoveryCallback(5) + registerMusicRecoveryIntent(1)。
     *
     * @param action "恢复上次音源"时要投递的 Intent action，例如 com.feimi.car.proxy.RECOVER
     * @param pkg    接收该 Intent 的包名
     */
    public static boolean setupRecovery(int type, String action, String pkg) {
        if (stateSvc == null && !init()) return false;
        if (stateSvc == null) {
            ProxyConfig.log("[nfull] 拿不到 IStateRecoverApiSvc，恢复回调未注册");
            return false;
        }
        if (listener == null) listener = new RecoveryListener();
        Boolean cb = stateCall(S_SET_RECOVERY_CALLBACK, new Filler() {
            @Override
            public void fill(Parcel d) {
                d.writeStrongBinder(token);
                d.writeStrongBinder(listener);
            }
        });
        ProxyConfig.log("[nfull] setMusicRecoveryCallback(5) 已发送 -> " + cb);

        Boolean it = stateCall(S_REGISTER_RECOVERY_INTENT, new Filler() {
            @Override
            public void fill(Parcel d) {
                d.writeStrongBinder(token);
                d.writeInt(type);
                d.writeString(intentJson(action, pkg));
            }
        });
        ProxyConfig.log("[nfull] registerMusicRecoveryIntent(1) -> " + it
                + " action=" + action + " pkg=" + pkg);
        return Boolean.TRUE.equals(it);
    }

    /** 恢复意图以 JSON 形式传给服务端：{"state_action":..., "state_package":...}。 */
    private static String intentJson(String action, String pkg) {
        return "{\"state_action\":\"" + esc(action) + "\",\"state_package\":\"" + esc(pkg) + "\"}";
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private interface Filler {
        void fill(Parcel d);
    }

    private static Boolean stateCall(int code, Filler filler) {
        IBinder s = stateSvc;
        IBinder tok = token;
        if (s == null || tok == null) return null;
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            d.writeInterfaceToken(STATE_DESCRIPTOR);
            filler.fill(d);
            if (!s.transact(code, d, r, 0)) return null;
            r.readException();
            return r.readInt() != 0;
        } catch (Throwable t) {
            ProxyConfig.log("[nfull] stateRecover code=" + code + " 失败: " + t);
            return null;
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    /**
     * 车机「恢复上次音源」时回调进来：1=onGetMediaList（不需要列表，空实现）、
     * 2=onGetMusicPlaybackInfo（立刻回推一次完整状态）。
     * 回推放到主线程做，避免在 Binder 线程里再同步打进媒体中心导致互相等待。
     */
    private static final class RecoveryListener extends Binder {

        @Override
        public String getInterfaceDescriptor() {
            return LISTENER_DESCRIPTOR;
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code == INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString(LISTENER_DESCRIPTOR);
                return true;
            }
            if (data != null) {
                try {
                    data.enforceInterface(LISTENER_DESCRIPTOR);
                } catch (Throwable ignored) {
                }
            }
            if (code == 2) {
                ProxyConfig.log("[nfull] 车机请求恢复播放信息");
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        MediaCenterBridge.pushState();
                    }
                });
            } else {
                ProxyConfig.log("[nfull] 车机请求恢复媒体列表 code=" + code + "（忽略）");
            }
            if (reply != null) reply.writeNoException();
            return true;
        }
    }

    /**
     * registerEx 的扩展动作回调：1=onAction → String，2=onExAction → IExContent。
     * 本项目未声明收藏/下载能力，统一返回 null / 空对象。
     */
    private static final class ExCallback extends Binder {

        @Override
        public String getInterfaceDescriptor() {
            return EX_DESCRIPTOR;
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code == INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString(EX_DESCRIPTOR);
                return true;
            }
            if (data != null) {
                try {
                    data.enforceInterface(EX_DESCRIPTOR);
                } catch (Throwable ignored) {
                }
            }
            if (reply == null) return true;
            reply.writeNoException();
            if (code == 1) {
                reply.writeString(null);
            } else {
                reply.writeInt(0);   // IExContent = null
            }
            return true;
        }
    }
}

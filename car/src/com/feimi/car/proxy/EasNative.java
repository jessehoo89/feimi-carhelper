package com.feimi.car.proxy;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Parcel;

/**
 * EAS Framework 传输层 —— 只用 Android 公开 API 实现。
 *
 * EAS Framework 服务由系统内置的 com.ecarx.sdk.openapi 提供；本类直接 bindService
 * 并用 IBinder.transact 手写 Parcel 完成调用。这里只实现协议契约，不复制第三方代码。
 *
 * request Parcel（IEASFrameworkService.call(EASFrameworkMessage)）：
 *   writeInterfaceToken(descriptor) + writeInt(1) + service + module + method
 *   + writeByteArray(methodParam) + writeByteArray(attachParam)
 * response Parcel（EASFrameworkRetMessage / SupportServiceRetMessage）：
 *   readException + int(hasResult) + int(code) + String(msg) + byte[](attachInfo)
 *   + String(retClassName) + [int(code) + String(msg) + byte[](data) + IBinder]
 * 两层 code 均为 200 时取 data；mBinder 供第二阶段使用。
 *
 * 取媒体中心主 Binder 走通用 RPC
 *   EASFrameworkMessage("mediacenter", "MediaCenterAPI", "getMainBinder",
 *                       "NoParam".getBytes(), new byte[0])
 * 从 mRetMsg.mBinder 取 IMediaCenterSvc Binder，之后 17 个方法走该 Binder 的 AIDL。
 * attachParam 只是空字节数组，不是鉴权。callBinder() 供上层取 Binder 使用。
 *
 * transaction code 实车确定为 4（2026-11-06）。固件升级后若失效再扫描 1..40
 * 自发现，也可 settings put global carproxy_eas_call_code N 手动指定。
 */
public final class EasNative {

    private static final String ACTION = "com.ecarx.easframework.intent.action.EASFRAMEWORK";
    private static final String SERVICE_PKG = "com.ecarx.sdk.openapi";
    /** 2026-11-06 实车自发现：IEASFrameworkService.call 的 transaction code。 */
    private static final int DEFAULT_CALL_CODE = 4;
    /** IEASFrameworkService.asyncBinderCall 的 transaction code。 */
    private static final int ASYNC_BINDER_CODE = 6;
    private static final String MODULE_SOURCE = "media_session_controller";
    private static final String MODULE_MEDIACENTER = "MediaCenterAPI";
    /** protobuf: zoneId = 0，用于自发现时的只读探针请求。 */
    private static final byte[] PROBE_REQ = new byte[]{0x08, 0x00};
    private static final byte[] EMPTY_ATTACH = new byte[0];

    private static volatile IBinder binder;
    private static volatile String descriptor;
    /** 自发现结果；0 表示用 DEFAULT_CALL_CODE。 */
    private static volatile int callCode = 0;
    private static volatile boolean bound;
    private static final Object LOCK = new Object();

    /** 通用 RPC 结果：SupportServiceRetMessage 的 mData 与 mBinder。 */
    private static final class Ret {
        byte[] data;
        IBinder binder;
    }

    private EasNative() {
    }

    /** 幂等连接，最多等 3 秒。 */
    public static boolean connectAndWait(Context ctx) {
        if (!ready()) {
            if (ctx == null) return false;
            if (!bound) {
                synchronized (LOCK) {
                    if (!bound) {
                        try {
                            Intent i = new Intent(ACTION).setPackage(SERVICE_PKG);
                            bound = ctx.getApplicationContext()
                                    .bindService(i, conn, Context.BIND_AUTO_CREATE);
                            ProxyConfig.log("[eas] bindService " + ACTION + " -> " + bound);
                        } catch (Throwable t) {
                            bound = false;
                            ProxyConfig.log("[eas] bindService 失败: " + t);
                            return false;
                        }
                    }
                }
            }
            long deadline = System.currentTimeMillis() + 3000L;
            while (binder == null && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(50L);
                } catch (InterruptedException ignored) {
                }
            }
        }
        return ready();
    }

    private static final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder b) {
            binder = b;
            try {
                descriptor = b.getInterfaceDescriptor();
            } catch (Throwable ignored) {
            }
            ProxyConfig.log("[eas] 已连接 descriptor=" + descriptor);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            binder = null;
            bound = false;
            ProxyConfig.log("[eas] 服务断开，下次调用会重新 bind");
        }
    };

    public static boolean ready() {
        IBinder b = binder;
        return b != null && b.isBinderAlive();
    }

    private static int effectiveCode() {
        int manual = ProxyConfig.easCallCode();
        if (manual > 0) return manual;
        int c = callCode;
        return c > 0 ? c : DEFAULT_CALL_CODE;
    }

    /** 发一次通用 RPC；成功返回 mRetMsg.mData，失败返回 null。 */
    public static byte[] call(String service, String module, String method, byte[] param) {
        if (!connectAndWait(MediaCenterBridge.context())) return null;
        int code = effectiveCode();
        Ret r = transact(code, service, module, method, param, null);
        if (r == null || r.data == null) {
            // 启动瞬间可能与其它 Binder 调用并发，先用同一个 code 重试一次
            try {
                Thread.sleep(150L);
            } catch (InterruptedException ignored) {
            }
            r = transact(code, service, module, method, param, null);
        }
        if (r == null || r.data == null) {
            // 固件换了 code：清缓存重新发现一次
            callCode = 0;
            int found = discover();
            if (found > 0) r = transact(found, service, module, method, param, null);
        }
        return r == null ? null : r.data;
    }

    /**
     * 取回 mRetMsg.mBinder（即 IMediaCenterSvc）。
     * 只读、无副作用；attachParam 用空字节数组。
     */
    public static IBinder callBinder(String service, String module, String method, byte[] param) {
        if (!connectAndWait(MediaCenterBridge.context())) return null;
        Ret r = transact(effectiveCode(), service, module, method, param, EMPTY_ATTACH);
        return r == null ? null : r.binder;
    }

    /** 取 IMediaCenterSvc 主 Binder。 */
    public static IBinder mediaCenterBinder() {
        return callBinder("mediacenter", MODULE_MEDIACENTER, "getMainBinder", "NoParam".getBytes());
    }

    /**
     * IEASFrameworkService.asyncBinderCall(message, extraBinder) —— 比 call() 多一个
     * Binder 参数，用于把 token / callback 交给服务端。transaction code 实车由
     * 客户端代理字节码确认为 6（call 为 4）。
     *
     * 线上格式：writeInterfaceToken + writeInt(1) + EASFrameworkMessage(5 段)
     *           + writeStrongBinder(extra)；reply 解析与 call() 相同。
     */
    public static boolean asyncBinderCall(String service, String module, String method,
                                          byte[] param, IBinder extra) {
        if (extra == null) return false;
        if (!connectAndWait(MediaCenterBridge.context())) return false;
        Ret r = transact(ASYNC_BINDER_CODE, service, module, method, param, EMPTY_ATTACH, extra);
        return r != null;
    }

    /** 扫描 1..40，找到能返回合法 mData 的 transaction code 并缓存。 */
    private static int discover() {
        int cached = callCode;
        if (cached > 0) return cached;
        synchronized (LOCK) {
            if (callCode > 0) return callCode;
            if (binder == null) return 0;
            for (int c = 1; c <= 40; c++) {
                Ret r = transact(c, "mediacenter", MODULE_SOURCE,
                        "getAppSourceConfig", PROBE_REQ, null);
                if (r != null && r.data != null && r.data.length > 0) {
                    callCode = c;
                    ProxyConfig.log("[eas] 自发现 transaction code=" + c
                            + "，探针返回 " + r.data.length + "B（后续复用）");
                    return c;
                }
            }
        }
        ProxyConfig.log("[eas] 未自发现 transaction code");
        return 0;
    }

    private static Ret transact(int code, String service, String module,
                                String method, byte[] param, byte[] attach) {
        return transact(code, service, module, method, param, attach, null);
    }

    /** extra 非空时在 EASFrameworkMessage 之后追加 writeStrongBinder（asyncBinderCall）。 */
    private static Ret transact(int code, String service, String module,
                                String method, byte[] param, byte[] attach, IBinder extra) {
        IBinder b = binder;
        if (b == null) return null;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            if (descriptor != null) data.writeInterfaceToken(descriptor);
            data.writeInt(1);                  // Parcelable 参数非 null
            data.writeString(service);         // mServiceName
            data.writeString(module);          // mMoudleName
            data.writeString(method);          // mMethod
            data.writeByteArray(param);        // mMethodParam
            data.writeByteArray(attach);       // mAttachParam
            if (extra != null) data.writeStrongBinder(extra);
            if (!b.transact(code, data, reply, 0)) return null;
            reply.readException();
            if (reply.readInt() == 0) return null;   // _result == null
            int topCode = reply.readInt();
            reply.readString();                      // mMsg
            reply.createByteArray();                 // mAttachInfo
            String retCls = reply.readString();      // writeParcelable 写的类名
            if (retCls == null || topCode != 200) {
                ProxyConfig.log("[eas] " + method + " 失败 topCode=" + topCode
                        + " retCls=" + retCls);
                return null;
            }
            int subCode = reply.readInt();
            reply.readString();                      // SupportServiceRetMessage.mMsg
            byte[] payload = reply.createByteArray();// mData
            IBinder sub = reply.readStrongBinder();  // mBinder（第二阶段入口）
            if (subCode != 200) {
                ProxyConfig.log("[eas] " + method + " 失败 subCode=" + subCode);
                return null;
            }
            Ret r = new Ret();
            r.data = payload;
            r.binder = sub;
            return r;
        } catch (Throwable ignored) {
            return null;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

}

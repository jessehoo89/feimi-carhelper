package com.feimi.car.proxy;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;

/**
 * 音源列表（媒体中心"可切换音源"）注入 + 保活。
 *
 * 机制：媒体中心的音源列表不是"注册即出现"，而是一份可读写的配置
 * —— EAS 框架服务 `mediacenter / media_session_controller` 的
 * `getAppSourceConfig` / `setAppSourceConfig`。
 *
 * 配置是 protobuf：开头是 zoneId(08 xx)，之后重复 field20 块，每块一条音源：
 *   field1 packageName / field2 iconPath / field3 appName /
 *   field4 sourceTypeList / field5 priorityLevel / field8 isRunning
 *
 * 写法：把已有块原样保留（去掉本应用的旧块），末尾追加新块再整体写回。
 * repeated 字段允许这样做，不需要 protobuf 库。
 */
public final class CarMusicSource {

    private static final String SVC = "mediacenter";
    private static final String MODULE = "media_session_controller";
    private static final byte[] ZONE_REQ = new byte[]{0x08, 0x00};
    private static final int FIELD_APP_SOURCE = 20;
    /** 音源条目指向的转发壳包名：点开跳落雪、图标是落雪 logo。 */
    static final String SHELL_PKG = "com.feimi.srchost";

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static volatile boolean started;
    private static volatile boolean keepAliveRunning;

    /** 供界面显示：音源条目是否已成功写回媒体中心配置。 */
    public static volatile boolean injectedOk = false;
    public static volatile long injectedAt = 0L;

    private CarMusicSource() {
    }

    /** 界面「重写音源条目」：清掉记忆状态，立即重新注入一次。 */
    public static void reinstall() {
        started = false;
        injectedOk = false;
        install(MediaCenterBridge.context());
    }

    public static void install(Context ctx) {
        if (started) return;
        started = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    doInstall();
                } catch (Throwable t) {
                    ProxyConfig.log("[source] 注入异常: " + t);
                    started = false;
                }
            }
        }, "carproxy-source").start();
    }

    /** 传输层就绪：EAS Framework Binder 是否已连上。 */
    private static boolean transportReady() {
        return EasNative.connectAndWait(MediaCenterBridge.context());
    }

    private static void doInstall() {
        if (!transportReady()) {
            ProxyConfig.log("[source] 找不到 EAS 框架服务，跳过注入");
            return;
        }
        String pkg = entryPkg();

        byte[] cur = call("getAppSourceConfig", ZONE_REQ);
        ProxyConfig.log("[source] 当前配置 " + (cur == null ? "null" : cur.length + "B"));
        if (cur == null || cur.length == 0) return;

        byte[] entry = buildEntry(pkg, iconPath(), ProxyConfig.targetLabel());
        // 三个包名的旧条目都要清掉，否则换过身份会留下重复项
        byte[] merged = merge(cur,
                new String[]{pkg, MediaCenterBridge.pkg(), ProxyConfig.targetPkg(),
                        ProxyConfig.prevTargetPkg(), SHELL_PKG}, entry);
        if (merged == null) {
            ProxyConfig.log("[source] 配置解析失败，放弃写入");
            return;
        }
        if (merged.length == cur.length && sameBytes(merged, cur)) {
            ProxyConfig.log("[source] 配置已是目标状态，无需写入");
            return;
        }

        call("setAppSourceConfig", merged);
        byte[] after = call("getAppSourceConfig", ZONE_REQ);
        boolean ok = after != null && indexOf(after, pkg.getBytes()) >= 0 && indexOf(after, iconPath().getBytes()) >= 0;
        injectedOk = ok;
        injectedAt = System.currentTimeMillis();
        ProxyConfig.log("[source] 写回 " + merged.length + "B → 回读 " + (after == null ? "null" : after.length + "B")
                + " 含本应用=" + ok);
    }

    /** 每 15s 复查，被系统重置后自动补回（M2 保活）。重复调用只保留一个循环。 */
    public static void startKeepAlive() {
        if (keepAliveRunning) return;
        keepAliveRunning = true;
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    if (transportReady()) {
                        byte[] cur = call("getAppSourceConfig", ZONE_REQ);
                        String pkg = entryPkg();
                        if (cur != null && indexOf(cur, pkg.getBytes()) < 0) {
                            ProxyConfig.log("[source] 配置丢失，重新注入");
                            started = false;
                            install(null);
                        }
                    }
                } catch (Throwable ignored) {
                }
                main.postDelayed(this, 15000L);
            }
        }, 15000L);
    }

    /** 把当前配置 dump 出来（base64 分片进日志 + 落一份到外部目录），用于核对系统条目的真实格式。 */
    static void dumpConfig() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!transportReady()) {
                        ProxyConfig.log("[cfg] 传输层未就绪");
                        return;
                    }
                    byte[] cur = call("getAppSourceConfig", ZONE_REQ);
                    if (cur == null) {
                        ProxyConfig.log("[cfg] 读取失败");
                        return;
                    }
                    String b64 = android.util.Base64.encodeToString(cur, android.util.Base64.NO_WRAP);
                    int chunk = 700;
                    int total = (b64.length() + chunk - 1) / chunk;
                    for (int i = 0; i < total; i++) {
                        int from = i * chunk;
                        int to = Math.min(b64.length(), from + chunk);
                        ProxyConfig.log("[cfg] " + (i + 1) + "/" + total + " " + b64.substring(from, to));
                    }
                    try {
                        java.io.File f = new java.io.File(
                                MediaCenterBridge.context().getExternalFilesDir(null), "src.cfg");
                        java.io.FileOutputStream fo = new java.io.FileOutputStream(f);
                        fo.write(cur);
                        fo.close();
                        ProxyConfig.log("[cfg] 文件 " + f.getAbsolutePath() + " " + cur.length + "B");
                    } catch (Throwable ignored) {
                    }
                } catch (Throwable t) {
                    ProxyConfig.log("[cfg] dump 异常: " + t);
                }
            }
        }, "carproxy-cfg").start();
    }

    /**
     * 音源条目的包名必须是我们自己。
     *
     * 试过指向转发壳包（为了点开跳落雪）：媒体中心会把条目包名当成"当前音源的提供方"，
     * 包名对不上就认不出正在播放的代理，界面直接变成"当前暂无播放内容"。
     * 所以条目不换包名，跳转问题另行解决。
     */
    static String entryPkg() {
        return MediaCenterBridge.proxyPkg();
    }

    // ------------------------------------------------------------------ 配置合并

    /**
     * 保留 zoneId 前缀与所有"非本应用"的条目，末尾追加新条目。
     * 返回 null 表示结构不认识，不写（避免把车机的配置写坏）。
     */
    static byte[] merge(byte[] cur, String[] dropPkgs, byte[] entry) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int i = 0;
        try {
            if (cur.length >= 2 && (cur[0] & 0xFF) == 0x08) {
                out.write(cur, 0, 2);   // zoneId
                i = 2;
            }
            while (i < cur.length) {
                int blockStart = i;
                long[] key = readVarint(cur, i);
                if (key == null) return null;
                int keyVal = (int) key[0];
                i = (int) key[1];
                if ((keyVal >>> 3) != FIELD_APP_SOURCE) {
                    ProxyConfig.log("[source] 未知字段 " + (keyVal >>> 3) + "，放弃写入");
                    return null;
                }
                long[] len = readVarint(cur, i);
                if (len == null) return null;
                int body = (int) len[1];
                int payloadLen = (int) len[0];
                if (body + payloadLen > cur.length) return null;

                byte[] payload = new byte[payloadLen];
                System.arraycopy(cur, body, payload, 0, payloadLen);
                boolean mine = false;
                for (String p : dropPkgs) {
                    if (p != null && p.length() > 0 && indexOf(payload, p.getBytes()) >= 0) {
                        mine = true;
                        break;
                    }
                }
                if (!mine) {
                    out.write(cur, blockStart, body + payloadLen - blockStart);
                } else {
                    ProxyConfig.log("[source] 移除旧条目 " + payloadLen + "B");
                }
                i = body + payloadLen;
            }
            out.write(entry, 0, entry.length);
            return out.toByteArray();
        } catch (Throwable t) {
            ProxyConfig.log("[source] merge 异常: " + t);
            return null;
        }
    }

    private static long[] readVarint(byte[] b, int off) {
        long value = 0;
        int shift = 0;
        int i = off;
        while (i < b.length && shift < 64) {
            int x = b[i++] & 0xFF;
            value |= (long) (x & 0x7F) << shift;
            if ((x & 0x80) == 0) return new long[]{value, i};
            shift += 7;
        }
        return null;
    }

    // ------------------------------------------------------------------ 调用

    private static byte[] call(String method, byte[] payload) {
        byte[] out = EasNative.call(SVC, MODULE, method, payload);
        if (out == null) ProxyConfig.log("[source] " + method + " 无结果");
        return out;
    }

    // ------------------------------------------------------------------ protobuf 手写

    /** 图标用我们自己 APK 里的资源：别的 App 的 adaptive icon 车机解不出来，会退化成安卓机器人。 */
    private static String iconPath() {
        try {
            Context c = MediaCenterBridge.context();
            int id = c.getResources().getIdentifier("ic_target", "drawable", c.getPackageName());
            if (id != 0) {
                return "android.resource://" + c.getPackageName() + "/" + id;
            }
        } catch (Throwable t) {
            ProxyConfig.log("[source] 取图标失败: " + t);
        }
        return "";
    }

    private static byte[] buildEntry(String pkg, String icon, String name) {
        byte[] inner = concat(
                str(1, pkg),
                str(2, icon == null ? "" : icon),
                str(3, name),
                varintField(4, ProxyConfig.SOURCE_ONLINE),
                varintField(5, 1),
                varintField(8, 1));
        // field 20 wire type 2 → key 162 → varint 0xA2 0x01
        return concat(new byte[]{(byte) 0xA2, 0x01}, varint(inner.length), inner);
    }

    private static byte[] str(int field, String v) {
        byte[] b = v.getBytes();
        return concat(varint((field << 3) | 2), varint(b.length), b);
    }

    private static byte[] varintField(int field, int v) {
        return concat(varint(field << 3), varint(v));
    }

    private static byte[] varint(int value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int v = value;
        while (true) {
            if ((v & ~0x7F) == 0) {
                out.write(v);
                break;
            }
            out.write((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        return out.toByteArray();
    }

    static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] out = new byte[n];
        int off = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, off, p.length);
            off += p.length;
        }
        return out;
    }

    static int indexOf(byte[] hay, byte[] needle) {
        if (hay == null || needle == null || needle.length == 0) return -1;
        outer:
        for (int i = 0; i + needle.length <= hay.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static boolean sameBytes(byte[] a, byte[] b) {
        if (a == null || b == null || a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) return false;
        }
        return true;
    }
}

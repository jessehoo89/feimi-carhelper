package com.feimi.car;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;

import com.feimi.car.proxy.ProxyConfig;
import com.feimi.car.proxy.ProxyService;

/**
 * 开机自启：让"音乐接入媒体中心"默认常驻，不用每次上车手动点。
 *
 * 只在用户没有手动关掉接入（ProxyConfig.enabled）时才拉起；关掉后重启也保持关闭。
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String a = intent == null ? "null" : String.valueOf(intent.getAction());
        U.log("BootReceiver: " + a);
        try {
            ProxyConfig.init(context);
            if (ProxyConfig.enabled()) {
                ProxyService.start(context);
            } else {
                U.log("音乐接入已关闭，开机不拉起");
            }
            // 悬浮球默认不启用：只有用户打开过（偏好为 true）才自启
            if (ProxyConfig.ballEnabled()) {
                if (Build.VERSION.SDK_INT < 23
                        || Settings.canDrawOverlays(context)) {
                    context.startService(new Intent(context, FullscreenService.class));
                    U.log("悬浮球偏好为开，已自启");
                } else {
                    U.log("悬浮球偏好为开，但悬浮窗权限没了，不拉起");
                }
            }
        } catch (Throwable t) {
            U.log("开机自启失败: " + t);
        }
    }
}

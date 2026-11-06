package com.feimi.car.proxy;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 车机"恢复上次音源"的落点。
 *
 * 注册时通过 registerMusicRecoveryIntent 给了车机一个 Intent，车机在需要把音源重新拉起时
 * 会把它发回来（广播或启动服务两种形式都接）。收到后直接把播放动作转发给目标 App，
 * 这样即使车机不走 onPlay 回调，第二条按键也能把歌续上。
 */
public class RecoverReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String a = intent == null ? "null" : String.valueOf(intent.getAction());
        ProxyConfig.log("[恢复] 收到 " + a + " 组件=" + (intent == null ? "" : intent.getComponent()));
        try {
            ProxyService.start(context);
        } catch (Throwable ignored) {
        }
        MediaCenterBridge.recoverRegistration("车机恢复音源");
        ControlForwarder.action("play");
    }
}

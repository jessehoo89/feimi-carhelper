package com.feimi.car.proxy;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.view.KeyEvent;

/**
 * 硬件/方向盘媒体键兜底通道。
 *
 * 背景：媒体中心 UI 的播放键走 MusicClient.onPlay/onPause，实测暂停后能正常恢复；
 * 但方向盘中间的播放/暂停键走的是另一条链路（OEM 按键 → 系统媒体键），
 * 暂停之后再按就没人接了 —— 表现为"能暂停、不能继续"。
 *
 * 我们调了 requestPlay 抢了播放焦点，所以这些媒体键有机会落到本应用；
 * 这里接住，按当前真实播放状态翻成明确的 play/pause 再转发给目标 App。
 */
public class MediaKeyReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            if (intent == null) return;
            String action = intent.getAction();
            KeyEvent ke = (KeyEvent) intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
            ProxyConfig.log("[键] " + action + " code=" + (ke == null ? "null" : String.valueOf(ke.getKeyCode()))
                    + " act=" + (ke == null ? "-" : String.valueOf(ke.getAction())));
            if (ke == null) return;
            if (ke.getAction() != KeyEvent.ACTION_DOWN) return;
            if (ke.getRepeatCount() > 0) return;   // 长按只处理第一次

            // 用"真实播放状态"决定方向，不用乐观值，避免状态漂移后越按越乱
            boolean playing = SessionWatcher.isPlaying();
            switch (ke.getKeyCode()) {
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                case KeyEvent.KEYCODE_HEADSETHOOK:
                    ControlForwarder.action(playing ? "pause" : "play");
                    break;
                case KeyEvent.KEYCODE_MEDIA_PLAY:
                    ControlForwarder.action("play");
                    break;
                case KeyEvent.KEYCODE_MEDIA_PAUSE:
                    ControlForwarder.action("pause");
                    break;
                case KeyEvent.KEYCODE_MEDIA_NEXT:
                    ControlForwarder.action("next");
                    break;
                case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                    ControlForwarder.action("previous");
                    break;
                default:
                    break;
            }
        } catch (Throwable t) {
            ProxyConfig.log("[键] 处理失败: " + t);
        }
    }
}

package com.feimi.car;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.feimi.car.proxy.CarMusicSource;
import com.feimi.car.proxy.ControlForwarder;
import com.feimi.car.proxy.MediaCenterBridge;
import com.feimi.car.proxy.NowPlaying;
import com.feimi.car.proxy.ProxyConfig;
import com.feimi.car.proxy.ProxyService;
import com.feimi.car.proxy.SessionWatcher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 飞谧车机助手 · 车机端控制台。
 *
 * 两块主功能：
 *   1) 音乐接入媒体中心（默认开启）—— 把目标音乐 App 的播放状态 / 封面 / 歌词翻译给车机媒体中心，
 *      方向盘中间键可暂停续播，退出 App 后再点播放会自动把 App 拉起来；
 *   2) 万物全屏 —— 悬浮球把当前页面搬到全屏区（系统限制：不支持的 App 会自动回滚）。
 *
 * UI 全部代码构建：车机工程没有 AndroidX / Material，用自绘卡片 + 自绘开关保证观感统一。
 * 画布 2560×1600（density=160，1dp=1px），所以尺寸基准再乘 S=2 —— 视觉上等于在 1280×800 上设计。
 */
public class MainActivity extends Activity {

    /** 画布缩放：density=160 的 2560×1600 屏，按 2× 出图才符合正常观感 */
    private static final float S = 2f;

    // ------------------------------------------------------------------ 配色
    private static final int C_BG = 0xFF080B0F;
    private static final int C_BG2 = 0xFF0C1116;
    private static final int C_CARD = 0xFF11171E;
    private static final int C_CHIP = 0xFF0B1015;
    private static final int C_LINE = 0xFF1E2833;
    private static final int C_TITLE = 0xFFF2F5F8;
    private static final int C_TEXT = 0xFFC7D1DB;
    private static final int C_SUB = 0xFF7B8794;
    private static final int C_DIM = 0xFF54606C;
    private static final int C_ACCENT = 0xFF34D399;
    private static final int C_BLUE = 0xFF3B82F6;
    private static final int C_AMBER = 0xFFF5A524;
    private static final int C_RED = 0xFFEF4444;

    private final Handler ui = new Handler(Looper.getMainLooper());

    // 媒体接入
    private ToggleView toggle;
    private TextView pillMedia;
    private TextView valService;
    private TextView valRegistered;
    private TextView valSource;
    private TextView valTarget;
    private TextView nowTitle;
    private TextView nowState;
    private TextView recentLog;
    private TextView playBtnLabel;

    // 全屏
    private TextView fullStatus;
    private TextView pillBall;
    private ToggleView ballToggle;

    // 上传
    private TextView upUrl;
    private TextView upStatus;
    private UploadServer uploadServer;
    private String uploadToken = "";

    // 底部
    private TextView msg;

    // 浮层（车机上用 Dialog 的独立 Window 渲染不正常，改成应用内浮层，完全可控）
    private FrameLayout overlay;

    private volatile boolean resumed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);   // 不要系统 ActionBar
        Geely.bypassHiddenApi();
        ProxyConfig.init(this);
        setContentView(buildUi());

        // 默认开启：只要用户没有手动关掉，进应用就把接入服务拉起来
        if (ProxyConfig.enabled()) {
            ProxyService.start(this);
        }
        // 悬浮球默认不启用（偏好）；关着的时候顺手清掉上次可能残留的悬浮球
        if (ProxyConfig.ballEnabled()) {
            if (canOverlay()) {
                startService(new Intent(this, FullscreenService.class));
            }
        } else {
            try {
                stopService(new Intent(this, FullscreenService.class));
            } catch (Throwable ignored) {
            }
        }
    }

    // ------------------------------------------------------------------ 尺寸 / 绘制小工具

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density * S + 0.5f);
    }

    private GradientDrawable bg(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        return d;
    }

    private GradientDrawable bgStroke(int color, float radius, int stroke) {
        GradientDrawable d = bg(color, radius);
        d.setStroke(dp(1), stroke);
        return d;
    }

    private TextView text(String s, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, size * S);
        t.setTextColor(color);
        if (bold) t.setTypeface(t.getTypeface(), Typeface.BOLD);
        return t;
    }

    private View gap(int h) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(h)));
        return v;
    }

    private View hgap(int w) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(w), 1));
        return v;
    }

    /** 弹性占位（把后面的内容推到右边）。 */
    private View flex() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        return v;
    }

    private View divider() {
        View v = new View(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        lp.topMargin = dp(12);
        lp.bottomMargin = dp(12);
        v.setLayoutParams(lp);
        v.setBackgroundColor(C_LINE);
        return v;
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout card() {
        LinearLayout c = column();
        c.setBackground(bg(C_CARD, 16));
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        return c;
    }

    private TextView chipValue(String s) {
        TextView t = text(s, 12.5f, C_TITLE, true);
        t.setPadding(0, dp(4), 0, 0);
        return t;
    }

    private LinearLayout chip(String label, TextView value) {
        LinearLayout box = column();
        box.setBackground(bg(C_CHIP, 10));
        box.setPadding(dp(11), dp(8), dp(11), dp(9));
        box.addView(text(label, 10f, C_SUB, false));
        box.addView(value);
        return box;
    }

    private Button action(String label, int bgColor, int textColor, boolean bold, final Runnable r) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f * S);
        b.setTextColor(textColor);
        if (bold) b.setTypeface(b.getTypeface(), Typeface.BOLD);
        b.setBackground(bg(bgColor, 10));
        b.setPadding(dp(4), 0, dp(4), 0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                r.run();
            }
        });
        b.setLayoutParams(new LinearLayout.LayoutParams(0, dp(38), 1f));
        return b;
    }

    private Button outline(String label, int color, final Runnable r) {
        Button b = action(label, C_CHIP, color, false, r);
        b.setBackground(bgStroke(C_CHIP, 10, C_LINE));
        return b;
    }

    private TextView pill(TextView out, String s) {
        out.setText(s);
        out.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f * S);
        out.setTextColor(C_SUB);
        out.setGravity(Gravity.CENTER);
        out.setPadding(dp(11), dp(6), dp(11), dp(6));
        out.setBackground(bgStroke(C_BG2, 20, C_LINE));
        return out;
    }

    // ------------------------------------------------------------------ 界面

    private View buildUi() {
        FrameLayout rootFrame = new FrameLayout(this);
        rootFrame.setBackgroundColor(C_BG);

        LinearLayout root = column();
        root.setPadding(dp(20), dp(14), dp(20), dp(12));

        root.addView(header());
        root.addView(gap(12));

        // 两栏：左 = 音乐接入（主功能），右 = 全屏 + 上传
        LinearLayout cols = row();
        cols.setGravity(Gravity.TOP);
        root.addView(cols, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout left = column();
        left.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1.12f));
        left.addView(mediaCard(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));

        LinearLayout right = column();
        right.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 0.88f));
        right.addView(fullscreenCard(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.62f));
        right.addView(gap(12));
        right.addView(uploadCard(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.38f));

        cols.addView(left);
        cols.addView(hgap(14));
        cols.addView(right);

        root.addView(footer());

        rootFrame.addView(root, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0xB8000000);
        overlay.setVisibility(View.GONE);
        overlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismissOverlay();
            }
        });
        rootFrame.addView(overlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        return rootFrame;
    }

    /** 弹出一个居中浮层（panel 自己负责背景和圆角）。 */
    private void showOverlay(View panel, int widthDp, int heightDp) {
        if (overlay == null) return;
        overlay.removeAllViews();
        panel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 吞掉点击，避免穿透到遮罩
            }
        });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                dp(widthDp), dp(heightDp), Gravity.CENTER);
        overlay.addView(panel, lp);
        overlay.setVisibility(View.VISIBLE);
        overlay.setAlpha(0f);
        overlay.animate().alpha(1f).setDuration(140L).start();
        panel.setScaleX(0.96f);
        panel.setScaleY(0.96f);
        panel.animate().scaleX(1f).scaleY(1f).setDuration(160L).start();
    }

    private void dismissOverlay() {
        if (overlay == null) return;
        overlay.setVisibility(View.GONE);
        overlay.removeAllViews();
    }

    @Override
    public void onBackPressed() {
        if (overlay != null && overlay.getVisibility() == View.VISIBLE) {
            dismissOverlay();
            return;
        }
        super.onBackPressed();
    }

    private View header() {
        LinearLayout h = row();
        h.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(50)));

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_app);
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        icon.setBackground(bg(C_CARD, 12));
        icon.setClipToOutline(true);
        icon.setLayoutParams(new LinearLayout.LayoutParams(dp(42), dp(42)));
        h.addView(icon);
        h.addView(hgap(12));

        LinearLayout titleCol = column();
        titleCol.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        titleCol.addView(text("飞谧车机助手", 19f, C_TITLE, true));
        TextView s = text("音乐接入 · 万物全屏 · 上传安装", 11f, C_SUB, false);
        s.setPadding(0, dp(3), 0, 0);
        titleCol.addView(s);
        h.addView(titleCol);

        pillMedia = new TextView(this);
        pill(pillMedia, "媒体接入：—");
        h.addView(pillMedia);
        h.addView(hgap(8));
        pillBall = new TextView(this);
        pill(pillBall, "悬浮球：—");
        h.addView(pillBall);
        return h;
    }

    // ---------------------------------------------------------------- 音乐接入卡片

    private View mediaCard() {
        LinearLayout c = card();

        LinearLayout head = row();
        LinearLayout titleCol = column();
        titleCol.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout nameRow = row();
        View dot = new View(this);
        dot.setBackground(bg(C_ACCENT, 3));
        dot.setLayoutParams(new LinearLayout.LayoutParams(dp(6), dp(6)));
        nameRow.addView(dot);
        nameRow.addView(hgap(8));
        nameRow.addView(text("音乐接入媒体中心", 15.5f, C_TITLE, true));
        titleCol.addView(nameRow);

        TextView sub = text("把音乐 App 当作车机原生音源：卡片显示歌名 / 封面 / 歌词，方向盘中间键暂停续播，"
                + "退出 App 后点播放会自动拉起。", 10.5f, C_SUB, false);
        sub.setPadding(0, dp(6), dp(12), 0);
        sub.setLineSpacing(dp(2), 1f);
        titleCol.addView(sub);

        // 适配范围说清楚：只有落雪做过适配和实测，别的 App 不保证
        TextView fit = text("已适配：落雪音乐（LX Music）。其他音乐 App 未验证，接入后歌名 / 歌词 / 按键可能不准。",
                9.5f, 0xFFD8A657, false);
        fit.setPadding(0, dp(5), dp(12), 0);
        fit.setLineSpacing(dp(2), 1f);
        titleCol.addView(fit);
        head.addView(titleCol);

        toggle = new ToggleView(this, S);
        toggle.setChecked(ProxyConfig.enabled(), false);
        toggle.setOnChanged(new ToggleView.OnChanged() {
            @Override
            public void onChanged(boolean checked) {
                setMediaEnabled(checked);
            }
        });
        head.addView(toggle);
        c.addView(head);

        c.addView(divider());

        LinearLayout r1 = row();
        valService = chipValue("● 检查中");
        valRegistered = chipValue("● 检查中");
        LinearLayout c1 = chip("接入服务", valService);
        LinearLayout c2 = chip("媒体中心注册", valRegistered);
        LinearLayout.LayoutParams lpC1 = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        LinearLayout.LayoutParams lpC2 = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lpC1.rightMargin = dp(5);
        lpC2.leftMargin = dp(5);
        c1.setLayoutParams(lpC1);
        c2.setLayoutParams(lpC2);
        r1.addView(c1);
        r1.addView(c2);
        c.addView(r1);

        c.addView(gap(10));

        LinearLayout r2 = row();
        valSource = chipValue("● 检查中");
        valTarget = chipValue("落雪音乐");
        LinearLayout c3 = chip("音源条目", valSource);
        LinearLayout c4 = chip("目标 App", valTarget);
        LinearLayout.LayoutParams lpC3 = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        LinearLayout.LayoutParams lpC4 = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lpC3.rightMargin = dp(5);
        lpC4.leftMargin = dp(5);
        c3.setLayoutParams(lpC3);
        c4.setLayoutParams(lpC4);
        r2.addView(c3);
        r2.addView(c4);
        c.addView(r2);

        c.addView(gap(10));

        // 正在播放
        LinearLayout nowBox = row();
        nowBox.setBackground(bg(C_CHIP, 10));
        nowBox.setPadding(dp(12), dp(8), dp(12), dp(9));
        LinearLayout nowCol = column();
        nowCol.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        nowCol.addView(text("正在播放", 10f, C_SUB, false));
        nowTitle = text("暂无播放内容", 13f, C_TITLE, true);
        nowTitle.setPadding(0, dp(4), 0, 0);
        nowTitle.setSingleLine(true);
        nowTitle.setEllipsize(TextUtils.TruncateAt.END);
        nowCol.addView(nowTitle);
        nowBox.addView(nowCol);
        nowState = text("—", 11f, C_SUB, true);
        nowBox.addView(nowState);
        c.addView(nowBox);

        c.addView(gap(10));

        // 最近事件：直接读代理的环形日志尾部，排查时不用再点开对话框
        LinearLayout logBox = column();
        logBox.setBackground(bg(C_CHIP, 10));
        logBox.setPadding(dp(12), dp(8), dp(12), dp(10));
        LinearLayout logHead = row();
        logHead.addView(text("最近事件", 9.5f, C_SUB, false));
        logHead.addView(flex());
        logHead.addView(text("点「查看日志」看完整 80 条", 9f, C_DIM, false));
        logBox.addView(logHead);
        recentLog = text("—", 9.5f, C_DIM, false);
        recentLog.setTypeface(Typeface.MONOSPACE);
        recentLog.setLineSpacing(dp(3), 1f);
        recentLog.setPadding(0, dp(5), 0, 0);
        logBox.addView(recentLog);
        c.addView(logBox);

        c.addView(flex());
        c.addView(gap(10));

        LinearLayout btns = row();
        Button play = action("播放", C_ACCENT, 0xFF052018, true, new Runnable() {
            public void run() {
                togglePlay();
            }
        });
        playBtnLabel = play;
        btns.addView(play);
        btns.addView(hgap(8));
        btns.addView(action("打开 App", C_CHIP, C_TEXT, false, new Runnable() {
            public void run() {
                openTargetApp();
            }
        }));
        btns.addView(hgap(8));
        btns.addView(outline("切换 App", C_TEXT, new Runnable() {
            public void run() {
                pickTargetApp();
            }
        }));
        c.addView(btns);

        c.addView(gap(8));

        LinearLayout btns2 = row();
        btns2.addView(outline("重写音源条目", C_SUB, new Runnable() {
            public void run() {
                repairSource();
            }
        }));
        btns2.addView(hgap(8));
        btns2.addView(outline("重启接入", C_SUB, new Runnable() {
            public void run() {
                restartService();
            }
        }));
        btns2.addView(hgap(8));
        btns2.addView(outline("查看日志", C_SUB, new Runnable() {
            public void run() {
                showLog();
            }
        }));
        c.addView(btns2);
        return c;
    }

    // ---------------------------------------------------------------- 万物全屏卡片

    private View fullscreenCard() {
        LinearLayout c = card();

        LinearLayout head = row();
        LinearLayout titleCol = column();
        titleCol.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout nameRow = row();
        View dot = new View(this);
        dot.setBackground(bg(C_BLUE, 3));
        dot.setLayoutParams(new LinearLayout.LayoutParams(dp(6), dp(6)));
        nameRow.addView(dot);
        nameRow.addView(hgap(8));
        nameRow.addView(text("万物全屏", 15.5f, C_TITLE, true));
        titleCol.addView(nameRow);

        TextView sub = text("在任何 App 里点屏幕边缘的圆球，把那个 App 搬到全屏区；再点一次还原。", 10.5f, C_SUB, false);
        sub.setPadding(0, dp(6), dp(12), 0);
        sub.setLineSpacing(dp(2), 1f);
        titleCol.addView(sub);
        head.addView(titleCol);

        ballToggle = new ToggleView(this, S);
        ballToggle.setChecked(ProxyConfig.ballEnabled(), false);
        ballToggle.setOnChanged(new ToggleView.OnChanged() {
            @Override
            public void onChanged(boolean checked) {
                setBallEnabled(checked);
            }
        });
        head.addView(ballToggle);
        c.addView(head);

        c.addView(divider());

        fullStatus = text("检查中…", 11f, C_TEXT, false);
        fullStatus.setLineSpacing(dp(4), 1f);
        c.addView(fullStatus);

        c.addView(gap(12));

        LinearLayout tip = column();
        tip.setBackground(bg(C_CHIP, 10));
        tip.setPadding(dp(12), dp(9), dp(12), dp(10));
        TextView tipText = text("悬浮球会一直浮在屏幕上，默认关闭；开关状态会记住，重启车机后按这个偏好恢复。\n"
                + "桌面 / 系统界面不能搬；不受支持的应用搬过去后会在 800ms 内自动回滚。", 9.5f, C_DIM, false);
        tipText.setLineSpacing(dp(3), 1f);
        tip.addView(tipText);
        c.addView(tip);

        c.addView(flex());
        c.addView(gap(8));

        LinearLayout b1 = row();
        b1.addView(action("授权悬浮窗", C_BLUE, 0xFFFFFFFF, true, new Runnable() {
            public void run() {
                askOverlay();
            }
        }));
        b1.addView(hgap(8));
        b1.addView(outline("退出全屏 / 还原", C_TEXT, new Runnable() {
            public void run() {
                exitFullscreen();
            }
        }));
        c.addView(b1);
        return c;
    }

    // ---------------------------------------------------------------- 上传安装卡片

    private View uploadCard() {
        LinearLayout c = card();
        LinearLayout head = row();
        head.addView(text("手机上传安装", 15.5f, C_TITLE, true));
        head.addView(flex());
        head.addView(text("实验", 10.5f, C_AMBER, false));
        c.addView(head);

        TextView sub = text("手机连同一热点 → 浏览器打开网址 → 上传 APK → 车机走系统安装流程。", 10.5f, C_SUB, false);
        sub.setPadding(0, dp(6), 0, dp(8));
        sub.setLineSpacing(dp(2), 1f);
        c.addView(sub);

        upUrl = text("（点「启动上传」生成网址）", 12f, C_ACCENT, false);
        upUrl.setTextIsSelectable(true);
        upUrl.setBackground(bg(C_CHIP, 8));
        upUrl.setPadding(dp(10), dp(8), dp(10), dp(8));
        upUrl.setSingleLine(true);
        upUrl.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        c.addView(upUrl);

        upStatus = text("未启动。", 10f, C_DIM, false);
        upStatus.setPadding(0, dp(6), 0, 0);
        c.addView(upStatus);

        c.addView(flex());
        c.addView(gap(10));
        LinearLayout b = row();
        b.addView(action("启动上传", C_CHIP, C_TEXT, false, new Runnable() {
            public void run() {
                startUpload();
            }
        }));
        b.addView(hgap(8));
        b.addView(outline("停止", C_SUB, new Runnable() {
            public void run() {
                stopUpload();
            }
        }));
        c.addView(b);
        return c;
    }

    private View footer() {
        LinearLayout f = row();
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(30));
        lp.topMargin = dp(8);
        f.setLayoutParams(lp);
        msg = text("就绪。", 10.5f, C_DIM, false);
        msg.setSingleLine(true);
        msg.setEllipsize(TextUtils.TruncateAt.END);
        msg.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        f.addView(msg);

        Button more = action("日志", C_CHIP, C_SUB, false, new Runnable() {
            public void run() {
                showLog();
            }
        });
        more.setLayoutParams(new LinearLayout.LayoutParams(dp(72), dp(28)));
        more.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f * S);
        f.addView(more);
        return f;
    }

    // ------------------------------------------------------------------ 媒体接入动作

    private void setMediaEnabled(boolean on) {
        ProxyConfig.setEnabled(on);
        if (on) {
            ProxyService.start(this);
            say("已开启音乐接入：正在向媒体中心注册音源…");
        } else {
            try {
                stopService(new Intent(this, ProxyService.class));
            } catch (Throwable ignored) {
            }
            say("已关闭音乐接入：媒体中心不再显示该音源（重启后也保持关闭）。");
        }
        ui.postDelayed(new Runnable() {
            public void run() {
                refresh();
            }
        }, 800);
    }

    private void togglePlay() {
        boolean playing = SessionWatcher.isPlaying();
        if (!ProxyService.running) {
            ProxyService.start(this);
            ui.postDelayed(new Runnable() {
                public void run() {
                    ControlForwarder.action(SessionWatcher.isPlaying() ? "pause" : "play");
                }
            }, 900);
        } else {
            ControlForwarder.action(playing ? "pause" : "play");
        }
        say(playing ? "已下发暂停。" : "已下发播放（App 已退出会自动拉起）。");
        ui.postDelayed(new Runnable() {
            public void run() {
                refresh();
            }
        }, 1300);
    }

    private void openTargetApp() {
        try {
            String pkg = ProxyConfig.targetPkg();
            Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
            if (i == null) {
                say("找不到 " + pkg + " 的启动入口。");
                return;
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            say("打开失败：" + t);
        }
    }

    private void restartService() {
        ProxyConfig.log("[ui] 手动重启接入服务");
        try {
            stopService(new Intent(this, ProxyService.class));
        } catch (Throwable ignored) {
        }
        ui.postDelayed(new Runnable() {
            public void run() {
                ProxyService.start(MainActivity.this);
                say("接入服务已重启，正在重新注册…");
                ui.postDelayed(new Runnable() {
                    public void run() {
                        refresh();
                    }
                }, 1500);
            }
        }, 500);
    }

    private void repairSource() {
        ProxyConfig.log("[ui] 手动重写音源条目");
        CarMusicSource.reinstall();
        say("正在重写音源条目…（媒体中心列表几秒后刷新）");
        ui.postDelayed(new Runnable() {
            public void run() {
                refresh();
            }
        }, 2500);
    }

    /** 目标 App 选择器。切换目标后接入服务要重新注册，这里自动重启并刷新。 */
    private void pickTargetApp() {
        final PackageManager pm = getPackageManager();
        final String cur = ProxyConfig.targetPkg();

        // 先只找"音频类"应用：车机上声明 CATEGORY_APP_MUSIC 的才是真音源；
        // 全量 LAUNCHER 列表会把休憩空间/洗车模式这类场景应用混进来。
        Intent music = new Intent(Intent.ACTION_MAIN)
                .addCategory("android.intent.category.APP_MUSIC");
        List<ResolveInfo> ris = pm.queryIntentActivities(music, 0);
        if (ris == null || ris.size() == 0) {
            Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            ris = pm.queryIntentActivities(main, 0);
        }

        final ArrayList<String[]> items = new ArrayList<String[]>();
        for (int i = 0; i < ris.size(); i++) {
            ResolveInfo ri = ris.get(i);
            if (ri == null || ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg == null || pkg.equals(getPackageName())) continue;
            boolean dup = false;
            for (int j = 0; j < items.size(); j++) {
                if (pkg.equals(items.get(j)[1])) {
                    dup = true;
                    break;
                }
            }
            if (dup) continue;
            CharSequence label = ri.loadLabel(pm);
            items.add(new String[]{String.valueOf(label), pkg});
        }

        // 当前目标即使不在列表里也要能看见（否则用户不知道自己接的是谁）
        boolean hasCur = false;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i)[1].equals(cur)) hasCur = true;
        }
        if (!hasCur) {
            items.add(0, new String[]{ProxyConfig.targetLabel(), cur});
        }

        final ArrayList<String[]> sorted = new ArrayList<String[]>();
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i)[1].equals(cur)) sorted.add(items.get(i));
        }
        for (int i = 0; i < items.size(); i++) {
            if (!items.get(i)[1].equals(cur)) sorted.add(items.get(i));
        }
        items.clear();
        items.addAll(sorted);

        if (items.isEmpty()) {
            say("没有找到可启动的应用。");
            return;
        }

        LinearLayout box = column();
        box.setBackground(bg(C_CARD, 18));
        box.setPadding(dp(18), dp(16), dp(18), dp(14));
        box.addView(text("选择要接入的音乐 App", 15.5f, C_TITLE, true));
        TextView tip = text("仅落雪音乐（LX Music）做过适配与实测，其他 App 不保证歌名 / 歌词 / 按键正确。"
                + "切换后会自动重新注册（约 2 秒）。", 10.5f, C_SUB, false);
        tip.setPadding(0, dp(6), 0, dp(10));
        tip.setLineSpacing(dp(2), 1f);
        box.addView(tip);

        ScrollView sc = new ScrollView(this);
        sc.setVerticalScrollBarEnabled(false);
        LinearLayout list = column();
        for (int i = 0; i < items.size(); i++) {
            final String[] it = items.get(i);
            boolean isCur = it[1].equals(cur);

            LinearLayout itRow = row();
            LinearLayout.LayoutParams lpIt = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
            if (i > 0) lpIt.topMargin = dp(6);
            itRow.setLayoutParams(lpIt);
            itRow.setBackground(bgStroke(C_CHIP, 10, isCur ? C_ACCENT : C_LINE));
            itRow.setPadding(dp(12), 0, dp(12), 0);

            LinearLayout col = column();
            col.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            col.addView(text(it[0], 13f, C_TITLE, isCur));
            col.addView(text(it[1], 9.5f, C_DIM, false));
            itRow.addView(col);
            boolean adapted = ProxyConfig.isAdapted(it[1]);
            if (isCur) {
                itRow.addView(text("当前", 10.5f, C_ACCENT, true));
                itRow.addView(hgap(10));
            }
            itRow.addView(text(adapted ? "已适配" : "未验证", 10f,
                    adapted ? C_ACCENT : 0xFFD8A657, false));

            itRow.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    dismissOverlay();
                    applyTarget(it[1], it[0]);
                }
            });
            list.addView(itRow);
        }
        sc.addView(list);
        box.addView(sc, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        box.addView(gap(10));
        Button cancel = action("取消", C_CHIP, C_SUB, false, new Runnable() {
            public void run() {
                dismissOverlay();
            }
        });
        cancel.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(36)));
        box.addView(cancel);

        // 高度跟着列表条目数走，避免只有 1 个应用时留一大片空
        int rows = Math.min(items.size(), 8);
        int hDp = 112 + rows * 50 + 56;
        if (hDp > 620) hDp = 620;
        showOverlay(box, 760, hDp);
    }

    private void applyTarget(String pkg, String label) {
        ProxyConfig.setTargetPkg(pkg);
        ProxyConfig.setTargetLabel(label);
        ProxyConfig.log("[ui] 切换目标 App: " + pkg + " / " + label);
        String warn = ProxyConfig.isAdapted(pkg) ? ""
                : "。注意：该 App 未验证适配，歌名 / 歌词 / 按键可能不准";

        if (!ProxyService.running) {
            say("已切换目标：" + label + warn);
            ui.postDelayed(new Runnable() {
                public void run() {
                    refresh();
                }
            }, 600);
            return;
        }

        // 注册身份必须换成新包名，否则媒体中心对不上（会显示"当前暂无播放内容"）
        say("已切换目标：" + label + "，正在重新注册…" + warn);
        try {
            MediaCenterBridge.retarget();
            CarMusicSource.reinstall();
            SessionWatcher.rescan();
        } catch (Throwable t) {
            ProxyConfig.log("[ui] retarget 失败: " + t);
        }
        ui.postDelayed(new Runnable() {
            public void run() {
                refresh();
            }
        }, 2000);
    }

    private void showLog() {
        LinearLayout box = column();
        box.setBackground(bg(C_CARD, 18));
        box.setPadding(dp(18), dp(16), dp(18), dp(14));

        LinearLayout head = row();
        head.addView(text("接入日志", 15.5f, C_TITLE, true));
        head.addView(flex());
        head.addView(text("最近 80 条", 10.5f, C_SUB, false));
        box.addView(head);

        ScrollView sc = new ScrollView(this);
        sc.setVerticalScrollBarEnabled(true);
        TextView tv = text(prettyLog(), 10f, C_TEXT, false);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setLineSpacing(dp(3), 1f);
        tv.setTextIsSelectable(true);
        tv.setPadding(dp(12), dp(10), dp(12), dp(10));
        tv.setBackground(bg(C_CHIP, 10));
        sc.addView(tv);
        LinearLayout.LayoutParams lpSc = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        lpSc.topMargin = dp(10);
        box.addView(sc, lpSc);

        box.addView(gap(10));
        Button close = action("关闭", C_CHIP, C_TEXT, false, new Runnable() {
            public void run() {
                dismissOverlay();
            }
        });
        close.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(36)));
        box.addView(close);

        showOverlay(box, 900, 620);
    }

    // ------------------------------------------------------------------ 全屏动作

    private boolean canOverlay() {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this);
    }

    private void askOverlay() {
        say("正在打开悬浮窗权限设置…");
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Throwable t) {
            say("无法打开设置页：" + t.getMessage());
        }
    }

    /** 悬浮球开关：写偏好 + 起停服务。关掉会先还原分屏。 */
    private void setBallEnabled(boolean on) {
        if (on && !canOverlay()) {
            ProxyConfig.setBallEnabled(false);
            if (ballToggle != null) ballToggle.setChecked(false, false);
            say("需要先授予「显示在其他应用上层」权限，再来打开悬浮球。");
            askOverlay();
            return;
        }
        ProxyConfig.setBallEnabled(on);
        if (on) {
            startService(new Intent(this, FullscreenService.class));
            say("悬浮球已开启：切到任意 App，点屏幕边缘的圆球即可全屏 / 还原。");
        } else {
            startService(new Intent(this, FullscreenService.class)
                    .setAction(FullscreenService.ACTION_EXIT));
            stopService(new Intent(this, FullscreenService.class));
            say("悬浮球已关闭（已还原分屏），重启车机后也不会自启。");
        }
        ui.postDelayed(new Runnable() {
            public void run() {
                refresh();
            }
        }, 800);
    }

    private void exitFullscreen() {
        startService(new Intent(this, FullscreenService.class)
                .setAction(FullscreenService.ACTION_EXIT));
        say("已下发还原。");
        ui.postDelayed(new Runnable() {
            public void run() {
                refresh();
            }
        }, 900);
    }

    // ------------------------------------------------------------------ 上传安装

    private String localIp() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> es = java.net.NetworkInterface.getNetworkInterfaces();
            String best = null;
            while (es != null && es.hasMoreElements()) {
                java.net.NetworkInterface ni = es.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (java.net.InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    java.net.InetAddress a = ia.getAddress();
                    if (a instanceof java.net.Inet4Address) {
                        String ip = a.getHostAddress();
                        if (ip.startsWith("172.") || ip.startsWith("192.168.")) return ip;
                        if (best == null && !ip.startsWith("127.")) best = ip;
                    }
                }
            }
            return best == null ? "127.0.0.1" : best;
        } catch (Throwable t) {
            return "127.0.0.1";
        }
    }

    private String uploadUrlText() {
        return "http://" + localIp() + ":" + (uploadServer == null ? 8766 : uploadServer.port())
                + "/?token=" + uploadToken;
    }

    private void startUpload() {
        if (uploadServer != null && uploadServer.isRunning()) {
            say("上传服务已在运行：" + uploadUrlText());
            return;
        }
        uploadToken = randomToken();
        for (int port : new int[]{8766, 8767, 8768, 8769}) {
            try {
                uploadServer = new UploadServer(port, uploadToken, "飞谧车机助手 · 上传安装",
                        new UploadServer.Handler() {
                            public String onUpload(java.io.InputStream body, long len,
                                                   String name, boolean install) throws Exception {
                                return handleUpload(body, len, name, install);
                            }
                        });
                uploadServer.start();
                upUrl.setText(uploadUrlText());
                upStatus.setText("已启动，端口 " + port + "。");
                say("上传服务已启动：" + uploadUrlText());
                return;
            } catch (Throwable t) {
                U.log("端口 " + port + " 启动失败: " + t);
            }
        }
        upStatus.setText("启动失败：8766~8769 都被占用");
        say("上传服务启动失败：端口被占用");
    }

    private void stopUpload() {
        if (uploadServer != null) uploadServer.stop();
        upStatus.setText("已停止。");
        say("上传服务已停止");
    }

    private static String randomToken() {
        String chars = "abcdefghjkmnpqrstuvwxyz23456789";
        StringBuilder sb = new StringBuilder();
        java.util.Random r = new java.util.Random();
        for (int i = 0; i < 4; i++) sb.append(chars.charAt(r.nextInt(chars.length())));
        return sb.toString();
    }

    /** 收到手机上传：落盘 → 可选走 PackageInstaller 安装。 */
    private String handleUpload(java.io.InputStream body, long len, String name, boolean install) throws Exception {
        java.io.File dir = new java.io.File(getCacheDir(), "uploads");
        dir.mkdirs();
        String safe = name.replaceAll("[^A-Za-z0-9._-]", "_");
        final java.io.File f = new java.io.File(dir, System.currentTimeMillis() + "-" + safe);
        java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
        long total = 0;
        try {
            byte[] buf = new byte[65536];
            int n;
            while ((n = body.read(buf)) > 0) {
                fos.write(buf, 0, n);
                total += n;
                if (len > 0 && total >= len) break;
            }
        } finally {
            try {
                fos.close();
            } catch (Exception ignored) {
            }
        }
        U.log("收到上传 " + name + " " + total + " 字节 → " + f);
        if (total <= 0) {
            upStatus.setText("收到空文件");
            return "没收到数据（0 字节）";
        }
        final String head = name + "（" + (total / 1048576) + " MB）";
        ui.post(new Runnable() {
            public void run() {
                upStatus.setText("已收到 " + head + "，正在提交安装…");
            }
        });
        if (!install) {
            upStatus.setText("已保存 " + head);
            return "已保存到车机：" + f.getAbsolutePath() + "\n大小 " + total + " 字节（未安装）";
        }
        String r = ApkInstaller.install(this, f);
        upStatus.setText(head + " → " + r);
        return r + "\n文件：" + f.getAbsolutePath() + "（" + total + " 字节）";
    }

    // ------------------------------------------------------------------ 状态刷新

    private void refresh() {
        new Thread(new Runnable() {
            public void run() {
                final boolean svcOk = Geely.svcBinder() != null;
                final String csd = Geely.topPkg(Geely.AREA_CSD);
                final String fullTop = Geely.topPkg(Geely.AREA_FULL);
                final boolean overlay = canOverlay();
                final boolean running = ProxyService.running;
                final boolean ready = MediaCenterBridge.isReady();
                final boolean injected = CarMusicSource.injectedOk;
                final boolean session = SessionWatcher.sessionAlive();
                final boolean playing = SessionWatcher.isPlaying();
                final String title = NowPlaying.title == null ? "" : NowPlaying.title;
                final String artist = NowPlaying.artist == null ? "" : NowPlaying.artist;
                final boolean enabled = ProxyConfig.enabled();
                final String targetLabel = ProxyConfig.targetLabel();
                final String tail = tailLog(6);

                runOnUiThread(new Runnable() {
                    public void run() {
                        if (isFinishing()) return;
                        if (!running) {
                            // 服务停了，静态标记还会残留 true，这里按"未运行"显示，避免误导
                            setChip(valService, "● 已停止", C_RED);
                            setChip(valRegistered, "● 未运行", C_DIM);
                            setChip(valSource, "● 已保留", C_DIM);
                        } else {
                            setChip(valService, "● 运行中", C_ACCENT);
                            setChip(valRegistered, ready ? "● 已注册" : "● 注册中…",
                                    ready ? C_ACCENT : C_AMBER);
                            setChip(valSource, injected ? "● 已写入" : "● 待写入",
                                    injected ? C_ACCENT : C_AMBER);
                        }
                        valTarget.setText(targetLabel);
                        valTarget.setTextColor(C_TITLE);

                        if (title.length() == 0) {
                            nowTitle.setText(session ? "（已连接，等待歌曲信息）" : "暂无播放内容");
                            nowState.setText(session ? "—" : "未连接");
                            nowState.setTextColor(C_DIM);
                        } else {
                            nowTitle.setText(artist.length() == 0 ? title : title + " · " + artist);
                            nowState.setText(playing ? "播放中" : "已暂停");
                            nowState.setTextColor(playing ? C_ACCENT : C_AMBER);
                        }
                        if (playBtnLabel != null) playBtnLabel.setText(playing ? "暂停" : "播放");
                        if (recentLog != null) recentLog.setText(tail);

                        pillMedia.setText(enabled
                                ? (running ? "媒体接入 · 已开启" : "媒体接入 · 启动中")
                                : "媒体接入 · 已关闭");
                        pillMedia.setTextColor(enabled ? C_ACCENT : C_SUB);
                        boolean ballOn = ProxyConfig.ballEnabled();
                        pillBall.setText(!overlay ? "悬浮球 · 待授权"
                                : (ballOn ? "悬浮球 · 已开启" : "悬浮球 · 已关闭"));
                        pillBall.setTextColor(ballOn && overlay ? C_ACCENT : (overlay ? C_SUB : C_AMBER));

                        StringBuilder sb = new StringBuilder();
                        sb.append("悬浮窗权限：").append(overlay ? "已授予" : "未授予");
                        sb.append("　·　服务：").append(svcOk ? "正常" : "不可用");
                        sb.append("\n全屏状态：").append(FullscreenService.active
                                ? "全屏中 · " + FullscreenService.lastLabel : "未全屏");
                        sb.append("\n中控顶层：").append(nz(csd));
                        sb.append("\n全屏区顶层：").append(nz(fullTop));
                        fullStatus.setText(sb.toString());

                        if (!enabled && toggle.isChecked()) toggle.setChecked(false, false);
                        if (enabled && !toggle.isChecked()) toggle.setChecked(true, false);
                        if (ballToggle != null && ballToggle.isChecked() != ballOn) {
                            ballToggle.setChecked(ballOn, false);
                        }
                    }
                });
            }
        }, "ui-refresh").start();
    }

    private void setChip(TextView v, String s, int color) {
        if (v == null) return;
        v.setText(s);
        v.setTextColor(color);
    }

    private static String nz(String s) {
        return (s == null || s.length() == 0) ? "—" : s;
    }

    /** 完整日志：把时间戳（epoch 毫秒）换成 HH:mm:ss，长行也保留。 */
    private static String prettyLog() {
        try {
            String all = ProxyConfig.dumpLog();
            if (all == null || all.length() == 0) return "（暂无日志）";
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("HH:mm:ss",
                    java.util.Locale.CHINA);
            StringBuilder sb = new StringBuilder();
            String[] lines = all.split("\n");
            for (int i = 0; i < lines.length; i++) {
                String s = lines[i];
                int sp = s.indexOf("  ");
                if (sp > 0 && sp <= 15) {
                    try {
                        long ts = Long.parseLong(s.substring(0, sp).trim());
                        s = f.format(new java.util.Date(ts)) + s.substring(sp);
                    } catch (Throwable ignored) {
                    }
                }
                if (sb.length() > 0) sb.append('\n');
                sb.append(s);
            }
            return sb.toString();
        } catch (Throwable t) {
            return "（读日志失败：" + t + "）";
        }
    }

    /** 取环形日志最后 n 条（去掉时间戳前缀、过滤 base64/hex 噪声），压缩成几行给卡片做摘要。 */
    private static String tailLog(int n) {
        try {
            String all = ProxyConfig.dumpLog();
            if (all == null || all.length() == 0) return "—";
            String[] lines = all.split("\n");
            ArrayList<String> keep = new ArrayList<String>();
            for (int i = 0; i < lines.length; i++) {
                String s = lines[i];
                int sp = s.indexOf("  ");
                if (sp > 0 && sp < 20) s = s.substring(sp + 2);
                // 配置 dump 的 base64 分片、probe 的十六进制串/探测细节对用户没意义，直接滤掉
                if (s.indexOf("[cfg]") >= 0 || s.indexOf("hex=") >= 0 || s.indexOf("[probe]") >= 0) {
                    continue;
                }
                if (s.length() > 90) s = s.substring(0, 90) + "…";
                keep.add(s);
            }
            StringBuilder sb = new StringBuilder();
            int from = Math.max(0, keep.size() - n);
            for (int i = from; i < keep.size(); i++) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(keep.get(i));
            }
            return sb.length() == 0 ? "—" : sb.toString();
        } catch (Throwable t) {
            return "—";
        }
    }

    private void say(final String s) {
        ui.post(new Runnable() {
            public void run() {
                if (msg != null) msg.setText(s);
            }
        });
    }

    private void toast(String s) {
        try {
            Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }

    private final Runnable poll = new Runnable() {
        public void run() {
            if (!resumed) return;
            refresh();
            ui.postDelayed(this, 1500L);
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        refresh();
        ui.removeCallbacks(poll);
        ui.postDelayed(poll, 1500L);
    }

    @Override
    protected void onPause() {
        resumed = false;
        ui.removeCallbacks(poll);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (uploadServer != null) uploadServer.stop();
        super.onDestroy();
    }
}

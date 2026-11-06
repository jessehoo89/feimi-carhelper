package com.feimi.car;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * 自绘开关（车机工程没有 AndroidX / Material，系统 Switch 在深色卡片上很难看）。
 * 圆角轨道 + 白点，带 160ms 过渡。
 */
public class ToggleView extends View {

    public interface OnChanged {
        void onChanged(boolean checked);
    }

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF trackRect = new RectF();

    private boolean checked;
    private float anim = 0f;          // 0 = off, 1 = on
    private ValueAnimator running;
    private OnChanged listener;
    private float scale = 1f;

    private int colorOn = 0xFF34D399;
    private int colorOff = 0xFF2A323C;
    private int colorThumb = 0xFFFFFFFF;

    public ToggleView(Context c) {
        this(c, 1f);
    }

    /** scale：画布放大倍数（车机 2560×1600 画布传 2）。 */
    public ToggleView(Context c, float scale) {
        super(c);
        this.scale = scale;
        setClickable(true);
        setFocusable(true);
        thumbPaint.setColor(colorThumb);
        thumbPaint.setShadowLayer(2f, 0f, 1f, 0x40000000);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    public void setOnChanged(OnChanged l) {
        listener = l;
    }

    public boolean isChecked() {
        return checked;
    }

    /** 设置状态；notify=false 时不触发回调（用于初始化/外部同步）。 */
    public void setChecked(boolean v, boolean notify) {
        if (checked == v && anim == (v ? 1f : 0f)) return;
        checked = v;
        animateTo(v ? 1f : 0f);
        if (notify && listener != null) listener.onChanged(v);
    }

    private void animateTo(float target) {
        if (running != null) running.cancel();
        running = ValueAnimator.ofFloat(anim, target);
        running.setDuration(160L);
        running.setInterpolator(new DecelerateInterpolator());
        running.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                anim = (Float) a.getAnimatedValue();
                invalidate();
            }
        });
        running.start();
    }

    @Override
    protected void onMeasure(int w, int h) {
        int width = (int) (getResources().getDisplayMetrics().density * 46 * scale + 0.5f);
        int height = (int) (getResources().getDisplayMetrics().density * 26 * scale + 0.5f);
        setMeasuredDimension(resolveSize(width, w), resolveSize(height, h));
    }

    @Override
    protected void onDraw(Canvas cv) {
        float w = getWidth(), h = getHeight();
        float r = h / 2f;
        int on = colorOn, off = colorOff;
        int color = blend(off, on, anim);
        trackPaint.setColor(color);
        trackRect.set(0, 0, w, h);
        cv.drawRoundRect(trackRect, r, r, trackPaint);

        float pad = h * 0.11f;
        float d = h - pad * 2;
        float cx = pad + d / 2f + (w - pad * 2 - d) * anim;
        cv.drawCircle(cx, h / 2f, d / 2f, thumbPaint);
    }

    private static int blend(int a, int b, float t) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int r = (int) (ar + (br - ar) * t);
        int g = (int) (ag + (bg - ag) * t);
        int bl = (int) (ab + (bb - ab) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    @Override
    public boolean performClick() {
        boolean v = !checked;
        setChecked(v, true);
        return super.performClick();
    }
}

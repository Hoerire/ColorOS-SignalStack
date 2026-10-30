package io.github.hoerire.stacksig;

import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/**
 * ColorOS 17 的 WiFi 图标（素材取自 C17 SystemUI 的 ic_wifi_0..3）。
 * 等级 0/1/2/3 由外部按 RSSI 给出；颜色照旧转发系统 tint。
 */
public final class Wifi17 extends Drawable {

    private final Drawable fallback;
    private final int intrinsicW;
    private final int intrinsicH;
    private ColorStateList tint;
    private ColorFilter filter;
    private int alpha = 255;
    private int index = 3;

    public Wifi17(Drawable from) {
        fallback = from;
        intrinsicW = from != null && from.getIntrinsicWidth() > 0 ? from.getIntrinsicWidth() : 48;
        intrinsicH = from != null && from.getIntrinsicHeight() > 0 ? from.getIntrinsicHeight() : 48;
    }

    /** i < 0 表示没连上（或 WiFi 关着），这时退回系统原图 */
    public void setIndex(int i) {
        int v = i < 0 ? -1 : Math.max(0, Math.min(3, i));
        if (v != index) {
            index = v;
            invalidateSelf();
        }
    }

    @Override
    public void setTintList(ColorStateList t) {
        tint = t;
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        filter = cf;
        invalidateSelf();
    }

    @Override
    public void setAlpha(int a) {
        alpha = a;
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    public void draw(Canvas c) {
        try {
            Drawable d = index < 0 ? fallback : Res17.get("ic_s17_wifi_" + index);
            if (d == null) return;
            Rect b = getBounds();
            if (b.width() <= 0 || b.height() <= 0) return;
            // 绘制尺寸沿用原图标的 intrinsic，别按 View 高度放大会显胖
            int w = Math.min(b.width(), intrinsicW);
            int h = intrinsicW > 0 ? Math.round(w * (float) intrinsicH / intrinsicW) : b.height();
            if (h > b.height()) {
                h = b.height();
                w = intrinsicH > 0 ? Math.round(h * (float) intrinsicW / intrinsicH) : b.width();
            }
            int left = b.left + (b.width() - w) / 2;
            int top = b.top + (b.height() - h) / 2;
            d.setBounds(left, top, left + w, top + h);
            if (tint != null) d.setTintList(tint);
            if (filter != null) d.setColorFilter(filter);
            d.setAlpha(alpha);
            d.draw(c);
        } catch (Throwable ignore) {}
    }

    @Override public int getIntrinsicWidth() { return intrinsicW; }
    @Override public int getIntrinsicHeight() { return intrinsicH; }
}

package io.github.hoerire.stacksig;

import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/**
 * C17 双排信号（主卡 + 副卡）。
 *
 * 颜色：完全交给系统。状态栏图标是 ImageView，系统换色时调用
 * setImageTintList / setColorFilter，这两个调用会经由下面三个转发方法
 * 落到 primary / secondary 两张图上。本类不自行决定颜色。
 *
 * 唯一例外：如果宿主 ImageView 身上始终没有 tint（极少数启动器/旧版本），
 * 才用系统 onDarkChanged 给的 darkIntensity 按原厂公式算一个兜底色。
 *
 * 制式标（5G / 4G）自 v1.10 起是 mobile_combo 上的独立 TextView，
 * 不再由本类绘制 —— 这里原来那套 badge 字段/绘制路径已经删干净。
 */
public final class VStack extends Drawable {

    private final Drawable primary;
    private final Drawable secondary;
    private int alpha = 255;

    private boolean hasSystemTint = false;
    private float fallbackDark = -1f;
    private int appliedFallback = 0;

    public VStack(Drawable primary, Drawable secondary, ColorStateList tint) {
        this.primary = primary;
        this.secondary = secondary;
        if (tint != null) setTintList(tint);   // 初始色 = 系统此刻给这一格的 tint
    }

    /** 系统 onDarkChanged 的 darkIntensity：只在没有 tint 时用作兜底 */
    public void setFallbackDark(float d) {
        if (d < 0f) return;
        fallbackDark = d;
        if (!hasSystemTint) applyFallback();
    }

    private void applyFallback() {
        int v = Math.max(0, Math.min(255, Math.round(fallbackDark * 255f)));
        int c = 0xFF000000 | (v << 16) | (v << 8) | v;
        if (c == appliedFallback) return;
        appliedFallback = c;
        pushTint(ColorStateList.valueOf(c));
    }

    @Override
    public void setTintList(ColorStateList tint) {
        hasSystemTint = tint != null;
        pushTint(tint);
    }

    private void pushTint(ColorStateList tint) {
        try { primary.setTintList(tint); } catch (Throwable ignore) {}
        if (secondary != null) { try { secondary.setTintList(tint); } catch (Throwable ignore) {} }
        invalidateSelf();
    }

    @Override
    public void setTintMode(PorterDuff.Mode mode) {
        try { primary.setTintMode(mode); } catch (Throwable ignore) {}
        if (secondary != null) { try { secondary.setTintMode(mode); } catch (Throwable ignore) {} }
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        try { primary.setColorFilter(cf); } catch (Throwable ignore) {}
        if (secondary != null) { try { secondary.setColorFilter(cf); } catch (Throwable ignore) {} }
        invalidateSelf();
    }

    @Override
    public void draw(Canvas c) {
        try {
            Rect b = getBounds();
            if (b.width() <= 0 || b.height() <= 0) return;

            // 原厂做法：整张素材画布直接铺进 View，墨迹两侧的留白原样留着，
            // 间距完全交给 StatusIconContainer，四段视觉才会一致。
            //
            // 关键：宿主是 FIT_CENTER，它会按**本类的 intrinsic**再把内容缩一遍。
            // 切数据卡后主副卡素材互换、intrinsic 跟着变，视觉高度就会跳（实测 44→37）。
            // 这里让 primary/secondary 都严格铺满传入的 bounds，并把 bounds 按
            // intrinsic 的比例内切，使「缩放结果」只由 bounds 决定、与谁当主卡无关。
            Rect pb = aspectFit(b, primary);
            primary.setBounds(pb);
            primary.setAlpha(alpha);
            primary.draw(c);
            if (secondary != null) {
                secondary.setBounds(pb);
                secondary.setAlpha(alpha);
                secondary.draw(c);
            }
        } catch (Throwable ignore) {}
    }

    @Override public void setAlpha(int a) { alpha = a; }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }

    @Override public int getIntrinsicWidth() {
        int w = primary.getIntrinsicWidth();
        if (secondary != null) w = Math.max(w, secondary.getIntrinsicWidth());
        return w;
    }

    /**
     * 固有高度取 primary 的。
     *
     * 注：这里曾经想改成「两张素材取最大值」来避免切卡后高度跳动，
     * 但实测 draw() 里的 aspectFit 是按 primary 自己的宽高比铺满 bounds 的，
     * 高度一旦改成 max，铺出来的柱高反而与传入的 bounds 对不上（柱子被压矮一截），
     * 所以保持 primary —— 高度跳动的真正解法在 build() 里固定素材族（family 恒为 lte）。
     */
    @Override public int getIntrinsicHeight() { return primary.getIntrinsicHeight(); }

    /**
     * 把素材按「纵向铺满、横向保比例、水平居中」放进 b。
     *
     * 不做等比收缩 —— 状态栏信号柱必须顶满 View 高度，缩了就会矮一截。
     * 宽度跟着原图比例走，避免 5G/5GA 那种宽素材被横向压扁。
     */
    private static Rect aspectFit(Rect b, Drawable d) {
        int iw = d == null ? 0 : d.getIntrinsicWidth();
        int ih = d == null ? 0 : d.getIntrinsicHeight();
        if (iw <= 0 || ih <= 0) return b;
        int w = Math.round(b.height() * (float) iw / ih);
        if (w > b.width()) w = b.width();
        int left = b.left + (b.width() - w) / 2;
        return new Rect(left, b.top, left + w, b.bottom);
    }
}

package io.github.hoerire.stacksig;

import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/**
 * C17 双排信号 + 制式标。
 *
 * 颜色：完全交给系统。状态栏图标是 ImageView，系统换色时调用
 * setImageTintList / setColorFilter，这两个调用会经由下面三个转发方法
 * 落到 primary / secondary / badge 三张图上。本类不自行决定颜色。
 *
 * 唯一例外：如果宿主 ImageView 身上始终没有 tint（极少数启动器/旧版本），
 * 才用系统 onDarkChanged 给的 darkIntensity 按原厂公式算一个兜底色。
 */
public final class VStack extends Drawable {

    private static final float BADGE_H = 0.25f;     // 制式标高度占图标高度
    private static final float BADGE_RATIO = 1.38f; // 14.42 / 10.42
    private static final float BADGE_Y = 0.08f;
    private static final float BADGE_X = 0.02f;

    private final Drawable primary;
    private final Drawable secondary;
    private Drawable badge;
    private int alpha = 255;

    private boolean hasSystemTint = false;
    private float fallbackDark = -1f;
    private int appliedFallback = 0;

    public VStack(Drawable primary, Drawable secondary, ColorStateList tint, Drawable badge) {
        this.primary = primary;
        this.secondary = secondary;
        this.badge = badge;
        if (tint != null) setTintList(tint);   // 初始色 = 系统此刻给这一格的 tint
    }

    public void setHost(android.widget.ImageView v) { /* 保留接口 */ }

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
        if (badge != null) { try { badge.setTintList(tint); } catch (Throwable ignore) {} }
        invalidateSelf();
    }

    @Override
    public void setTintMode(PorterDuff.Mode mode) {
        try { primary.setTintMode(mode); } catch (Throwable ignore) {}
        if (secondary != null) { try { secondary.setTintMode(mode); } catch (Throwable ignore) {} }
        if (badge != null) { try { badge.setTintMode(mode); } catch (Throwable ignore) {} }
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        try { primary.setColorFilter(cf); } catch (Throwable ignore) {}
        if (secondary != null) { try { secondary.setColorFilter(cf); } catch (Throwable ignore) {} }
        if (badge != null) { try { badge.setColorFilter(cf); } catch (Throwable ignore) {} }
        invalidateSelf();
    }

    @Override
    public void draw(Canvas c) {
        try {
            Rect b = getBounds();
            if (b.width() <= 0 || b.height() <= 0) return;

            // 原厂做法：整张素材画布直接铺进 View，墨迹两侧的留白原样留着，
            // 间距完全交给 StatusIconContainer，四段视觉才会一致。
            primary.setBounds(b);
            primary.setAlpha(alpha);
            primary.draw(c);
            if (secondary != null) {
                secondary.setBounds(b);
                secondary.setAlpha(alpha);
                secondary.draw(c);
            }

            if (badge != null) {
                int bh = Math.round(b.height() * BADGE_H);
                int bw = Math.round(bh * BADGE_RATIO);
                int bl = b.left + Math.round(b.width() * BADGE_X);
                int bt = b.top + Math.round(b.height() * BADGE_Y);
                badge.setBounds(bl, bt, bl + bw, bt + bh);
                badge.setAlpha(alpha);
                badge.draw(c);
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

    @Override public int getIntrinsicHeight() { return primary.getIntrinsicHeight(); }
}

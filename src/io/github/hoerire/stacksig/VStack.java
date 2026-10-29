package io.github.hoerire.stacksig;

import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/**
 * C17 双排信号 + 大制式标。
 *
 * 排列（从左到右）：
 *      [ 5G ] [ 主卡大信号 / 副卡小信号 ]
 * 5G 占独立宽度，所以它夹在网速和信号之间；底边与信号底边对齐。
 * WiFi 连上时不画 5G（宽度自动收回）。
 */
public final class VStack extends Drawable {

    private static final float BADGE_H = 0.25f;     // 5G 高度占图标高度
    private static final float BADGE_RATIO = 1.38f; // 14.42 / 10.42
    private static final float BADGE_Y = 0.08f;
    private static final float BADGE_X = -0.01f;

    private final Drawable primary;
    private final Drawable secondary;
    private Drawable badge;
    private int alpha = 255;
    private java.lang.ref.WeakReference<android.widget.ImageView> host;
    private int lastColor;

    public VStack(Drawable primary, Drawable secondary, ColorStateList tint, Drawable badge) {
        this.primary = primary;
        this.secondary = secondary;
        this.badge = badge;
        if (tint != null) setTintList(tint);
    }

    public void setHost(android.widget.ImageView v) { host = new java.lang.ref.WeakReference<>(v); }

    private void syncTint() {
        try {
            android.widget.ImageView v = host == null ? null : host.get();
            if (v == null) return;
            // 只认状态栏时钟色（和系统图标同色）；取不到才退回 imageTintList
            int color = Engine.currentIconColor(v);
            if (color == 0) {
                ColorStateList csl = v.getImageTintList();
                if (csl == null) return;
                color = csl.getDefaultColor();
            }
            if (color != lastColor) {
                lastColor = color;
                setTintList(ColorStateList.valueOf(color));
            }
        } catch (Throwable ignore) {}
    }

    @Override
    public void setTintList(ColorStateList tint) {
        try { primary.setTintList(tint); } catch (Throwable ignore) {}
        if (secondary != null) { try { secondary.setTintList(tint); } catch (Throwable ignore) {} }
        if (badge != null) { try { badge.setTintList(tint); } catch (Throwable ignore) {} }
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
    }

    @Override
    public void draw(Canvas c) {
        try {
            syncTint();
            Rect b = getBounds();
            if (b.width() <= 0 || b.height() <= 0) return;

            // 素材画布 19 宽，但墨迹只占 x=2.0~16.44，两侧留白会变成视觉间距；
            // 这里按"墨迹"铺满 bounds，左右留白就没有了。
            final float SRC_W = 19f, X0 = 2.0f, X1 = 16.44f;
            int rw = Math.round(b.width() * SRC_W / (X1 - X0));
            int rl = b.left - Math.round(X0 / (X1 - X0) * b.width());
            Rect r = new Rect(rl, b.top, rl + rw, b.bottom);
            primary.setBounds(r);
            primary.setAlpha(alpha);
            primary.draw(c);
            if (secondary != null) {
                secondary.setBounds(r);
                secondary.setAlpha(alpha);
                secondary.draw(c);
            }

            // 制式标画在信号左上角（不占额外宽度）
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
        // 宽度也只算墨迹部分（去左右留白），否则图标之间会显得空
        return Math.round(primary.getIntrinsicWidth() * (16.44f - 2.0f) / 19f);
    }

    @Override public int getIntrinsicHeight() { return primary.getIntrinsicHeight(); }
}
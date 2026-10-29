package io.github.hoerire.stacksig;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/**
 * C17 双排造型：主卡占满，副卡缩到 62% 贴右上角。
 */
public final class StackDraw extends Drawable {

    private static final float SCALE = 0.62f;

    private final Drawable main;
    private final Drawable sub;

    public StackDraw(Drawable main, Drawable sub) {
        this.main = main;
        this.sub = sub;
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        main.setBounds(b);
        main.draw(c);

        if (b.width() <= 0 || b.height() <= 0) return;
        int w = Math.round(b.width() * SCALE);
        int h = Math.round(b.height() * SCALE);
        int l = b.right - w;
        int t = b.top;
        sub.setBounds(l, t, l + w, t + h);
        sub.draw(c);
    }

    @Override public void setAlpha(int a) { main.setAlpha(a); sub.setAlpha(a); }
    @Override public void setColorFilter(ColorFilter cf) { main.setColorFilter(cf); sub.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }

    @Override public int getIntrinsicWidth() { return Math.max(main.getIntrinsicWidth(), 0); }
    @Override public int getIntrinsicHeight() { return Math.max(main.getIntrinsicHeight(), 0); }

    @Override public boolean setVisible(boolean v, boolean r) {
        boolean a = main.setVisible(v, r);
        boolean b = sub.setVisible(v, r);
        return a || b;
    }

    @Override public boolean isStateful() { return main.isStateful() || sub.isStateful(); }

    @Override protected boolean onStateChange(int[] st) {
        boolean a = main.setState(st);
        boolean b = sub.setState(st);
        return a || b;
    }
}

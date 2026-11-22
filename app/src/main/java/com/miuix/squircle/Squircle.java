package com.miuix.squircle;

import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewOutlineProvider;

/**
 * miuix-squircle：连续圆角（超椭圆 / squircle）。
 *
 * <p>Miuix 的卡片与按钮使用「连续曲率」圆角，而不是普通圆弧。这里用超椭圆
 * {@code |x|^n + |y|^n = 1} 采样生成路径，n 越大越接近方形，n≈4 接近 iOS/小米的连续圆角。</p>
 */
public final class Squircle {

    /** 默认超椭圆指数（Miuix 观感接近 4）。 */
    public static final float N = 4.0f;

    /** 每个圆角的采样段数。 */
    private static final int SEG = 18;

    private Squircle() {
    }

    public static Path path(float w, float h, float radius) {
        return path(w, h, radius, N);
    }

    /** 生成超椭圆圆角矩形路径（左上角为原点）。 */
    public static Path path(float w, float h, float radius, float n) {
        Path p = new Path();
        if (w <= 0 || h <= 0) {
            return p;
        }
        float r = Math.max(0f, Math.min(radius, Math.min(w, h) / 2f));
        if (r < 0.5f) {
            p.addRect(0, 0, w, h, Path.Direction.CW);
            return p;
        }
        p.moveTo(r, 0);
        p.lineTo(w - r, 0);
        corner(p, w, 0, r, n, -1f, 1f, true);    // 右上
        p.lineTo(w, h - r);
        corner(p, w, h, r, n, -1f, -1f, false);  // 右下
        p.lineTo(r, h);
        corner(p, 0, h, r, n, 1f, -1f, true);    // 左下
        p.lineTo(0, r);
        corner(p, 0, 0, r, n, 1f, 1f, false);    // 左上
        p.close();
        return p;
    }

    /**
     * 追加一个角的超椭圆曲线。
     *
     * @param cx  角点 x
     * @param cy  角点 y
     * @param dirX 向内的 x 方向（±1）
     * @param dirY 向内的 y 方向（±1）
     * @param reverse 是否反向采样（保证顺时针）
     */
    private static void corner(Path p, float cx, float cy, float r, float n,
                               float dirX, float dirY, boolean reverse) {
        double exp = 2.0 / n;
        for (int i = 0; i <= SEG; i++) {
            double t = Math.PI / 2.0 * i / SEG;
            if (reverse) {
                t = Math.PI / 2.0 - t;
            }
            double lx = r * (1.0 - Math.pow(Math.cos(t), exp));
            double ly = r * (1.0 - Math.pow(Math.sin(t), exp));
            float x = cx + dirX * (float) lx;
            float y = cy + dirY * (float) ly;
            p.lineTo(x, y);
        }
    }

    /** 用超椭圆路径填充一个圆角矩形（canvas 上直接画）。 */
    public static void draw(Canvas canvas, float w, float h, float radius, Paint paint) {
        canvas.drawPath(path(w, h, radius), paint);
    }

    /** 描边一个超椭圆圆角矩形。 */
    public static void stroke(Canvas canvas, float w, float h, float radius,
                              float strokeWidth, Paint paint) {
        Paint p = new Paint(paint);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(strokeWidth);
        float inset = strokeWidth / 2f;
        Path path = path(w, h, radius);
        canvas.save();
        canvas.translate(inset, inset);
        canvas.drawPath(path(w - strokeWidth, h - strokeWidth, radius), p);
        canvas.restore();
    }

    /** 让 View 的子内容按超椭圆裁剪（API 21+ 用 Outline，低版本退化为圆角矩形）。 */
    public static void clipChildren(final View view, final float radiusPx) {
        view.setClipToOutline(true);
        view.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline outline) {
                Path path = path(v.getWidth(), v.getHeight(), radiusPx);
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    outline.setPath(path);
                } else {
                    outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radiusPx);
                }
            }
        });
    }

    /** 便捷：dp → 超椭圆路径。 */
    public static Path pathDp(float wDp, float hDp, float radiusDp, float density) {
        return path(wDp * density, hDp * density, radiusDp * density);
    }

    /** 圆角矩形 RectF（给需要 RectF 的场景）。 */
    public static RectF rectF(float w, float h) {
        return new RectF(0, 0, w, h);
    }
}
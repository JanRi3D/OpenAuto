package me.ri3d.openauto.ui;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import me.ri3d.openauto.R;

/**
 * Stroke icons drawn with Canvas on a 24x24 grid (the reference SVGs), scaled to the view.
 * No bitmaps, no vector drawables (API 21), crisp at any density.
 */
public class IconView extends View {
    public static final int WIFI = 0, USB = 1, SELF = 2, SLIDERS = 3, ARROW_RIGHT = 4, ARROW_LEFT = 5,
            MONITOR = 6, SPEAKER = 7, TARGET = 8, BLUETOOTH = 9, PLUS = 10, MINUS = 11, ACTIVITY = 12, CLOSE = 13,
            MINIMIZE = 14;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private int icon = WIFI;
    private float strokeUnits = 2f;

    public IconView(Context context, AttributeSet attrs) {
        super(context, attrs);
        int color = 0xFFEEF2F4;
        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.IconView);
            icon = a.getInt(R.styleable.IconView_icon, WIFI);
            color = a.getColor(R.styleable.IconView_iconColor, color);
            strokeUnits = a.getFloat(R.styleable.IconView_iconStroke, 2f);
            a.recycle();
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(color);
    }

    public void setIcon(int icon) {
        this.icon = icon;
        invalidate();
    }

    public void setColor(int color) {
        paint.setColor(color);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float size = Math.min(getWidth(), getHeight());
        float s = size / 24f;
        c.save();
        c.translate((getWidth() - size) / 2f, (getHeight() - size) / 2f);
        c.scale(s, s);
        paint.setStrokeWidth(strokeUnits);
        path.reset();
        switch (icon) {
            case WIFI:
                arc(c, 12, 20, 15, 228.2f, 83.6f);
                arc(c, 12, 20, 10, 225.6f, 88.8f);
                arc(c, 12, 20, 5, 225.6f, 88.8f);
                c.drawPoint(12, 20, paint);
                break;
            case USB:
                c.drawLine(12, 22, 12, 17, paint);
                c.drawLine(9, 8, 9, 2, paint);
                c.drawLine(15, 8, 15, 2, paint);
                path.moveTo(18, 8);
                path.lineTo(18, 13);
                rect.set(10, 9, 18, 17);
                path.arcTo(rect, 0, 90);
                path.lineTo(10, 17);
                rect.set(6, 9, 14, 17);
                path.arcTo(rect, 90, 90);
                path.lineTo(6, 8);
                path.close();
                c.drawPath(path, paint);
                break;
            case SELF:
                c.drawCircle(12, 12, 9.5f, paint);
                c.drawCircle(12, 12, 2.5f, paint);
                c.drawLine(9.5f, 12, 2.5f, 12, paint);
                c.drawLine(14.5f, 12, 21.5f, 12, paint);
                c.drawLine(12, 14.5f, 12, 21.5f, paint);
                break;
            case SLIDERS:
                c.drawLine(21, 4, 14, 4, paint);
                c.drawLine(10, 4, 3, 4, paint);
                c.drawLine(21, 12, 12, 12, paint);
                c.drawLine(8, 12, 3, 12, paint);
                c.drawLine(21, 20, 16, 20, paint);
                c.drawLine(12, 20, 3, 20, paint);
                c.drawLine(14, 2, 14, 6, paint);
                c.drawLine(8, 10, 8, 14, paint);
                c.drawLine(16, 18, 16, 22, paint);
                break;
            case ARROW_RIGHT:
                c.drawLine(5, 12, 19, 12, paint);
                path.moveTo(12, 5);
                path.lineTo(19, 12);
                path.lineTo(12, 19);
                c.drawPath(path, paint);
                break;
            case ARROW_LEFT:
                c.drawLine(19, 12, 5, 12, paint);
                path.moveTo(12, 19);
                path.lineTo(5, 12);
                path.lineTo(12, 5);
                c.drawPath(path, paint);
                break;
            case MONITOR:
                rect.set(2, 3, 22, 17);
                c.drawRoundRect(rect, 2, 2, paint);
                c.drawLine(8, 21, 16, 21, paint);
                c.drawLine(12, 17, 12, 21, paint);
                break;
            case SPEAKER:
                path.moveTo(11, 5);
                path.lineTo(6, 9);
                path.lineTo(2, 9);
                path.lineTo(2, 15);
                path.lineTo(6, 15);
                path.lineTo(11, 19);
                path.close();
                c.drawPath(path, paint);
                arc(c, 12, 12, 5, -45, 90);
                arc(c, 12, 12, 10, -45, 90);
                break;
            case TARGET:
                c.drawCircle(12, 12, 3, paint);
                c.drawCircle(12, 12, 9, paint);
                break;
            case BLUETOOTH:
                path.moveTo(7, 7);
                path.lineTo(17, 17);
                path.lineTo(12, 22);
                path.lineTo(12, 2);
                path.lineTo(17, 7);
                path.lineTo(7, 17);
                c.drawPath(path, paint);
                break;
            case PLUS:
                c.drawLine(5, 12, 19, 12, paint);
                c.drawLine(12, 5, 12, 19, paint);
                break;
            case MINUS:
                c.drawLine(5, 12, 19, 12, paint);
                break;
            case ACTIVITY:
                path.moveTo(22, 12);
                path.lineTo(18, 12);
                path.lineTo(15, 21);
                path.lineTo(9, 3);
                path.lineTo(6, 12);
                path.lineTo(2, 12);
                c.drawPath(path, paint);
                break;
            case CLOSE:
                c.drawLine(6, 6, 18, 18, paint);
                c.drawLine(18, 6, 6, 18, paint);
                break;
            case MINIMIZE:
                c.drawLine(6, 18, 18, 18, paint);
                break;
            default:
                break;
        }
        c.restore();
    }

    private void arc(Canvas c, float cx, float cy, float r, float start, float sweep) {
        rect.set(cx - r, cy - r, cx + r, cy + r);
        c.drawArc(rect, start, sweep, false, paint);
    }
}

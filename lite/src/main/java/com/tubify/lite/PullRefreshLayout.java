package com.tubify.lite;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

/** Pull-to-refresh: swipe down while the page is at the very top. */
public class PullRefreshLayout extends FrameLayout {
    public interface Host {
        boolean canPull();
        void onRefresh();
    }

    private final Host host;
    private final int slop;
    private final float trigger;
    private final float maxPull;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private float startX, startY, rawStartY, pull;
    private boolean eligible, pulling;
    private int accent = 0xFFFF3B3B, bg = 0xFF20232E;

    public PullRefreshLayout(Context c, Host host) {
        super(c);
        this.host = host;
        float d = c.getResources().getDisplayMetrics().density;
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        trigger = 72 * d;
        maxPull = 110 * d;
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeWidth(3 * d);
        arc.setStrokeCap(Paint.Cap.ROUND);
        setWillNotDraw(false);
    }

    public void setColors(int accent, int bg) {
        this.accent = accent;
        this.bg = bg;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                startX = e.getX();
                startY = e.getY();
                rawStartY = e.getY();
                pulling = false;
                eligible = host.canPull();
                break;
            case MotionEvent.ACTION_MOVE:
                if (eligible && !pulling && e.getPointerCount() == 1) {
                    float dy = e.getY() - startY;
                    float dx = Math.abs(e.getX() - startX);
                    if (dy > slop * 2 && dy > dx * 1.5f) {
                        pulling = true;
                        startY = e.getY() - slop * 2;
                    } else if (dy < -slop) {
                        eligible = false;
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                eligible = false;
                break;
            default:
                break;
        }
        return pulling;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!pulling) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                pull = Math.max(0, Math.min(maxPull, (e.getY() - startY) * 0.5f));
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                // Trigger on the real finger travel from ACTION_DOWN; the
                // visible pull is damped (x0.5), so using it doubled the
                // distance a user had to drag before a refresh fired.
                boolean go = (e.getY() - rawStartY) >= trigger;
                finish();
                if (go) host.onRefresh();
                return true;
            case MotionEvent.ACTION_CANCEL:
                finish();
                return true;
            default:
                return true;
        }
    }

    private void finish() {
        pulling = false;
        eligible = false;
        pull = 0;
        invalidate();
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (!pulling || pull <= 0) return;
        float d = getResources().getDisplayMetrics().density;
        float r = 18 * d;
        float cx = getWidth() / 2f;
        float cy = pull - r - 4 * d;
        fill.setColor(bg);
        canvas.drawCircle(cx, cy, r, fill);
        arc.setColor(accent);
        rect.set(cx - r * 0.55f, cy - r * 0.55f, cx + r * 0.55f, cy + r * 0.55f);
        float sweep = 300f * Math.min(1f, pull / trigger);
        canvas.drawArc(rect, -90, sweep, false, arc);
    }
}

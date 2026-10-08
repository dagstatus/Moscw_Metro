package ru.moscowmetro.offline;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Parcelable;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.OverScroller;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Схема метро: рисование, масштабирование пальцами, нажатие на станцию, подсветка маршрута. */
final class MetroMapView extends View {

    interface Listener { void onStationTap(int station); }

    static final class Palette {
        int bg, text, connector, from, to;
        boolean dark;

        static Palette of(boolean dark) {
            Palette p = new Palette();
            p.dark = dark;
            p.bg = dark ? 0xFF121316 : 0xFFF6F6F3;
            p.text = dark ? 0xFFE8E8EA : 0xFF1C1C1F;
            p.connector = dark ? 0xFF55585E : 0xFFC4C4C4;
            p.from = dark ? 0xFF4CC274 : 0xFF1E8E3E;
            p.to = dark ? 0xFFFF6B5E : 0xFFD93025;
            return p;
        }
    }

    private MetroData d;
    private Palette pal = Palette.of(false);
    private Listener listener;
    private Path[] linePaths;
    private boolean[] isTransfer;

    private float scale = 1, tx, ty, minScale = 0.05f, maxScale = 20;
    private boolean positioned;
    private int insetTop, insetBottom;

    private Router.Route route;
    private MetroData.Entry from, to;

    private final float density;
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint haloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint boldText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint boldHalo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;
    private final OverScroller scroller;
    private ValueAnimator animator;

    // подписи: раскладка считается для ступеней масштаба и кэшируется
    private final Map<Integer, Labels> labelCache = new HashMap<>();
    private float[] labelWidthPx;
    private int[] labelOrder;
    private static final float LEVEL_STEP = 1.25f;

    private static final class Labels {
        int[] entry;
        float[] dx, dy; // смещение базовой линии текста от центра станции, px
        boolean[] bold;
        int count;
    }

    MetroMapView(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        float sd = c.getResources().getDisplayMetrics().scaledDensity;
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        textPaint.setTextSize(12.5f * sd);
        textPaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        haloPaint.set(textPaint);
        haloPaint.setStyle(Paint.Style.STROKE);
        haloPaint.setStrokeWidth(3.2f * density);
        haloPaint.setStrokeJoin(Paint.Join.ROUND);
        boldText.set(textPaint);
        boldText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        boldText.setTextSize(13.5f * sd);
        boldHalo.set(boldText);
        boldHalo.setStyle(Paint.Style.STROKE);
        boldHalo.setStrokeWidth(3.5f * density);
        boldHalo.setStrokeJoin(Paint.Join.ROUND);
        scroller = new OverScroller(c);
        scaleDetector = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector det) {
                zoomAt(det.getScaleFactor(), det.getFocusX(), det.getFocusY());
                return true;
            }
        });
        gestureDetector = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) {
                scroller.forceFinished(true);
                if (animator != null) animator.cancel();
                return true;
            }
            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                tx -= dx; ty -= dy;
                clampPan();
                invalidate();
                return true;
            }
            @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                scroller.fling((int) tx, (int) ty, (int) vx, (int) vy, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE);
                postInvalidateOnAnimation();
                return true;
            }
            @Override public boolean onDoubleTap(MotionEvent e) {
                float target = Math.min(maxScale, scale * 2.2f);
                animateTo(target, e.getX() - (e.getX() - tx) * target / scale, e.getY() - (e.getY() - ty) * target / scale);
                return true;
            }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                int s = hitTest(e.getX(), e.getY());
                if (s >= 0 && listener != null) listener.onStationTap(s);
                return true;
            }
        });
        scaleDetector.setQuickScaleEnabled(true);
        setId(0x7f0f0001);
        setSaveEnabled(true);
    }

    void setData(MetroData data) {
        d = data;
        linePaths = new Path[d.lines.length];
        for (int i = 0; i < d.lines.length; i++) {
            Path p = new Path();
            for (int[] path : d.lines[i].paths) {
                for (int k = 0; k < path.length; k++) {
                    MetroData.Station s = d.stations[path[k]];
                    if (k == 0) p.moveTo(s.x, s.y); else p.lineTo(s.x, s.y);
                }
            }
            linePaths[i] = p;
        }
        isTransfer = new boolean[d.stations.length];
        for (int i = 0; i < d.trA.length; i++) { isTransfer[d.trA[i]] = true; isTransfer[d.trB[i]] = true; }
        labelWidthPx = new float[d.entries.size()];
        Integer[] order = new Integer[d.entries.size()];
        Map<MetroData.Entry, Integer> idx = new HashMap<>();
        for (int i = 0; i < order.length; i++) {
            MetroData.Entry e = d.entries.get(i);
            idx.put(e, i);
            order[i] = i;
            labelWidthPx[i] = textPaint.measureText(e.name);
        }
        // сначала подписываем пересадочные узлы, затем метро, затем МЦД
        final int[] prio = new int[order.length];
        int[] groupSize = new int[d.groupCount];
        for (MetroData.Station s : d.stations) groupSize[s.group]++;
        for (int i = 0; i < order.length; i++) {
            MetroData.Entry e = d.entries.get(i);
            MetroData.Station s = d.stations[e.stations[0]];
            String type = d.lines[s.line].type;
            prio[i] = groupSize[s.group] * 10 + ("metro".equals(type) ? 3 : "mcc".equals(type) ? 2 : 0);
        }
        Arrays.sort(order, (a, b) -> prio[b] - prio[a]);
        labelOrder = new int[order.length];
        for (int i = 0; i < order.length; i++) labelOrder[i] = order[i];
        entryIndex = idx;
        labelCache.clear();
        positioned = false;
        requestLayout();
        invalidate();
    }

    private Map<MetroData.Entry, Integer> entryIndex;

    void setPalette(Palette p) {
        pal = p;
        haloPaint.setColor(p.bg);
        boldHalo.setColor(p.bg);
        textPaint.setColor(p.text);
        boldText.setColor(p.text);
        invalidate();
    }

    void setListener(Listener l) { listener = l; }

    void setInsets(int top, int bottom) {
        insetTop = top;
        insetBottom = bottom;
    }

    void setSelection(MetroData.Entry from, MetroData.Entry to, Router.Route route) {
        this.from = from;
        this.to = to;
        this.route = route;
        labelCache.clear();
        invalidate();
    }

    // ------------------------------------------------------------ геометрия

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (d == null || w == 0) return;
        minScale = Math.min(w / (d.bounds.width() + 200), h / (d.bounds.height() + 200)) * 0.9f;
        maxScale = Math.max(minScale * 6, w * 9f / d.coreBounds.width());
        if (!positioned) {
            positioned = true;
            fit(d.coreBounds, false);
        } else {
            clampPan();
        }
    }

    void fitAll() { if (d != null) fit(d.coreBounds, true); }

    void fitRoute() {
        if (route == null) return;
        RectF r = null;
        for (int n : route.nodes) {
            MetroData.Station s = d.stations[n];
            if (r == null) r = new RectF(s.x, s.y, s.x, s.y); else r.union(s.x, s.y);
        }
        if (r != null) {
            float pad = 40;
            r.inset(-pad, -pad);
            fit(r, true);
        }
    }

    void focusStation(int station) {
        MetroData.Station s = d.stations[station];
        float target = Math.max(scale, Math.min(maxScale, minScale * 5));
        float cx = getWidth() / 2f, cy = (insetTop + getHeight() - insetBottom) / 2f;
        animateTo(target, cx - s.x * target, cy - s.y * target);
    }

    private void fit(RectF r, boolean animate) {
        float w = getWidth(), h = getHeight();
        if (w == 0) return;
        float pad = 16 * density;
        float aw = w - 2 * pad, ah = h - insetTop - insetBottom - 2 * pad - 48 * density;
        if (ah < h * 0.25f) ah = h * 0.25f;
        float s = Math.min(aw / Math.max(1, r.width()), ah / Math.max(1, r.height()));
        s = Math.max(minScale, Math.min(maxScale, s));
        float cx = w / 2f, cy = insetTop + 48 * density + pad + ah / 2f;
        float ntx = cx - r.centerX() * s, nty = cy - r.centerY() * s;
        if (animate) animateTo(s, ntx, nty);
        else { scale = s; tx = ntx; ty = nty; invalidate(); }
    }

    private void animateTo(final float s1, final float tx1, final float ty1) {
        if (animator != null) animator.cancel();
        scroller.forceFinished(true);
        final float s0 = scale, tx0 = tx, ty0 = ty;
        animator = ValueAnimator.ofFloat(0, 1);
        animator.setDuration(380);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            // масштаб меняем по логарифму, сдвиг — так, чтобы точка-цель двигалась плавно
            scale = (float) (s0 * Math.pow(s1 / s0, f));
            float k = Math.abs(s1 - s0) < 1e-6 ? f : (scale - s0) / (s1 - s0);
            tx = tx0 + (tx1 - tx0) * k;
            ty = ty0 + (ty1 - ty0) * k;
            invalidate();
        });
        animator.start();
    }

    private void zoomAt(float factor, float fx, float fy) {
        float ns = Math.max(minScale, Math.min(maxScale, scale * factor));
        float k = ns / scale;
        tx = fx - (fx - tx) * k;
        ty = fy - (fy - ty) * k;
        scale = ns;
        clampPan();
        invalidate();
    }

    private void clampPan() {
        if (d == null) return;
        float w = getWidth(), h = getHeight();
        float l = d.bounds.left * scale + tx, r = d.bounds.right * scale + tx;
        float t = d.bounds.top * scale + ty, b = d.bounds.bottom * scale + ty;
        if (r < w * 0.35f) tx += w * 0.35f - r;
        if (l > w * 0.65f) tx -= l - w * 0.65f;
        if (b < h * 0.35f) ty += h * 0.35f - b;
        if (t > h * 0.65f) ty -= t - h * 0.65f;
    }

    @Override public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            tx = scroller.getCurrX();
            ty = scroller.getCurrY();
            float otx = tx, oty = ty;
            clampPan();
            if (otx != tx || oty != ty) scroller.forceFinished(true);
            postInvalidateOnAnimation();
        }
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        boolean a = scaleDetector.onTouchEvent(e);
        boolean b = scaleDetector.isInProgress() || gestureDetector.onTouchEvent(e);
        return a || b || super.onTouchEvent(e);
    }

    private int hitTest(float x, float y) {
        if (d == null) return -1;
        float best = (26 * density) * (26 * density);
        int res = -1;
        for (MetroData.Station s : d.stations) {
            float dx = s.x * scale + tx - x, dy = s.y * scale + ty - y;
            float dd = dx * dx + dy * dy;
            if (dd < best) { best = dd; res = s.idx; }
        }
        return res;
    }

    // ------------------------------------------------------------ рисование

    private float lineWidth() { return clamp(6f, 1.7f * density / scale, 5.5f * density / scale); }

    private float stationRadius() { return lineWidth() * 0.82f; }

    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }

    @Override protected void onDraw(Canvas c) {
        c.drawColor(pal.bg);
        if (d == null) return;
        float lw = lineWidth(), rs = stationRadius();

        c.save();
        c.translate(tx, ty);
        c.scale(scale, scale);
        drawNetwork(c, lw, rs, null);
        c.restore();

        if (route != null) {
            c.drawColor((pal.bg & 0x00FFFFFF) | (pal.dark ? 0xC8000000 : 0xC0000000));
            c.save();
            c.translate(tx, ty);
            c.scale(scale, scale);
            drawRoute(c, lw * 1.35f, rs * 1.2f);
            c.restore();
        }

        drawMarker(c, from, pal.from);
        drawMarker(c, to, pal.to);
        drawLabels(c);
    }

    private void drawNetwork(Canvas c, float lw, float rs, Set<Integer> only) {
        linePaint.setPathEffect(null);
        // переходы между станциями
        linePaint.setColor(pal.connector);
        linePaint.setStrokeWidth(lw * 1.5f);
        for (int i = 0; i < d.trA.length; i++) {
            MetroData.Station a = d.stations[d.trA[i]], b = d.stations[d.trB[i]];
            c.drawLine(a.x, a.y, b.x, b.y, linePaint);
        }
        // линии
        for (int i = 0; i < d.lines.length; i++) {
            MetroData.Line l = d.lines[i];
            float w = l.isMetro() ? lw : lw * 0.85f;
            linePaint.setColor(l.color);
            linePaint.setStrokeWidth(w);
            c.drawPath(linePaths[i], linePaint);
            if (!l.isMetro()) {
                linePaint.setColor(pal.bg);
                linePaint.setStrokeWidth(w * 0.38f);
                c.drawPath(linePaths[i], linePaint);
            }
        }
        // станции
        for (MetroData.Station s : d.stations) {
            int color = d.lines[s.line].color;
            if (isTransfer[s.idx]) {
                fillPaint.setStyle(Paint.Style.FILL);
                fillPaint.setColor(pal.bg);
                c.drawCircle(s.x, s.y, rs * 1.05f, fillPaint);
                fillPaint.setStyle(Paint.Style.STROKE);
                fillPaint.setStrokeWidth(rs * 0.55f);
                fillPaint.setColor(color);
                c.drawCircle(s.x, s.y, rs * 0.9f, fillPaint);
            } else {
                fillPaint.setStyle(Paint.Style.FILL);
                fillPaint.setColor(color);
                c.drawCircle(s.x, s.y, rs * 0.85f, fillPaint);
            }
        }
    }

    private void drawRoute(Canvas c, float lw, float rs) {
        for (Router.Step st : route.steps) {
            if (st.walk) {
                linePaint.setColor(pal.text);
                linePaint.setStrokeWidth(lw * 0.45f);
                linePaint.setPathEffect(new DashPathEffect(new float[]{lw * 0.6f, lw * 0.9f}, 0));
            } else {
                linePaint.setColor(d.lines[st.line].color);
                linePaint.setStrokeWidth(lw);
                linePaint.setPathEffect(null);
            }
            Path p = new Path();
            for (int k = 0; k < st.stations.size(); k++) {
                MetroData.Station s = d.stations[st.stations.get(k)];
                if (k == 0) p.moveTo(s.x, s.y); else p.lineTo(s.x, s.y);
            }
            c.drawPath(p, linePaint);
        }
        linePaint.setPathEffect(null);
        for (int n : route.nodes) {
            MetroData.Station s = d.stations[n];
            fillPaint.setStyle(Paint.Style.FILL);
            fillPaint.setColor(pal.bg);
            c.drawCircle(s.x, s.y, rs, fillPaint);
            fillPaint.setStyle(Paint.Style.STROKE);
            fillPaint.setStrokeWidth(rs * 0.5f);
            fillPaint.setColor(d.lines[s.line].color);
            c.drawCircle(s.x, s.y, rs * 0.85f, fillPaint);
        }
    }

    private void drawMarker(Canvas c, MetroData.Entry e, int color) {
        if (e == null) return;
        for (int st : e.stations) {
            MetroData.Station s = d.stations[st];
            float x = s.x * scale + tx, y = s.y * scale + ty;
            fillPaint.setStyle(Paint.Style.FILL);
            fillPaint.setColor(color);
            c.drawCircle(x, y, 7 * density, fillPaint);
            fillPaint.setColor(Color.WHITE);
            c.drawCircle(x, y, 3 * density, fillPaint);
        }
    }

    // ------------------------------------------------------------ подписи

    private void drawLabels(Canvas c) {
        int level = Math.round((float) (Math.log(scale) / Math.log(LEVEL_STEP)));
        Labels lab = labelCache.get(level);
        if (lab == null) {
            lab = layoutLabels((float) Math.pow(LEVEL_STEP, level));
            labelCache.put(level, lab);
        }
        float w = getWidth(), h = getHeight();
        for (int i = 0; i < lab.count; i++) {
            MetroData.Entry e = d.entries.get(lab.entry[i]);
            float ax = 0, ay = 0;
            for (int st : e.stations) { ax += d.stations[st].x; ay += d.stations[st].y; }
            ax = ax / e.stations.length * scale + tx;
            ay = ay / e.stations.length * scale + ty;
            float x = ax + lab.dx[i], y = ay + lab.dy[i];
            if (x > w || y < -20 * density || y > h + 20 * density || x + labelWidthPx[lab.entry[i]] * 1.2f < 0) continue;
            if (lab.bold[i]) {
                c.drawText(e.name, x, y, boldHalo);
                c.drawText(e.name, x, y, boldText);
            } else {
                c.drawText(e.name, x, y, haloPaint);
                c.drawText(e.name, x, y, textPaint);
            }
        }
    }

    /** Жадная раскладка подписей без наложений для данного масштаба. Координаты считаются в пикселях экрана. */
    private Labels layoutLabels(float s) {
        float rsPx = clamp(6f * s, 1.7f * density, 5.5f * density) * 0.82f * (route != null ? 1.2f : 1f);
        float gap = rsPx + 3.5f * density;
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float h = fm.descent - fm.ascent;

        List<Integer> order = new ArrayList<>();
        Set<Integer> forced = new LinkedHashSet<>();
        if (from != null) forced.add(entryIndex.get(from));
        if (to != null) forced.add(entryIndex.get(to));
        if (route != null) {
            for (Router.Step st : route.steps) {
                forced.add(entryIndex.get(d.entryOfStation[st.stations.get(0)]));
                forced.add(entryIndex.get(d.entryOfStation[st.stations.get(st.stations.size() - 1)]));
            }
            for (int n : route.nodes) forced.add(entryIndex.get(d.entryOfStation[n]));
            order.addAll(forced);
        } else {
            order.addAll(forced);
            for (int i : labelOrder) if (!forced.contains(i)) order.add(i);
        }

        // точки станций — препятствия (в пикселях при масштабе s)
        int n = d.stations.length;
        float[] sx = new float[n], sy = new float[n];
        for (int i = 0; i < n; i++) { sx[i] = d.stations[i].x * s; sy[i] = d.stations[i].y * s; }

        Labels out = new Labels();
        out.entry = new int[order.size()];
        out.dx = new float[order.size()];
        out.dy = new float[order.size()];
        out.bold = new boolean[order.size()];
        List<RectF> placed = new ArrayList<>();
        RectF cand = new RectF();
        Set<Integer> routeEnds = new LinkedHashSet<>();
        if (from != null) routeEnds.add(entryIndex.get(from));
        if (to != null) routeEnds.add(entryIndex.get(to));

        for (int ei : order) {
            MetroData.Entry e = d.entries.get(ei);
            boolean bold = routeEnds.contains(ei) || (route != null && forced.contains(ei) && isStepEnd(ei));
            float tw = bold ? boldText.measureText(e.name) : labelWidthPx[ei];
            float th = bold ? h * 1.08f : h;
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, cx = 0, cy = 0;
            for (int st : e.stations) {
                minX = Math.min(minX, sx[st]); maxX = Math.max(maxX, sx[st]);
                minY = Math.min(minY, sy[st]); maxY = Math.max(maxY, sy[st]);
                cx += sx[st]; cy += sy[st];
            }
            cx /= e.stations.length; cy /= e.stations.length;
            float bestScore = Float.MAX_VALUE, bl = 0, bt = 0;
            for (int k = 0; k < 8; k++) {
                float l, t, g2 = gap * 0.75f;
                switch (k) {
                    case 0: l = maxX + gap; t = cy - th / 2; break;
                    case 1: l = minX - gap - tw; t = cy - th / 2; break;
                    case 2: l = maxX + g2; t = minY - g2 - th; break;
                    case 3: l = maxX + g2; t = maxY + g2; break;
                    case 4: l = cx - tw / 2; t = minY - gap - th; break;
                    case 5: l = cx - tw / 2; t = maxY + gap; break;
                    case 6: l = minX - g2 - tw; t = minY - g2 - th; break;
                    default: l = minX - g2 - tw; t = maxY + g2; break;
                }
                cand.set(l, t, l + tw, t + th);
                float score = k * 0.01f;
                boolean hit = false;
                for (RectF p : placed) if (RectF.intersects(p, cand)) { hit = true; break; }
                if (hit) continue;
                for (int i = 0; i < n; i++) {
                    if (sx[i] > cand.left - rsPx && sx[i] < cand.right + rsPx && sy[i] > cand.top - rsPx && sy[i] < cand.bottom + rsPx) {
                        if (d.entryOfStation[i] != e) score += 1;
                    }
                }
                if (score < bestScore) { bestScore = score; bl = l; bt = t; }
            }
            if (bestScore == Float.MAX_VALUE) continue;
            if (bestScore >= 1 && !forced.contains(ei)) continue; // не закрываем станции подписями
            placed.add(new RectF(bl, bt, bl + tw, bt + th));
            int i = out.count++;
            out.entry[i] = ei;
            out.dx[i] = bl - cx;
            out.dy[i] = bt - (bold ? boldText.getFontMetrics().ascent : fm.ascent) - cy;
            out.bold[i] = bold;
        }
        return out;
    }

    private boolean isStepEnd(int ei) {
        for (Router.Step st : route.steps) {
            if (entryIndex.get(d.entryOfStation[st.stations.get(0)]) == ei) return true;
            if (entryIndex.get(d.entryOfStation[st.stations.get(st.stations.size() - 1)]) == ei) return true;
        }
        return false;
    }

    // ------------------------------------------------------------ состояние

    @Override protected Parcelable onSaveInstanceState() {
        Bundle b = new Bundle();
        b.putParcelable("super", super.onSaveInstanceState());
        b.putFloat("scale", scale);
        b.putFloat("tx", tx);
        b.putFloat("ty", ty);
        b.putBoolean("positioned", positioned);
        return b;
    }

    @Override protected void onRestoreInstanceState(Parcelable state) {
        if (state instanceof Bundle) {
            Bundle b = (Bundle) state;
            super.onRestoreInstanceState(b.getParcelable("super"));
            if (b.getBoolean("positioned")) {
                scale = b.getFloat("scale");
                tx = b.getFloat("tx");
                ty = b.getFloat("ty");
                positioned = true;
            }
        } else {
            super.onRestoreInstanceState(state);
        }
    }
}

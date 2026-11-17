package com.termux.mod.split;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.List;

/**
 * god-termux: lays out the split tree as pane rectangles with 8dp dividers between them.
 * Dividers have a ~24dp touch zone and can be dragged to change the split ratio.
 */
public final class SplitLayout extends ViewGroup {

    private static final int DIVIDER_DP = 8;
    private static final int TOUCH_ZONE_DP = 24;
    private static final int MIN_PANE_DP = 48;

    private SplitNode mRoot;
    private final List<Divider> mDividers = new ArrayList<>();
    private final Paint mDividerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Divider mDrag;

    // pane drag & drop (docking): the dragged pane, the pane under the finger and the snap zone
    private PaneView mDragSrc, mDropTarget;
    private int mDropZone = -1;
    private float mDragX, mDragY;
    private final Paint mDropFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mDropStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGhostFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGhostText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int mDividerPx, mZonePx, mMinPanePx;

    private static final class Divider {
        SplitNode node;
        Rect region;
        Rect gap;
        boolean horizontal; // true = panes side by side (divider is a vertical bar)
    }

    public SplitLayout(Context context) {
        this(context, null);
    }

    public SplitLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        float dp = context.getResources().getDisplayMetrics().density;
        mDividerPx = Math.round(DIVIDER_DP * dp);
        mZonePx = Math.round(TOUCH_ZONE_DP * dp);
        mMinPanePx = Math.round(MIN_PANE_DP * dp);
        mDividerPaint.setColor(0x66888888);
        mDropFill.setStyle(Paint.Style.FILL);
        mDropStroke.setStyle(Paint.Style.STROKE);
        mDropStroke.setStrokeWidth(2 * dp);
        mGhostFill.setColor(0xEE263238);
        mGhostText.setColor(0xFFFFFFFF);
        mGhostText.setTextSize(13 * dp);
        mGhostText.setTextAlign(Paint.Align.CENTER);
        setWillNotDraw(false);
    }

    public void setTree(SplitNode root) {
        mRoot = root;
        requestLayout();
        invalidate();
    }

    public SplitNode getTree() {
        return mRoot;
    }

    public void addPane(PaneView pane) {
        addView(pane, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    public void removePane(PaneView pane) {
        removeView(pane);
    }

    public PaneView getPane(int paneId) {
        for (int i = 0; i < getChildCount(); i++) {
            PaneView pane = (PaneView) getChildAt(i);
            if (pane.paneId == paneId) return pane;
        }
        return null;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec), h = MeasureSpec.getSize(heightMeasureSpec);
        setMeasuredDimension(w, h);
        mDividers.clear();
        if (mRoot != null) walk(mRoot, new Rect(0, 0, w, h), true);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        mDividers.clear();
        if (mRoot != null) walk(mRoot, new Rect(0, 0, r - l, b - t), false);
    }

    private void walk(SplitNode node, Rect rect, boolean measure) {
        if (node.isLeaf()) {
            PaneView pane = getPane(node.paneId);
            if (pane == null) return;
            if (measure) {
                pane.measure(MeasureSpec.makeMeasureSpec(Math.max(0, rect.width()), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(Math.max(0, rect.height()), MeasureSpec.EXACTLY));
            } else {
                pane.layout(rect.left, rect.top, rect.right, rect.bottom);
            }
            return;
        }
        boolean horizontal = node.orientation == SplitNode.HORIZONTAL;
        int size = horizontal ? rect.width() : rect.height();
        int usable = size - mDividerPx;
        if (usable <= 0) { // degenerate: everything to the first child
            walk(node.a, rect, measure);
            return;
        }
        int first = Math.round(usable * node.ratio);
        Rect ra, rb, gap;
        if (horizontal) {
            ra = new Rect(rect.left, rect.top, rect.left + first, rect.bottom);
            gap = new Rect(ra.right, rect.top, ra.right + mDividerPx, rect.bottom);
            rb = new Rect(gap.right, rect.top, rect.right, rect.bottom);
        } else {
            ra = new Rect(rect.left, rect.top, rect.right, rect.top + first);
            gap = new Rect(rect.left, ra.bottom, rect.right, ra.bottom + mDividerPx);
            rb = new Rect(rect.left, gap.bottom, rect.right, rect.bottom);
        }
        Divider divider = new Divider();
        divider.node = node;
        divider.region = new Rect(rect);
        divider.gap = gap;
        divider.horizontal = horizontal;
        mDividers.add(divider);
        walk(node.a, ra, measure);
        walk(node.b, rb, measure);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (mRoot == null || mDividers.isEmpty()) return false;
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            mDrag = findDivider(ev.getX(), ev.getY());
            if (mDrag != null) {
                applyDrag(ev);
                return true;
            }
        } else if (mDrag != null) {
            if (action == MotionEvent.ACTION_MOVE) applyDrag(ev);
            else mDrag = null;
            return true;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (mDrag == null) return false;
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_MOVE) applyDrag(ev);
        else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) mDrag = null;
        return true;
    }

    private Divider findDivider(float x, float y) {
        int expand = Math.max(0, (mZonePx - mDividerPx) / 2);
        for (Divider divider : mDividers) {
            Rect zone = new Rect(divider.gap);
            if (divider.horizontal) zone.inset(-expand, 0);
            else zone.inset(0, -expand);
            if (zone.contains((int) x, (int) y)) return divider;
        }
        return null;
    }

    private void applyDrag(MotionEvent ev) {
        Divider divider = mDrag;
        int size = (divider.horizontal ? divider.region.width() : divider.region.height()) - mDividerPx;
        if (size <= 0) return;
        float pos = divider.horizontal ? ev.getX() - divider.region.left : ev.getY() - divider.region.top;
        float raw = (pos - mDividerPx / 2f) / size;
        float minFrac = Math.max(SplitNode.MIN_RATIO, mMinPanePx / (float) size);
        float maxFrac = Math.min(SplitNode.MAX_RATIO, 1f - minFrac);
        if (minFrac > maxFrac) return;
        float ratio = Math.max(minFrac, Math.min(maxFrac, raw));
        if (ratio != divider.node.ratio) {
            divider.node.ratio = ratio;
            requestLayout();
        }
    }

    public void beginDrag(PaneView src) {
        mDragSrc = src;
        mDropTarget = null;
        mDropZone = -1;
        src.setAlpha(0.45f);
        invalidate();
    }

    /** Update the drag from raw screen coordinates: find the pane under the finger and its snap zone. */
    public void updateDrag(float rawX, float rawY) {
        if (mDragSrc == null) return;
        int[] loc = new int[2];
        getLocationOnScreen(loc);
        mDragX = rawX - loc[0];
        mDragY = rawY - loc[1];
        mDropTarget = null;
        mDropZone = -1;
        for (int i = 0; i < getChildCount(); i++) {
            PaneView pane = (PaneView) getChildAt(i);
            if (pane == mDragSrc || pane.getWidth() <= 0 || pane.getHeight() <= 0) continue;
            if (mDragX >= pane.getLeft() && mDragX < pane.getRight() && mDragY >= pane.getTop() && mDragY < pane.getBottom()) {
                mDropTarget = pane;
                mDropZone = SplitNode.zoneFor((mDragX - pane.getLeft()) / pane.getWidth(), (mDragY - pane.getTop()) / pane.getHeight());
                break;
            }
        }
        invalidate();
    }

    public PaneView getDragSource() {
        return mDragSrc;
    }

    public PaneView getDropTarget() {
        return mDropTarget;
    }

    public int getDropZone() {
        return mDropZone;
    }

    public void endDrag() {
        if (mDragSrc != null) mDragSrc.setAlpha(1f);
        mDragSrc = null;
        mDropTarget = null;
        mDropZone = -1;
        invalidate();
    }

    public boolean isDragging() {
        return mDragSrc != null;
    }

    /** The area the dragged pane would occupy if dropped now (half of the target for an edge, all of it for the centre). */
    private RectF dropPreview() {
        PaneView t = mDropTarget;
        RectF r = new RectF(t.getLeft(), t.getTop(), t.getRight(), t.getBottom());
        switch (mDropZone) {
            case SplitNode.ZONE_LEFT: r.right = r.left + r.width() / 2f; break;
            case SplitNode.ZONE_RIGHT: r.left = r.left + r.width() / 2f; break;
            case SplitNode.ZONE_TOP: r.bottom = r.top + r.height() / 2f; break;
            case SplitNode.ZONE_BOTTOM: r.top = r.top + r.height() / 2f; break;
            default: break;
        }
        return r;
    }

    /** god-termux: lets RadialGesture ignore taps that land on a divider (window coordinates). */
    public boolean isOverDivider(float windowX, float windowY) {
        int[] loc = new int[2];
        getLocationInWindow(loc);
        return findDivider(windowX - loc[0], windowY - loc[1]) != null;
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        drawDragOverlay(canvas);
        if (mDividers.isEmpty()) return;
        float radius = mDividerPx / 2f;
        for (Divider divider : mDividers) {
            RectF bar = new RectF(divider.gap);
            if (divider.horizontal) bar.inset(mDividerPx * 0.3f, mDividerPx * 0.15f);
            else bar.inset(mDividerPx * 0.15f, mDividerPx * 0.3f);
            canvas.drawRoundRect(bar, radius, radius, mDividerPaint);
        }
    }


    private void drawDragOverlay(Canvas canvas) {
        if (mDragSrc == null) return;
        float dp = getResources().getDisplayMetrics().density;
        if (mDropTarget != null) {
            int color = mDragSrc.getPaneColor();
            RectF preview = dropPreview();
            preview.inset(3 * dp, 3 * dp);
            mDropFill.setColor(color);
            mDropFill.setAlpha(90);
            mDropStroke.setColor(color);
            canvas.drawRoundRect(preview, 6 * dp, 6 * dp, mDropFill);
            canvas.drawRoundRect(preview, 6 * dp, 6 * dp, mDropStroke);
        }
        CharSequence title = mDragSrc.getTitleText();
        String label = (mDropZone == SplitNode.ZONE_CENTER ? "⇆ " : "") + (title == null || title.length() == 0 ? "bash" : title);
        float w = Math.min(getWidth() - 8 * dp, Math.max(90 * dp, mGhostText.measureText(label) + 24 * dp)), h = 30 * dp;
        float cx = Math.max(w / 2f + 4 * dp, Math.min(getWidth() - w / 2f - 4 * dp, mDragX));
        float cy = Math.max(h / 2f, Math.min(getHeight() - h / 2f, mDragY - 36 * dp));
        RectF ghost = new RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f);
        canvas.drawRoundRect(ghost, 8 * dp, 8 * dp, mGhostFill);
        mDropStroke.setColor(mDragSrc.getPaneColor());
        canvas.drawRoundRect(ghost, 8 * dp, 8 * dp, mDropStroke);
        canvas.drawText(label, cx, cy + mGhostText.getTextSize() * 0.35f, mGhostText);
    }

}

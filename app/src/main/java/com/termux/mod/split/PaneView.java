package com.termux.mod.split;

import android.annotation.SuppressLint;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewConfiguration;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.terminal.TermuxTerminalViewClient;
import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;

/**
 * god-termux: one split pane — a TerminalView with a focus border and a mini header
 * (session title + ⋮ pane menu). The header is hidden while there is only one pane.
 */
public final class PaneView extends FrameLayout {

    public final int paneId;
    final TerminalView view;
    private final TermuxTerminalViewClient mClient;
    private final PaneManager mManager;
    private final LinearLayout mHeader;
    private final TextView mTitle;
    private final IconButton mGrip, mSplitRight, mSplitDown, mClose;
    private final Paint mBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float mBorderWidth;
    private int mColor = 0xFF4FC3F7;
    private boolean mFocused;
    private boolean mMultiPane;
    private boolean mSwallowTouch;
    private float mDownX, mDownY;
    private boolean mDragging;

    @SuppressLint("ClickableViewAccessibility")
    PaneView(TermuxActivity activity, int paneId, PaneManager manager) {
        super(activity);
        this.paneId = paneId;
        mManager = manager;
        float dp = getResources().getDisplayMetrics().density;
        mBorderWidth = 2 * dp;
        mBorderPaint.setStyle(Paint.Style.STROKE);
        mBorderPaint.setStrokeWidth(mBorderWidth);

        view = new TerminalView(activity, null);
        view.setFocusable(true);
        view.setFocusableInTouchMode(true);
        if (android.os.Build.VERSION.SDK_INT >= 26) view.setDefaultFocusHighlightEnabled(false);
        view.setVerticalScrollBarEnabled(true);
        if (android.os.Build.VERSION.SDK_INT >= 29)
            view.setVerticalScrollbarThumbDrawable(activity.getDrawable(R.drawable.terminal_scroll_shape));
        mClient = new TermuxTerminalViewClient(activity, activity.getTermuxTerminalSessionClient(), view);
        view.setTerminalViewClient(mClient);
        addView(view, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        int headerHeight = Math.round(20 * dp);
        mHeader = new LinearLayout(activity);
        mHeader.setOrientation(LinearLayout.HORIZONTAL);
        mHeader.setGravity(Gravity.CENTER_VERTICAL);
        mHeader.setBackgroundColor(0xCC1A1A1A);

        // grip + title = the drag handle: drag it to move the pane, tap it to focus
        LinearLayout handle = new LinearLayout(activity);
        handle.setOrientation(LinearLayout.HORIZONTAL);
        handle.setGravity(Gravity.CENTER_VERTICAL);
        mGrip = new IconButton(activity, IconButton.GRIP);
        handle.addView(mGrip, new LinearLayout.LayoutParams(Math.round(22 * dp), LayoutParams.MATCH_PARENT));
        mTitle = new TextView(activity);
        mTitle.setTextSize(10);
        mTitle.setTextColor(0xFFDDDDDD);
        mTitle.setSingleLine(true);
        mTitle.setEllipsize(TextUtils.TruncateAt.END);
        handle.addView(mTitle, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        handle.setOnTouchListener((v, ev) -> onHandleTouch(ev));
        mHeader.addView(handle, new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f));

        mSplitRight = new IconButton(activity, IconButton.SPLIT_RIGHT);
        mSplitRight.setContentDescription(activity.getString(R.string.action_split_right));
        mSplitRight.setOnClickListener(v -> mManager.split(this, SplitNode.HORIZONTAL));
        mSplitDown = new IconButton(activity, IconButton.SPLIT_DOWN);
        mSplitDown.setContentDescription(activity.getString(R.string.action_split_down));
        mSplitDown.setOnClickListener(v -> mManager.split(this, SplitNode.VERTICAL));
        mClose = new IconButton(activity, IconButton.CLOSE);
        mClose.setContentDescription(activity.getString(R.string.action_close_pane));
        mClose.setOnClickListener(v -> mManager.close(this));
        int buttonWidth = Math.round(26 * dp);
        mHeader.addView(mSplitRight, new LinearLayout.LayoutParams(buttonWidth, LayoutParams.MATCH_PARENT));
        mHeader.addView(mSplitDown, new LinearLayout.LayoutParams(buttonWidth, LayoutParams.MATCH_PARENT));
        mHeader.addView(mClose, new LinearLayout.LayoutParams(buttonWidth, LayoutParams.MATCH_PARENT));

        LayoutParams headerParams = new LayoutParams(LayoutParams.MATCH_PARENT, headerHeight);
        headerParams.gravity = Gravity.TOP;
        addView(mHeader, headerParams);
        mHeader.setVisibility(GONE);
        setWillNotDraw(false);
    }

    public TerminalView getTerminalView() {
        return view;
    }

    public TermuxTerminalViewClient getClient() {
        return mClient;
    }

    public TerminalSession getSession() {
        return view.getCurrentSession();
    }

    public int getPaneColor() {
        return mColor;
    }

    void setFocused(boolean focused) {
        mFocused = focused;
        invalidate();
    }

    void setPaneColor(int color) {
        mColor = color;
        invalidate();
    }

    void updateChrome(boolean multiPane, boolean showTitles) {
        mMultiPane = multiPane;
        mHeader.setVisibility(multiPane ? VISIBLE : GONE);
        mTitle.setVisibility(showTitles ? VISIBLE : GONE);
        boolean canSplit = mManager.count() < PaneManager.MAX_PANES && !mManager.getActivity().getProperties().isSplitScreenDisabled();
        mSplitRight.setDim(!canSplit);
        mSplitDown.setDim(!canSplit);
        LayoutParams params = (LayoutParams) view.getLayoutParams();
        params.topMargin = multiPane ? mHeader.getLayoutParams().height : 0;
        view.setLayoutParams(params);
        invalidate();
    }

    public void updateTitle() {
        TerminalSession session = getSession();
        String name = session == null ? null : session.mSessionName;
        String title = session == null ? null : session.getTitle();
        String text = !TextUtils.isEmpty(name) ? name : (!TextUtils.isEmpty(title) ? title : "bash");
        mTitle.setText(text);
    }

    public CharSequence getTitleText() {
        return mTitle.getText();
    }

    /** Drag the header to move the pane (see SplitLayout drop zones); a plain tap just focuses it. */
    private boolean onHandleTouch(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDownX = ev.getRawX();
                mDownY = ev.getRawY();
                mDragging = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!mDragging && mManager.count() > 1) {
                    float slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
                    if (Math.hypot(ev.getRawX() - mDownX, ev.getRawY() - mDownY) > slop) {
                        mDragging = true;
                        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                        mManager.startDrag(this);
                    }
                }
                if (mDragging) mManager.dragTo(ev.getRawX(), ev.getRawY());
                return true;
            case MotionEvent.ACTION_UP:
                if (mDragging) mManager.endDrag(true); else mManager.focus(this);
                mDragging = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (mDragging) mManager.endDrag(false);
                mDragging = false;
                return true;
            default:
                return true;
        }
    }

    /** god-termux: the first tap on an unfocused pane only moves focus — no click or selection reaches the terminal. */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        boolean onHeader = mHeader.getVisibility() == VISIBLE && ev.getY() < mHeader.getHeight();
        if (!mFocused && action == MotionEvent.ACTION_DOWN && !onHeader) {
            mSwallowTouch = true;
            mManager.focus(this);
            return true;
        }
        if (mSwallowTouch) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) mSwallowTouch = false;
            return true;
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (!mMultiPane) return;
        mBorderPaint.setColor(mColor);
        mBorderPaint.setAlpha(mFocused ? 255 : 70);
        float inset = mBorderWidth / 2f;
        canvas.drawRect(inset, inset, getWidth() - inset, getHeight() - inset, mBorderPaint);
    }


    /** Font-independent header icon, drawn with strokes. */
    private static final class IconButton extends View {
        static final int GRIP = 0, SPLIT_RIGHT = 1, SPLIT_DOWN = 2, CLOSE = 3;
        private final int mKind;
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float mDp;
        private boolean mDim;

        IconButton(android.content.Context context, int kind) {
            super(context);
            mKind = kind;
            mDp = context.getResources().getDisplayMetrics().density;
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeWidth(1.2f * mDp);
            mPaint.setStrokeCap(Paint.Cap.ROUND);
            mPaint.setColor(0xFFDDDDDD);
            if (kind != GRIP) setClickable(true);
        }

        void setDim(boolean dim) {
            mDim = dim;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), cx = w / 2f, cy = h / 2f, r = 4.5f * mDp;
            mPaint.setAlpha(mDim ? 90 : 255);
            switch (mKind) {
                case GRIP:
                    for (int i = -1; i <= 1; i++) c.drawLine(cx - 4 * mDp, cy + i * 2.5f * mDp, cx + 4 * mDp, cy + i * 2.5f * mDp, mPaint);
                    break;
                case SPLIT_RIGHT:
                    c.drawRoundRect(new RectF(cx - r, cy - r, cx + r, cy + r), 2 * mDp, 2 * mDp, mPaint);
                    c.drawLine(cx, cy - r, cx, cy + r, mPaint);
                    break;
                case SPLIT_DOWN:
                    c.drawRoundRect(new RectF(cx - r, cy - r, cx + r, cy + r), 2 * mDp, 2 * mDp, mPaint);
                    c.drawLine(cx - r, cy, cx + r, cy, mPaint);
                    break;
                default:
                    float d = 3.5f * mDp;
                    c.drawLine(cx - d, cy - d, cx + d, cy + d, mPaint);
                    c.drawLine(cx - d, cy + d, cx + d, cy - d, mPaint);
            }
        }
    }

}

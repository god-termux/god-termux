package com.termux.mod.split;

import android.annotation.SuppressLint;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
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
    private final TextView mMenuButton;
    private final Paint mBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float mBorderWidth;
    private int mColor = 0xFF4FC3F7;
    private boolean mFocused;
    private boolean mMultiPane;
    private boolean mSwallowTouch;

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

        int headerHeight = Math.round(22 * dp);
        mHeader = new LinearLayout(activity);
        mHeader.setOrientation(LinearLayout.HORIZONTAL);
        mHeader.setGravity(Gravity.CENTER_VERTICAL);
        mHeader.setBackgroundColor(0xCC1A1A1A);
        mHeader.setPadding(Math.round(8 * dp), 0, Math.round(2 * dp), 0);

        mTitle = new TextView(activity);
        mTitle.setTextSize(10);
        mTitle.setTextColor(0xFFDDDDDD);
        mTitle.setSingleLine(true);
        mTitle.setEllipsize(TextUtils.TruncateAt.END);
        mHeader.addView(mTitle, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        mMenuButton = new TextView(activity);
        mMenuButton.setText("⋮");
        mMenuButton.setTextSize(14);
        mMenuButton.setTextColor(0xFFDDDDDD);
        mMenuButton.setGravity(Gravity.CENTER);
        mMenuButton.setOnClickListener(v -> showMenu());
        mHeader.addView(mMenuButton, new LinearLayout.LayoutParams(headerHeight, LayoutParams.MATCH_PARENT));

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

    private void showMenu() {
        PopupMenu menu = new PopupMenu(getContext(), mMenuButton);
        menu.getMenu().add(0, 1, 0, R.string.action_split_right);
        menu.getMenu().add(0, 2, 1, R.string.action_split_down);
        menu.getMenu().add(0, 3, 2, R.string.action_swap_pane);
        menu.getMenu().add(0, 4, 3, R.string.action_close_pane);
        boolean canSplit = mManager.count() < PaneManager.MAX_PANES && !mManager.getActivity().getProperties().isSplitScreenDisabled();
        menu.getMenu().findItem(1).setEnabled(canSplit);
        menu.getMenu().findItem(2).setEnabled(canSplit);
        menu.getMenu().findItem(3).setEnabled(mManager.count() > 1);
        menu.getMenu().findItem(4).setEnabled(mManager.count() > 1);
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: mManager.split(this, SplitNode.HORIZONTAL); return true;
                case 2: mManager.split(this, SplitNode.VERTICAL); return true;
                case 3: mManager.swapWithNeighbor(this); return true;
                case 4: mManager.close(this); return true;
                default: return false;
            }
        });
        menu.show();
    }

    /** god-termux: the first tap on an unfocused pane only moves focus — no click or selection reaches the terminal. */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        if (!mFocused && action == MotionEvent.ACTION_DOWN) {
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

}

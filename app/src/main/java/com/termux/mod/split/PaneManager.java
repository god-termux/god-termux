package com.termux.mod.split;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.TermuxService;
import com.termux.app.terminal.TermuxTerminalSessionActivityClient;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * god-termux: owns the split tree, the panes and the focus.
 * Invariants: one session lives in at most one pane; exactly one pane is focused
 * (IME, extra keys, toolbar input and macros all follow the focus).
 */
public final class PaneManager {

    public static final int MAX_PANES = 4;
    private static final int[] PANE_COLORS = {0xFF4FC3F7, 0xFF81C784, 0xFFFFB74D, 0xFFBA68C8};
    private static final String LOG_TAG = "PaneManager";

    private final TermuxActivity mActivity;
    private SplitLayout mLayout;
    private SplitNode mTree;
    private final LinkedHashMap<Integer, PaneView> mPanes = new LinkedHashMap<>();
    private int mFocusedId = -1;
    private int mNextId = 0;

    public PaneManager(TermuxActivity activity) {
        mActivity = activity;
    }

    /** Create the single initial pane; the session is attached later in restoreState(). */
    public void init() {
        mLayout = mActivity.findViewById(R.id.split_layout);
        int id = mNextId++;
        mTree = SplitNode.leaf(id);
        createPane(id);
        mLayout.setTree(mTree);
        focus(mPanes.get(id));
    }

    public TermuxActivity getActivity() {
        return mActivity;
    }

    public SplitLayout getLayout() {
        return mLayout;
    }

    public int count() {
        return mPanes.size();
    }

    public PaneView focused() {
        PaneView pane = mPanes.get(mFocusedId);
        if (pane == null && !mPanes.isEmpty()) pane = mPanes.values().iterator().next();
        return pane;
    }

    public TerminalView focusedView() {
        PaneView pane = focused();
        return pane == null ? null : pane.view;
    }

    public PaneView paneFor(TerminalSession session) {
        if (session == null) return null;
        for (PaneView pane : mPanes.values()) if (pane.getSession() == session) return pane;
        return null;
    }

    public PaneView paneForView(TerminalView view) {
        for (PaneView pane : mPanes.values()) if (pane.view == view) return pane;
        return null;
    }

    public List<TerminalView> allViews() {
        List<TerminalView> views = new ArrayList<>();
        for (PaneView pane : mPanes.values()) views.add(pane.view);
        return views;
    }

    public List<PaneView> allPanes() {
        return new ArrayList<>(mPanes.values());
    }

    public boolean anyTerminalViewFocused() {
        for (PaneView pane : mPanes.values()) if (pane.view.hasFocus()) return true;
        return false;
    }

    /** Position of the pane in layout order (0-based), used for colors and drawer markers. */
    public int displayIndexOf(PaneView pane) {
        return mTree.leafIds().indexOf(pane.paneId);
    }

    private PaneView createPane(int id) {
        PaneView pane = new PaneView(mActivity, id, this);
        mPanes.put(id, pane);
        mLayout.addPane(pane);
        mActivity.registerForContextMenu(pane.view);
        pane.getClient().onCreate();
        TerminalView focusedView = focusedView();
        if (focusedView != null && focusedView != pane.view)
            pane.view.setTypeface(focusedView.getTypeface());
        return pane;
    }

    private void destroyPane(PaneView pane) {
        pane.view.setTerminalCursorBlinkerState(false, false);
        pane.view.attachSession(null); // session itself keeps living in the service/drawer
        mActivity.unregisterForContextMenu(pane.view);
        mLayout.removePane(pane);
        mPanes.remove(pane.paneId);
    }

    public void focus(PaneView pane) {
        if (pane == null || !mPanes.containsKey(pane.paneId)) return;
        mFocusedId = pane.paneId;
        for (PaneView p : mPanes.values()) p.setFocused(p == pane);
        if (!pane.view.hasFocus()) pane.view.requestFocus();
        if (mActivity.getTermuxTerminalExtraKeys() != null)
            mActivity.getTermuxTerminalExtraKeys().setTerminalView(pane.view);
        TermuxTerminalSessionActivityClient sessionClient = mActivity.getTermuxTerminalSessionClient();
        if (sessionClient != null) {
            sessionClient.setCurrentStoredSession();
            sessionClient.checkAndScrollToSession(pane.getSession());
            sessionClient.updateBackgroundColor();
        }
        updateTitles();
        mActivity.termuxSessionListNotifyUpdated();
        saveState();
    }

    private void updateTitles() {
        for (PaneView pane : mPanes.values()) pane.updateTitle();
    }

    public void updateChrome() {
        boolean multi = count() > 1;
        boolean showTitles = mActivity.getPreferences().shouldShowPaneTitles();
        List<Integer> ids = mTree.leafIds();
        for (PaneView pane : mPanes.values()) {
            int index = ids.indexOf(pane.paneId);
            pane.setPaneColor(PANE_COLORS[(index < 0 ? 0 : index) % PANE_COLORS.length]);
            pane.updateChrome(multi, showTitles);
            pane.updateTitle();
        }
        mLayout.invalidate();
    }

    /** Split a pane; the new pane gets a brand new session (cwd of the focused one). Focus stays put. */
    public void split(PaneView pane, int orientation) {
        if (mActivity.getProperties().isSplitScreenDisabled()) {
            mActivity.showToast(mActivity.getString(R.string.msg_split_screen_disabled), true);
            return;
        }
        if (count() >= MAX_PANES) {
            mActivity.showToast(mActivity.getString(R.string.msg_max_panes_reached), true);
            return;
        }
        if (mActivity.getTermuxService() == null) return;
        int id = mNextId++;
        PaneView fresh = createPane(id);
        if (mTree.split(pane.paneId, orientation, id, MAX_PANES) == null) {
            destroyPane(fresh);
            return;
        }
        mLayout.setTree(mTree);
        updateChrome();
        boolean created = mActivity.getTermuxTerminalSessionClient().addNewSession(false, null, fresh);
        if (!created) {
            close(fresh);
            return;
        }
        saveState();
    }

    /** Close a pane; its sibling takes the space. The session keeps living in the drawer. */
    public void close(PaneView pane) {
        if (pane == null || count() <= 1) return;
        boolean wasFocused = pane.paneId == mFocusedId;
        SplitNode replacement = mTree.close(pane.paneId);
        destroyPane(pane);
        mLayout.setTree(mTree);
        if (wasFocused && replacement != null) {
            int firstId = replacement.leafIds().get(0);
            focus(mPanes.get(firstId));
        }
        updateChrome();
        mActivity.termuxSessionListNotifyUpdated();
        saveState();
    }

    /**
     * Assign a session to a pane. If the session already lives in another pane, the two panes
     * swap their sessions. Returns true if this pane's session changed.
     */
    public boolean assign(PaneView pane, TerminalSession session) {
        if (pane == null || session == null) return false;
        PaneView other = paneFor(session);
        boolean changed;
        if (other == pane) {
            changed = false;
        } else if (other != null) {
            TerminalSession mine = pane.getSession();
            other.view.attachSession(mine);
            changed = pane.view.attachSession(session);
            other.updateTitle();
        } else {
            changed = pane.view.attachSession(session);
        }
        if (changed) {
            pane.updateTitle();
            if (pane.paneId == mFocusedId) {
                TermuxTerminalSessionActivityClient sessionClient = mActivity.getTermuxTerminalSessionClient();
                if (sessionClient != null) {
                    sessionClient.checkAndScrollToSession(session);
                    sessionClient.updateBackgroundColor();
                }
            }
            mActivity.termuxSessionListNotifyUpdated();
            saveState();
        }
        return changed;
    }

    public void swapWithNeighbor(PaneView pane) {
        PaneView neighbor = neighbor(pane, -1);
        if (neighbor == null) return;
        TerminalSession session = neighbor.getSession();
        if (session != null) assign(pane, session);
    }

    /** Spatial neighbor: direction 0=right, 1=left, 2=down, 3=up; -1 = nearest in any direction. */
    public PaneView neighbor(PaneView pane, int direction) {
        if (pane == null || count() <= 1) return null;
        float cx = pane.getLeft() + pane.getWidth() / 2f, cy = pane.getTop() + pane.getHeight() / 2f;
        PaneView best = null;
        float bestDist = Float.MAX_VALUE;
        for (PaneView p : mPanes.values()) {
            if (p == pane) continue;
            float dx = p.getLeft() + p.getWidth() / 2f - cx;
            float dy = p.getTop() + p.getHeight() / 2f - cy;
            boolean ok;
            switch (direction) {
                case 0: ok = dx > 0 && dx >= Math.abs(dy); break;
                case 1: ok = dx < 0 && -dx >= Math.abs(dy); break;
                case 2: ok = dy > 0 && dy >= Math.abs(dx); break;
                case 3: ok = dy < 0 && -dy >= Math.abs(dx); break;
                default: ok = true;
            }
            if (!ok) continue;
            float dist = dx * dx + dy * dy;
            if (dist < bestDist) {
                bestDist = dist;
                best = p;
            }
        }
        return best;
    }

    public boolean focusNeighbor(PaneView pane, int direction) {
        PaneView neighbor = neighbor(pane, direction);
        if (neighbor == null) return false;
        focus(neighbor);
        return true;
    }

    /** Pane under a point in window coordinates (used by the radial macro gesture). */
    public PaneView paneAt(float windowX, float windowY) {
        if (mLayout == null) return null;
        int[] loc = new int[2];
        mLayout.getLocationInWindow(loc);
        float x = windowX - loc[0], y = windowY - loc[1];
        for (PaneView pane : mPanes.values())
            if (x >= pane.getLeft() && x < pane.getRight() && y >= pane.getTop() && y < pane.getBottom())
                return pane;
        return null;
    }

    /** Replace sessions that vanished while the activity was away (panes must never show dead sessions). */
    public void validateSessions() {
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return;
        for (PaneView pane : new ArrayList<>(mPanes.values())) {
            TerminalSession session = pane.getSession();
            if (session != null && service.getIndexOfSession(session) >= 0) continue;
            TerminalSession free = firstFreeSession(service);
            if (free != null) assign(pane, free);
            else if (count() > 1) close(pane);
        }
    }

    private TerminalSession firstFreeSession(TermuxService service) {
        for (TermuxSession termuxSession : service.getTermuxSessions()) {
            TerminalSession session = termuxSession.getTerminalSession();
            if (session != null && paneFor(session) == null) return session;
        }
        return null;
    }

    public void onStart() {
        for (PaneView pane : mPanes.values()) pane.getClient().onStart();
    }

    public void onResume() {
        for (PaneView pane : mPanes.values()) pane.getClient().onResume();
    }

    public void onStop() {
        for (PaneView pane : mPanes.values()) pane.getClient().onStop();
    }

    public void onReloadProperties() {
        for (PaneView pane : mPanes.values()) pane.getClient().onReloadProperties();
    }

    public void onReloadActivityStyling() {
        for (PaneView pane : mPanes.values()) pane.getClient().onReloadActivityStyling();
    }

    /** Persist tree + session handles + focus so the layout survives app restarts. */
    public void saveState() {
        if (mActivity.getPreferences() == null || mTree == null) return;
        try {
            JSONObject json = new JSONObject();
            json.put("f", mFocusedId);
            json.put("n", mNextId);
            json.put("t", mTree.toJson());
            JSONObject sessions = new JSONObject();
            for (PaneView pane : mPanes.values()) {
                TerminalSession session = pane.getSession();
                sessions.put(String.valueOf(pane.paneId), session == null ? "" : session.mHandle);
            }
            json.put("s", sessions);
            mActivity.getPreferences().setSplitLayout(json.toString());
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to save split state", e);
        }
    }

    /** Rebuild the saved layout; panes whose session died are dropped. Falls back to a single pane. */
    public void restoreState() {
        TermuxTerminalSessionActivityClient sessionClient = mActivity.getTermuxTerminalSessionClient();
        TermuxService service = mActivity.getTermuxService();
        String stored = mActivity.getPreferences() == null ? null : mActivity.getPreferences().getSplitLayout();
        if (stored != null && service != null && !mActivity.getProperties().isSplitScreenDisabled()) {
            try {
                JSONObject json = new JSONObject(stored);
                SplitNode tree = SplitNode.fromJson(json.getJSONObject("t"));
                JSONObject sessions = json.getJSONObject("s");
                if (tree.leafCount() <= MAX_PANES) {
                    Map<Integer, TerminalSession> resolved = new HashMap<>();
                    for (int id : tree.leafIds()) {
                        String handle = sessions.optString(String.valueOf(id), "");
                        if (handle.isEmpty()) continue;
                        TerminalSession session = service.getTerminalSessionForHandle(handle);
                        if (session != null && !resolved.containsValue(session)) resolved.put(id, session);
                    }
                    SplitNode pruned = prune(tree, resolved);
                    if (pruned != null) {
                        for (PaneView pane : new ArrayList<>(mPanes.values())) destroyPane(pane);
                        mTree = pruned;
                        mNextId = Math.max(json.optInt("n", 0), maxLeafId(pruned) + 1);
                        for (int id : pruned.leafIds()) {
                            PaneView pane = createPane(id);
                            pane.view.attachSession(resolved.get(id));
                        }
                        mLayout.setTree(mTree);
                        int focusId = json.optInt("f", -1);
                        mFocusedId = pruned.findLeaf(focusId) != null ? focusId : pruned.leafIds().get(0);
                        updateChrome();
                        focus(focused());
                        return;
                    }
                }
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to restore split state", e);
            }
        }
        // Fallback: single pane with the stored-or-last session (legacy behaviour).
        mFocusedId = mTree.leafIds().get(0);
        updateChrome();
        if (sessionClient != null) sessionClient.setCurrentSession(sessionClient.getCurrentStoredSessionOrLast());
        focus(focused());
    }

    private static SplitNode prune(SplitNode node, Map<Integer, TerminalSession> keep) {
        if (node.isLeaf()) return keep.containsKey(node.paneId) ? node : null;
        SplitNode a = prune(node.a, keep), b = prune(node.b, keep);
        if (a == null && b == null) return null;
        if (a == null) return b;
        if (b == null) return a;
        node.a = a;
        node.b = b;
        node.clampRatios();
        return node;
    }

    private static int maxLeafId(SplitNode node) {
        int max = 0;
        for (int id : node.leafIds()) max = Math.max(max, id);
        return max;
    }

}

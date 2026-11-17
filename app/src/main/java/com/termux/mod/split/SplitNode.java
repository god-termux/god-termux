package com.termux.mod.split;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * god-termux: binary split tree. A leaf holds a pane id; a branch holds an orientation
 * (HORIZONTAL = a|b, VERTICAL = a/b), a ratio and two children. At most 4 leaves.
 * Pure java (no android deps) so the tree logic is unit-testable.
 */
public final class SplitNode {

    public static final int HORIZONTAL = 0;
    public static final int VERTICAL = 1;
    public static final float MIN_RATIO = 0.15f;
    public static final float MAX_RATIO = 0.85f;

    /** Pane id for a leaf; -1 for a branch. */
    public int paneId = -1;
    public int orientation = HORIZONTAL;
    public float ratio = 0.5f;
    public SplitNode a, b;

    public static SplitNode leaf(int paneId) {
        SplitNode node = new SplitNode();
        node.paneId = paneId;
        return node;
    }

    public static SplitNode branch(int orientation, SplitNode a, SplitNode b) {
        SplitNode node = new SplitNode();
        node.paneId = -1;
        node.orientation = orientation;
        node.a = a;
        node.b = b;
        node.clampRatios();
        return node;
    }

    public boolean isLeaf() {
        return paneId >= 0;
    }

    public int leafCount() {
        if (isLeaf()) return 1;
        return a.leafCount() + b.leafCount();
    }

    /** Pane ids in layout order (a before b). */
    public List<Integer> leafIds() {
        List<Integer> ids = new ArrayList<>();
        collectLeafIds(ids);
        return ids;
    }

    private void collectLeafIds(List<Integer> ids) {
        if (isLeaf()) ids.add(paneId);
        else { a.collectLeafIds(ids); b.collectLeafIds(ids); }
    }

    public SplitNode findLeaf(int paneId) {
        if (isLeaf()) return this.paneId == paneId ? this : null;
        SplitNode found = a.findLeaf(paneId);
        return found != null ? found : b.findLeaf(paneId);
    }

    private SplitNode findParent(int paneId) {
        if (isLeaf()) return null;
        if ((a.isLeaf() && a.paneId == paneId) || (b.isLeaf() && b.paneId == paneId)) return this;
        SplitNode found = a.findParent(paneId);
        return found != null ? found : b.findParent(paneId);
    }

    /**
     * Split the leaf {@code paneId} into a branch with a new leaf {@code newPaneId}.
     * Returns the new leaf, or null if the pane was not found or the leaf limit is reached.
     */
    public SplitNode split(int paneId, int orientation, int newPaneId, int maxLeaves) {
        SplitNode target = findLeaf(paneId);
        if (target == null || leafCount() >= maxLeaves) return null;
        SplitNode old = leaf(paneId);
        SplitNode fresh = leaf(newPaneId);
        target.paneId = -1;
        target.orientation = orientation;
        target.ratio = 0.5f;
        target.a = old;
        target.b = fresh;
        return fresh;
    }

    /**
     * Close the leaf {@code paneId}: its sibling takes its place in the tree.
     * Returns the sibling subtree now occupying the closed pane's spot, or null if this is the root leaf.
     */
    public SplitNode close(int paneId) {
        SplitNode parent = findParent(paneId);
        if (parent == null) return null;
        SplitNode sibling = (parent.a.isLeaf() && parent.a.paneId == paneId) ? parent.b : parent.a;
        parent.copyFrom(sibling);
        return sibling;
    }

    /** Exchange the pane ids of two leaves (used when a session moves between panes). */
    public void swapPanes(int id1, int id2) {
        SplitNode first = findLeaf(id1), second = findLeaf(id2);
        if (first == null || second == null) return;
        first.paneId = id2;
        second.paneId = id1;
    }

    public void clampRatios() {
        if (isLeaf()) return;
        ratio = Math.max(MIN_RATIO, Math.min(MAX_RATIO, ratio));
        a.clampRatios();
        b.clampRatios();
    }

    private void copyFrom(SplitNode other) {
        paneId = other.paneId;
        orientation = other.orientation;
        ratio = other.ratio;
        a = other.a;
        b = other.b;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        if (isLeaf()) {
            json.put("p", paneId);
        } else {
            json.put("o", orientation);
            json.put("r", ratio);
            json.put("a", a.toJson());
            json.put("b", b.toJson());
        }
        return json;
    }

    public static SplitNode fromJson(JSONObject json) throws JSONException {
        if (json == null) throw new JSONException("null split node");
        if (json.has("p")) return leaf(json.getInt("p"));
        int orientation = json.getInt("o");
        if (orientation != HORIZONTAL && orientation != VERTICAL) throw new JSONException("bad orientation: " + orientation);
        SplitNode a = fromJson(json.getJSONObject("a"));
        SplitNode b = fromJson(json.getJSONObject("b"));
        SplitNode node = branch(orientation, a, b);
        node.ratio = (float) json.optDouble("r", 0.5);
        node.clampRatios();
        return node;
    }

}

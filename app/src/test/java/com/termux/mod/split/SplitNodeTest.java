package com.termux.mod.split;

import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;

/** god-termux: split tree logic — split/close/ratio/limit/swap (pure java, no android). */
public class SplitNodeTest {

    @Test
    public void testLeafBasics() {
        SplitNode root = SplitNode.leaf(0);
        Assert.assertTrue(root.isLeaf());
        Assert.assertEquals(1, root.leafCount());
        Assert.assertEquals(Arrays.asList(0), root.leafIds());
        Assert.assertSame(root, root.findLeaf(0));
        Assert.assertNull(root.findLeaf(1));
    }

    @Test
    public void testZoneFor() {
        Assert.assertEquals(SplitNode.ZONE_CENTER, SplitNode.zoneFor(0.5f, 0.5f));
        Assert.assertEquals(SplitNode.ZONE_LEFT, SplitNode.zoneFor(0.05f, 0.5f));
        Assert.assertEquals(SplitNode.ZONE_RIGHT, SplitNode.zoneFor(0.95f, 0.4f));
        Assert.assertEquals(SplitNode.ZONE_TOP, SplitNode.zoneFor(0.5f, 0.1f));
        Assert.assertEquals(SplitNode.ZONE_BOTTOM, SplitNode.zoneFor(0.6f, 0.9f));
        // corner: nearest edge wins
        Assert.assertEquals(SplitNode.ZONE_LEFT, SplitNode.zoneFor(0.1f, 0.2f));
        Assert.assertEquals(SplitNode.ZONE_TOP, SplitNode.zoneFor(0.2f, 0.1f));
    }

    @Test
    public void testDockSidesAndSwap() {
        // 0 | 1  -> dock 0 on the bottom of 1  => 1 / 0
        SplitNode root = SplitNode.leaf(0);
        root.split(0, SplitNode.HORIZONTAL, 1, 4);
        Assert.assertTrue(root.dock(0, 1, SplitNode.ZONE_BOTTOM));
        Assert.assertEquals(2, root.leafCount());
        Assert.assertEquals(SplitNode.VERTICAL, root.orientation);
        Assert.assertEquals(Arrays.asList(1, 0), root.leafIds());

        // dock back on the left of 1 => 0 | 1
        Assert.assertTrue(root.dock(0, 1, SplitNode.ZONE_LEFT));
        Assert.assertEquals(SplitNode.HORIZONTAL, root.orientation);
        Assert.assertEquals(Arrays.asList(0, 1), root.leafIds());

        // centre = swap
        Assert.assertTrue(root.dock(0, 1, SplitNode.ZONE_CENTER));
        Assert.assertEquals(Arrays.asList(1, 0), root.leafIds());

        // same pane / missing pane: unchanged
        Assert.assertFalse(root.dock(1, 1, SplitNode.ZONE_LEFT));
        Assert.assertFalse(root.dock(1, 9, SplitNode.ZONE_LEFT));
        Assert.assertEquals(Arrays.asList(1, 0), root.leafIds());
    }

    @Test
    public void testDockKeepsAllPanesInThreePaneTree() {
        SplitNode root = SplitNode.leaf(0);
        root.split(0, SplitNode.HORIZONTAL, 1, 4);
        root.split(1, SplitNode.VERTICAL, 2, 4);      // 0 | (1 / 2)
        Assert.assertTrue(root.dock(0, 2, SplitNode.ZONE_RIGHT)); // (1 / (2 | 0))
        Assert.assertEquals(3, root.leafCount());
        Assert.assertTrue(root.leafIds().containsAll(Arrays.asList(0, 1, 2)));
        Assert.assertEquals(Arrays.asList(1, 2, 0), root.leafIds());
        // a single leaf cannot be docked
        Assert.assertFalse(SplitNode.leaf(0).dock(0, 1, SplitNode.ZONE_LEFT));
    }

    @Test
    public void testSplit() {
        SplitNode root = SplitNode.leaf(0);
        SplitNode fresh = root.split(0, SplitNode.HORIZONTAL, 1, 4);
        Assert.assertNotNull(fresh);
        Assert.assertFalse(root.isLeaf());
        Assert.assertEquals(2, root.leafCount());
        Assert.assertEquals(Arrays.asList(0, 1), root.leafIds());
        Assert.assertEquals(SplitNode.HORIZONTAL, root.orientation);
        Assert.assertEquals(0.5f, root.ratio, 0.0001f);

        // split the second leaf vertically
        Assert.assertNotNull(root.split(1, SplitNode.VERTICAL, 2, 4));
        Assert.assertEquals(Arrays.asList(0, 1, 2), root.leafIds());
        Assert.assertEquals(3, root.leafCount());
    }

    @Test
    public void testSplitLimit() {
        SplitNode root = SplitNode.leaf(0);
        root.split(0, SplitNode.HORIZONTAL, 1, 4);
        root.split(1, SplitNode.VERTICAL, 2, 4);
        root.split(2, SplitNode.HORIZONTAL, 3, 4);
        Assert.assertEquals(4, root.leafCount());
        Assert.assertNull(root.split(3, SplitNode.VERTICAL, 4, 4));
        Assert.assertEquals(4, root.leafCount());
        // unknown pane id
        Assert.assertNull(root.split(99, SplitNode.HORIZONTAL, 5, 4));
    }

    @Test
    public void testCloseRootLeafIsNoOp() {
        SplitNode root = SplitNode.leaf(0);
        Assert.assertNull(root.close(0));
        Assert.assertTrue(root.isLeaf());
        Assert.assertNull(root.close(99));
    }

    @Test
    public void testCloseSiblingTakesPlace() {
        SplitNode root = SplitNode.leaf(0);
        root.split(0, SplitNode.HORIZONTAL, 1, 4);
        root.split(1, SplitNode.VERTICAL, 2, 4);
        // root = H(leaf0, V(leaf1, leaf2)); close leaf1 -> V node becomes leaf2
        SplitNode replacement = root.close(1);
        Assert.assertNotNull(replacement);
        Assert.assertTrue(replacement.isLeaf());
        Assert.assertEquals(2, replacement.paneId);
        Assert.assertEquals(Arrays.asList(0, 2), root.leafIds());
        Assert.assertEquals(2, root.leafCount());

        // close leaf0 -> root becomes leaf2
        replacement = root.close(0);
        Assert.assertNotNull(replacement);
        Assert.assertTrue(root.isLeaf());
        Assert.assertEquals(2, root.paneId);
    }

    @Test
    public void testCloseDeepLeafKeepsSubtree() {
        SplitNode root = SplitNode.leaf(0);
        root.split(0, SplitNode.HORIZONTAL, 1, 4);
        root.split(0, SplitNode.VERTICAL, 2, 4);
        root.split(2, SplitNode.HORIZONTAL, 3, 4);
        // root = H(V(leaf0, H(leaf2, leaf3)), leaf1); close leaf3 -> H(leaf2) collapses to leaf2
        SplitNode replacement = root.close(3);
        Assert.assertNotNull(replacement);
        Assert.assertTrue(replacement.isLeaf());
        Assert.assertEquals(2, replacement.paneId);
        Assert.assertEquals(Arrays.asList(0, 2, 1), root.leafIds());
    }

    @Test
    public void testSwapPanes() {
        SplitNode root = SplitNode.leaf(0);
        root.split(0, SplitNode.HORIZONTAL, 1, 4);
        root.split(1, SplitNode.VERTICAL, 2, 4);
        root.swapPanes(0, 2);
        Assert.assertEquals(Arrays.asList(2, 1, 0), root.leafIds());
        // swapping with a missing id is a no-op
        root.swapPanes(0, 99);
        Assert.assertEquals(Arrays.asList(2, 1, 0), root.leafIds());
    }

    @Test
    public void testRatioClamp() {
        SplitNode root = SplitNode.branch(SplitNode.HORIZONTAL, SplitNode.leaf(0), SplitNode.leaf(1));
        root.ratio = 0.99f;
        root.clampRatios();
        Assert.assertEquals(SplitNode.MAX_RATIO, root.ratio, 0.0001f);
        root.ratio = 0.01f;
        root.clampRatios();
        Assert.assertEquals(SplitNode.MIN_RATIO, root.ratio, 0.0001f);
    }

}

package com.termux.mod.split;

import org.json.JSONObject;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Arrays;

/** god-termux: split tree JSON serialization round-trips (org.json via robolectric). */
@RunWith(RobolectricTestRunner.class)
public class SplitNodeJsonTest {

    @Test
    public void testLeafRoundTrip() throws Exception {
        SplitNode root = SplitNode.leaf(3);
        SplitNode restored = SplitNode.fromJson(new JSONObject(root.toJson().toString()));
        Assert.assertTrue(restored.isLeaf());
        Assert.assertEquals(3, restored.paneId);
    }

    @Test
    public void testTreeRoundTrip() throws Exception {
        SplitNode root = SplitNode.leaf(0);
        root.split(0, SplitNode.HORIZONTAL, 1, 4);
        root.split(1, SplitNode.VERTICAL, 2, 4);
        root.split(2, SplitNode.HORIZONTAL, 3, 4);
        root.ratio = 0.7f;
        root.b.ratio = 0.3f;

        SplitNode restored = SplitNode.fromJson(new JSONObject(root.toJson().toString()));
        Assert.assertEquals(root.leafIds(), restored.leafIds());
        Assert.assertEquals(Arrays.asList(0, 1, 2, 3), restored.leafIds());
        Assert.assertEquals(SplitNode.HORIZONTAL, restored.orientation);
        Assert.assertEquals(0.7f, restored.ratio, 0.0001f);
        Assert.assertEquals(SplitNode.VERTICAL, restored.b.orientation);
        Assert.assertEquals(0.3f, restored.b.ratio, 0.0001f);
    }

    @Test
    public void testRatioClampedOnRestore() throws Exception {
        SplitNode root = SplitNode.branch(SplitNode.HORIZONTAL, SplitNode.leaf(0), SplitNode.leaf(1));
        JSONObject json = root.toJson();
        json.put("r", 0.99);
        SplitNode restored = SplitNode.fromJson(json);
        Assert.assertEquals(SplitNode.MAX_RATIO, restored.ratio, 0.0001f);
    }

    @Test
    public void testInvalidJsonRejected() {
        String[] bad = {
            "{}",                                  // neither leaf nor branch
            "{\"o\":5,\"a\":{\"p\":0},\"b\":{\"p\":1}}", // bad orientation
            "{\"o\":0,\"a\":{\"p\":0}}",           // missing child
        };
        for (String s : bad) {
            try {
                SplitNode.fromJson(new JSONObject(s));
                Assert.fail("expected JSONException for: " + s);
            } catch (Exception expected) {
                // ok
            }
        }
    }

}

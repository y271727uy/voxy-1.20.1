package com.y271727uy.voxy.client.render.gl;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class HiZLayoutPlannerTest {
    @Test
    public void productionLayoutRoundsViewportDownToPowerOfTwo() {
        HiZLayoutPlanner.Layout layout = HiZLayoutPlanner.plan(1920, 1080);

        assertEquals(1024, layout.width());
        assertEquals(1024, layout.height());
        assertEquals(10, layout.mipCount());
        assertEquals(new HiZLayoutPlanner.Level(0, 1024, 1024), layout.level(0));
        assertEquals(new HiZLayoutPlanner.Level(9, 2, 2), layout.level(9));
    }

    @Test
    public void rectangularChainClampsShortAxisAtOne() {
        HiZLayoutPlanner.Layout layout = HiZLayoutPlanner.plan(7, 1);

        assertEquals(List.of(
                new HiZLayoutPlanner.Level(0, 4, 1),
                new HiZLayoutPlanner.Level(1, 2, 1)
        ), layout.levels());
    }

    @Test
    public void oneTexelViewportRetainsOneSafeRasterLevel() {
        HiZLayoutPlanner.Layout layout = HiZLayoutPlanner.plan(1, 1);

        assertEquals(1, layout.mipCount());
        assertEquals(new HiZLayoutPlanner.Level(0, 1, 1), layout.level(0));
    }

    @Test
    public void packedDimensionsMatchScreenspaceShaderAbi() {
        HiZLayoutPlanner.Layout layout = HiZLayoutPlanner.plan(1920, 720);

        assertEquals(1024, HiZLayoutPlanner.unpackWidth(layout.packedDimensions()));
        assertEquals(512, HiZLayoutPlanner.unpackHeight(layout.packedDimensions()));
    }

    @Test
    public void rejectsInvalidOrUnpackableDimensions() {
        assertThrows(IllegalArgumentException.class, () -> HiZLayoutPlanner.plan(0, 1));
        assertThrows(IllegalArgumentException.class, () -> HiZLayoutPlanner.plan(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> HiZLayoutPlanner.plan(65_536, 1));
        assertThrows(IllegalArgumentException.class, () -> HiZLayoutPlanner.packDimensions(3, 4));
    }

    @Test
    public void levelListIsImmutable() {
        HiZLayoutPlanner.Layout layout = HiZLayoutPlanner.plan(8, 8);

        assertThrows(UnsupportedOperationException.class,
                () -> layout.levels().add(new HiZLayoutPlanner.Level(3, 1, 1)));
    }
}

package com.maxlananas.fawebim.core.test;

import com.maxlananas.fawebim.core.extent.EditSession;
import com.maxlananas.fawebim.core.function.Operations;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.mask.Masks;
import com.maxlananas.fawebim.core.pattern.Patterns;
import com.maxlananas.fawebim.core.region.Region;
import com.maxlananas.fawebim.core.session.LocalSession;
import com.maxlananas.fawebim.core.region.RegionSelector;
import com.maxlananas.fawebim.core.region.SelectorLimits;
import com.maxlananas.fawebim.core.world.BlockState;

/** Scratch probe for the smoke shape block. */
public final class HollowProbe {

    public static void main(String[] args) {
        BlockState.setRegistry(new TestBlockStateRegistry());
        com.maxlananas.fawebim.core.extent.EditSession.BlockStateRegistryHolder.set(BlockState.registry());
        TestWorld world = new TestWorld("probe");
        TestActor actor = new TestActor("Smoke", world, new BlockVector3(0, 70, 0));
        LocalSession local = actor.session();
        local.setMaxBlocksChanged(100000);
        int stone = BlockState.registry().defaultState("minecraft:stone");
        int air = BlockState.registry().air();

        for (int y = 60; y <= 62; y++) {
            for (int z = 0; z <= 15; z++) {
                for (int x = 0; x <= 15; x++) {
                    world.setBlock(x, y, z, stone);
                }
            }
        }
        RegionSelector selector = LocalSession.newSelectors(world, "cuboid");
        selector.selectPrimary(new BlockVector3(-1, 59, -1), SelectorLimits.unlimited());
        selector.selectSecondary(new BlockVector3(16, 63, 16), SelectorLimits.unlimited());
        local.setSelector(selector);
        Region region = local.getSelection(world);
        System.out.println("region volume " + region.getVolume() + " min " + region.getMinimumPoint()
                + " max " + region.getMaximumPoint());

        EditSession edit = new EditSession(world, local, "//hollow probe");
        Masks.ExtentHolder.set(edit);
        int changed = Operations.hollow(edit, region, 1, new Patterns.Single(air), new Masks.SolidMask(null));
        edit.flushQueue();
        System.out.println("hollow 1 changed " + changed);
        int left = 0;
        for (int y = 59; y <= 63; y++) {
            for (int z = -1; z <= 16; z++) {
                for (int x = -1; x <= 16; x++) {
                    if (world.getBlock(x, y, z) == stone) {
                        left++;
                    }
                }
            }
        }
        System.out.println("stone left " + left);
        StringBuilder slice = new StringBuilder();
        for (int z = -1; z <= 16; z++) {
            slice.append(world.getBlock(2, 61, z) == stone ? '#' : '.');
        }
        System.out.println("y=61 x=2 row: " + slice);
    }
}

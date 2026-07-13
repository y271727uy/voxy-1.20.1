package com.y271727uy.voxy.common.world;

import com.y271727uy.voxy.common.voxelization.VoxelizedSection;

import java.util.function.Consumer;
import java.util.function.Supplier;

public final class WorldUpdater {
    private WorldUpdater() {
    }

    public static void insertUpdate(WorldEngine engine, VoxelizedSection update) {
        if (!engine.isLive()) {
            throw new IllegalStateException("Cannot update a closed world engine");
        }
        WorldSection childForPropagation = null;
        boolean propagateExistence = false;
        try {
            for (int level = 0; level <= WorldEngine.MAX_LOD_LEVEL; level++) {
                WorldSection target = engine.acquire(level,
                        update.x() >> (level + 1),
                        update.y() >> (level + 1),
                        update.z() >> (level + 1));
                WorldSection propagatedChild = level != 0 && propagateExistence
                        ? childForPropagation : null;
                if (propagatedChild != null) childForPropagation = null;
                int currentLevel = level;
                WorldSection.UpdateResult result = updateOwnedTarget(target, propagatedChild,
                        () -> insertLevel(update, target, currentLevel, propagatedChild), engine::markDirty);
                boolean dataChanged = result.dataChanged();
                int childChange = result.childChange();
                if (childChange == 2) {
                    propagateExistence = true;
                    childForPropagation = target;
                } else {
                    propagateExistence = false;
                    if (!dataChanged) break;
                }
            }
        } finally {
            if (childForPropagation != null) {
                childForPropagation.release();
            }
        }
    }

    private static WorldSection.UpdateResult insertLevel(VoxelizedSection update, WorldSection target,
                                                          int level, WorldSection child) {
        int sourceSide = 16 >> level;
        int sectionMask = (1 << (level + 1)) - 1;
        int baseX = (update.x() & sectionMask) << (4 - level);
        int baseY = (update.y() & sectionMask) << (4 - level);
        int baseZ = (update.z() & sectionMask) << (4 - level);
        long[] values = new long[sourceSide * sourceSide * sourceSide];
        int index = 0;
        for (int y = 0; y < sourceSide; y++) {
            for (int z = 0; z < sourceSide; z++) {
                for (int x = 0; x < sourceSide; x++) {
                    values[index++] = update.get(level, x, y, z);
                }
            }
        }
        return target.updateRegion(baseX, baseY, baseZ, sourceSide, values, child);
    }

    static WorldSection.UpdateResult updateOwnedTarget(WorldSection target, WorldSection propagatedChild,
                                                        Supplier<WorldSection.UpdateResult> updater,
                                                        Consumer<WorldSection> dirtyMarker) {
        boolean retainTarget = false;
        try {
            WorldSection.UpdateResult result = updater.get();
            if (result.dataChanged() || result.childChange() != 0) dirtyMarker.accept(target);
            retainTarget = result.childChange() == 2;
            return result;
        } finally {
            try {
                if (propagatedChild != null) propagatedChild.release();
            } finally {
                if (!retainTarget) target.release();
            }
        }
    }
}

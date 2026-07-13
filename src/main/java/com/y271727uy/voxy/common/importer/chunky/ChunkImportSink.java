package com.y271727uy.voxy.common.importer.chunky;

import net.minecraft.nbt.CompoundTag;

@FunctionalInterface
public interface ChunkImportSink {
    boolean importChunk(int expectedChunkX, int expectedChunkZ, CompoundTag chunk) throws Exception;
}

package com.y271727uy.voxy.common.importer.dh;

@FunctionalInterface
public interface DhSectionSink {
    void accept(int sectionX, int sectionY, int sectionZ, long[] voxels, int nonAirCount);
}

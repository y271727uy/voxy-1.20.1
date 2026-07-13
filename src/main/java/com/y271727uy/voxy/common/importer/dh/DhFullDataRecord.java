package com.y271727uy.voxy.common.importer.dh;

import java.util.Arrays;

public record DhFullDataRecord(int x, int z, int detailLevel, int dataFormatVersion,
                               int compressionMode, byte[] data, byte[] mapping) {
    public DhFullDataRecord {
        data = Arrays.copyOf(data, data.length);
        mapping = Arrays.copyOf(mapping, mapping.length);
    }

    @Override
    public byte[] data() {
        return Arrays.copyOf(this.data, this.data.length);
    }

    @Override
    public byte[] mapping() {
        return Arrays.copyOf(this.mapping, this.mapping.length);
    }
}

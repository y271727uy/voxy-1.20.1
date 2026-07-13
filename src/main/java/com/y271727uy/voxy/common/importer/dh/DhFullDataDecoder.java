package com.y271727uy.voxy.common.importer.dh;

import com.y271727uy.voxy.common.voxelization.PackedVoxel;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

public final class DhFullDataDecoder {
    static final String BLOCK_STATE_SEPARATOR = "_DH-BSW_";
    private static final int SIDE = 64;
    private static final int CHUNK_SIDE = 16;
    private static final int MAX_MAPPING_ENTRIES = 1 << 20;
    private static final int MAX_COLUMN_RUNS = 8192;

    private final int bottomSection;
    private final int heightSections;
    private final DhMappingResolver mappingResolver;
    private final DhSectionSink sink;

    public DhFullDataDecoder(int bottomOfWorld, int worldHeight, DhMappingResolver mappingResolver,
                             DhSectionSink sink) {
        if (worldHeight <= 0) throw new IllegalArgumentException("World height must be positive");
        this.bottomSection = Math.floorDiv(bottomOfWorld, CHUNK_SIDE);
        this.heightSections = (worldHeight + CHUNK_SIDE - 1) / CHUNK_SIDE;
        this.mappingResolver = mappingResolver;
        this.sink = sink;
    }

    public int decode(DhFullDataRecord record, DhDecompressor decompressor) throws IOException {
        return decode(record, decompressor, DhImportCancellation.never());
    }

    public int decode(DhFullDataRecord record, DhDecompressor decompressor,
                      DhImportCancellation cancellation) throws IOException {
        cancellation.throwIfCancelled();
        if (record.detailLevel() != 0) {
            throw new IOException("Only Distant Horizons detail level 0 is supported");
        }
        if (record.dataFormatVersion() != 1) {
            throw new IOException("Unsupported Distant Horizons data format: " + record.dataFormatVersion());
        }
        if (record.compressionMode() != 3 && record.compressionMode() != 4) {
            throw new IOException("Unsupported Distant Horizons compression mode: " + record.compressionMode());
        }

        long[] mappings;
        try (InputStream input = decompressor.decompress(record.compressionMode(), record.mapping())) {
            mappings = readMappings(input, cancellation);
        }
        cancellation.throwIfCancelled();
        try (InputStream input = decompressor.decompress(record.compressionMode(), record.data())) {
            readColumns(record.x(), record.z(), input, mappings, cancellation);
        }
        return 16;
    }

    long[] readMappings(InputStream input, DhImportCancellation cancellation) throws IOException {
        DataInputStream data = new DataInputStream(input);
        int count = data.readInt();
        if (count < 0 || count > MAX_MAPPING_ENTRIES) {
            throw new IOException("Invalid Distant Horizons mapping count: " + count);
        }
        long[] mappings = new long[count];
        for (int index = 0; index < count; index++) {
            cancellation.throwIfCancelled();
            String encoded = data.readUTF();
            int separator = encoded.indexOf(BLOCK_STATE_SEPARATOR);
            if (separator <= 0) {
                throw new IOException("Invalid Distant Horizons mapping entry at index " + index);
            }
            String biome = encoded.substring(0, separator);
            String blockState = encoded.substring(separator + BLOCK_STATE_SEPARATOR.length());
            if (blockState.isEmpty()) {
                throw new IOException("Empty block state in Distant Horizons mapping at index " + index);
            }
            mappings[index] = this.mappingResolver.resolve(biome, blockState);
        }
        return mappings;
    }

    private void readColumns(int recordX, int recordZ, InputStream input, long[] mappings,
                             DhImportCancellation cancellation) throws IOException {
        DataInputStream data = new DataInputStream(input);
        int heightBlocks = this.heightSections * CHUNK_SIDE;
        long[] volume = new long[SIDE * SIDE * heightBlocks];
        for (int x = 0; x < SIDE; x++) {
            for (int z = 0; z < SIDE; z++) {
                cancellation.throwIfCancelled();
                int runCount;
                try {
                    runCount = data.readUnsignedShort();
                } catch (EOFException exception) {
                    throw new IOException("Truncated Distant Horizons column at " + x + "," + z, exception);
                }
                if (runCount > MAX_COLUMN_RUNS) {
                    throw new IOException("Distant Horizons column run count exceeds limit: " + runCount);
                }
                for (int run = 0; run < runCount; run++) {
                    long packed = data.readLong();
                    int mappingId = (int) (packed & Integer.MAX_VALUE);
                    if (mappingId < 0 || mappingId >= mappings.length) {
                        throw new IOException("Distant Horizons mapping id out of range: " + mappingId);
                    }
                    int height = (int) ((packed >>> 32) & 0xFFF);
                    int minimum = (int) ((packed >>> 44) & 0xFFF);
                    int skyLight = (int) ((packed >>> 56) & 0xF);
                    int blockLight = (int) ((packed >>> 60) & 0xF);
                    int start = Math.min(minimum, heightBlocks);
                    int end = Math.min(minimum + height, heightBlocks);
                    long voxel = PackedVoxel.withLight(mappings[mappingId], (blockLight << 4) | skyLight);
                    for (int y = start; y < end; y++) {
                        volume[(y * SIDE + z) * SIDE + x] = voxel;
                    }
                }
            }
        }
        emitSections(recordX, recordZ, volume, cancellation);
    }

    private void emitSections(int recordX, int recordZ, long[] volume,
                              DhImportCancellation cancellation) throws DhImportCancelledException {
        for (int chunkX = 0; chunkX < 4; chunkX++) {
            for (int chunkZ = 0; chunkZ < 4; chunkZ++) {
                for (int sectionY = 0; sectionY < this.heightSections; sectionY++) {
                    cancellation.throwIfCancelled();
                    long[] voxels = new long[CHUNK_SIDE * CHUNK_SIDE * CHUNK_SIDE];
                    int nonAir = 0;
                    int target = 0;
                    for (int y = 0; y < CHUNK_SIDE; y++) {
                        int sourceY = sectionY * CHUNK_SIDE + y;
                        for (int z = 0; z < CHUNK_SIDE; z++) {
                            int sourceZ = chunkZ * CHUNK_SIDE + z;
                            for (int x = 0; x < CHUNK_SIDE; x++) {
                                int sourceX = chunkX * CHUNK_SIDE + x;
                                long voxel = volume[(sourceY * SIDE + sourceZ) * SIDE + sourceX];
                                voxels[target++] = voxel;
                                if (!PackedVoxel.isAir(voxel)) nonAir++;
                            }
                        }
                    }
                    cancellation.throwIfCancelled();
                    this.sink.accept(recordX * 4 + chunkX, this.bottomSection + sectionY,
                            recordZ * 4 + chunkZ, voxels, nonAir);
                }
            }
        }
    }
}

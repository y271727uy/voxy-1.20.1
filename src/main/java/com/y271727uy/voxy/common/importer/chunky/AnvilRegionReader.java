package com.y271727uy.voxy.common.importer.chunky;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

public final class AnvilRegionReader {
    static final int SECTOR_BYTES = 4096;
    static final int HEADER_BYTES = SECTOR_BYTES * 2;
    static final int CHUNK_COUNT = 1024;
    private static final int MAX_COMPRESSED_BYTES = 16 * 1024 * 1024;
    private static final int MAX_DECOMPRESSED_BYTES = 64 * 1024 * 1024;

    public List<ChunkLocation> scan(RegionSource.RegionFile region) throws IOException {
        verifyFile(region.path());
        try (FileChannel channel = FileChannel.open(region.path(), StandardOpenOption.READ)) {
            if (channel.size() < HEADER_BYTES) {
                throw new IOException("Truncated region header: " + region.path());
            }
            ByteBuffer header = ByteBuffer.allocate(SECTOR_BYTES).order(ByteOrder.BIG_ENDIAN);
            readFully(channel, header, 0);
            header.flip();
            List<ChunkLocation> chunks = new ArrayList<>();
            for (int index = 0; index < CHUNK_COUNT; index++) {
                int location = header.getInt();
                if (location != 0) {
                    chunks.add(new ChunkLocation(region, index, location >>> 8, location & 0xff));
                }
            }
            rejectSectorOverlap(chunks);
            return List.copyOf(chunks);
        }
    }

    public CompoundTag read(ChunkLocation chunk) throws IOException {
        Path path = chunk.region().path();
        verifyFile(path);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            long fileSize = channel.size();
            if (chunk.sectorOffset() < 2 || chunk.sectorCount() <= 0) {
                throw new IOException("Invalid sector allocation for " + chunk.describe());
            }
            long start = Math.multiplyExact((long) chunk.sectorOffset(), SECTOR_BYTES);
            long allocation = Math.multiplyExact((long) chunk.sectorCount(), SECTOR_BYTES);
            if (start > fileSize || allocation > fileSize - start || allocation < 5) {
                throw new IOException("Chunk allocation exceeds region file for " + chunk.describe());
            }
            ByteBuffer prefix = ByteBuffer.allocate(5).order(ByteOrder.BIG_ENDIAN);
            readFully(channel, prefix, start);
            prefix.flip();
            int storedLength = prefix.getInt();
            int compression = prefix.get() & 0xff;
            boolean external = (compression & 0x80) != 0;
            compression &= 0x7f;
            int payloadLength = storedLength - 1;
            if (payloadLength < 0 || payloadLength > allocation - 5 || payloadLength > MAX_COMPRESSED_BYTES) {
                throw new IOException("Invalid chunk payload length for " + chunk.describe() + ": " + payloadLength);
            }
            byte[] payload;
            if (external) {
                if (payloadLength != 0) {
                    throw new IOException("External chunk has an internal payload for " + chunk.describe());
                }
                payload = readExternalPayload(chunk);
            } else {
                ByteBuffer stored = ByteBuffer.allocate(payloadLength);
                readFully(channel, stored, start + 5);
                payload = stored.array();
            }
            byte[] decoded = decompress(compression, payload);
            try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(decoded))) {
                return NbtIo.read(input);
            } catch (RuntimeException exception) {
                throw new IOException("Invalid chunk NBT for " + chunk.describe(), exception);
            }
        }
    }

    private static byte[] readExternalPayload(ChunkLocation chunk) throws IOException {
        Path parent = chunk.region().path().toAbsolutePath().normalize().getParent();
        if (parent == null) {
            throw new IOException("Region file has no parent directory for " + chunk.describe());
        }
        Path external = parent.resolve("c." + chunk.chunkX() + "." + chunk.chunkZ() + ".mcc")
                .toAbsolutePath().normalize();
        if (!external.getParent().equals(parent) || Files.isSymbolicLink(external)
                || !Files.isRegularFile(external, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Unsafe or missing external chunk stream for " + chunk.describe());
        }
        long size = Files.size(external);
        if (size < 0 || size > MAX_COMPRESSED_BYTES) {
            throw new IOException("External chunk stream exceeds compressed size limit for " + chunk.describe());
        }
        try (FileChannel channel = FileChannel.open(external, StandardOpenOption.READ)) {
            ByteBuffer payload = ByteBuffer.allocate((int) size);
            readFully(channel, payload, 0);
            if (channel.size() > MAX_COMPRESSED_BYTES) {
                throw new IOException("External chunk stream grew beyond compressed size limit for " + chunk.describe());
            }
            return payload.array();
        }
    }

    private static void rejectSectorOverlap(List<ChunkLocation> chunks) throws IOException {
        List<ChunkLocation> allocated = chunks.stream()
                .filter(chunk -> chunk.sectorCount() > 0)
                .sorted(Comparator.comparingInt(ChunkLocation::sectorOffset))
                .toList();
        long previousEnd = 2;
        for (ChunkLocation chunk : allocated) {
            long start = chunk.sectorOffset();
            long end = start + chunk.sectorCount();
            if (start < previousEnd) {
                throw new IOException("Overlapping chunk sectors in " + chunk.region().path());
            }
            previousEnd = end;
        }
    }

    private static byte[] decompress(int compression, byte[] payload) throws IOException {
        InputStream raw = new ByteArrayInputStream(payload);
        InputStream decoded = switch (compression) {
            case 1 -> new GZIPInputStream(raw);
            case 2 -> new InflaterInputStream(raw);
            case 3 -> raw;
            default -> throw new IOException("Unsupported Anvil compression type: " + compression);
        };
        try (decoded; ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(payload.length * 2, 65536))) {
            byte[] buffer = new byte[8192];
            int total = 0;
            for (int count; (count = decoded.read(buffer)) != -1; ) {
                total = Math.addExact(total, count);
                if (total > MAX_DECOMPRESSED_BYTES) {
                    throw new IOException("Decompressed chunk exceeds " + MAX_DECOMPRESSED_BYTES + " bytes");
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static void readFully(FileChannel channel, ByteBuffer target, long position) throws IOException {
        while (target.hasRemaining()) {
            int count = channel.read(target, position + target.position());
            if (count < 0) {
                throw new EOFException("Unexpected end of region file");
            }
            if (count == 0) {
                throw new IOException("Region file read made no progress");
            }
        }
    }

    private static void verifyFile(Path path) throws IOException {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Unsafe or missing region file: " + path);
        }
    }

    public record ChunkLocation(RegionSource.RegionFile region, int index, int sectorOffset, int sectorCount) {
        public ChunkLocation {
            if (index < 0 || index >= CHUNK_COUNT) {
                throw new IllegalArgumentException("Chunk index outside region: " + index);
            }
        }

        public int chunkX() {
            return Math.addExact(Math.multiplyExact(region.regionX(), 32), index & 31);
        }

        public int chunkZ() {
            return Math.addExact(Math.multiplyExact(region.regionZ(), 32), index >>> 5);
        }

        public String describe() {
            return region.path().getFileName() + " chunk [" + chunkX() + ", " + chunkZ() + "]";
        }
    }
}

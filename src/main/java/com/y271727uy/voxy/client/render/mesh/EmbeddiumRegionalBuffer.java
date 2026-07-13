package com.y271727uy.voxy.client.render.mesh;

import com.mojang.blaze3d.systems.RenderSystem;
import me.jellysquid.mods.sodium.client.gl.arena.GlBufferArena;
import me.jellysquid.mods.sodium.client.gl.arena.GlBufferSegment;
import me.jellysquid.mods.sodium.client.gl.arena.PendingUpload;
import me.jellysquid.mods.sodium.client.gl.arena.staging.FallbackStagingBuffer;
import me.jellysquid.mods.sodium.client.gl.arena.staging.StagingBuffer;
import me.jellysquid.mods.sodium.client.gl.attribute.GlVertexAttribute;
import me.jellysquid.mods.sodium.client.gl.attribute.GlVertexAttributeBinding;
import me.jellysquid.mods.sodium.client.gl.attribute.GlVertexAttributeFormat;
import me.jellysquid.mods.sodium.client.gl.device.CommandList;
import me.jellysquid.mods.sodium.client.gl.device.DrawCommandList;
import me.jellysquid.mods.sodium.client.gl.device.MultiDrawBatch;
import me.jellysquid.mods.sodium.client.gl.device.RenderDevice;
import me.jellysquid.mods.sodium.client.gl.tessellation.GlIndexType;
import me.jellysquid.mods.sodium.client.gl.tessellation.GlPrimitiveType;
import me.jellysquid.mods.sodium.client.gl.tessellation.GlTessellation;
import me.jellysquid.mods.sodium.client.gl.tessellation.TessellationBinding;
import me.jellysquid.mods.sodium.client.util.NativeBuffer;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Pointer;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

final class EmbeddiumRegionalBuffer implements AutoCloseable {
    static final int REGION_SIZE = 512;
    private static final int INITIAL_VERTEX_CAPACITY = 16_384;
    private static final int INITIAL_INDEX_CAPACITY = 24_576;
    private static final GlVertexAttributeBinding[] ATTRIBUTE_BINDINGS = {
            binding(0, GlVertexAttributeFormat.FLOAT, 3, false, 0, false),
            binding(1, GlVertexAttributeFormat.UNSIGNED_BYTE, 4, true, 12, false),
            binding(2, GlVertexAttributeFormat.FLOAT, 2, false, 16, false),
            binding(3, GlVertexAttributeFormat.UNSIGNED_SHORT, 2, false, 24, true)
    };

    private final Map<RegionKey, Region> regions = new HashMap<>();
    private StagingBuffer stagingBuffer;
    private long reclaimedRegions;
    private boolean closed;

    Allocation upload(EmbeddiumIndexedBuffer.EncodedMesh mesh, int sectionX, int sectionY, int sectionZ) {
        RenderSystem.assertOnRenderThread();
        if (this.closed) throw new IllegalStateException("Regional buffer pool is closed");
        RenderDevice.enterManagedCode();
        try {
            CommandList commands = RenderDevice.INSTANCE.createCommandList();
            if (this.stagingBuffer == null) {
                this.stagingBuffer = new FallbackStagingBuffer(commands);
            }
            RegionKey key = RegionKey.fromBlock(sectionX, sectionY, sectionZ);
            Region region = this.regions.computeIfAbsent(key,
                    ignored -> new Region(commands, this.stagingBuffer, key));
            return region.upload(commands, mesh);
        } finally {
            RenderDevice.exitManagedCode();
        }
    }

    void draw(Region region, Collection<Allocation> allocations, Matrix4f modelView, Matrix4f projection,
              VoxyGeometryProgram program) {
        RenderSystem.assertOnRenderThread();
        RenderDevice.enterManagedCode();
        try {
            program.bind(modelView, projection);
            CommandList commands = RenderDevice.INSTANCE.createCommandList();
            MultiDrawBatch batch = region.prepareBatch(allocations.size());
            batch.clear();
            int command = 0;
            for (Allocation allocation : allocations) {
                MemoryUtil.memPutAddress(batch.pElementPointer
                        + (long) command * Pointer.POINTER_SIZE,
                        (long) allocation.indexSegment.getOffset() * Integer.BYTES);
                MemoryUtil.memPutInt(batch.pElementCount + (long) command * Integer.BYTES,
                        allocation.indexCount);
                MemoryUtil.memPutInt(batch.pBaseVertex + (long) command * Integer.BYTES,
                        allocation.vertexSegment.getOffset());
                command++;
            }
            batch.size = command;
            try (DrawCommandList draws = commands.beginTessellating(region.tessellation)) {
                draws.multiDrawElementsBaseVertex(batch, GlIndexType.UNSIGNED_INT);
            } finally {
                program.unbind();
            }
        } finally {
            RenderDevice.exitManagedCode();
        }
    }

    int regionCount() {
        return this.regions.size();
    }

    long allocatedBytes() {
        long bytes = 0;
        for (Region region : this.regions.values()) {
            bytes += region.vertexArena.getDeviceAllocatedMemoryL();
            bytes += region.indexArena.getDeviceAllocatedMemoryL();
        }
        return bytes;
    }

    long reclaimedRegions() {
        return this.reclaimedRegions;
    }

    private void release(Allocation allocation) {
        if (allocation.closed) return;
        RenderSystem.assertOnRenderThread();
        RenderDevice.enterManagedCode();
        try {
            allocation.indexSegment.delete();
            allocation.vertexSegment.delete();
            allocation.closed = true;
            Region region = allocation.region;
            region.allocations--;
            if (region.allocations == 0) {
                CommandList commands = RenderDevice.INSTANCE.createCommandList();
                this.regions.remove(region.key, region);
                region.delete(commands);
                this.reclaimedRegions++;
            }
        } finally {
            RenderDevice.exitManagedCode();
        }
    }

    @Override
    public void close() {
        if (this.closed) return;
        RenderSystem.assertOnRenderThread();
        RenderDevice.enterManagedCode();
        try {
            CommandList commands = RenderDevice.INSTANCE.createCommandList();
            for (Region region : this.regions.values()) region.delete(commands);
            this.regions.clear();
            if (this.stagingBuffer != null) {
                this.stagingBuffer.delete(commands);
                this.stagingBuffer = null;
            }
            this.closed = true;
        } finally {
            RenderDevice.exitManagedCode();
        }
    }

    static RegionKey regionKey(int blockX, int blockY, int blockZ) {
        return RegionKey.fromBlock(blockX, blockY, blockZ);
    }

    record RegionKey(int x, int y, int z) {
        static RegionKey fromBlock(int blockX, int blockY, int blockZ) {
            return new RegionKey(Math.floorDiv(blockX, REGION_SIZE),
                    Math.floorDiv(blockY, REGION_SIZE), Math.floorDiv(blockZ, REGION_SIZE));
        }

        int originX() { return this.x * REGION_SIZE; }
        int originY() { return this.y * REGION_SIZE; }
        int originZ() { return this.z * REGION_SIZE; }
    }

    static final class Allocation implements AutoCloseable {
        private final EmbeddiumRegionalBuffer owner;
        private final Region region;
        private final GlBufferSegment vertexSegment;
        private final GlBufferSegment indexSegment;
        private final int indexCount;
        private final long byteSize;
        private boolean closed;

        private Allocation(EmbeddiumRegionalBuffer owner, Region region,
                           GlBufferSegment vertexSegment, GlBufferSegment indexSegment,
                           int indexCount, long byteSize) {
            this.owner = owner;
            this.region = region;
            this.vertexSegment = vertexSegment;
            this.indexSegment = indexSegment;
            this.indexCount = indexCount;
            this.byteSize = byteSize;
        }

        Region region() { return this.region; }
        RegionKey regionKey() { return this.region.key; }
        long byteSize() { return this.byteSize; }

        @Override
        public void close() {
            this.owner.release(this);
        }
    }

    final class Region {
        private final RegionKey key;
        private final GlBufferArena vertexArena;
        private final GlBufferArena indexArena;
        private GlTessellation tessellation;
        private MultiDrawBatch batch;
        private int allocations;

        private Region(CommandList commands, StagingBuffer stagingBuffer, RegionKey key) {
            this.key = key;
            this.vertexArena = new GlBufferArena(commands, INITIAL_VERTEX_CAPACITY,
                    EmbeddiumIndexedBuffer.VERTEX_STRIDE, stagingBuffer);
            this.indexArena = new GlBufferArena(commands, INITIAL_INDEX_CAPACITY,
                    Integer.BYTES, stagingBuffer);
            this.rebuildTessellation(commands);
        }

        private Allocation upload(CommandList commands, EmbeddiumIndexedBuffer.EncodedMesh mesh) {
            NativeBuffer vertexData = NativeBuffer.copy(mesh.vertices());
            NativeBuffer indexData = NativeBuffer.copy(mesh.indices());
            PendingUpload vertexUpload = new PendingUpload(vertexData);
            PendingUpload indexUpload = new PendingUpload(indexData);
            try {
                boolean vertexChanged = this.vertexArena.upload(commands, Stream.of(vertexUpload));
                boolean indexChanged = this.indexArena.upload(commands, Stream.of(indexUpload));
                if (vertexChanged || indexChanged) this.rebuildTessellation(commands);
                this.allocations++;
                return new Allocation(EmbeddiumRegionalBuffer.this, this,
                        vertexUpload.getResult(), indexUpload.getResult(),
                        mesh.indexCount(), mesh.vertices().remaining() + (long) mesh.indices().remaining());
            } catch (RuntimeException | Error failure) {
                tryDelete(vertexUpload);
                tryDelete(indexUpload);
                throw failure;
            } finally {
                indexData.free();
                vertexData.free();
            }
        }

        private MultiDrawBatch prepareBatch(int required) {
            if (this.batch == null || this.batch.capacity() < required) {
                if (this.batch != null) this.batch.delete();
                this.batch = new MultiDrawBatch(Math.max(16, Integer.highestOneBit(required - 1) << 1));
            }
            return this.batch;
        }

        private void rebuildTessellation(CommandList commands) {
            if (this.tessellation != null) commands.deleteTessellation(this.tessellation);
            this.tessellation = commands.createTessellation(GlPrimitiveType.TRIANGLES,
                    new TessellationBinding[]{
                            TessellationBinding.forVertexBuffer(this.vertexArena.getBufferObject(), ATTRIBUTE_BINDINGS),
                            TessellationBinding.forElementBuffer(this.indexArena.getBufferObject())
                    });
        }

        private void delete(CommandList commands) {
            if (this.batch != null) this.batch.delete();
            commands.deleteTessellation(this.tessellation);
            this.indexArena.delete(commands);
            this.vertexArena.delete(commands);
        }

        private static void tryDelete(PendingUpload upload) {
            try {
                upload.getResult().delete();
            } catch (IllegalStateException ignored) {
                // No arena segment was assigned.
            }
        }
    }

    private static GlVertexAttributeBinding binding(int index, GlVertexAttributeFormat format, int count,
                                                    boolean normalized, int offset, boolean integer) {
        return new GlVertexAttributeBinding(index, new GlVertexAttribute(format, count, normalized,
                offset, EmbeddiumIndexedBuffer.VERTEX_STRIDE, integer));
    }
}

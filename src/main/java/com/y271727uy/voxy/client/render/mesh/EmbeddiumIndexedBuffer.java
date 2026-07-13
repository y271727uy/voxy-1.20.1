package com.y271727uy.voxy.client.render.mesh;

import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.y271727uy.voxy.client.model.BakedBlockModel;
import me.jellysquid.mods.sodium.client.gl.attribute.GlVertexAttribute;
import me.jellysquid.mods.sodium.client.gl.attribute.GlVertexAttributeBinding;
import me.jellysquid.mods.sodium.client.gl.attribute.GlVertexAttributeFormat;
import me.jellysquid.mods.sodium.client.gl.buffer.GlBufferUsage;
import me.jellysquid.mods.sodium.client.gl.buffer.GlMutableBuffer;
import me.jellysquid.mods.sodium.client.gl.device.CommandList;
import me.jellysquid.mods.sodium.client.gl.device.DrawCommandList;
import me.jellysquid.mods.sodium.client.gl.device.MultiDrawBatch;
import me.jellysquid.mods.sodium.client.gl.device.RenderDevice;
import me.jellysquid.mods.sodium.client.gl.tessellation.GlIndexType;
import me.jellysquid.mods.sodium.client.gl.tessellation.GlPrimitiveType;
import me.jellysquid.mods.sodium.client.gl.tessellation.GlTessellation;
import me.jellysquid.mods.sodium.client.gl.tessellation.TessellationBinding;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class EmbeddiumIndexedBuffer implements AutoCloseable {
    static final int VERTEX_STRIDE = 28;
    private static final int INDICES_PER_QUAD = 6;
    private static final int[] QUAD_INDICES = {0, 1, 2, 2, 3, 0};
    private static final GlVertexAttributeBinding[] ATTRIBUTE_BINDINGS = {
            binding(0, GlVertexAttributeFormat.FLOAT, 3, false, 0, false),
            binding(1, GlVertexAttributeFormat.UNSIGNED_BYTE, 4, true, 12, false),
            binding(2, GlVertexAttributeFormat.FLOAT, 2, false, 16, false),
            binding(3, GlVertexAttributeFormat.UNSIGNED_SHORT, 2, false, 24, true)
    };

    private final GlMutableBuffer vertexBuffer;
    private final GlMutableBuffer indexBuffer;
    private final GlTessellation tessellation;
    private final MultiDrawBatch drawBatch;
    private final long byteSize;
    private boolean closed;

    private EmbeddiumIndexedBuffer(GlMutableBuffer vertexBuffer, GlMutableBuffer indexBuffer,
                                   GlTessellation tessellation, MultiDrawBatch drawBatch, long byteSize) {
        this.vertexBuffer = vertexBuffer;
        this.indexBuffer = indexBuffer;
        this.tessellation = tessellation;
        this.drawBatch = drawBatch;
        this.byteSize = byteSize;
    }

    static EmbeddiumIndexedBuffer upload(EncodedMesh mesh) {
        RenderSystem.assertOnRenderThread();
        RenderDevice.enterManagedCode();
        GlMutableBuffer vertices = null;
        GlMutableBuffer indices = null;
        GlTessellation tessellation = null;
        MultiDrawBatch batch = null;
        try {
            CommandList commands = RenderDevice.INSTANCE.createCommandList();
            vertices = commands.createMutableBuffer();
            indices = commands.createMutableBuffer();
            commands.uploadData(vertices, mesh.vertices(), GlBufferUsage.STATIC_DRAW);
            commands.uploadData(indices, mesh.indices(), GlBufferUsage.STATIC_DRAW);
            tessellation = commands.createTessellation(GlPrimitiveType.TRIANGLES, new TessellationBinding[]{
                    TessellationBinding.forVertexBuffer(vertices, ATTRIBUTE_BINDINGS),
                    TessellationBinding.forElementBuffer(indices)
            });
            batch = new MultiDrawBatch(1);
            MemoryUtil.memPutAddress(batch.pElementPointer, 0L);
            MemoryUtil.memPutInt(batch.pElementCount, mesh.indexCount());
            MemoryUtil.memPutInt(batch.pBaseVertex, 0);
            batch.size = 1;
            return new EmbeddiumIndexedBuffer(vertices, indices, tessellation, batch,
                    mesh.vertices().remaining() + (long) mesh.indices().remaining());
        } catch (RuntimeException | Error failure) {
            cleanupFailedUpload(vertices, indices, tessellation, batch);
            throw failure;
        } finally {
            RenderDevice.exitManagedCode();
        }
    }

    void draw(Matrix4f modelView, Matrix4f projection, ShaderInstance shader) {
        RenderSystem.assertOnRenderThread();
        if (this.closed) {
            throw new IllegalStateException("Cannot draw a deleted Voxy buffer");
        }
        RenderDevice.enterManagedCode();
        try {
            applyShader(shader, modelView, projection);
            CommandList commands = RenderDevice.INSTANCE.createCommandList();
            try (DrawCommandList draws = commands.beginTessellating(this.tessellation)) {
                draws.multiDrawElementsBaseVertex(this.drawBatch, GlIndexType.UNSIGNED_INT);
            } finally {
                shader.clear();
            }
        } finally {
            RenderDevice.exitManagedCode();
        }
    }

    void draw(Matrix4f modelView, Matrix4f projection, VoxyGeometryProgram program) {
        RenderSystem.assertOnRenderThread();
        if (this.closed) {
            throw new IllegalStateException("Cannot draw a deleted Voxy buffer");
        }
        RenderDevice.enterManagedCode();
        try {
            program.bind(modelView, projection);
            CommandList commands = RenderDevice.INSTANCE.createCommandList();
            try (DrawCommandList draws = commands.beginTessellating(this.tessellation)) {
                draws.multiDrawElementsBaseVertex(this.drawBatch, GlIndexType.UNSIGNED_INT);
            } finally {
                program.unbind();
            }
        } finally {
            RenderDevice.exitManagedCode();
        }
    }

    long byteSize() {
        return this.byteSize;
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        RenderSystem.assertOnRenderThread();
        RenderDevice.enterManagedCode();
        try {
            CommandList commands = RenderDevice.INSTANCE.createCommandList();
            commands.deleteTessellation(this.tessellation);
            commands.deleteBuffer(this.indexBuffer);
            commands.deleteBuffer(this.vertexBuffer);
            this.drawBatch.delete();
            this.closed = true;
        } finally {
            RenderDevice.exitManagedCode();
        }
    }

    static EncodedMesh encode(LodSectionMesh mesh, int sectionOriginX, int sectionOriginY, int sectionOriginZ) {
        return encode(mesh, sectionOriginX, sectionOriginY, sectionOriginZ,
                BakedBlockModel.MaterialPass.OPAQUE_OR_CUTOUT, null);
    }

    static EncodedMesh encode(LodSectionMesh mesh, int sectionOriginX, int sectionOriginY, int sectionOriginZ,
                              BakedBlockModel.MaterialPass pass, net.minecraft.world.phys.Vec3 sortCamera) {
        List<LodSectionMesh.QuadInstance> instances = new ArrayList<>();
        for (LodSectionMesh.QuadInstance instance : mesh.quads()) {
            if (instance.quad().materialPass() == pass) instances.add(instance);
        }
        if (pass == BakedBlockModel.MaterialPass.TRANSLUCENT && sortCamera != null) {
            instances.sort(Comparator.comparingDouble(
                    (LodSectionMesh.QuadInstance instance) -> distanceSquared(instance, sortCamera)).reversed());
        }
        int quadCount = instances.size();
        ByteBuffer vertices = MemoryUtil.memAlloc(quadCount * 4 * VERTEX_STRIDE);
        ByteBuffer indices = MemoryUtil.memAlloc(quadCount * INDICES_PER_QUAD * Integer.BYTES);
        int emittedVertices = 0;
        for (LodSectionMesh.QuadInstance instance : instances) {
            emitQuad(vertices, instance, sectionOriginX, sectionOriginY, sectionOriginZ);
            for (int index : QUAD_INDICES) {
                indices.putInt(emittedVertices + index);
            }
            emittedVertices += 4;
        }
        vertices.flip();
        indices.flip();
        return new EncodedMesh(vertices, indices, quadCount * INDICES_PER_QUAD);
    }

    private static double distanceSquared(LodSectionMesh.QuadInstance instance,
                                          net.minecraft.world.phys.Vec3 camera) {
        double centerX = instance.worldX() + instance.scale() * 0.5;
        double centerY = instance.worldY() + instance.scale() * 0.5;
        double centerZ = instance.worldZ() + instance.scale() * 0.5;
        double dx = centerX - camera.x;
        double dy = centerY - camera.y;
        double dz = centerZ - camera.z;
        return dx * dx + dy * dy + dz * dz;
    }

    private static void emitQuad(ByteBuffer output, LodSectionMesh.QuadInstance instance,
                                 int sectionOriginX, int sectionOriginY, int sectionOriginZ) {
        BakedBlockModel.Quad quad = instance.quad();
        int[] source = quad.vertices();
        int sourceStride = source.length / 4;
        if (sourceStride < 6) {
            throw new IllegalArgumentException("Baked quad vertex stride is shorter than position/color/UV");
        }
        int tint = instance.tintColor();
        float shade = faceShade(quad);
        int packedLight = instance.packedLight();
        int vanillaLight = LightTexture.pack((packedLight >>> 4) & 0xF, packedLight & 0xF);
        for (int vertex = 0; vertex < 4; vertex++) {
            int base = vertex * sourceStride;
            output.putFloat(Float.intBitsToFloat(source[base]) * instance.scale()
                    + instance.worldX() - sectionOriginX);
            output.putFloat(Float.intBitsToFloat(source[base + 1]) * instance.scale()
                    + instance.worldY() - sectionOriginY);
            output.putFloat(Float.intBitsToFloat(source[base + 2]) * instance.scale()
                    + instance.worldZ() - sectionOriginZ);
            int abgr = source[base + 3];
            float vertexShade = shade * instance.ambientOcclusion(vertex) / 255.0F;
            output.put((byte) shadedChannel(abgr & 0xFF, (tint >>> 16) & 0xFF, vertexShade));
            output.put((byte) shadedChannel((abgr >>> 8) & 0xFF, (tint >>> 8) & 0xFF, vertexShade));
            output.put((byte) shadedChannel((abgr >>> 16) & 0xFF, tint & 0xFF, vertexShade));
            output.put((byte) VertexColorUtil.multiplyAlpha(abgr, tint));
            output.putFloat(Float.intBitsToFloat(source[base + 4]));
            output.putFloat(Float.intBitsToFloat(source[base + 5]));
            output.putShort((short) (vanillaLight & 0xFFFF));
            output.putShort((short) ((vanillaLight >>> 16) & 0xFFFF));
        }
    }

    private static int shadedChannel(int source, int tint, float shade) {
        return Math.round(source * tint / 255.0F * shade);
    }

    private static float faceShade(BakedBlockModel.Quad quad) {
        if (!quad.shade() || quad.cullFace() == null) return 1.0F;
        return switch (quad.cullFace()) {
            case DOWN -> 0.5F;
            case NORTH, SOUTH -> 0.8F;
            case WEST, EAST -> 0.6F;
            default -> 1.0F;
        };
    }

    private static GlVertexAttributeBinding binding(int index, GlVertexAttributeFormat format, int count,
                                                    boolean normalized, int offset, boolean integer) {
        return new GlVertexAttributeBinding(index,
                new GlVertexAttribute(format, count, normalized, offset, VERTEX_STRIDE, integer));
    }

    private static void applyShader(ShaderInstance shader, Matrix4f modelView, Matrix4f projection) {
        for (int texture = 0; texture < 12; texture++) {
            shader.setSampler("Sampler" + texture, RenderSystem.getShaderTexture(texture));
        }
        if (shader.MODEL_VIEW_MATRIX != null) shader.MODEL_VIEW_MATRIX.set(modelView);
        if (shader.PROJECTION_MATRIX != null) shader.PROJECTION_MATRIX.set(projection);
        if (shader.INVERSE_VIEW_ROTATION_MATRIX != null) {
            shader.INVERSE_VIEW_ROTATION_MATRIX.set(RenderSystem.getInverseViewRotationMatrix());
        }
        if (shader.COLOR_MODULATOR != null) shader.COLOR_MODULATOR.set(RenderSystem.getShaderColor());
        if (shader.GLINT_ALPHA != null) shader.GLINT_ALPHA.set(RenderSystem.getShaderGlintAlpha());
        if (shader.FOG_START != null) shader.FOG_START.set(RenderSystem.getShaderFogStart());
        if (shader.FOG_END != null) shader.FOG_END.set(RenderSystem.getShaderFogEnd());
        if (shader.FOG_COLOR != null) shader.FOG_COLOR.set(RenderSystem.getShaderFogColor());
        if (shader.FOG_SHAPE != null) shader.FOG_SHAPE.set(RenderSystem.getShaderFogShape().getIndex());
        if (shader.TEXTURE_MATRIX != null) shader.TEXTURE_MATRIX.set(RenderSystem.getTextureMatrix());
        if (shader.GAME_TIME != null) shader.GAME_TIME.set(RenderSystem.getShaderGameTime());
        if (shader.SCREEN_SIZE != null) {
            Window window = Minecraft.getInstance().getWindow();
            shader.SCREEN_SIZE.set((float) window.getWidth(), (float) window.getHeight());
        }
        RenderSystem.setupShaderLights(shader);
        shader.apply();
    }

    private static void cleanupFailedUpload(GlMutableBuffer vertices, GlMutableBuffer indices,
                                            GlTessellation tessellation, MultiDrawBatch batch) {
        CommandList commands = RenderDevice.INSTANCE.createCommandList();
        if (tessellation != null) commands.deleteTessellation(tessellation);
        if (indices != null) commands.deleteBuffer(indices);
        if (vertices != null) commands.deleteBuffer(vertices);
        if (batch != null) batch.delete();
    }

    record EncodedMesh(ByteBuffer vertices, ByteBuffer indices, int indexCount) implements AutoCloseable {
        @Override
        public void close() {
            MemoryUtil.memFree(this.indices);
            MemoryUtil.memFree(this.vertices);
        }
    }
}

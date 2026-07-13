package com.y271727uy.voxy.client.render.mesh;

import com.y271727uy.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import com.y271727uy.voxy.client.core.rendering.hierachical.GpuTraversalBufferLayout;
import com.y271727uy.voxy.client.core.rendering.hierachical.GpuTraversalBuffers;
import com.y271727uy.voxy.client.core.rendering.hierachical.GpuTraversalComputeProgram;
import com.y271727uy.voxy.client.core.rendering.hierachical.GpuTraversalQueueReadback;
import com.y271727uy.voxy.client.core.rendering.hierachical.GpuTraversalShadowController;
import com.y271727uy.voxy.client.core.rendering.hierachical.LwjglGpuTraversalBufferAdapter;
import com.y271727uy.voxy.client.core.rendering.hierachical.LwjglGpuTraversalComputeAdapter;
import com.y271727uy.voxy.client.core.rendering.hierachical.LwjglGpuTraversalReadbackAdapter;
import com.y271727uy.voxy.client.core.rendering.hierachical.NodeGpuTraversalSnapshot;
import com.y271727uy.voxy.client.render.gl.RasterHiZBuffer;
import com.y271727uy.voxy.client.render.gl.shader.ShaderSourceLoader;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.opengl.GL45C;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Optional;

/** Production OpenGL owner behind the compatibility-only shadow runtime. */
public final class LwjglGpuTraversalShadowDriver implements GpuTraversalShadowRuntime.Driver {
    private static final int SCENE_UNIFORM_BYTES = 208;
    private static final int ITERATIONS = 5;

    private GpuTraversalBuffers buffers;
    private GpuTraversalComputeProgram program;
    private GpuTraversalQueueReadback readback;
    private RasterHiZBuffer hiZ;
    private int sceneUniform;
    private boolean initialized;
    private boolean closed;
    private String disableReason = "";

    @Override
    public void initialize(NodeGpuTraversalSnapshot snapshot) {
        if (this.initialized) throw new IllegalStateException("GPU shadow driver is already initialized");
        this.initialized = true;
        GpuTraversalBufferLayout layout = GpuTraversalBufferLayout.create(
                GpuTraversalShadowRuntime.MAX_NODES,
                GpuTraversalBufferLayout.DEFAULT_QUEUE_CAPACITY,
                GpuTraversalBufferLayout.DEFAULT_REQUEST_CAPACITY,
                ITERATIONS, GpuTraversalBufferLayout.DEFAULT_LOCAL_SIZE);
        try {
            this.buffers = GpuTraversalBuffers.create(new LwjglGpuTraversalBufferAdapter(), layout, snapshot);
            this.program = GpuTraversalComputeProgram.create(new LwjglGpuTraversalComputeAdapter(),
                    ShaderSourceLoader.classpath(), layout);
            if (!this.program.isAvailable()) {
                throw new IllegalStateException("traversal compute unavailable", this.program.disableCause());
            }
            this.readback = GpuTraversalQueueReadback.create(new LwjglGpuTraversalReadbackAdapter(),
                    this.buffers, 3);
            this.hiZ = new RasterHiZBuffer();
            this.sceneUniform = GL45C.glCreateBuffers();
            GL45C.glNamedBufferData(this.sceneUniform, SCENE_UNIFORM_BYTES, GL15C.GL_DYNAMIC_DRAW);
        } catch (RuntimeException | LinkageError failure) {
            this.disableReason = describe("initialization failed", failure);
            closeResources();
        }
    }

    @Override
    public void uploadNodesAndRoots(AsyncNodeManager.SyncBatch batch) {
        requireAvailable();
        this.buffers.upload(batch.gpuUploadBatch());
        this.buffers.uploadRoots(batch.rootNodeIds());
    }

    @Override
    public GpuTraversalShadowRuntime.FrameResult runFrame(GpuTraversalShadowRuntime.FrameInput frame) {
        requireAvailable();
        try {
            Optional<GpuTraversalQueueReadback.Result> completed = this.readback.beginFrame();
            this.hiZ.build(frame.sourceDepthTexture(), frame.viewportWidth(), frame.viewportHeight());
            uploadScene(frame);
            boolean dispatched = this.program.dispatch(this.buffers, this.sceneUniform,
                    this.hiZ.textureId(), this.hiZ.samplerId());
            if (!dispatched) {
                this.disableReason = describe("compute dispatch failed", this.program.disableCause());
                return GpuTraversalShadowRuntime.FrameResult.unavailable();
            }
            this.readback.endFrame(frame.frameId());
            if (this.readback.isDisabled()) {
                this.disableReason = this.readback.failureReason();
                return GpuTraversalShadowRuntime.FrameResult.unavailable();
            }
            Optional<GpuTraversalShadowController.GpuReadback> result = completed.map(value ->
                    GpuTraversalShadowController.GpuReadback.ready(value.frameId(),
                            new HashSet<>(Arrays.stream(value.renders().geometryIds()).boxed().toList())));
            return new GpuTraversalShadowRuntime.FrameResult(true, result);
        } catch (RuntimeException | LinkageError failure) {
            this.disableReason = describe("frame failed", failure);
            throw failure;
        }
    }

    private void uploadScene(GpuTraversalShadowRuntime.FrameInput frame) {
        ByteBuffer data = ByteBuffer.allocateDirect(SCENE_UNIFORM_BYTES).order(ByteOrder.nativeOrder());
        float[] mvpValues = frame.mvp();
        for (float value : mvpValues) data.putFloat(value);

        int sectionX = floorSection(frame.cameraX());
        int sectionY = floorSection(frame.cameraY());
        int sectionZ = floorSection(frame.cameraZ());
        data.putInt(sectionX).putInt(sectionY).putInt(sectionZ);
        data.putInt(this.hiZ.packedDimensions());
        data.putFloat((float) (frame.cameraX() - sectionX * 32.0));
        data.putFloat((float) (frame.cameraY() - sectionY * 32.0));
        data.putFloat((float) (frame.cameraZ() - sectionZ * 32.0));
        data.putFloat(64.0F / (frame.viewportWidth() * (float) frame.viewportHeight()));

        Matrix4f matrix = new Matrix4f().set(mvpValues);
        Vector4f plane = new Vector4f();
        for (int index = 0; index < 6; index++) {
            matrix.frustumPlane(index, plane);
            data.putFloat(plane.x).putFloat(plane.y).putFloat(plane.z).putFloat(plane.w);
        }
        data.putInt(this.buffers.layout().queueCapacity());
        data.putInt((int) frame.frameId());
        data.putInt(this.buffers.layout().requestCapacity());
        float distance = frame.renderDistanceBlocks();
        data.putFloat(distance < 0 ? -1.0F : distance * distance);
        data.flip();
        GL45C.glNamedBufferSubData(this.sceneUniform, 0, data);
    }

    private static int floorSection(double coordinate) {
        return (int) Math.floor(coordinate / 32.0);
    }

    @Override
    public boolean isAvailable() {
        return !this.closed && this.initialized && this.disableReason.isEmpty()
                && this.buffers != null && this.program != null && this.program.isAvailable()
                && this.readback != null && !this.readback.isDisabled()
                && this.hiZ != null && this.sceneUniform > 0;
    }

    @Override public String disableReason() { return this.disableReason; }

    private void requireAvailable() {
        if (!isAvailable()) throw new IllegalStateException("GPU shadow driver unavailable: " + this.disableReason);
    }

    @Override
    public void close() {
        if (this.closed) return;
        this.closed = true;
        closeResources();
    }

    private void closeResources() {
        Throwable failure = null;
        if (this.sceneUniform != 0) {
            try { GL15C.glDeleteBuffers(this.sceneUniform); } catch (Throwable value) { failure = value; }
            this.sceneUniform = 0;
        }
        failure = close(this.hiZ, failure); this.hiZ = null;
        failure = close(this.readback, failure); this.readback = null;
        failure = close(this.program, failure); this.program = null;
        failure = close(this.buffers, failure); this.buffers = null;
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException runtime) throw runtime;
    }

    private static Throwable close(AutoCloseable resource, Throwable first) {
        if (resource == null) return first;
        try { resource.close(); } catch (Throwable next) {
            if (first == null) return next;
            first.addSuppressed(next);
        }
        return first;
    }

    private static String describe(String prefix, Throwable failure) {
        if (failure == null) return prefix;
        return prefix + ": " + failure.getClass().getSimpleName() + ": " + failure.getMessage();
    }
}

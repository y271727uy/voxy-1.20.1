package com.y271727uy.voxy.common.release;

import java.util.ArrayList;
import java.util.List;

/** Conservative evidence gate evaluated before changing renderingEnabled's default. */
public final class DefaultOnReadiness {
    public static final int MIN_COLD_STARTS = 3;
    public static final int MIN_RESTARTS = 3;
    public static final int MIN_CONTINUOUS_MINUTES = 120;
    public static final int MIN_WORLD_TRANSITIONS = 20;
    public static final int MIN_GPU_VENDORS = 3;
    public static final int MIN_SHADER_PACKS = 3;

    private DefaultOnReadiness() {
    }

    public static Evaluation evaluate(Evidence evidence) {
        List<String> blockers = new ArrayList<>();
        requireAtLeast(blockers, "cold-starts", evidence.successfulColdStarts(), MIN_COLD_STARTS);
        requireAtLeast(blockers, "restart-restores", evidence.successfulRestarts(), MIN_RESTARTS);
        requireAtLeast(blockers, "continuous-minutes", evidence.continuousMinutes(), MIN_CONTINUOUS_MINUTES);
        requireAtLeast(blockers, "world-transitions", evidence.worldTransitions(), MIN_WORLD_TRANSITIONS);
        requireAtLeast(blockers, "gpu-vendors", evidence.gpuVendors(), MIN_GPU_VENDORS);
        requireAtLeast(blockers, "shader-packs", evidence.shaderPacks(), MIN_SHADER_PACKS);
        requireZero(blockers, "uncaught-errors", evidence.uncaughtErrors());
        requireZero(blockers, "gl-errors", evidence.glErrors());
        requireZero(blockers, "uncovered-frames", evidence.uncoveredFrames());
        requireZero(blockers, "corrupt-restores", evidence.corruptRestores());
        requireZero(blockers, "lifecycle-failures", evidence.lifecycleFailures());
        if (!evidence.testsPassed()) blockers.add("tests-not-passing");
        if (!evidence.releaseArtifactClean()) blockers.add("release-artifact-not-clean");
        if (!evidence.safeFallbackVerified()) blockers.add("safe-fallback-not-verified");
        if (!evidence.normalAndShaderPipelinesVerified()) blockers.add("render-pipeline-matrix-incomplete");
        return new Evaluation(blockers.isEmpty(), List.copyOf(blockers));
    }

    private static void requireAtLeast(List<String> blockers, String name, long actual, long minimum) {
        if (actual < minimum) blockers.add(name + ":" + actual + "<" + minimum);
    }

    private static void requireZero(List<String> blockers, String name, long actual) {
        if (actual != 0) blockers.add(name + ":" + actual + "!=0");
    }

    public record Evidence(int successfulColdStarts, int successfulRestarts, int continuousMinutes,
                           int worldTransitions, int gpuVendors, int shaderPacks,
                           long uncaughtErrors, long glErrors, long uncoveredFrames,
                           long corruptRestores, long lifecycleFailures,
                           boolean testsPassed, boolean releaseArtifactClean,
                           boolean safeFallbackVerified, boolean normalAndShaderPipelinesVerified) {
        public Evidence {
            if (successfulColdStarts < 0 || successfulRestarts < 0 || continuousMinutes < 0
                    || worldTransitions < 0 || gpuVendors < 0 || shaderPacks < 0
                    || uncaughtErrors < 0 || glErrors < 0 || uncoveredFrames < 0
                    || corruptRestores < 0 || lifecycleFailures < 0) {
                throw new IllegalArgumentException("Release evidence counters cannot be negative");
            }
        }
    }

    public record Evaluation(boolean readyForDefaultOn, List<String> blockers) {
    }
}

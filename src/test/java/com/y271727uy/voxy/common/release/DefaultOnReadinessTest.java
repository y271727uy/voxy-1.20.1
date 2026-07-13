package com.y271727uy.voxy.common.release;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class DefaultOnReadinessTest {
    @Test
    public void completeZeroErrorMatrixAllowsDefaultOnEvaluation() {
        DefaultOnReadiness.Evidence evidence = evidence(3, 3, 120, 20, 3, 3,
                0, 0, 0, 0, 0, true, true, true, true);

        assertTrue(DefaultOnReadiness.evaluate(evidence).readyForDefaultOn());
    }

    @Test
    public void shortSingleGpuRunCannotApproveDefaultOn() {
        DefaultOnReadiness.Evidence evidence = evidence(1, 1, 1, 1, 1, 0,
                0, 0, 0, 0, 0, true, true, true, false);

        DefaultOnReadiness.Evaluation result = DefaultOnReadiness.evaluate(evidence);

        assertFalse(result.readyForDefaultOn());
        assertTrue(result.blockers().stream().anyMatch(value -> value.startsWith("continuous-minutes:")));
        assertTrue(result.blockers().stream().anyMatch(value -> value.startsWith("gpu-vendors:")));
        assertTrue(result.blockers().contains("render-pipeline-matrix-incomplete"));
    }

    @Test
    public void anyRuntimeErrorBlocksOtherwiseCompleteEvidence() {
        DefaultOnReadiness.Evidence evidence = evidence(3, 3, 120, 20, 3, 3,
                0, 1, 0, 0, 0, true, true, true, true);
        assertFalse(DefaultOnReadiness.evaluate(evidence).readyForDefaultOn());
    }

    @Test
    public void negativeEvidenceIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> evidence(-1, 3, 120, 20, 3, 3,
                0, 0, 0, 0, 0, true, true, true, true));
    }

    private static DefaultOnReadiness.Evidence evidence(int starts, int restarts, int minutes,
                                                         int transitions, int vendors, int shaderPacks,
                                                         long errors, long glErrors, long uncovered,
                                                         long corrupt, long lifecycleFailures,
                                                         boolean tests, boolean artifact, boolean fallback,
                                                         boolean pipelines) {
        return new DefaultOnReadiness.Evidence(starts, restarts, minutes, transitions, vendors, shaderPacks,
                errors, glErrors, uncovered, corrupt, lifecycleFailures,
                tests, artifact, fallback, pipelines);
    }
}

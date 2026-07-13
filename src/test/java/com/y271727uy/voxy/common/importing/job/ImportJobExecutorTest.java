package com.y271727uy.voxy.common.importing.job;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ImportJobExecutorTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void successfulJobPublishesProgressClearsCheckpointAndClosesTask() {
        MemoryCheckpointStore checkpoints = new MemoryCheckpointStore();
        checkpoints.values.put("world", new ImportCheckpoint("world", "region-4", 4, 10));
        AtomicReference<ImportCheckpoint> resumed = new AtomicReference<>();
        AtomicBoolean closed = new AtomicBoolean();
        ImportJobTask task = new ImportJobTask() {
            @Override
            public void execute(ImportJobContext context) throws Exception {
                resumed.set(context.resumeCheckpoint().orElseThrow());
                context.checkpoint("region-8", 8, 10);
                context.reportProgress(10, 10);
            }

            @Override
            public void close() {
                closed.set(true);
            }
        };

        try (ImportJobExecutor executor = new ImportJobExecutor(checkpoints, 2, "success-test")) {
            ImportProgressSnapshot result = executor.submit("world", task).await();
            assertEquals(ImportJobState.SUCCEEDED, result.state());
            assertEquals(10, result.completed());
            assertEquals("region-8", result.checkpointCursor());
            assertTrue(result.resumed());
            assertEquals("region-4", resumed.get().cursor());
            assertFalse(checkpoints.values.containsKey("world"));
            assertTrue(closed.get());
            assertEquals(ImportJobState.SUCCEEDED,
                    executor.submit("world", context -> { }).await().state());
            assertEquals(2, executor.metrics().completedJobs());
        }
    }

    @Test
    public void cancellationRetainsLastDurableCheckpointAndClosesTask() throws Exception {
        MemoryCheckpointStore checkpoints = new MemoryCheckpointStore();
        CountDownLatch checkpointed = new CountDownLatch(1);
        CountDownLatch continueWork = new CountDownLatch(1);
        AtomicBoolean closed = new AtomicBoolean();
        ImportJobTask task = new ImportJobTask() {
            @Override
            public void execute(ImportJobContext context) throws Exception {
                context.checkpoint("chunk-12", 12, 100);
                checkpointed.countDown();
                continueWork.await(2, TimeUnit.SECONDS);
                context.throwIfCancellationRequested();
            }

            @Override
            public void close() {
                closed.set(true);
            }
        };

        try (ImportJobExecutor executor = new ImportJobExecutor(checkpoints, 1, "cancel-test")) {
            ImportJobHandle handle = executor.submit("world", task);
            assertTrue(checkpointed.await(2, TimeUnit.SECONDS));
            assertTrue(handle.cancel());
            assertFalse(handle.cancel());
            continueWork.countDown();
            ImportProgressSnapshot result = handle.await();
            assertEquals(ImportJobState.CANCELLED, result.state());
            assertEquals("chunk-12", checkpoints.values.get("world").cursor());
            assertTrue(closed.get());
        }
    }

    @Test
    public void taskAndCloseFailuresPropagateAndRemainInSnapshot() {
        MemoryCheckpointStore checkpoints = new MemoryCheckpointStore();
        ImportJobTask task = new ImportJobTask() {
            @Override
            public void execute(ImportJobContext context) throws Exception {
                context.checkpoint("before-error", 3, 9);
                throw new IllegalStateException("decode failed");
            }

            @Override
            public void close() {
                throw new IllegalArgumentException("close failed");
            }
        };

        try (ImportJobExecutor executor = new ImportJobExecutor(checkpoints, 1, "failure-test")) {
            ImportJobHandle handle = executor.submit("broken", task);
            CompletionException failure = assertThrows(CompletionException.class, handle::await);
            assertEquals("decode failed", failure.getCause().getMessage());
            assertEquals(1, failure.getCause().getSuppressed().length);
            assertEquals(ImportJobState.FAILED, handle.snapshot().state());
            assertEquals(IllegalStateException.class.getName(), handle.snapshot().failureType());
            assertEquals("decode failed", handle.snapshot().failureMessage());
            assertEquals("before-error", checkpoints.values.get("broken").cursor());
        }
    }

    @Test
    public void boundedQueueBackpressuresThirdSubmission() throws Exception {
        MemoryCheckpointStore checkpoints = new MemoryCheckpointStore();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        ImportJobTask first = context -> {
            firstStarted.countDown();
            releaseFirst.await(2, TimeUnit.SECONDS);
        };

        try (ImportJobExecutor executor = new ImportJobExecutor(checkpoints, 1, "bounded-test")) {
            ImportJobHandle firstHandle = executor.submit("first", first);
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS));
            ImportJobHandle secondHandle = executor.submit("second", context -> { });
            AtomicReference<ImportJobHandle> thirdHandle = new AtomicReference<>();
            Thread producer = new Thread(() -> thirdHandle.set(executor.submit("third", context -> { })));
            producer.start();
            Thread.sleep(30);
            assertNull(thirdHandle.get());
            releaseFirst.countDown();
            producer.join(2_000);
            assertNotNull(thirdHandle.get());
            firstHandle.await();
            secondHandle.await();
            thirdHandle.get().await();
            assertEquals(1, executor.metrics().capacity());
            assertEquals(1, executor.metrics().highWaterMark());
            assertTrue(executor.metrics().backpressureEvents() >= 1);
        }
    }

    @Test
    public void duplicateActiveIdIsRejectedAndQueuedCancellationStillCloses() throws Exception {
        MemoryCheckpointStore checkpoints = new MemoryCheckpointStore();
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger queuedCloses = new AtomicInteger();
        ImportJobTask queued = new ImportJobTask() {
            @Override
            public void execute(ImportJobContext context) {
                throw new AssertionError("cancelled queued task must not execute");
            }

            @Override
            public void close() {
                queuedCloses.incrementAndGet();
            }
        };

        try (ImportJobExecutor executor = new ImportJobExecutor(checkpoints, 2, "duplicate-test")) {
            ImportJobHandle running = executor.submit("same", context -> release.await(2, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, () -> executor.submit("same", context -> { }));
            ImportJobHandle cancelled = executor.submit("queued", queued);
            assertTrue(cancelled.cancel());
            release.countDown();
            running.await();
            assertEquals(ImportJobState.CANCELLED, cancelled.await().state());
            assertEquals(1, queuedCloses.get());
        }
    }

    @Test
    public void fileCheckpointStoreRoundTripsAndRejectsCorruption() throws Exception {
        FileImportCheckpointStore store = new FileImportCheckpointStore(this.temporaryFolder.getRoot().toPath());
        ImportCheckpoint checkpoint = new ImportCheckpoint("dim:overworld", "r.2.-3/17", 17, 42);
        store.save(checkpoint);
        assertEquals(checkpoint, store.load(checkpoint.jobId()).orElseThrow());

        java.nio.file.Path file;
        try (Stream<java.nio.file.Path> files = Files.list(this.temporaryFolder.getRoot().toPath())) {
            file = files.findFirst().orElseThrow();
        }
        Files.write(file, new byte[] {1, 2, 3, 4});
        assertThrows(IOException.class, () -> store.load(checkpoint.jobId()));
        store.clear(checkpoint.jobId());
        assertTrue(store.load(checkpoint.jobId()).isEmpty());
    }

    @Test
    public void closeUnblocksSubmissionWaitingOnFullQueue() throws Exception {
        MemoryCheckpointStore checkpoints = new MemoryCheckpointStore();
        CountDownLatch running = new CountDownLatch(1);
        ImportJobExecutor executor = new ImportJobExecutor(checkpoints, 1, "close-backpressure-test");
        executor.submit("running", context -> {
            running.countDown();
            while (!context.isCancellationRequested()) Thread.sleep(1);
            context.throwIfCancellationRequested();
        });
        assertTrue(running.await(2, TimeUnit.SECONDS));
        executor.submit("queued", context -> { });

        AtomicReference<Throwable> submitFailure = new AtomicReference<>();
        CountDownLatch blockedCloseEntered = new CountDownLatch(1);
        CountDownLatch releaseBlockedClose = new CountDownLatch(1);
        ImportJobTask blocked = new ImportJobTask() {
            @Override
            public void execute(ImportJobContext context) { }

            @Override
            public void close() throws Exception {
                blockedCloseEntered.countDown();
                releaseBlockedClose.await(2, TimeUnit.SECONDS);
            }
        };
        Thread producer = new Thread(() -> {
            try {
                executor.submit("blocked", blocked);
            } catch (Throwable throwable) {
                submitFailure.set(throwable);
            }
        });
        producer.start();
        Thread.sleep(30);
        Thread closer = new Thread(executor::close);
        closer.start();
        assertTrue(blockedCloseEntered.await(2, TimeUnit.SECONDS));
        Thread.sleep(20);
        assertTrue("close must wait for registered task cleanup", closer.isAlive());
        releaseBlockedClose.countDown();
        producer.join(2_000);
        closer.join(2_000);

        assertFalse("blocked submit must terminate", producer.isAlive());
        assertFalse("executor close must terminate", closer.isAlive());
        assertTrue(submitFailure.get() instanceof IllegalStateException);
    }

    @Test
    public void inlineCompletionCallbackCanCloseExecutorWithoutInterruptLeakOrSelfJoin() throws Exception {
        ImportJobExecutor executor = new ImportJobExecutor(new MemoryCheckpointStore(), 1,
                "inline-completion-test");
        CountDownLatch releaseTask = new CountDownLatch(1);
        CountDownLatch callbackFinished = new CountDownLatch(1);
        AtomicBoolean callbackInterrupted = new AtomicBoolean(true);
        ImportJobHandle handle = executor.submit("world", context -> {
            releaseTask.await(2, TimeUnit.SECONDS);
            Thread.currentThread().interrupt();
        });
        handle.completion().whenComplete((result, failure) -> {
            callbackInterrupted.set(Thread.currentThread().isInterrupted());
            executor.close();
            callbackFinished.countDown();
        });
        releaseTask.countDown();
        assertTrue(callbackFinished.await(2, TimeUnit.SECONDS));
        assertFalse(callbackInterrupted.get());
        assertEquals(ImportJobState.SUCCEEDED, handle.await().state());
        executor.close();
    }

    @Test
    public void rejectedSubmissionRestoresExistingInterruptFlag() {
        ImportJobExecutor executor = new ImportJobExecutor(new MemoryCheckpointStore(), 1,
                "interrupt-restore-test");
        executor.close();
        Thread.currentThread().interrupt();
        try {
            assertThrows(IllegalStateException.class,
                    () -> executor.submit("world", context -> { }));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void rejectedSubmissionTaskCloseMayReenterExecutorClose() throws Exception {
        ImportJobExecutor executor = new ImportJobExecutor(new MemoryCheckpointStore(), 1,
                "reentrant-cleanup-test");
        CountDownLatch running = new CountDownLatch(1);
        executor.submit("running", context -> {
            running.countDown();
            while (!context.isCancellationRequested()) Thread.sleep(1);
            context.throwIfCancellationRequested();
        });
        assertTrue(running.await(2, TimeUnit.SECONDS));
        executor.submit("queued", context -> { });

        AtomicReference<Throwable> submitFailure = new AtomicReference<>();
        ImportJobTask reentrant = new ImportJobTask() {
            @Override public void execute(ImportJobContext context) { }
            @Override public void close() { executor.close(); }
        };
        Thread producer = new Thread(() -> {
            try {
                executor.submit("blocked", reentrant);
            } catch (Throwable throwable) {
                submitFailure.set(throwable);
            }
        });
        producer.start();
        Thread.sleep(30);
        Thread closer = new Thread(executor::close);
        closer.start();
        producer.join(2_000);
        closer.join(2_000);

        assertFalse("reentrant cleanup submit must terminate", producer.isAlive());
        assertFalse("outer close must terminate", closer.isAlive());
        assertTrue(submitFailure.get() instanceof IllegalStateException);
    }

    @Test
    public void invalidCheckpointTransitionIsRejectedBeforePersistence() {
        MemoryCheckpointStore checkpoints = new MemoryCheckpointStore();
        try (ImportJobExecutor executor = new ImportJobExecutor(checkpoints, 1, "checkpoint-order-test")) {
            ImportProgressSnapshot result = executor.submit("world", context -> {
                context.reportProgress(10, 20);
                assertThrows(IllegalArgumentException.class,
                        () -> context.checkpoint("behind", 8, 20));
                assertEquals(0, checkpoints.saveCalls.get());
            }).await();
            assertEquals(ImportJobState.SUCCEEDED, result.state());
        }
    }

    @Test
    public void closeFailureRetainsSuccessfulExecutionCheckpoint() {
        MemoryCheckpointStore checkpoints = new MemoryCheckpointStore();
        ImportJobTask task = new ImportJobTask() {
            @Override
            public void execute(ImportJobContext context) throws Exception {
                context.checkpoint("committed", 4, 8);
            }

            @Override
            public void close() {
                throw new IllegalStateException("close failed");
            }
        };
        try (ImportJobExecutor executor = new ImportJobExecutor(checkpoints, 1, "close-failure-test")) {
            ImportJobHandle handle = executor.submit("world", task);
            assertThrows(CompletionException.class, handle::await);
            assertEquals("committed", checkpoints.values.get("world").cursor());
            assertEquals(ImportJobState.FAILED, handle.snapshot().state());
        }
    }

    private static final class MemoryCheckpointStore implements ImportCheckpointStore {
        private final Map<String, ImportCheckpoint> values = new ConcurrentHashMap<>();
        private final AtomicInteger saveCalls = new AtomicInteger();

        @Override
        public Optional<ImportCheckpoint> load(String jobId) {
            return Optional.ofNullable(this.values.get(jobId));
        }

        @Override
        public void save(ImportCheckpoint checkpoint) {
            this.saveCalls.incrementAndGet();
            this.values.put(checkpoint.jobId(), checkpoint);
        }

        @Override
        public void clear(String jobId) {
            this.values.remove(jobId);
        }
    }
}

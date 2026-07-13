package com.y271727uy.voxy.common.importing.job;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Owns a single importer worker and a bounded queue. Different import formats
 * share this lifecycle so storage mutation remains serialized and bounded.
 */
public final class ImportJobExecutor implements AutoCloseable {
    private final ImportCheckpointStore checkpointStore;
    private final ArrayBlockingQueue<Control> queue;
    private final ConcurrentHashMap<String, Control> activeJobs = new ConcurrentHashMap<>();
    private final Object lifecycleLock = new Object();
    private final ThreadLocal<Boolean> submissionCleanup = ThreadLocal.withInitial(() -> false);
    private final Thread worker;
    private final AtomicLong submittedJobs = new AtomicLong();
    private final AtomicLong completedJobs = new AtomicLong();
    private final AtomicLong backpressureEvents = new AtomicLong();
    private final AtomicInteger highWaterMark = new AtomicInteger();
    private volatile boolean closing;
    private volatile boolean closed;
    private int pendingSubmissions;

    public ImportJobExecutor(ImportCheckpointStore checkpointStore, int capacity, String workerName) {
        this.checkpointStore = Objects.requireNonNull(checkpointStore, "checkpointStore");
        if (capacity < 1) {
            throw new IllegalArgumentException("Import job capacity must be positive");
        }
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.worker = new Thread(this::runWorker, ImportCheckpoint.requireText(workerName, "workerName"));
        this.worker.setDaemon(true);
        this.worker.start();
    }

    public ImportJobExecutor(ImportCheckpointStore checkpointStore, int capacity) {
        this(checkpointStore, capacity, "Voxy import jobs");
    }

    /**
     * Submits one resumable task. Submission blocks under backpressure and
     * restores the caller's interrupt flag after the task has been accepted.
     */
    public ImportJobHandle submit(String jobId, ImportJobTask task) {
        ImportCheckpoint.requireText(jobId, "jobId");
        Objects.requireNonNull(task, "task");
        Control control = new Control(jobId, task);
        boolean registered = false;
        boolean backpressured = false;
        boolean interrupted = Thread.interrupted();
        try {
            while (true) {
                synchronized (this.lifecycleLock) {
                    if (!registered) {
                        requireOpen();
                        if (this.activeJobs.putIfAbsent(jobId, control) != null) {
                            throw new IllegalStateException("Import job is already active: " + jobId);
                        }
                        this.submittedJobs.incrementAndGet();
                        this.pendingSubmissions++;
                        registered = true;
                    }
                    if (this.closing || this.closed) break;
                    if (this.queue.offer(control)) {
                        this.highWaterMark.accumulateAndGet(this.queue.size(), Math::max);
                        this.pendingSubmissions--;
                        this.lifecycleLock.notifyAll();
                        return control;
                    }
                    if (!backpressured) {
                        this.backpressureEvents.incrementAndGet();
                        backpressured = true;
                    }
                }
                try {
                    Thread.sleep(1);
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
            Throwable closeFailure = null;
            try {
                this.submissionCleanup.set(true);
                task.close();
            } catch (Throwable throwable) {
                closeFailure = throwable;
            } finally {
                this.submissionCleanup.remove();
            }
            synchronized (this.lifecycleLock) {
                this.activeJobs.remove(jobId, control);
                this.submittedJobs.decrementAndGet();
                this.pendingSubmissions--;
                this.lifecycleLock.notifyAll();
            }
            IllegalStateException failure = new IllegalStateException("Import job executor closed during submission");
            if (closeFailure != null) failure.addSuppressed(closeFailure);
            throw failure;
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    public Optional<ImportJobHandle> activeJob(String jobId) {
        return Optional.ofNullable(this.activeJobs.get(jobId)).map(job -> (ImportJobHandle) job);
    }

    public Metrics metrics() {
        return new Metrics(this.queue.size(), this.queue.size() + this.queue.remainingCapacity(),
                this.highWaterMark.get(), this.activeJobs.size(), this.submittedJobs.get(),
                this.completedJobs.get(), this.backpressureEvents.get(), this.closing || this.closed,
                this.worker.getName());
    }

    @Override
    public void close() {
        boolean interrupted = false;
        synchronized (this.lifecycleLock) {
            if (this.closed) {
                return;
            }
            this.closing = true;
            this.activeJobs.values().forEach(Control::cancel);
            if (this.submissionCleanup.get()) {
                return;
            }
            while (this.pendingSubmissions != 0) {
                try {
                    this.lifecycleLock.wait();
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
        }
        if (Thread.currentThread() == this.worker) {
            if (interrupted) Thread.currentThread().interrupt();
            return;
        }
        while (this.worker.isAlive()) {
            try {
                this.worker.join();
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void runWorker() {
        try {
            while (!this.closing || !this.queue.isEmpty()) {
                Control control;
                try {
                    control = this.queue.poll(250, TimeUnit.MILLISECONDS);
                } catch (InterruptedException exception) {
                    if (this.closing) {
                        continue;
                    }
                    Thread.currentThread().interrupt();
                    break;
                }
                if (control == null) {
                    continue;
                }
                run(control);
                // Do not leak a task-originated interrupt into queue polling.
                Thread.interrupted();
            }
        } finally {
            this.closed = true;
        }
    }

    private void run(Control control) {
        Throwable failure = null;
        ImportJobState terminalState = ImportJobState.SUCCEEDED;
        boolean clearCheckpoint = false;
        try {
            if (control.isCancellationRequested()) {
                terminalState = ImportJobState.CANCELLED;
            } else {
                Optional<ImportCheckpoint> resume = this.checkpointStore.load(control.jobId);
                if (resume.isPresent() && !resume.get().jobId().equals(control.jobId)) {
                    throw new IllegalStateException("Checkpoint belongs to another import job");
                }
                if (control.isCancellationRequested()) {
                    terminalState = ImportJobState.CANCELLED;
                } else {
                    control.start(resume);
                    Context context = new Context(control, resume);
                    control.task.execute(context);
                    if (!control.beginCompletion()) {
                        terminalState = ImportJobState.CANCELLED;
                    } else {
                        clearCheckpoint = true;
                    }
                }
            }
        } catch (Throwable throwable) {
            if (control.isCancellationRequested()
                    && (throwable instanceof ImportCancelledException || throwable instanceof InterruptedException)) {
                terminalState = ImportJobState.CANCELLED;
            } else {
                terminalState = ImportJobState.FAILED;
                failure = throwable;
            }
        }
        try {
            control.task.close();
        } catch (Throwable closeFailure) {
            terminalState = ImportJobState.FAILED;
            if (failure == null) {
                failure = closeFailure;
            } else {
                failure.addSuppressed(closeFailure);
            }
        }
        if (clearCheckpoint && terminalState == ImportJobState.SUCCEEDED && failure == null) {
            try {
                this.checkpointStore.clear(control.jobId);
            } catch (Throwable clearFailure) {
                terminalState = ImportJobState.FAILED;
                failure = clearFailure;
            }
        }
        // CompletableFuture callbacks may execute inline on this worker.
        Thread.interrupted();
        // Publish lifecycle metrics and release the id before waking awaiters.
        this.activeJobs.remove(control.jobId, control);
        this.completedJobs.incrementAndGet();
        control.finish(terminalState, failure);
    }

    private void requireOpen() {
        if (this.closing || this.closed) {
            throw new IllegalStateException("Import job executor is closing or closed");
        }
    }

    public record Metrics(int queuedJobs, int capacity, int highWaterMark, int activeJobs,
                          long submittedJobs, long completedJobs, long backpressureEvents,
                          boolean closingOrClosed, String workerName) {
    }

    private final class Context implements ImportJobContext {
        private final Control control;
        private final Optional<ImportCheckpoint> resume;

        private Context(Control control, Optional<ImportCheckpoint> resume) {
            this.control = control;
            this.resume = resume;
        }

        @Override
        public String jobId() {
            return this.control.jobId;
        }

        @Override
        public Optional<ImportCheckpoint> resumeCheckpoint() {
            return this.resume;
        }

        @Override
        public boolean isCancellationRequested() {
            return this.control.isCancellationRequested();
        }

        @Override
        public void throwIfCancellationRequested() throws ImportCancelledException {
            if (isCancellationRequested()) {
                throw new ImportCancelledException(this.control.jobId);
            }
        }

        @Override
        public void reportProgress(long completed, long total) {
            this.control.progress(completed, total, null, false);
        }

        @Override
        public void checkpoint(String cursor, long completed, long total) throws Exception {
            throwIfCancellationRequested();
            ImportCheckpoint checkpoint = new ImportCheckpoint(this.control.jobId, cursor, completed, total);
            this.control.validateProgressTransition(completed, total);
            ImportJobExecutor.this.checkpointStore.save(checkpoint);
            this.control.progress(completed, total, cursor, true);
        }
    }

    private static final class Control implements ImportJobHandle {
        private final String jobId;
        private final ImportJobTask task;
        private final CompletableFuture<ImportProgressSnapshot> completion = new CompletableFuture<>();
        private ImportJobState state = ImportJobState.QUEUED;
        private long completed;
        private long total = ImportCheckpoint.UNKNOWN_TOTAL;
        private String checkpointCursor;
        private boolean resumed;
        private boolean cancellationRequested;
        private boolean acceptingCancellation = true;
        private long startedAtMillis;
        private long finishedAtMillis;
        private Throwable failure;

        private Control(String jobId, ImportJobTask task) {
            this.jobId = jobId;
            this.task = task;
        }

        @Override
        public String jobId() {
            return this.jobId;
        }

        @Override
        public synchronized ImportProgressSnapshot snapshot() {
            return new ImportProgressSnapshot(this.jobId, this.state, this.completed, this.total,
                    this.checkpointCursor, this.resumed, this.startedAtMillis, this.finishedAtMillis,
                    this.failure == null ? null : this.failure.getClass().getName(),
                    this.failure == null ? null : this.failure.getMessage());
        }

        @Override
        public synchronized boolean cancel() {
            if (this.state.isTerminal() || this.cancellationRequested || !this.acceptingCancellation) {
                return false;
            }
            this.cancellationRequested = true;
            this.state = ImportJobState.CANCELLING;
            return true;
        }

        @Override
        public CompletableFuture<ImportProgressSnapshot> completion() {
            return this.completion;
        }

        private synchronized boolean isCancellationRequested() {
            return this.cancellationRequested;
        }

        private synchronized void start(Optional<ImportCheckpoint> checkpoint) {
            this.state = ImportJobState.RUNNING;
            this.startedAtMillis = System.currentTimeMillis();
            checkpoint.ifPresent(value -> {
                this.completed = value.completed();
                this.total = value.total();
                this.checkpointCursor = value.cursor();
                this.resumed = true;
            });
        }

        private synchronized void progress(long completed, long total, String cursor, boolean durable) {
            validateProgressTransition(completed, total);
            this.completed = completed;
            this.total = total;
            if (durable) {
                this.checkpointCursor = cursor;
            }
        }

        private synchronized void validateProgressTransition(long completed, long total) {
            ImportCheckpoint.validateProgress(completed, total);
            if (completed < this.completed) {
                throw new IllegalArgumentException("Import progress cannot move backwards");
            }
            if (this.state != ImportJobState.RUNNING && this.state != ImportJobState.CANCELLING) {
                throw new IllegalStateException("Import job is not running: " + this.jobId);
            }
        }

        private synchronized boolean beginCompletion() {
            this.acceptingCancellation = false;
            return !this.cancellationRequested;
        }

        private void finish(ImportJobState terminalState, Throwable failure) {
            ImportProgressSnapshot result;
            synchronized (this) {
                this.state = terminalState;
                this.acceptingCancellation = false;
                this.failure = failure;
                this.finishedAtMillis = System.currentTimeMillis();
                result = snapshot();
            }
            if (failure == null) {
                this.completion.complete(result);
            } else {
                this.completion.completeExceptionally(failure);
            }
        }
    }
}

package com.y271727uy.voxy.client.core.model;

import com.y271727uy.voxy.client.model.BakedBlockModel;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

/** Lifecycle owner for asynchronous CPU model processing. */
public final class ModelBakerySubsystem implements AutoCloseable {
    private final ModelFactory factory = new ModelFactory();
    private final ConcurrentLinkedQueue<Integer> queue = new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<Integer, BakeWork> pending = new ConcurrentHashMap<>();
    private final AtomicInteger processing = new AtomicInteger();
    private final Thread worker;
    private final Consumer<List<ModelFactory.ModelEntry>> publisher;
    private volatile boolean running = true;
    private volatile boolean closed;
    private volatile Throwable failure;

    public ModelBakerySubsystem() {
        this(null);
    }

    public ModelBakerySubsystem(Consumer<List<ModelFactory.ModelEntry>> publisher) {
        this.publisher = publisher == null ? this.factory::publishPrepared : publisher;
        this.worker = new Thread(this::run, "Voxy model bakery");
        this.worker.setDaemon(true);
        this.worker.setUncaughtExceptionHandler((thread, throwable) -> {
            this.failure = throwable;
            this.running = false;
        });
        this.worker.start();
    }

    public ModelFactory factory() {
        checkFailure();
        return this.factory;
    }

    public void installNow(BakedBlockModel model) {
        checkRunning();
        this.factory.install(model);
    }

    public boolean submit(BakedBlockModel model) {
        return submit(model, null);
    }

    public boolean submit(BakedBlockModel model, ModelFactory.ModelDescriptor descriptor) {
        checkRunning();
        BakeWork previous = this.pending.put(model.blockId(), new BakeWork(model, descriptor));
        if (previous == null) this.queue.add(model.blockId());
        LockSupport.unpark(this.worker);
        return previous == null;
    }

    public int getProcessingCount() {
        return this.pending.size() + this.processing.get();
    }

    public boolean areQueuesEmpty() {
        return getProcessingCount() == 0;
    }

    public boolean awaitIdle(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!areQueuesEmpty() && System.nanoTime() < deadline) {
            checkFailure();
            Thread.sleep(1L);
        }
        checkFailure();
        return areQueuesEmpty();
    }

    @Override
    public synchronized void close() {
        if (this.closed) return;
        this.closed = true;
        this.running = false;
        LockSupport.unpark(this.worker);
        try {
            this.worker.join();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while stopping model bakery", exception);
        } finally {
            this.pending.clear();
            this.queue.clear();
            this.factory.close();
        }
        checkFailure();
    }

    private void run() {
        while (this.running) {
            Integer blockId = this.queue.poll();
            if (blockId == null) {
                LockSupport.parkNanos(this, 10_000_000L);
                continue;
            }
            this.processing.incrementAndGet();
            try {
                List<ModelFactory.ModelEntry> prepared = new ArrayList<>();
                while (blockId != null) {
                    BakeWork work = this.pending.remove(blockId);
                    if (work != null) prepared.add(ModelFactory.prepare(work.model(), work.descriptor()));
                    blockId = this.queue.poll();
                }
                if (!prepared.isEmpty()) this.publisher.accept(List.copyOf(prepared));
            } finally {
                this.processing.decrementAndGet();
            }
        }
    }

    private void checkRunning() {
        checkFailure();
        if (!this.running) throw new IllegalStateException("Model bakery is closed");
    }

    private void checkFailure() {
        if (this.failure != null) throw new IllegalStateException("Model bakery worker failed", this.failure);
    }

    private record BakeWork(BakedBlockModel model, ModelFactory.ModelDescriptor descriptor) {
    }
}

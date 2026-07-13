package com.y271727uy.voxy.common.storage;

import com.y271727uy.voxy.common.world.WorldSection;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongConsumer;

/**
 * Serializes all access to a section storage on one bounded worker. Section
 * saves capture their immutable codec image before returning to the caller.
 */
public final class BoundedAsyncSectionStorage implements SectionStorage {
    private final SectionStorage delegate;
    private final ArrayBlockingQueue<Command<?>> commands;
    private final Thread worker;
    private final Object lifecycleLock = new Object();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final AtomicLong submitted = new AtomicLong();
    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong backpressureEvents = new AtomicLong();
    private final AtomicInteger highWaterMark = new AtomicInteger();
    private volatile State state = State.OPEN;

    public BoundedAsyncSectionStorage(SectionStorage delegate, int capacity, String workerName) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        if (capacity < 1) {
            throw new IllegalArgumentException("Async storage capacity must be positive");
        }
        this.commands = new ArrayBlockingQueue<>(capacity);
        this.worker = new Thread(this::runWorker, Objects.requireNonNull(workerName, "workerName"));
        this.worker.setDaemon(true);
        this.worker.start();
    }

    public BoundedAsyncSectionStorage(SectionStorage delegate, int capacity) {
        this(delegate, capacity, "Voxy section storage");
    }

    @Override
    public int loadSection(WorldSection section) {
        Objects.requireNonNull(section, "section");
        return submitAndWait(() -> this.delegate.loadSection(section));
    }

    @Override
    public void saveSection(WorldSection section) {
        Objects.requireNonNull(section, "section");
        // Encoding here prevents later section mutations from changing this save generation.
        byte[] image = WorldSectionCodec.serialize(section);
        int level = section.level();
        int x = section.x();
        int y = section.y();
        int z = section.z();
        submitAsync(() -> {
            WorldSection snapshot = new WorldSection(level, x, y, z);
            WorldSectionCodec.deserialize(snapshot, image);
            this.delegate.saveSection(snapshot);
            return null;
        });
    }

    @Override
    public void iteratePositions(int level, LongConsumer consumer) {
        Objects.requireNonNull(consumer, "consumer");
        List<Long> positions = submitAndWait(() -> {
            List<Long> collected = new ArrayList<>();
            this.delegate.iteratePositions(level, collected::add);
            return collected;
        });
        positions.forEach(consumer::accept);
    }

    @Override
    public void putIdMapping(int key, ByteBuffer data) {
        Objects.requireNonNull(data, "data");
        ByteBuffer source = data.duplicate();
        byte[] image = new byte[source.remaining()];
        source.get(image);
        submitAndWait(() -> {
            this.delegate.putIdMapping(key, ByteBuffer.wrap(image));
            return null;
        });
    }

    @Override
    public Int2ObjectOpenHashMap<byte[]> getIdMappingsData() {
        return submitAndWait(this.delegate::getIdMappingsData);
    }

    /** Waits for every earlier command and the delegate flush to complete. */
    @Override
    public void flush() {
        submitAndWait(() -> {
            this.delegate.flush();
            return null;
        });
    }

    @Override
    public void close() {
        CompletableFuture<Void> closed;
        synchronized (this.lifecycleLock) {
            if (this.state == State.CLOSED) {
                rethrowFailure();
                return;
            }
            if (this.state == State.CLOSING) {
                closed = null;
            } else {
                this.state = State.CLOSING;
                closed = enqueue(new Command<>(() -> {
                    Throwable prior = this.failure.get();
                    try {
                        if (prior == null) {
                            this.delegate.flush();
                        }
                    } finally {
                        this.delegate.close();
                    }
                    return null;
                }, true));
            }
        }
        if (closed != null) {
            await(closed);
            joinWorker();
        } else {
            joinWorker();
        }
        rethrowFailure();
    }

    public Metrics metrics() {
        return new Metrics(this.commands.size(), this.commands.remainingCapacity() + this.commands.size(),
                this.highWaterMark.get(), this.submitted.get(), this.completed.get(),
                this.backpressureEvents.get(), this.failure.get() != null, this.state != State.OPEN,
                this.worker.getName());
    }

    private <T> T submitAndWait(StorageOperation<T> operation) {
        return await(submit(operation));
    }

    private void submitAsync(StorageOperation<Void> operation) {
        submit(operation);
    }

    private <T> CompletableFuture<T> submit(StorageOperation<T> operation) {
        synchronized (this.lifecycleLock) {
            requireOpen();
            rethrowFailure();
            return enqueue(new Command<>(operation, false));
        }
    }

    private <T> CompletableFuture<T> enqueue(Command<T> command) {
        this.submitted.incrementAndGet();
        if (!this.commands.offer(command)) {
            this.backpressureEvents.incrementAndGet();
            boolean interrupted = false;
            while (true) {
                try {
                    this.commands.put(command);
                    break;
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        this.highWaterMark.accumulateAndGet(this.commands.size(), Math::max);
        return command.completion;
    }

    private void runWorker() {
        boolean stop = false;
        while (!stop) {
            Command<?> command;
            try {
                command = this.commands.take();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                recordFailure(exception);
                break;
            }
            stop = command.close;
            execute(command);
        }
        this.state = State.CLOSED;
    }

    private <T> void execute(Command<T> command) {
        Throwable prior = this.failure.get();
        if (prior != null && !command.close) {
            this.completed.incrementAndGet();
            command.completion.completeExceptionally(prior);
            return;
        }
        try {
            T result = command.operation.run();
            this.completed.incrementAndGet();
            command.completion.complete(result);
        } catch (Throwable throwable) {
            recordFailure(throwable);
            this.completed.incrementAndGet();
            command.completion.completeExceptionally(throwable);
        }
    }

    private void recordFailure(Throwable throwable) {
        this.failure.compareAndSet(null, throwable);
    }

    private void requireOpen() {
        if (this.state != State.OPEN) {
            throw new IllegalStateException("Async section storage is closing or closed");
        }
    }

    private void rethrowFailure() {
        Throwable throwable = this.failure.get();
        if (throwable != null) {
            throw storageFailure(throwable);
        }
    }

    private void joinWorker() {
        try {
            this.worker.join();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while closing Voxy storage", exception);
        }
    }

    private static <T> T await(CompletableFuture<T> completion) {
        try {
            return completion.join();
        } catch (CompletionException exception) {
            throw storageFailure(exception.getCause());
        }
    }

    private static IllegalStateException storageFailure(Throwable throwable) {
        if (throwable instanceof IllegalStateException illegalState) {
            return illegalState;
        }
        return new IllegalStateException("Asynchronous Voxy storage failed", throwable);
    }

    public record Metrics(int queuedCommands, int capacity, int highWaterMark, long submittedCommands,
                          long completedCommands, long backpressureEvents, boolean failed,
                          boolean closingOrClosed, String workerName) {
    }

    @FunctionalInterface
    private interface StorageOperation<T> {
        T run();
    }

    private static final class Command<T> {
        private final StorageOperation<T> operation;
        private final boolean close;
        private final CompletableFuture<T> completion = new CompletableFuture<>();

        private Command(StorageOperation<T> operation, boolean close) {
            this.operation = operation;
            this.close = close;
        }
    }

    private enum State {
        OPEN,
        CLOSING,
        CLOSED
    }
}

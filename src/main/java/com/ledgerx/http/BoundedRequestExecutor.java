package com.ledgerx.http;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

final class BoundedRequestExecutor implements Executor {
    private static final ThreadLocal<Boolean> OVERLOADED_REQUEST = new ThreadLocal<>();
    private final ExecutorService delegate;
    private final Semaphore slots;

    BoundedRequestExecutor(int workers, int queueCapacity) {
        if (workers < 1 || queueCapacity < 0) {
            throw new IllegalArgumentException("workers must be positive and queueCapacity cannot be negative");
        }
        ThreadFactory factory = new NamedDaemonThreadFactory();
        java.util.concurrent.BlockingQueue<Runnable> queue = queueCapacity == 0
                ? new SynchronousQueue<>()
                : new ArrayBlockingQueue<>(queueCapacity);
        this.delegate = new ThreadPoolExecutor(
                workers,
                workers,
                0L,
                TimeUnit.MILLISECONDS,
                queue,
                factory,
                new ThreadPoolExecutor.AbortPolicy());
        this.slots = new Semaphore(workers + queueCapacity);
    }

    @Override
    public void execute(Runnable command) {
        if (!slots.tryAcquire()) {
            runRejected(command);
            return;
        }
        try {
            delegate.execute(() -> {
                try {
                    command.run();
                } finally {
                    slots.release();
                }
            });
        } catch (RejectedExecutionException ex) {
            slots.release();
            runRejected(command);
        }
    }

    static boolean isOverloadedRequest() {
        return Boolean.TRUE.equals(OVERLOADED_REQUEST.get());
    }

    void shutdown(Duration timeout) {
        delegate.shutdown();
        long millis = Math.max(0L, timeout.toMillis());
        try {
            if (!delegate.awaitTermination(millis, TimeUnit.MILLISECONDS)) {
                delegate.shutdownNow();
                delegate.awaitTermination(millis, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException ex) {
            delegate.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    boolean isTerminated() {
        return delegate.isTerminated();
    }

    private static void runRejected(Runnable command) {
        OVERLOADED_REQUEST.set(Boolean.TRUE);
        try {
            command.run();
        } finally {
            OVERLOADED_REQUEST.remove();
        }
    }

    private static final class NamedDaemonThreadFactory implements ThreadFactory {
        private int sequence;

        @Override
        public synchronized Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "ledgerx-http-" + (++sequence));
            thread.setDaemon(true);
            return thread;
        }
    }
}

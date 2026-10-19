package com.stockexchange.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Releases several tasks at the same instant to maximise contention. Each task waits until all are
 * started, then one latch lets them all go. The pool is reused across rounds; close it at the end.
 */
public final class Race implements AutoCloseable {

    private final ExecutorService pool;
    private final int width;

    public Race(int width) {
        this.width = width;
        this.pool = Executors.newFixedThreadPool(width, r -> {
            Thread t = new Thread(r, "race-worker");
            t.setDaemon(true);
            return t;
        });
    }

    /** Runs all tasks concurrently and returns their results in task order. */
    public <T> List<T> run(List<Callable<T>> tasks) {
        if (tasks.size() > width) {
            throw new IllegalArgumentException(tasks.size() + " tasks for " + width + " threads");
        }
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (Callable<T> task : tasks) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return task.call();
            }));
        }
        try {
            ready.await();
            go.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get(30, TimeUnit.SECONDS));
            }
            return results;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("race task failed", e.getCause());
        } catch (TimeoutException e) {
            throw new IllegalStateException("race task hung: possible deadlock", e);
        }
    }

    @SafeVarargs
    public final <T> List<T> run(Callable<T>... tasks) {
        return run(List.of(tasks));
    }

    @Override
    public void close() {
        pool.shutdownNow();
    }
}

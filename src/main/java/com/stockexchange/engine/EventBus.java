package com.stockexchange.engine;

import com.stockexchange.model.ExchangeEvent;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Hands events to listeners on one dispatcher thread, never on a trader thread and never under a
 * company lock. Events are delivered in globalSeq order with no gaps: engines draw sequence
 * numbers inside their locks but publish after unlocking, so publishers can arrive out of order
 * and the dispatcher holds back an event until every lower number was delivered or skipped.
 */
public final class EventBus implements AutoCloseable {

    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);
    /** The name Abud's ShutdownTest looks for when it checks that no dispatcher outlives close(). */
    public static final String THREAD_NAME = "exchange-dispatcher";
    private static final Item STOP = new Item(Long.MAX_VALUE, null);

    private final AtomicLong globalSeq = new AtomicLong();
    private final List<Consumer<ExchangeEvent>> listeners = new CopyOnWriteArrayList<>();
    private final BlockingQueue<Item> queue = new LinkedBlockingQueue<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong listenerFailures = new AtomicLong();
    private final Thread dispatcher;

    public EventBus() {
        dispatcher = new Thread(this::dispatch, THREAD_NAME);
        dispatcher.setDaemon(true);
        dispatcher.start();
    }

    /** Next global sequence number, starting at 1. Engines call this inside their lock. */
    public long nextSeq() {
        return globalSeq.incrementAndGet();
    }

    public void subscribe(Consumer<ExchangeEvent> listener) {
        listeners.add(Objects.requireNonNull(listener));
    }

    public void unsubscribe(Consumer<ExchangeEvent> listener) {
        listeners.remove(listener);
    }

    /** Queues events for delivery and returns at once. Call after the engine lock is released. */
    public void publish(List<ExchangeEvent> events) {
        if (closed.get()) {
            return;
        }
        for (ExchangeEvent event : events) {
            queue.add(new Item(event.globalSeq(), event));
        }
    }

    /** Marks a drawn sequence number that will never be published, so later events are not held back. */
    public void skip(long seq) {
        if (!closed.get()) {
            queue.add(new Item(seq, null));
        }
    }

    /** Number of listener calls that threw. The exception is caught so other listeners still run. */
    public long listenerFailures() {
        return listenerFailures.get();
    }

    /** Delivers everything published so far, stops the dispatcher with a poison pill and joins it. */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        queue.add(STOP);
        if (Thread.currentThread() == dispatcher) {
            return;
        }
        try {
            dispatcher.join(CLOSE_TIMEOUT.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (dispatcher.isAlive()) {
            dispatcher.interrupt();
        }
    }

    private void dispatch() {
        Map<Long, Item> pending = new TreeMap<>();
        long next = 1;
        try {
            while (true) {
                Item item = queue.take();
                if (item == STOP) {
                    // Nothing more will arrive, so a remaining gap can never close: flush in order.
                    pending.values().forEach(this::deliver);
                    return;
                }
                pending.put(item.seq(), item);
                Item head;
                while ((head = pending.remove(next)) != null) {
                    deliver(head);
                    next++;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void deliver(Item item) {
        if (item.event() == null) {
            return;
        }
        for (Consumer<ExchangeEvent> listener : listeners) {
            try {
                listener.accept(item.event());
            } catch (RuntimeException e) {
                listenerFailures.incrementAndGet();
                System.err.println("EventBus: listener failed on event #" + item.seq() + ": " + e);
            }
        }
    }

    /** A queued event, or a skipped sequence number when {@code event} is null. */
    private record Item(long seq, ExchangeEvent event) {}
}

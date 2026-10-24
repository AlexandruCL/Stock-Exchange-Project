package com.stockexchange.faulty;

import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Event consumer thread in two variants. {@code spin=true} is the FAULTY one (bug P2, busy
 * waiting): it polls an empty queue in a tight loop and burns a whole core to wait. {@code
 * spin=false} blocks in {@code take()} and uses no CPU while idle, which is what
 * {@code EventBus}'s dispatcher does. Both deliver the same events in the same order.
 */
public final class SpinningConsumer implements AutoCloseable {

    private static final Object POISON = new Object();

    private final Queue<Object> spinQueue = new ConcurrentLinkedQueue<>();
    private final BlockingQueue<Object> blockQueue = new LinkedBlockingQueue<>();
    private final boolean spin;
    private final AtomicLong consumed = new AtomicLong();
    private final Thread thread;

    public SpinningConsumer(boolean spin) {
        this.spin = spin;
        this.thread = new Thread(this::loop, spin ? "spinning-consumer" : "blocking-consumer");
        thread.setDaemon(true);
        thread.start();
    }

    public void publish(Object event) {
        if (spin) {
            spinQueue.add(event);
        } else {
            blockQueue.add(event);
        }
    }

    public long consumed() {
        return consumed.get();
    }

    public Thread thread() {
        return thread;
    }

    private void loop() {
        try {
            while (true) {
                Object event;
                if (spin) {
                    // BUG P2: no sleep, no blocking, no onSpinWait. CPU is 100% while nothing happens.
                    while ((event = spinQueue.poll()) == null) {
                        // spin
                    }
                } else {
                    event = blockQueue.take();
                }
                if (event == POISON) {
                    return;
                }
                consumed.incrementAndGet();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() throws InterruptedException {
        publish(POISON);
        thread.join(2_000);
    }
}

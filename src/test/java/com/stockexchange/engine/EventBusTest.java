package com.stockexchange.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.stockexchange.model.ExchangeEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class EventBusTest {

    private final EventBus bus = new EventBus();
    private final List<Long> received = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDown() {
        bus.close();
    }

    private static ExchangeEvent event(long seq) {
        return new ExchangeEvent.BookUpdated(seq, "ACME", seq);
    }

    private void record() {
        bus.subscribe(e -> received.add(e.globalSeq()));
    }

    @Test
    void nextSeq_should_startAtOneAndIncrease() {
        assertEquals(1, bus.nextSeq());
        assertEquals(2, bus.nextSeq());
    }

    @Test
    void events_should_arriveInSequenceOrder_when_publishedOutOfOrder() {
        record();

        bus.publish(List.of(event(3), event(4)));
        bus.publish(List.of(event(1)));
        bus.publish(List.of(event(2)));
        bus.close();

        assertEquals(List.of(1L, 2L, 3L, 4L), received);
    }

    @Test
    void laterEvent_should_waitForGap_until_missingSeqIsSkipped() throws Exception {
        CountDownLatch delivered = new CountDownLatch(1);
        bus.subscribe(e -> delivered.countDown());

        bus.publish(List.of(event(2)));
        assertFalse(delivered.await(100, TimeUnit.MILLISECONDS));

        bus.skip(1);
        assertTrue(delivered.await(5, TimeUnit.SECONDS));
    }

    @Test
    void listenerThrowingError_should_notStopDispatcher() {
        bus.subscribe(e -> {
            throw new AssertionError("listener error");
        });
        record();

        bus.publish(List.of(event(1), event(2)));
        bus.close();

        assertEquals(List.of(1L, 2L), received);
        assertEquals(2, bus.listenerFailures());
    }

    @Test
    void throwingListener_should_notStopOtherListeners() {
        bus.subscribe(e -> {
            throw new IllegalStateException("listener bug");
        });
        record();

        bus.publish(List.of(event(1), event(2), event(3)));
        bus.close();

        assertEquals(List.of(1L, 2L, 3L), received);
        assertEquals(3, bus.listenerFailures());
    }

    @Test
    void listeners_should_runOnDispatcherThread_notOnPublisher() {
        AtomicReference<Thread> deliveredOn = new AtomicReference<>();
        bus.subscribe(e -> deliveredOn.set(Thread.currentThread()));

        bus.publish(List.of(event(1)));
        bus.close();

        assertNotSame(Thread.currentThread(), deliveredOn.get());
        assertEquals(EventBus.THREAD_NAME, deliveredOn.get().getName());
        assertTrue(deliveredOn.get().isDaemon());
    }

    @Test
    void unsubscribe_should_stopDelivery() throws Exception {
        CountDownLatch first = new CountDownLatch(1);
        Consumer<ExchangeEvent> listener = e -> {
            received.add(e.globalSeq());
            first.countDown();
        };
        bus.subscribe(listener);
        bus.publish(List.of(event(1)));
        assertTrue(first.await(5, TimeUnit.SECONDS));

        bus.unsubscribe(listener);
        bus.publish(List.of(event(2)));
        bus.close();

        assertEquals(List.of(1L), received);
    }

    @Test
    @Timeout(10)
    void close_should_drainPendingEvents_and_terminateDispatcher() {
        AtomicReference<Thread> dispatcher = new AtomicReference<>();
        bus.subscribe(e -> {
            dispatcher.set(Thread.currentThread());
            try {
                Thread.sleep(1);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            received.add(e.globalSeq());
        });
        List<ExchangeEvent> events = new ArrayList<>();
        for (long seq = 1; seq <= 200; seq++) {
            events.add(event(seq));
        }

        bus.publish(events);
        bus.close();

        assertEquals(200, received.size());
        assertFalse(dispatcher.get().isAlive());
    }

    @Test
    void close_should_flushHeldBackEvents_when_gapNeverCloses() {
        record();

        bus.publish(List.of(event(5), event(3)));
        bus.close();

        assertEquals(List.of(3L, 5L), received);
    }

    @Test
    void publishAndClose_should_doNothing_after_close() {
        record();
        bus.close();

        bus.publish(List.of(event(1)));
        bus.skip(2);
        bus.close();

        assertTrue(received.isEmpty());
    }

    /** Eight publishers draw numbers and publish without coordination, so arrival order is mixed. */
    @Test
    @Timeout(30)
    void concurrentPublishers_should_deliverEveryEventOnceInOrder() throws Exception {
        record();
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> publishers = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            Thread publisher = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    return;
                }
                for (int i = 0; i < 2_000; i++) {
                    bus.publish(List.of(event(bus.nextSeq())));
                }
            });
            publishers.add(publisher);
            publisher.start();
        }
        start.countDown();
        for (Thread publisher : publishers) {
            publisher.join();
        }
        bus.close();

        assertEquals(16_000, received.size());
        for (int i = 0; i < received.size(); i++) {
            assertEquals((long) i + 1, received.get(i));
        }
    }
}

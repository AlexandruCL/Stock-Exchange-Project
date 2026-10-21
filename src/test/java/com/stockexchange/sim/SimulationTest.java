package com.stockexchange.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.stockexchange.engine.Exchange;
import com.stockexchange.engine.StubExchange;
import com.stockexchange.model.SeedData;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class SimulationTest {
    private final AtomicInteger closes = new AtomicInteger();
    private final Set<Thread> workers = ConcurrentHashMap.newKeySet();
    private final AtomicReference<Boolean> closedAfterWorkers = new AtomicReference<>();

    private Exchange exchange() {
        StubExchange stub = new StubExchange();
        return (Exchange) Proxy.newProxyInstance(Exchange.class.getClassLoader(),
                new Class<?>[] {Exchange.class}, (proxy, method, args) -> {
                    if (method.getName().equals("close")) {
                        closedAfterWorkers.set(workers.stream().noneMatch(Thread::isAlive));
                        closes.incrementAndGet();
                    }
                    try {
                        return method.invoke(stub, args);
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                });
    }

    @Test
    void durationStopsWorkersBeforeClosingExchange() throws InterruptedException {
        CountDownLatch entered = new CountDownLatch(SeedData.TRADER_COUNT);
        Simulation simulation = new Simulation(exchange(), new SeedData(), 42, 1, id ->
                (ex, trader, sequence, random) -> {
                    workers.add(Thread.currentThread());
                    entered.countDown();
                    entered.await();
                });
        simulation.run(Duration.ofSeconds(1));
        simulation.close();
        assertEquals(0, entered.getCount());
        assertEquals(1, closes.get());
        assertEquals(Boolean.TRUE, closedAfterWorkers.get());
        assertThrows(IllegalStateException.class, () -> simulation.run(Duration.ofSeconds(1)));
    }

    @Test
    void interruptionCleansUpAndPropagates() throws InterruptedException {
        CountDownLatch entered = new CountDownLatch(1);
        AtomicReference<Throwable> result = new AtomicReference<>();
        Simulation simulation = new Simulation(exchange(), new SeedData(), 42, 1, id ->
                (ex, trader, sequence, random) -> {
                    workers.add(Thread.currentThread());
                    entered.countDown();
                    Thread.sleep(60_000);
                });
        Thread runner = new Thread(() -> {
            try {
                simulation.run(Duration.ofSeconds(60));
            } catch (Throwable exception) {
                result.set(exception);
            }
        });
        runner.start();
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS));
        } finally {
            runner.interrupt();
            runner.join(3000);
        }
        assertFalse(runner.isAlive());
        assertTrue(result.get() instanceof InterruptedException);
        assertEquals(1, closes.get());
        assertEquals(Boolean.TRUE, closedAfterWorkers.get());
    }

    @Test
    void workerFailureIsReportedAndExchangeCloses() {
        Simulation simulation = new Simulation(exchange(), new SeedData(), 42, 1, id ->
                (ex, trader, sequence, random) -> {
                    workers.add(Thread.currentThread());
                    throw new IllegalArgumentException("strategy failed");
                });
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> simulation.run(Duration.ofSeconds(60)));
        assertNotNull(failure.getCause());
        assertEquals(1, closes.get());
        assertEquals(Boolean.TRUE, closedAfterWorkers.get());
    }

    @Test
    void closeBeforeRunIsIdempotent() {
        Simulation simulation = new Simulation(exchange(), new SeedData(), 42, 1);
        simulation.close();
        simulation.close();
        assertEquals(1, closes.get());
        assertThrows(IllegalStateException.class, () -> simulation.run(Duration.ofSeconds(1)));
    }

    @Test
    void externalCloseWakesRunnerAndJoinsWorkers() throws InterruptedException {
        CountDownLatch entered = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Simulation simulation = new Simulation(exchange(), new SeedData(), 42, 1, id ->
                (ex, trader, sequence, random) -> {
                    workers.add(Thread.currentThread());
                    entered.countDown();
                    Thread.sleep(60_000);
                });
        Thread runner = new Thread(() -> {
            try {
                simulation.run(Duration.ofSeconds(60));
            } catch (Throwable exception) {
                failure.set(exception);
            }
        });
        runner.start();
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS));
        } finally {
            simulation.close();
            runner.join(3000);
        }
        assertFalse(runner.isAlive());
        assertEquals(null, failure.get());
        assertEquals(1, closes.get());
        assertEquals(Boolean.TRUE, closedAfterWorkers.get());
    }
}

package com.stockexchange.verify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.stockexchange.engine.Exchange;
import com.stockexchange.model.BookSnapshot;
import com.stockexchange.model.ExchangeEvent;
import com.stockexchange.model.OrderResult;
import com.stockexchange.model.OrderState;
import com.stockexchange.model.OrderView;
import com.stockexchange.model.PortfolioView;
import com.stockexchange.model.Reject;
import com.stockexchange.model.SeedData;
import com.stockexchange.model.Side;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * 50 threads, the 5 seed companies, 100,000 seeded place/modify/cancel operations. Each thread has its
 * own Random(SEED + thread), so the order stream is reproducible; the interleaving is not. A reader
 * thread checks every snapshot it sees while trading runs. The test timeout catches deadlocks.
 */
class StressTest {

    static final long SEED = 42;
    static final int THREADS = 50;
    static final int OPERATIONS = 100_000;

    @Test
    @Timeout(60)
    void randomOperations_should_keepAllInvariants() throws Exception {
        SeedData seed = new SeedData();
        List<String> tickers = SeedData.TICKERS;
        Map<String, Long> basePrice = new ConcurrentHashMap<>();
        seed.companies().forEach(c -> basePrice.put(c.id(), c.initialLastPrice()));

        Map<Reject, LongAdder> rejects = new ConcurrentHashMap<>();
        LongAdder accepted = new LongAdder();
        LongAdder places = new LongAdder();
        LongAdder created = new LongAdder();
        AtomicLong lastEventSeq = new AtomicLong();
        AtomicReference<String> problem = new AtomicReference<>();

        try (Exchange ex = TestExchanges.create(seed.companies(), seed.traders())) {
            ex.subscribe(event -> {
                if (event.globalSeq() <= lastEventSeq.getAndSet(event.globalSeq())) {
                    problem.compareAndSet(null, "event out of order at globalSeq " + event.globalSeq());
                }
                if (event instanceof ExchangeEvent.OrderCreated) {
                    created.increment();
                }
            });

            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(THREADS);
            List<Thread> workers = new ArrayList<>();
            for (int t = 0; t < THREADS; t++) {
                int thread = t;
                Thread worker = new Thread(() -> {
                    try {
                        start.await();
                        runWorker(ex, thread, tickers, basePrice, accepted, places, rejects);
                    } catch (Throwable e) {
                        problem.compareAndSet(null, "worker " + thread + ": " + e);
                    } finally {
                        done.countDown();
                    }
                }, "stress-" + t);
                workers.add(worker);
                worker.start();
            }

            Thread reader = new Thread(() -> readWhileTrading(ex, done, problem), "stress-reader");
            reader.start();
            start.countDown();

            assertTrue(done.await(50, TimeUnit.SECONDS), "workers did not finish: possible deadlock");
            reader.join(5_000);
            for (Thread worker : workers) {
                worker.join(1_000);
            }
            ex.close();

            assertNull(problem.get(), problem.get());
            InvariantChecker.check(ex, seed);

            long trades = tickers.stream().mapToLong(c -> ex.history(c).size()).sum();
            long placed = places.sum();
            assertEquals(placed, created.sum(), "every accepted place produced one delivered OrderCreated");
            assertTrue(trades > 1_000, "stress run should trade a lot, traded " + trades);
            System.out.printf("stress: %d ops, %d accepted (%d places), %d trades, rejects %s%n",
                    OPERATIONS, accepted.sum(), placed, trades, summary(rejects));
        }
    }

    private static void runWorker(Exchange ex, int thread, List<String> tickers, Map<String, Long> basePrice,
                                  LongAdder accepted, LongAdder places, Map<Reject, LongAdder> rejects) {
        Random rnd = new Random(SEED + thread);
        long trader = thread % SeedData.TRADER_COUNT + 1;
        // Last view of this thread's orders that may still rest. Fills make versions stale; that is expected.
        List<OrderView> open = new ArrayList<>();
        for (int i = 0; i < OPERATIONS / THREADS; i++) {
            OrderResult result;
            if (open.isEmpty() || rnd.nextInt(100) < 60) {
                String company = tickers.get(rnd.nextInt(tickers.size()));
                long base = basePrice.get(company);
                long price = Math.max(1, base + (rnd.nextInt(11) - 5) * base / 100);
                Side side = rnd.nextBoolean() ? Side.BID : Side.ASK;
                result = ex.place("t" + thread + "-" + i, trader, company, side, price, 1 + rnd.nextInt(20));
                if (result.ok()) {
                    places.increment();
                }
            } else {
                OrderView order = open.remove(rnd.nextInt(open.size()));
                if (rnd.nextBoolean()) {
                    long price = Math.max(1, order.price() + rnd.nextInt(5) - 2);
                    result = ex.modify(trader, order.id(), order.version(), price, order.qty() + rnd.nextInt(3));
                } else {
                    result = ex.cancel(trader, order.id());
                }
            }
            if (result.ok()) {
                accepted.increment();
                if (result.order().state() == OrderState.OPEN) {
                    open.add(result.order());
                }
            } else {
                rejects.computeIfAbsent(result.reason(), r -> new LongAdder()).increment();
            }
        }
    }

    /** Lock-free reads during trading must only ever show complete states. */
    private static void readWhileTrading(Exchange ex, CountDownLatch done,
                                         AtomicReference<String> problem) {
        long reads = 0;
        while (done.getCount() > 0 && problem.get() == null) {
            for (String company : ex.companies()) {
                BookSnapshot book = ex.book(company);
                List<String> bad = InvariantChecker.bookViolations(book);
                if (!bad.isEmpty()) {
                    problem.compareAndSet(null, "reader saw " + bad);
                }
            }
            for (int id = 1; id <= SeedData.TRADER_COUNT; id++) {
                PortfolioView p = ex.portfolio(id);
                if (p.cash() < 0 || p.reservedCash() < 0
                        || p.shares().values().stream().anyMatch(v -> v < 0)
                        || p.reservedShares().values().stream().anyMatch(v -> v < 0)) {
                    problem.compareAndSet(null, "reader saw negative portfolio " + p);
                }
            }
            reads++;
        }
        if (reads == 0) {
            problem.compareAndSet(null, "reader never ran");
        }
    }

    private static Map<Reject, Long> summary(Map<Reject, LongAdder> rejects) {
        Map<Reject, Long> out = new EnumMap<>(Reject.class);
        rejects.forEach((r, n) -> out.put(r, n.sum()));
        return out;
    }
}

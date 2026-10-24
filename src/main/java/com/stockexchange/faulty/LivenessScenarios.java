package com.stockexchange.faulty;

import com.stockexchange.engine.AccountService;
import com.stockexchange.engine.Accounts;
import com.stockexchange.engine.MatchingEngine;
import com.stockexchange.faulty.FaultyAccounts.LockStrategy;
import com.stockexchange.model.Company;
import com.stockexchange.model.Order;
import com.stockexchange.model.SeedData;
import com.stockexchange.model.Side;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The liveness and performance bugs of {@link FaultyAccounts}, {@link CoarseLockEngines} and
 * {@link SpinningConsumer} as runnable experiments. Every thread is a daemon, so a deadlocked
 * scenario can be abandoned: the stuck threads stay stuck until the JVM exits.
 *
 * <p>All workloads work at engine level: threads call {@code MatchingEngine.submit} directly,
 * the way {@code StockExchange} does after validation. A "pair" is two orders that cross and fill
 * each other completely, so every iteration ends with one settled trade and an empty book.
 */
public final class LivenessScenarios {

    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();

    /** {@code deadlocked} lists the stuck threads and what each waits for; empty when none was found in time. */
    public record DeadlockResult(boolean deadlocked, long tradesBeforeStuck, List<String> description) {}

    public record LivelockResult(long retries, long settledFaulty, long settledOrdered, long millis) {}

    public record ThroughputRow(int threads, long coarseOpsPerSecond, long fineOpsPerSecond) {}

    public record CpuResult(long spinCpuMillis, long spinWallMillis, long blockCpuMillis, long blockWallMillis) {}

    private LivenessScenarios() {
    }

    /**
     * Bug L1. Thread X trades traders 1 and 2 in ACME, thread Y trades the same two traders in BETA.
     * Directions alternate, so sometimes X settles (buyer 2, seller 1) while Y settles (buyer 1, seller 2).
     * UNORDERED then locks 2 then 1 in X and 1 then 2 in Y: a cycle. The two company locks do not help,
     * they are different locks.
     */
    public static DeadlockResult lockOrderDeadlock(long maxMillis, long holdFirstLockNanos) throws InterruptedException {
        SeedData seed = new SeedData();
        FaultyAccounts accounts = new FaultyAccounts(seed.traders(), LockStrategy.UNORDERED, holdFirstLockNanos, null);
        Map<String, MatchingEngine> engines = engines(seed, accounts);
        AtomicLong ids = new AtomicLong();
        AtomicLong trades = new AtomicLong();
        startPingPong("lock-order", engines.get("ACME"), seed.companies().get(0), 1, 2, ids, trades, new AtomicBoolean());
        startPingPong("lock-order", engines.get("BETA"), seed.companies().get(1), 1, 2, ids, trades, new AtomicBoolean());
        return awaitDeadlock("lock-order", maxMillis, trades);
    }

    /**
     * Bug L2. Correct lock order, but {@code settle} calls a listener under the company lock. The listener
     * is a "hedger": after a trade in one company it books a small order in the other company. Thread X
     * holds ACME and wants BETA, thread Y holds BETA and wants ACME.
     */
    public static DeadlockResult callbackDeadlock(long maxMillis) throws InterruptedException {
        SeedData seed = new SeedData();
        Map<String, MatchingEngine> engines = new HashMap<>();
        AtomicLong ids = new AtomicLong();
        AtomicLong trades = new AtomicLong();
        FaultyAccounts accounts = new FaultyAccounts(seed.traders(), LockStrategy.ORDERED, 0, trade -> {
            String other = trade.companyId().equals("ACME") ? "BETA" : "ACME";
            Order hedge = new Order(ids.incrementAndGet(), 20, other, Side.BID, 1, 1);
            engines.get(other).submit(hedge);
        });
        engines.putAll(engines(seed, accounts));
        startPingPong("callback", engines.get("ACME"), seed.companies().get(0), 3, 4, ids, trades, new AtomicBoolean());
        startPingPong("callback", engines.get("BETA"), seed.companies().get(1), 5, 6, ids, trades, new AtomicBoolean());
        return awaitDeadlock("callback", maxMillis, trades);
    }

    /**
     * Bug L3 against the same workload as L1 with the correct ORDERED strategy as the baseline.
     * Both run for {@code millis}. Progress is the number of settled trades.
     */
    public static LivelockResult livelock(long millis, long holdFirstLockNanos) throws InterruptedException {
        long ordered = runSettling(LockStrategy.ORDERED, millis, holdFirstLockNanos).settledTrades();
        FaultyAccounts faulty = runSettling(LockStrategy.TRY_LOCK_RETRY, millis, holdFirstLockNanos);
        return new LivelockResult(faulty.retries(), faulty.settledTrades(), ordered, millis);
    }

    private static FaultyAccounts runSettling(LockStrategy strategy, long millis, long hold) throws InterruptedException {
        SeedData seed = new SeedData();
        FaultyAccounts accounts = new FaultyAccounts(seed.traders(), strategy, hold, null);
        Map<String, MatchingEngine> engines = engines(seed, accounts);
        AtomicLong ids = new AtomicLong();
        AtomicLong trades = new AtomicLong();
        AtomicBoolean stop = new AtomicBoolean();
        startPingPong("livelock", engines.get("ACME"), seed.companies().get(0), 1, 2, ids, trades, stop);
        startPingPong("livelock", engines.get("BETA"), seed.companies().get(1), 1, 2, ids, trades, stop);
        Thread.sleep(millis);
        stop.set(true);
        return accounts;
    }

    /**
     * Bug P1. {@code threads} workers, each trading in its own company with its own two traders, so
     * nothing is shared between them except, in the coarse version, the global monitor. Every worker
     * does the same fixed number of pairs, not "as many as fit in a time window": the engine keeps
     * its history in a CopyOnWriteArrayList, so a trade gets slower the longer the history is, and a
     * time window would compare runs of different cost.
     */
    public static List<ThroughputRow> granularity(int maxThreads, int pairsPerThread) throws InterruptedException {
        List<ThroughputRow> rows = new ArrayList<>();
        measure(1, pairsPerThread, true);
        measure(1, pairsPerThread, false);
        for (int threads = 1; threads <= maxThreads; threads++) {
            rows.add(new ThroughputRow(threads, bestOf3(threads, pairsPerThread, true),
                    bestOf3(threads, pairsPerThread, false)));
        }
        return rows;
    }

    private static long bestOf3(int threads, int pairs, boolean coarse) throws InterruptedException {
        long best = 0;
        for (int i = 0; i < 3; i++) {
            best = Math.max(best, measure(threads, pairs, coarse));
        }
        return best;
    }

    // 15 short rounds on fresh engines: the history stays small, so the cost per order stays the same.
    private static long measure(int threads, int pairs, boolean coarse) throws InterruptedException {
        long nanos = 0;
        for (int round = 0; round < 15; round++) {
            nanos += oneRound(threads, pairs, coarse);
        }
        return 15L * 2 * pairs * threads * 1_000_000_000L / nanos;
    }

    private static long oneRound(int threads, int pairs, boolean coarse) throws InterruptedException {
        SeedData seed = new SeedData();
        Accounts accounts = new AccountService(seed.traders());
        CoarseLockEngines coarseEngines = coarse ? new CoarseLockEngines(seed.companies(), accounts) : null;
        Map<String, MatchingEngine> fine = coarse ? null : engines(seed, accounts);
        AtomicLong ids = new AtomicLong();
        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Company company = seed.companies().get(i);
            long a = 4L * i + 1;
            long b = 4L * i + 2;
            Thread worker = new Thread(() -> {
                for (int n = 0; n < pairs; n++) {
                    long seller = n % 2 == 0 ? a : b;
                    long buyer = n % 2 == 0 ? b : a;
                    Order ask = new Order(ids.incrementAndGet(), seller, company.id(), Side.ASK, company.initialLastPrice(), 1);
                    Order bid = new Order(ids.incrementAndGet(), buyer, company.id(), Side.BID, company.initialLastPrice(), 1);
                    if (coarse) {
                        coarseEngines.submit(ask);
                        coarseEngines.submit(bid);
                    } else {
                        fine.get(company.id()).submit(ask);
                        fine.get(company.id()).submit(bid);
                    }
                }
            }, "perf-" + (coarse ? "coarse-" : "fine-") + i);
            worker.setDaemon(true);
            workers.add(worker);
        }
        long start = System.nanoTime();
        workers.forEach(Thread::start);
        for (Thread worker : workers) {
            worker.join();
        }
        return System.nanoTime() - start;
    }

    /**
     * Bug P2. The producer publishes an event about every 5 ms (more on a coarse OS timer) for
     * {@code millis}; the consumer's own CPU time is the measure, next to the wall time of its run.
     */
    public static CpuResult busyWait(long millis) throws InterruptedException {
        long[] spin = runConsumer(true, millis);
        long[] block = runConsumer(false, millis);
        return new CpuResult(spin[0], spin[1], block[0], block[1]);
    }

    private static long[] runConsumer(boolean spin, long millis) throws InterruptedException {
        long start = System.nanoTime();
        try (SpinningConsumer consumer = new SpinningConsumer(spin)) {
            while (System.nanoTime() - start < millis * 1_000_000) {
                consumer.publish(start);
                Thread.sleep(5);
            }
            return new long[] {cpuMillis(consumer.thread()), (System.nanoTime() - start) / 1_000_000};
        }
    }

    private static long cpuMillis(Thread thread) {
        return THREADS.getThreadCpuTime(thread.getId()) / 1_000_000;
    }

    private static Map<String, MatchingEngine> engines(SeedData seed, Accounts accounts) {
        Map<String, MatchingEngine> engines = new HashMap<>();
        for (Company company : seed.companies()) {
            engines.put(company.id(), new MatchingEngine(company, accounts));
        }
        return engines;
    }

    /**
     * One thread, endless loop: rest an ask, cross it with a bid, repeat, swapping seller and buyer
     * every iteration so that neither trader runs out of shares.
     */
    private static void startPingPong(String tag, MatchingEngine engine, Company company, long a, long b, AtomicLong ids,
                                      AtomicLong trades, AtomicBoolean stop) {
        Thread thread = new Thread(() -> {
            long n = 0;
            while (!stop.get()) {
                long seller = n % 2 == 0 ? a : b;
                long buyer = n % 2 == 0 ? b : a;
                engine.submit(new Order(ids.incrementAndGet(), seller, company.id(), Side.ASK, company.initialLastPrice(), 1));
                engine.submit(new Order(ids.incrementAndGet(), buyer, company.id(), Side.BID, company.initialLastPrice(), 1));
                trades.incrementAndGet();
                n++;
            }
        }, tag + "-pingpong-" + company.id() + "-" + a + "-" + b);
        thread.setDaemon(true);
        thread.start();
    }

    private static DeadlockResult awaitDeadlock(String tag, long maxMillis, AtomicLong trades) throws InterruptedException {
        long deadline = System.currentTimeMillis() + maxMillis;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
            long[] stuck = THREADS.findDeadlockedThreads();
            List<String> mine = stuck == null ? List.of() : describe(stuck, tag);
            if (!mine.isEmpty()) {
                return new DeadlockResult(true, trades.get(), mine);
            }
        }
        return new DeadlockResult(false, trades.get(), List.of());
    }

    // Only this scenario's threads: an earlier scenario's deadlocked threads stay stuck for good.
    private static List<String> describe(long[] ids, String tag) {
        List<String> lines = new ArrayList<>();
        for (ThreadInfo info : THREADS.getThreadInfo(ids, true, true)) {
            if (info.getThreadName().startsWith(tag + "-")) {
                lines.add(info.getThreadName() + " is " + info.getThreadState() + ", waits for "
                        + info.getLockName() + " held by " + info.getLockOwnerName());
            }
        }
        return lines;
    }
}

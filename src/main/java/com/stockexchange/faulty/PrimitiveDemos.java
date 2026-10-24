package com.stockexchange.faulty;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The exchange bugs in isolation, one primitive at a time, so each fix can be explained in a
 * sentence. Run: {@code java -cp target/classes com.stockexchange.faulty.PrimitiveDemos}.
 * (1) lost update, (2) check-then-act, (3) visibility. Results of (1) and (2) vary per run, and
 * (3) depends on the JIT, so a "no problem seen" line is possible and proves nothing.
 */
public final class PrimitiveDemos {

    private static final int THREADS = 8;
    private static final int PER_THREAD = 500_000;

    private static long plain;
    private static volatile long volatileCounter;
    private static long synced;
    private static final Object MONITOR = new Object();
    private static final AtomicLong atomic = new AtomicLong();
    private static long locked;
    private static final ReentrantLock LOCK = new ReentrantLock();
    private static boolean plainFlag = true;
    private static volatile boolean volatileFlag = true;

    private PrimitiveDemos() {
    }

    public static void main(String[] args) throws Exception {
        lostUpdate();
        checkThenAct();
        visibility();
    }

    /** Bug #2 and #6: "x++" is read, add, write. volatile fixes visibility, not atomicity. */
    private static void lostUpdate() throws InterruptedException {
        long expected = (long) THREADS * PER_THREAD;
        System.out.println("--- 1. lost update, expected " + expected + " ---");
        run(() -> plain++);
        System.out.println("plain long           : " + plain);
        run(() -> volatileCounter++);
        System.out.println("volatile long        : " + volatileCounter + "   (still wrong: ++ is 3 steps)");
        run(() -> {
            synchronized (MONITOR) {
                synced++;
            }
        });
        System.out.println("synchronized         : " + synced);
        run(() -> {
            LOCK.lock();
            try {
                locked++;
            } finally {
                LOCK.unlock();
            }
        });
        System.out.println("ReentrantLock        : " + locked);
        run(atomic::incrementAndGet);
        System.out.println("AtomicLong (CAS)     : " + atomic.get());
    }

    /** Bug #1 and #11: two threads both see "absent" and both act. */
    private static void checkThenAct() throws InterruptedException {
        System.out.println("--- 2. check-then-act on an idempotency key, 8 threads, all using the same 100000 keys ---");
        Map<Integer, Integer> plainMap = new HashMap<>();
        Map<Integer, Integer> safeMap = new ConcurrentHashMap<>();
        AtomicLong plainWins = new AtomicLong();
        AtomicLong safeWins = new AtomicLong();
        Thread[] threads = new Thread[THREADS];
        for (int t = 0; t < THREADS; t++) {
            threads[t] = new Thread(() -> {
                for (int key = 0; key < 100_000; key++) {
                    if (!plainMap.containsKey(key)) {
                        plainMap.put(key, 1);
                        plainWins.incrementAndGet();
                    }
                    if (safeMap.putIfAbsent(key, 1) == null) {
                        safeWins.incrementAndGet();
                    }
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join();
        }
        System.out.println("containsKey + put (HashMap)       : " + plainWins + " threads 'won' a key, ideal is 100000");
        System.out.println("putIfAbsent (ConcurrentHashMap)   : " + safeWins);
    }

    /** Bug #7: without volatile (or any synchronization) the reader may never see the write. */
    private static void visibility() throws InterruptedException {
        System.out.println("--- 3. visibility: reader spins on a flag, writer clears it after 200 ms ---");
        System.out.println("plain boolean   : " + (spinUntilCleared(false) ? "reader stopped" : "reader STILL SPINNING after 3 s (JIT hoisted the read)"));
        System.out.println("volatile boolean: " + (spinUntilCleared(true) ? "reader stopped" : "reader still spinning"));
    }

    private static boolean spinUntilCleared(boolean useVolatile) throws InterruptedException {
        plainFlag = true;
        volatileFlag = true;
        Thread reader = new Thread(() -> {
            long spins = 0;
            if (useVolatile) {
                while (volatileFlag) {
                    spins++;
                }
            } else {
                while (plainFlag) {
                    spins++;
                }
            }
            if (spins < 0) {
                System.out.println(spins);
            }
        });
        reader.setDaemon(true);
        reader.start();
        Thread.sleep(200);
        plainFlag = false;
        volatileFlag = false;
        reader.join(3_000);
        return !reader.isAlive();
    }

    private static void run(Runnable increment) throws InterruptedException {
        Thread[] threads = new Thread[THREADS];
        for (int t = 0; t < THREADS; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < PER_THREAD; i++) {
                    increment.run();
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join();
        }
    }
}

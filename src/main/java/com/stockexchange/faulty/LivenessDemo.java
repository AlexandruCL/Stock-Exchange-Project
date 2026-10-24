package com.stockexchange.faulty;

import com.stockexchange.faulty.LivenessScenarios.CpuResult;
import com.stockexchange.faulty.LivenessScenarios.DeadlockResult;
import com.stockexchange.faulty.LivenessScenarios.LivelockResult;
import com.stockexchange.faulty.LivenessScenarios.ThroughputRow;

/**
 * Prints what each liveness or performance bug does. Usage:
 * {@code java -cp target/classes com.stockexchange.faulty.LivenessDemo [lock-order|callback|livelock|granularity|busy-wait|all]}
 */
public final class LivenessDemo {

    private LivenessDemo() {
    }

    public static void main(String[] args) throws Exception {
        String which = args.length > 0 ? args[0] : "all";
        boolean all = which.equals("all");
        if (all || which.equals("lock-order")) {
            System.out.println("=== L1: deadlock, inconsistent lock order (buyer first, not lower id first) ===");
            printDeadlock(LivenessScenarios.lockOrderDeadlock(20_000, 0));
        }
        if (all || which.equals("callback")) {
            System.out.println("=== L2: deadlock, listener called while the company lock is held ===");
            printDeadlock(LivenessScenarios.callbackDeadlock(20_000));
        }
        if (all || which.equals("livelock")) {
            System.out.println("=== L3: livelock, tryLock and immediate retry (3 s each) ===");
            LivelockResult r = LivenessScenarios.livelock(3_000, 20_000);
            System.out.printf("ORDERED (correct):  %d trades settled%n", r.settledOrdered());
            System.out.printf("TRY_LOCK_RETRY:     %d trades settled, %d retries (every retry is a lock taken and given back)%n",
                    r.settledFaulty(), r.retries());
        }
        if (all || which.equals("granularity")) {
            System.out.println("=== P1: one global lock vs one lock per company (orders/second, 15 rounds of 4000 orders per thread, best of 3) ===");
            System.out.println("threads   global lock   per-company");
            for (ThroughputRow row : LivenessScenarios.granularity(5, 2_000)) {
                System.out.printf("%7d %13d %13d%n", row.threads(), row.coarseOpsPerSecond(), row.fineOpsPerSecond());
            }
        }
        if (all || which.equals("busy-wait")) {
            System.out.println("=== P2: busy waiting vs blocking queue (CPU time of the idle consumer) ===");
            CpuResult r = LivenessScenarios.busyWait(2_000);
            System.out.printf("spinning consumer: %d ms CPU in %d ms wall%n", r.spinCpuMillis(), r.spinWallMillis());
            System.out.printf("blocking consumer: %d ms CPU in %d ms wall%n", r.blockCpuMillis(), r.blockWallMillis());
        }
        System.exit(0);
    }

    private static void printDeadlock(DeadlockResult r) {
        if (!r.deadlocked()) {
            System.out.println("no deadlock within the time limit after " + r.tradesBeforeStuck() + " trades (probabilistic, run again)");
            return;
        }
        System.out.println("DEADLOCK after " + r.tradesBeforeStuck() + " trades. ThreadMXBean.findDeadlockedThreads():");
        r.description().forEach(line -> System.out.println("  " + line));
    }
}

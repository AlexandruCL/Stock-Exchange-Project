package com.stockexchange.faulty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.stockexchange.engine.MatchingEngine;
import com.stockexchange.faulty.FaultyAccounts.LockStrategy;
import com.stockexchange.model.Order;
import com.stockexchange.model.SeedData;
import com.stockexchange.model.Side;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Not regression tests: they prove the faulty classes are faulty. The deadlocks are detected by
 * the JVM itself (ThreadMXBean). The deadlocked daemon threads stay stuck until the test JVM ends.
 */
class LivenessScenariosTest {

    @Test
    @Timeout(60)
    void inconsistentLockOrder_should_deadlock() throws Exception {
        var result = LivenessScenarios.lockOrderDeadlock(30_000, 0);
        assertTrue(result.deadlocked(), "no deadlock in 30 s; the window is tiny, re-run");
        assertEquals(2, result.description().size());
    }

    @Test
    @Timeout(60)
    void listenerCalledUnderCompanyLock_should_deadlock() throws Exception {
        var result = LivenessScenarios.callbackDeadlock(30_000);
        assertTrue(result.deadlocked(), "no deadlock in 30 s, re-run");
    }

    @Test
    @Timeout(30)
    void spinningConsumer_should_burnFarMoreCpuThanBlockingOne() throws Exception {
        var result = LivenessScenarios.busyWait(1_000);
        assertTrue(result.spinCpuMillis() > 10 * Math.max(1, result.blockCpuMillis()),
                "spin " + result.spinCpuMillis() + " ms vs block " + result.blockCpuMillis() + " ms");
    }

    @ParameterizedTest
    @EnumSource(LockStrategy.class)
    @Timeout(10)
    void everyStrategy_should_settleCorrectlyOnASingleThread(LockStrategy strategy) {
        SeedData seed = new SeedData();
        FaultyAccounts accounts = new FaultyAccounts(seed.traders(), strategy, 0, null);
        var acme = seed.companies().get(0);
        MatchingEngine engine = new MatchingEngine(acme, accounts);
        engine.submit(new Order(1, 1, "ACME", Side.ASK, 1_000, 5));
        var result = engine.submit(new Order(2, 2, "ACME", Side.BID, 1_000, 5));
        assertEquals(1, result.trades().size());
        assertEquals(1, accounts.settledTrades());
        assertEquals(0, accounts.retries());
        assertEquals(seed.totalCash(), accounts.totalCash(), "only liveness is faulty, never balances");
    }
}

package com.stockexchange.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.stockexchange.engine.StubExchange;
import com.stockexchange.model.Side;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TraderTest {
    @Test
    void trader_placesOrdersThroughExchange_and_stopsOnInterrupt() throws InterruptedException {
        StubExchange exchange = new StubExchange();
        CountDownLatch placed = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        Trader trader = new Trader(1, exchange, (ex, id, sequence, random) -> {
            if (ex.place(id + "-" + sequence, id, "ACME", Side.BID, 1000, 10).ok()) {
                accepted.incrementAndGet();
            }
            placed.countDown();
        }, 42, 60_000);
        trader.start();
        try {
            assertTrue(placed.await(5, TimeUnit.SECONDS));
        } finally {
            trader.shutdown();
            trader.join(5000);
        }
        assertFalse(trader.isAlive());
        assertEquals(1, accepted.get());
        assertEquals(1, exchange.cancel(1, 1).order().id());
    }

    @Test
    void shutdownBeforeStart_preventsActions() throws InterruptedException {
        AtomicInteger actions = new AtomicInteger();
        Trader trader = new Trader(1, new StubExchange(),
                (ex, id, sequence, random) -> actions.incrementAndGet(), 42, 1);
        trader.shutdown();
        trader.start();
        trader.join(5000);
        assertFalse(trader.isAlive());
        assertEquals(0, actions.get());
    }

    @Test
    void sameSeedAndTrader_produceSameRandomSequence() throws InterruptedException {
        assertEquals(samples(42, 1), samples(42, 1));
        assertFalse(samples(42, 1).equals(samples(42, 2)));
    }

    private List<Long> samples(long seed, long traderId) throws InterruptedException {
        List<Long> samples = new ArrayList<>();
        List<Long> sequences = new ArrayList<>();
        Trader trader = new Trader(traderId, new StubExchange(), (ex, id, sequence, random) -> {
            samples.add(random.nextLong());
            sequences.add(sequence);
            if (sequence == 5) {
                Thread.currentThread().interrupt();
            }
        }, seed, 1);
        trader.start();
        trader.join(5000);
        if (trader.isAlive()) {
            trader.shutdown();
            trader.join(5000);
        }
        assertFalse(trader.isAlive());
        assertEquals(5, samples.size());
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L), sequences);
        return samples;
    }
}

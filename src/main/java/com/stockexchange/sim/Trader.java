package com.stockexchange.sim;

import com.stockexchange.engine.Exchange;
import java.util.Objects;
import java.util.SplittableRandom;

/** A single trader thread. The simulation owns and closes the shared exchange. */
public final class Trader extends Thread {
    private final long traderId;
    private final Exchange exchange;
    private final TradingStrategy strategy;
    private final SplittableRandom random;
    private final long pauseMillis;
    private volatile boolean stopping;

    public Trader(long traderId, Exchange exchange, TradingStrategy strategy,
                  long seed, long pauseMillis) {
        super("trader-" + traderId);
        if (traderId <= 0) {
            throw new IllegalArgumentException("traderId must be positive");
        }
        if (pauseMillis <= 0) {
            throw new IllegalArgumentException("pauseMillis must be positive");
        }
        this.traderId = traderId;
        this.exchange = Objects.requireNonNull(exchange, "exchange");
        this.strategy = Objects.requireNonNull(strategy, "strategy");
        this.random = new SplittableRandom(seed + traderId);
        this.pauseMillis = pauseMillis;
    }

    /** Also prevents actions if requested before start(). Call join() to await termination. */
    public void shutdown() {
        stopping = true;
        interrupt();
    }

    @Override
    public void run() {
        long sequence = 1;
        try {
            while (!stopping && !isInterrupted()) {
                strategy.act(exchange, traderId, sequence++, random);
                if (!stopping && !isInterrupted()) {
                    Thread.sleep(pauseMillis);
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}

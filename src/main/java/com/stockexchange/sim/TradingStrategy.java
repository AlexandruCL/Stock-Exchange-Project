package com.stockexchange.sim;

import com.stockexchange.engine.Exchange;
import java.util.random.RandomGenerator;

/** One trader action; implementations should use the supplied random source and immutable views. */
@FunctionalInterface
public interface TradingStrategy {
    /** Sequence starts at one and can be combined with traderId for unique request IDs. */
    void act(Exchange exchange, long traderId, long sequence, RandomGenerator random)
            throws InterruptedException;
}

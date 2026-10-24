package com.stockexchange.engine;

import com.stockexchange.model.Trade;

/**
 * The part of {@code AccountService} that the matching engine calls. {@code AccountService}
 * implements it; tests use in-memory implementations.
 * Every call arrives while the company lock is held, so implementations take the trader lock
 * (lower id first when two traders are involved) and must not print, log or call listeners.
 */
public interface Accounts {

    /** Checks available cash >= amount and moves it to reserved in one step. */
    boolean reserveCash(long traderId, long amount);

    /** Checks available shares >= qty and moves them to reserved in one step. */
    boolean reserveShares(long traderId, String companyId, long qty);

    void releaseCash(long traderId, long amount);

    void releaseShares(long traderId, String companyId, long qty);

    /**
     * Moves cash and shares for one trade and consumes the reservations. The buyer gets back
     * (buyerLimitPrice - trade.price()) * trade.qty() of reserved cash.
     */
    void settle(Trade trade, long buyerLimitPrice);
}

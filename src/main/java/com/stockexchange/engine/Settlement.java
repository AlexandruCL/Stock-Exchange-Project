package com.stockexchange.engine;

import com.stockexchange.model.Order;
import com.stockexchange.model.Reject;
import com.stockexchange.model.Trade;

/**
 * Account side of the matching engine, implemented by {@code AccountService}.
 * {@code MatchingEngine} calls every method while holding the company lock, so implementations
 * take the trader locks (lower id first) themselves and must not print, log or call listeners.
 */
public interface Settlement {

    /**
     * Checks and reserves in one step: a bid reserves price * remaining cash, an ask reserves
     * remaining shares. Returns null on success, otherwise UNKNOWN_TRADER, INSUFFICIENT_FUNDS or
     * INSUFFICIENT_SHARES, with nothing reserved.
     */
    Reject reserve(Order order);

    /**
     * Replaces the order's current reservation with one for newPrice and newRemaining, atomically.
     * Returns null on success; on a reject the old reservation is unchanged.
     */
    Reject reserveChange(Order order, long newPrice, long newRemaining);

    /** Releases whatever is still reserved for the order's remaining quantity (cancel). */
    void release(Order order);

    /**
     * Moves cash and shares for one trade and consumes the reservations. Called after bid and ask
     * have had trade.qty() taken off their remaining, so bid.price() is still the bid's limit and
     * the buyer gets back (bid.price() - trade.price()) * trade.qty() of reserved cash.
     */
    void settle(Trade trade, Order bid, Order ask);
}
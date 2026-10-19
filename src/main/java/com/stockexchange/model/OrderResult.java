package com.stockexchange.model;

import java.util.List;

/** {@code reason} is null when {@code ok}; {@code order} is null when rejected before an order exists. */
public record OrderResult(boolean ok, Reject reason, OrderView order, List<Trade> trades) {
    public OrderResult {
        trades = List.copyOf(trades);
    }
}

package com.stockexchange.engine;

import com.stockexchange.model.ExchangeEvent;
import com.stockexchange.model.OrderResult;
import com.stockexchange.model.OrderView;
import com.stockexchange.model.Reject;
import com.stockexchange.model.Trade;
import java.util.List;

/**
 * What one command produced. {@code events} must be published by the caller after the engine
 * call returns, i.e. outside the company lock. {@code reason} is null when {@code ok}.
 */
public record MatchResult(boolean ok, Reject reason, OrderView order, List<Trade> trades,
                          List<ExchangeEvent> events) {
    public MatchResult {
        trades = List.copyOf(trades);
        events = List.copyOf(events);
    }

    static MatchResult rejected(Reject reason) {
        return new MatchResult(false, reason, null, List.of(), List.of());
    }

    public OrderResult toOrderResult() {
        return new OrderResult(ok, reason, order, trades);
    }
}

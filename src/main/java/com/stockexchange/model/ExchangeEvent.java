package com.stockexchange.model;

public sealed interface ExchangeEvent {
    long globalSeq();

    record OrderCreated(long globalSeq, OrderView order) implements ExchangeEvent {}
    record OrderModified(long globalSeq, OrderView order) implements ExchangeEvent {}
    record OrderCancelled(long globalSeq, OrderView order) implements ExchangeEvent {}
    record TradeExecuted(long globalSeq, Trade trade) implements ExchangeEvent {}
    record BookUpdated(long globalSeq, String companyId, long bookVersion) implements ExchangeEvent {}
}

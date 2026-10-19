package com.stockexchange.model;

import java.util.List;

public record BookSnapshot(String companyId, long lastPrice, long version,
                           List<OrderView> bids, List<OrderView> asks) {
    public BookSnapshot {
        bids = List.copyOf(bids);
        asks = List.copyOf(asks);
    }
}

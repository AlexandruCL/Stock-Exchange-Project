package com.stockexchange.model;

import java.util.Map;

public record PortfolioView(long traderId, long cash, long reservedCash,
                            Map<String, Long> shares, Map<String, Long> reservedShares) {
    public PortfolioView {
        shares = Map.copyOf(shares);
        reservedShares = Map.copyOf(reservedShares);
    }
}

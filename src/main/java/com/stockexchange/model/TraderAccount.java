package com.stockexchange.model;

import java.util.Map;

/** Starting state of one trader's account. Cash in cents, shares by company id. */
public record TraderAccount(long id, long cash, Map<String, Long> shares) {
    public TraderAccount {
        shares = Map.copyOf(shares);
    }
}

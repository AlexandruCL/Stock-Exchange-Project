package com.stockexchange.model;

/** Immutable view returned to callers, never the live {@link Order}. */
public record OrderView(long id, long traderId, String companyId, Side side, long price,
                        long qty, long remaining, long version, OrderState state,
                        long seq, long timestampNanos) {}

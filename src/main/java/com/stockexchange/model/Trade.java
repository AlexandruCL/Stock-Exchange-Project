package com.stockexchange.model;

public record Trade(long id, long globalSeq, String companyId, long bidOrderId, long askOrderId,
                    long buyerId, long sellerId, long price, long qty, long timestampNanos) {}

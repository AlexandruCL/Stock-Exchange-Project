package com.stockexchange.model;

/** Prices in cents. */
public record Company(String id, String name, long initialLastPrice, long totalShares) {}

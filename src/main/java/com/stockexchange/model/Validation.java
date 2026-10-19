package com.stockexchange.model;

public final class Validation {

    private Validation() {
    }

    public static boolean isValidPrice(long price) {
        return price > 0;
    }

    public static boolean isValidQty(long qty) {
        return qty > 0;
    }

    public static boolean isValidId(String id) {
        return id != null;
    }
}

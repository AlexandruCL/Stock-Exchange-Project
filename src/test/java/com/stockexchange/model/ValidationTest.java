package com.stockexchange.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ValidationTest {

    @ParameterizedTest
    @ValueSource(longs = {0, -1, Long.MIN_VALUE})
    void isValidPrice_should_returnFalse_when_priceNotPositive(long price) {
        assertFalse(Validation.isValidPrice(price));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, Long.MIN_VALUE})
    void isValidQty_should_returnFalse_when_qtyNotPositive(long qty) {
        assertFalse(Validation.isValidQty(qty));
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 1000, Long.MAX_VALUE})
    void priceAndQty_should_beValid_when_positive(long value) {
        assertTrue(Validation.isValidPrice(value));
        assertTrue(Validation.isValidQty(value));
    }

    @Test
    void isValidId_should_returnFalse_when_null() {
        assertFalse(Validation.isValidId(null));
    }

    @Test
    void isValidId_should_returnTrue_when_nonNull() {
        assertTrue(Validation.isValidId("ACME"));
    }
}

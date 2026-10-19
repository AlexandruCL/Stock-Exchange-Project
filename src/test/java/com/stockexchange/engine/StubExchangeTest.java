package com.stockexchange.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.stockexchange.model.OrderResult;
import com.stockexchange.model.OrderState;
import com.stockexchange.model.Reject;
import com.stockexchange.model.Side;
import org.junit.jupiter.api.Test;

class StubExchangeTest {

    private final Exchange exchange = new StubExchange();

    @Test
    void place_should_returnOpenOrder_when_inputKnown() {
        OrderResult result = exchange.place("r1", 1, "ACME", Side.BID, 1000, 10);

        assertTrue(result.ok());
        assertEquals(OrderState.OPEN, result.order().state());
    }

    @Test
    void place_should_reject_when_companyUnknown() {
        OrderResult result = exchange.place("r1", 1, "NOPE", Side.BID, 1000, 10);

        assertFalse(result.ok());
        assertEquals(Reject.UNKNOWN_COMPANY, result.reason());
    }

    @Test
    void modify_should_rejectStaleVersion_and_acceptCurrentVersion() {
        long id = exchange.place("r1", 1, "ACME", Side.ASK, 1000, 10).order().id();

        assertEquals(1, exchange.modify(1, id, 0, 1100, 5).order().version());
        assertEquals(Reject.VERSION_CONFLICT, exchange.modify(1, id, 0, 1200, 5).reason());
    }

    @Test
    void cancel_should_closeOrder_when_owner() {
        long id = exchange.place("r1", 1, "ACME", Side.ASK, 1000, 10).order().id();

        assertEquals(Reject.NOT_OWNER, exchange.cancel(2, id).reason());
        assertEquals(OrderState.CANCELLED, exchange.cancel(1, id).order().state());
        assertEquals(Reject.NOT_FOUND, exchange.cancel(1, id).reason());
    }

    @Test
    void readMethods_should_returnSeedValues() {
        assertEquals(5, exchange.companies().size());
        assertEquals(1000, exchange.book("ACME").lastPrice());
        assertTrue(exchange.history("ACME").isEmpty());
        assertEquals(100_000_000L, exchange.portfolio(1).cash());
    }

    @Test
    void close_should_workInTryWithResources() {
        try (Exchange ex = new StubExchange()) {
            assertTrue(ex.place("r1", 1, "ACME", Side.BID, 1000, 10).ok());
        }
    }
}

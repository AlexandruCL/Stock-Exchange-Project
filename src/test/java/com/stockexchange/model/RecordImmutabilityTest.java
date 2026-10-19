package com.stockexchange.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RecordImmutabilityTest {

    private static OrderView view(long id, Side side) {
        return new OrderView(id, 1, "ACME", side, 1000, 10, 10, 0, OrderState.OPEN, id, 0);
    }

    private static Trade trade(long id) {
        return new Trade(id, id, "ACME", 1, 2, 10, 20, 1000, 5, 0);
    }

    @Test
    void bookSnapshot_should_keepOriginalLists_when_sourceListsChangeLater() {
        List<OrderView> bids = new ArrayList<>(List.of(view(1, Side.BID)));
        List<OrderView> asks = new ArrayList<>(List.of(view(2, Side.ASK)));
        BookSnapshot snapshot = new BookSnapshot("ACME", 1000, 1, bids, asks);

        bids.add(view(3, Side.BID));
        asks.clear();

        assertEquals(1, snapshot.bids().size());
        assertEquals(1, snapshot.asks().size());
    }

    @Test
    void bookSnapshot_should_rejectMutation_when_callerModifiesReturnedList() {
        BookSnapshot snapshot = new BookSnapshot("ACME", 1000, 1, List.of(view(1, Side.BID)), List.of());

        assertThrows(UnsupportedOperationException.class, () -> snapshot.bids().add(view(2, Side.BID)));
    }

    @Test
    void orderResult_should_keepOriginalTrades_when_sourceListChangesLater() {
        List<Trade> trades = new ArrayList<>(List.of(trade(1)));
        OrderResult result = new OrderResult(true, null, view(1, Side.BID), trades);

        trades.add(trade(2));

        assertEquals(1, result.trades().size());
        assertThrows(UnsupportedOperationException.class, () -> result.trades().add(trade(3)));
    }

    @Test
    void orderResult_should_allowNullOrder_when_rejectedBeforeOrderExists() {
        OrderResult result = new OrderResult(false, Reject.UNKNOWN_COMPANY, null, List.of());

        assertEquals(Reject.UNKNOWN_COMPANY, result.reason());
    }

    @Test
    void portfolioView_should_keepOriginalMaps_when_sourceMapsChangeLater() {
        Map<String, Long> shares = new HashMap<>(Map.of("ACME", 100L));
        Map<String, Long> reserved = new HashMap<>(Map.of("ACME", 10L));
        PortfolioView portfolio = new PortfolioView(1, 5000, 0, shares, reserved);

        shares.put("ACME", 999L);
        reserved.put("BETA", 1L);

        assertEquals(100L, portfolio.shares().get("ACME"));
        assertEquals(1, portfolio.reservedShares().size());
        assertThrows(UnsupportedOperationException.class, () -> portfolio.shares().put("BETA", 1L));
    }
}

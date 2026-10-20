package com.stockexchange.verify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.stockexchange.engine.Exchange;
import com.stockexchange.model.BookSnapshot;
import com.stockexchange.model.ExchangeEvent;
import com.stockexchange.model.OrderResult;
import com.stockexchange.model.OrderState;
import com.stockexchange.model.PortfolioView;
import com.stockexchange.model.Reject;
import com.stockexchange.model.SeedData;
import com.stockexchange.model.Side;
import com.stockexchange.model.Trade;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Cross-cutting checks of the Exchange contract, through the interface only. */
class ExchangeContractTest {

    private final SeedData seed = new SeedData();
    private final Exchange ex = TestExchanges.seeded();

    @AfterEach
    void closeExchange() {
        ex.close();
    }

    @Test
    void companies_should_beTheSeedTickers() {
        assertEquals(Set.copyOf(SeedData.TICKERS), ex.companies());
    }

    @Test
    void place_should_rejectBadInputWithoutChangingState() {
        assertReject(Reject.INVALID_INPUT, ex.place("r1", 1, "ACME", Side.BID, 0, 10));
        assertReject(Reject.INVALID_INPUT, ex.place("r2", 1, "ACME", Side.BID, -5, 10));
        assertReject(Reject.INVALID_INPUT, ex.place("r3", 1, "ACME", Side.ASK, 1_000, 0));
        assertReject(Reject.INVALID_INPUT, ex.place("r4", 1, "ACME", Side.ASK, 1_000, -1));
        assertReject(Reject.UNKNOWN_COMPANY, ex.place("r5", 1, "NOPE", Side.BID, 1_000, 1));
        assertReject(Reject.UNKNOWN_TRADER, ex.place("r6", 999, "ACME", Side.BID, 1_000, 1));
        assertReject(Reject.INSUFFICIENT_FUNDS, ex.place("r7", 1, "ACME", Side.BID, SeedData.STARTING_CASH_CENTS, 2));
        assertReject(Reject.INSUFFICIENT_SHARES, ex.place("r8", 1, "ACME", Side.ASK, 1_000, 1_000_000));

        assertTrue(ex.book("ACME").bids().isEmpty());
        assertTrue(ex.book("ACME").asks().isEmpty());
        PortfolioView p = ex.portfolio(1);
        assertEquals(SeedData.STARTING_CASH_CENTS, p.cash());
        assertEquals(0, p.reservedCash());
        InvariantChecker.check(ex, seed);
    }

    @Test
    void place_should_reserveCashForBidAndSharesForAsk() {
        long sharesBefore = ex.portfolio(1).shares().get("BETA");
        ex.place("b", 1, "ACME", Side.BID, 1_000, 30);
        ex.place("a", 1, "BETA", Side.ASK, 9_000, 50);

        PortfolioView p = ex.portfolio(1);
        assertEquals(30_000, p.reservedCash());
        assertEquals(SeedData.STARTING_CASH_CENTS - 30_000, p.cash());
        assertEquals(50, p.reservedShares().get("BETA"));
        assertEquals(sharesBefore - 50, p.shares().get("BETA"));
        InvariantChecker.check(ex, seed);
    }

    @Test
    void cancel_should_releaseTheReservation() {
        OrderResult bid = ex.place("b", 1, "ACME", Side.BID, 1_000, 30);
        OrderResult cancelled = ex.cancel(1, bid.order().id());

        assertTrue(cancelled.ok());
        assertEquals(OrderState.CANCELLED, cancelled.order().state());
        assertEquals(0, ex.portfolio(1).reservedCash());
        assertReject(Reject.ORDER_CLOSED, ex.cancel(1, bid.order().id()));
        assertReject(Reject.NOT_FOUND, ex.cancel(1, 123_456));
        InvariantChecker.check(ex, seed);
    }

    @Test
    void modify_should_rejectStaleVersionWrongOwnerAndClosedOrder() {
        OrderResult ask = ex.place("a", 1, "ACME", Side.ASK, 1_100, 100);
        long id = ask.order().id();

        assertReject(Reject.NOT_OWNER, ex.modify(2, id, 0, 1_200, 100));
        assertTrue(ex.modify(1, id, 0, 1_200, 100).ok());
        assertReject(Reject.VERSION_CONFLICT, ex.modify(1, id, 0, 1_300, 100));

        ex.place("b", 2, "ACME", Side.BID, 1_200, 100);
        assertReject(Reject.ORDER_CLOSED, ex.modify(1, id, 1, 1_300, 100));
        InvariantChecker.check(ex, seed);
    }

    @Test
    void results_should_beImmutableViewsNotLiveOrders() {
        OrderResult first = ex.place("a", 1, "ACME", Side.ASK, 1_000, 100);
        BookSnapshot before = ex.book("ACME");
        ex.place("b", 2, "ACME", Side.BID, 1_000, 40);

        assertEquals(100, first.order().remaining(), "returned view must not change after a fill");
        assertEquals(100, before.asks().get(0).remaining(), "old snapshot must not change");
        assertEquals(60, ex.book("ACME").asks().get(0).remaining());
        assertThrows(UnsupportedOperationException.class, () -> ex.book("ACME").asks().clear());
        assertThrows(UnsupportedOperationException.class, () -> ex.history("ACME").clear());
        assertThrows(UnsupportedOperationException.class, () -> ex.portfolio(1).shares().put("X", 1L));
    }

    @Test
    void history_should_beACopyThatOnlyGrows() {
        ex.place("a", 1, "ACME", Side.ASK, 1_000, 100);
        ex.place("b", 2, "ACME", Side.BID, 1_000, 40);
        List<Trade> before = ex.history("ACME");
        ex.place("c", 3, "ACME", Side.BID, 1_000, 10);

        assertEquals(1, before.size());
        List<Trade> after = ex.history("ACME");
        assertEquals(2, after.size());
        assertEquals(before.get(0), after.get(0));
    }

    @Test
    void events_should_arriveInGlobalSeqOrder_and_tradeBeforeBookUpdate() {
        List<ExchangeEvent> events = new ArrayList<>();
        ex.subscribe(events::add);
        ex.place("a", 1, "ACME", Side.ASK, 1_000, 100);
        ex.place("b", 2, "ACME", Side.BID, 1_000, 100);
        ex.close();

        assertEquals(5, events.size(), events.toString());
        assertTrue(events.get(0) instanceof ExchangeEvent.OrderCreated);
        assertTrue(events.get(1) instanceof ExchangeEvent.BookUpdated);
        assertTrue(events.get(2) instanceof ExchangeEvent.OrderCreated);
        assertTrue(events.get(3) instanceof ExchangeEvent.TradeExecuted);
        assertTrue(events.get(4) instanceof ExchangeEvent.BookUpdated);
        for (int i = 1; i < events.size(); i++) {
            assertTrue(events.get(i).globalSeq() > events.get(i - 1).globalSeq());
        }
    }

    @Test
    void throwingListener_should_notStopOtherListeners() {
        List<ExchangeEvent> seen = new ArrayList<>();
        ex.subscribe(e -> {
            throw new IllegalStateException("boom");
        });
        ex.subscribe(seen::add);
        OrderResult result = ex.place("a", 1, "ACME", Side.ASK, 1_000, 100);
        ex.close();

        assertTrue(result.ok());
        assertFalse(seen.isEmpty());
    }

    @Test
    void unsubscribe_should_stopDelivery() {
        List<ExchangeEvent> seen = new ArrayList<>();
        Consumer<ExchangeEvent> listener = seen::add;
        ex.subscribe(listener);
        ex.unsubscribe(listener);
        ex.place("a", 1, "ACME", Side.ASK, 1_000, 100);
        ex.close();
        assertTrue(seen.isEmpty());
    }

    @Test
    void book_should_returnSnapshotForEveryCompany() {
        for (String company : ex.companies()) {
            BookSnapshot book = ex.book(company);
            assertNotNull(book, company);
            assertEquals(company, book.companyId());
        }
    }

    private static void assertReject(Reject expected, OrderResult result) {
        assertFalse(result.ok(), "expected " + expected);
        assertEquals(expected, result.reason());
    }
}

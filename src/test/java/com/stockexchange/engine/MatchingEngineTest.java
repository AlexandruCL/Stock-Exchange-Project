package com.stockexchange.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.stockexchange.model.BookSnapshot;
import com.stockexchange.model.Company;
import com.stockexchange.model.ExchangeEvent;
import com.stockexchange.model.Order;
import com.stockexchange.model.OrderResult;
import com.stockexchange.model.OrderState;
import com.stockexchange.model.Reject;
import com.stockexchange.model.Side;
import com.stockexchange.model.Trade;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MatchingEngineTest {

    private static final String ACME = "ACME";
    private static final String BETA = "BETA";
    private static final long CASH = 1_000_000;
    private static final long SHARES = 1_000;

    private LedgerSettlement ledger;
    private MatchingEngine engine;
    private final AtomicLong orderIds = new AtomicLong();
    private final AtomicLong globalSeq = new AtomicLong();

    @BeforeEach
    void setUp() {
        ledger = new LedgerSettlement();
        for (long trader = 1; trader <= 4; trader++) {
            ledger.addTrader(trader, CASH);
            ledger.addShares(trader, ACME, SHARES);
            ledger.addShares(trader, BETA, SHARES);
        }
        engine = new MatchingEngine(
                List.of(new Company(ACME, ACME, 100, 4 * SHARES), new Company(BETA, BETA, 200, 4 * SHARES)),
                ledger, globalSeq::incrementAndGet);
    }

    private OrderResult place(long trader, String company, Side side, long price, long qty) {
        return engine.place(new Order(orderIds.incrementAndGet(), trader, company, side, price, qty)).result();
    }

    @Test
    void place_should_restInBook_when_nothingCrosses() {
        OrderResult result = place(1, ACME, Side.BID, 90, 10);

        assertTrue(result.ok());
        assertEquals(OrderState.OPEN, result.order().state());
        assertEquals(1, engine.book(ACME).bids().size());
        assertEquals(10 * 90, ledger.reservedCash(1));
    }

    @Test
    void place_should_tradeAtRestingPrice_when_incomingBidIsHigher() {
        place(1, ACME, Side.ASK, 100, 10);

        OrderResult result = place(2, ACME, Side.BID, 120, 10);

        Trade trade = result.trades().get(0);
        assertEquals(100, trade.price());
        assertEquals(10, trade.qty());
        assertEquals(OrderState.DONE, result.order().state());
        assertEquals(CASH - 1000, ledger.cash(2));
        assertEquals(0, ledger.reservedCash(2));
        assertEquals(CASH + 1000, ledger.cash(1));
        assertEquals(SHARES + 10, ledger.shares(2, ACME));
    }

    @Test
    void place_should_tradeAtRestingBidPrice_when_incomingAskIsLower() {
        place(1, ACME, Side.BID, 120, 10);

        OrderResult result = place(2, ACME, Side.ASK, 100, 10);

        assertEquals(120, result.trades().get(0).price());
        assertEquals(120, engine.book(ACME).lastPrice());
    }

    @Test
    void place_should_leaveRemainder_when_partialFill() {
        place(1, ACME, Side.ASK, 100, 4);

        OrderResult result = place(2, ACME, Side.BID, 100, 10);

        assertEquals(4, result.trades().get(0).qty());
        assertEquals(6, result.order().remaining());
        assertEquals(OrderState.OPEN, result.order().state());
        assertEquals(1, result.order().version());
        assertEquals(1, engine.book(ACME).bids().size());
        assertTrue(engine.book(ACME).asks().isEmpty());
    }

    @Test
    void place_should_matchOlderOrderFirst_when_samePrice() {
        OrderResult first = place(1, ACME, Side.ASK, 100, 5);
        place(2, ACME, Side.ASK, 100, 5);

        OrderResult result = place(3, ACME, Side.BID, 100, 5);

        assertEquals(first.order().id(), result.trades().get(0).askOrderId());
    }

    @Test
    void place_should_matchBestPriceFirst_when_severalAsks() {
        place(1, ACME, Side.ASK, 105, 5);
        OrderResult best = place(2, ACME, Side.ASK, 101, 5);

        OrderResult result = place(3, ACME, Side.BID, 110, 5);

        assertEquals(best.order().id(), result.trades().get(0).askOrderId());
        assertEquals(101, result.trades().get(0).price());
    }

    @Test
    void place_should_sweepSeveralLevels_when_largeBid() {
        place(1, ACME, Side.ASK, 100, 5);
        place(2, ACME, Side.ASK, 102, 5);

        OrderResult result = place(3, ACME, Side.BID, 102, 8);

        assertEquals(2, result.trades().size());
        assertEquals(5, result.trades().get(0).qty());
        assertEquals(3, result.trades().get(1).qty());
        assertEquals(2, engine.book(ACME).asks().get(0).remaining());
    }

    @Test
    void place_should_reject_when_inputInvalidOrCompanyUnknown() {
        assertEquals(Reject.INVALID_INPUT, place(1, ACME, Side.BID, 0, 5).reason());
        assertEquals(Reject.INVALID_INPUT, place(1, ACME, Side.BID, 5, 0).reason());
        assertEquals(Reject.UNKNOWN_COMPANY, place(1, "ZZZ", Side.BID, 5, 5).reason());
    }

    @Test
    void place_should_rejectWithoutReserving_when_fundsOrSharesMissing() {
        assertEquals(Reject.INSUFFICIENT_FUNDS, place(1, ACME, Side.BID, 1001, 1000).reason());
        assertEquals(Reject.INSUFFICIENT_SHARES, place(1, ACME, Side.ASK, 10, SHARES + 1).reason());
        assertEquals(Reject.UNKNOWN_TRADER, place(99, ACME, Side.BID, 10, 1).reason());

        assertEquals(0, ledger.reservedCash(1));
        assertEquals(0, ledger.reservedShares(1, ACME));
        assertTrue(engine.book(ACME).bids().isEmpty());
    }

    @Test
    void cancel_should_releaseReservation_and_removeFromBook() {
        OrderResult placed = place(1, ACME, Side.BID, 90, 10);

        OrderResult result = engine.cancel(1, placed.order().id()).result();

        assertTrue(result.ok());
        assertEquals(OrderState.CANCELLED, result.order().state());
        assertEquals(CASH, ledger.cash(1));
        assertEquals(0, ledger.reservedCash(1));
        assertTrue(engine.book(ACME).bids().isEmpty());
    }

    @Test
    void cancel_should_reject_when_notOwnerClosedOrUnknown() {
        OrderResult placed = place(1, ACME, Side.BID, 90, 10);
        long id = placed.order().id();

        assertEquals(Reject.NOT_OWNER, engine.cancel(2, id).result().reason());
        assertEquals(Reject.NOT_FOUND, engine.cancel(1, 9999).result().reason());
        engine.cancel(1, id);
        assertEquals(Reject.ORDER_CLOSED, engine.cancel(1, id).result().reason());
    }

    @Test
    void modify_should_reject_when_orderAlreadyDone() {
        OrderResult ask = place(1, ACME, Side.ASK, 100, 5);
        place(2, ACME, Side.BID, 100, 5);

        OrderResult result = engine.modify(1, ask.order().id(), 1, 90, 5).result();

        assertEquals(Reject.ORDER_CLOSED, result.reason());
    }

    @Test
    void modify_should_reject_when_versionStale() {
        OrderResult placed = place(1, ACME, Side.BID, 90, 10);

        OrderResult result = engine.modify(1, placed.order().id(), 7, 95, 10).result();

        assertEquals(Reject.VERSION_CONFLICT, result.reason());
        assertEquals(90, engine.book(ACME).bids().get(0).price());
    }

    @Test
    void modify_should_rereserveAndBumpVersion_when_priceChanges() {
        OrderResult placed = place(1, ACME, Side.BID, 90, 10);

        OrderResult result = engine.modify(1, placed.order().id(), 0, 95, 20).result();

        assertTrue(result.ok());
        assertEquals(1, result.order().version());
        assertEquals(95, result.order().price());
        assertEquals(95 * 20, ledger.reservedCash(1));
        assertEquals(CASH - 95 * 20, ledger.cash(1));
    }

    @Test
    void modify_should_loseTimePriority_when_priceChanges_and_keepItWhenQtyShrinks() {
        OrderResult first = place(1, ACME, Side.ASK, 100, 5);
        OrderResult second = place(2, ACME, Side.ASK, 100, 5);

        engine.modify(2, second.order().id(), 0, 100, 3);
        assertEquals(second.order().seq(), engine.book(ACME).asks().get(1).seq());

        engine.modify(1, first.order().id(), 0, 101, 5);
        engine.modify(1, first.order().id(), 1, 100, 5);
        assertEquals(second.order().id(), engine.book(ACME).asks().get(0).id());
    }

    @Test
    void modify_should_trade_when_newPriceCrosses() {
        place(1, ACME, Side.ASK, 100, 5);
        OrderResult bid = place(2, ACME, Side.BID, 90, 5);

        OrderResult result = engine.modify(2, bid.order().id(), 0, 100, 5).result();

        assertEquals(1, result.trades().size());
        assertEquals(OrderState.DONE, result.order().state());
    }

    @Test
    void modify_should_rejectQtyAtOrBelowFilled() {
        place(1, ACME, Side.ASK, 100, 4);
        OrderResult bid = place(2, ACME, Side.BID, 100, 10);

        OrderResult result = engine.modify(2, bid.order().id(), 1, 100, 4).result();

        assertEquals(Reject.INVALID_INPUT, result.reason());
    }

    @Test
    void place_should_emitEventsInOrder() {
        place(1, ACME, Side.ASK, 100, 5);

        List<ExchangeEvent> events = engine.place(
                new Order(orderIds.incrementAndGet(), 2, ACME, Side.BID, 100, 5)).events();

        assertTrue(events.get(0) instanceof ExchangeEvent.OrderCreated);
        assertTrue(events.get(1) instanceof ExchangeEvent.TradeExecuted);
        assertTrue(events.get(2) instanceof ExchangeEvent.BookUpdated);
        assertTrue(events.get(0).globalSeq() < events.get(1).globalSeq());
    }

    @Test
    void history_should_beACopy_and_bookSnapshotImmutable() {
        place(1, ACME, Side.ASK, 100, 5);
        place(2, ACME, Side.BID, 100, 5);
        BookSnapshot before = engine.book(ACME);

        List<Trade> history = engine.history(ACME);
        place(3, ACME, Side.BID, 50, 1);

        assertEquals(1, history.size());
        assertEquals(1, engine.history(ACME).size());
        assertTrue(before.bids().isEmpty());
        assertNull(engine.book("ZZZ"));
    }

    @Test
    void concurrentOrders_should_keepCashAndSharesConstant() throws Exception {
        int threads = 4;
        int opsPerThread = 3000;
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> workers = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();
        for (int t = 1; t <= threads; t++) {
            long trader = t;
            Thread worker = new Thread(() -> {
                Random random = new Random(42 + trader);
                List<OrderResult> mine = new ArrayList<>();
                try {
                    start.await();
                    for (int i = 0; i < opsPerThread; i++) {
                        String company = random.nextBoolean() ? ACME : BETA;
                        int action = random.nextInt(10);
                        if (action < 7 || mine.isEmpty()) {
                            Side side = random.nextBoolean() ? Side.BID : Side.ASK;
                            OrderResult r = place(trader, company, side, 95 + random.nextInt(11), 1 + random.nextInt(20));
                            if (r.ok()) {
                                mine.add(r);
                            }
                        } else {
                            OrderResult o = mine.get(random.nextInt(mine.size()));
                            if (action == 7) {
                                engine.cancel(trader, o.order().id());
                            } else {
                                engine.modify(trader, o.order().id(), o.order().version(),
                                        95 + random.nextInt(11), 30 + random.nextInt(20));
                            }
                        }
                    }
                } catch (Throwable e) {
                    synchronized (failures) {
                        failures.add(e);
                    }
                }
            });
            workers.add(worker);
            worker.start();
        }
        start.countDown();
        for (Thread worker : workers) {
            worker.join();
        }

        assertTrue(failures.isEmpty(), () -> String.valueOf(failures));
        assertEquals(threads * CASH, ledger.totalCash());
        assertEquals(threads * SHARES, ledger.totalShares(ACME));
        assertEquals(threads * SHARES, ledger.totalShares(BETA));
        for (long trader = 1; trader <= threads; trader++) {
            assertTrue(ledger.cash(trader) >= 0 && ledger.shares(trader, ACME) >= 0
                    && ledger.shares(trader, BETA) >= 0);
        }
        for (String company : List.of(ACME, BETA)) {
            BookSnapshot book = engine.book(company);
            if (!book.bids().isEmpty() && !book.asks().isEmpty()) {
                assertFalse(book.bids().get(0).price() >= book.asks().get(0).price());
            }
        }
    }
}
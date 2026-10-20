package com.stockexchange.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.stockexchange.model.BookSnapshot;
import com.stockexchange.model.Company;
import com.stockexchange.model.ExchangeEvent;
import com.stockexchange.model.OrderResult;
import com.stockexchange.model.OrderState;
import com.stockexchange.model.OrderView;
import com.stockexchange.model.PortfolioView;
import com.stockexchange.model.Reject;
import com.stockexchange.model.SeedData;
import com.stockexchange.model.Side;
import com.stockexchange.model.Trade;
import com.stockexchange.model.TraderAccount;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class StockExchangeTest {

    private static final String ACME = "ACME";
    private static final String BETA = "BETA";
    private static final long ANA = 1;
    private static final long BOB = 2;
    private static final long CARL = 3;
    private static final long DAN = 4;
    private static final long CASH = 100_000;
    private static final long SHARES = 1_000;

    private StockExchange exchange;
    private final AtomicLong requestIds = new AtomicLong();

    @BeforeEach
    void setUp() {
        exchange = newExchange();
    }

    @AfterEach
    void tearDown() {
        exchange.close();
    }

    /** Ana, Bob and Carl are rich; Dan has 1,000 cents and 100 shares of each company. */
    private static StockExchange newExchange() {
        List<TraderAccount> traders = List.of(
                new TraderAccount(ANA, CASH, Map.of(ACME, SHARES, BETA, SHARES)),
                new TraderAccount(BOB, CASH, Map.of(ACME, SHARES, BETA, SHARES)),
                new TraderAccount(CARL, CASH, Map.of(ACME, SHARES, BETA, SHARES)),
                new TraderAccount(DAN, 1_000, Map.of(ACME, 100L, BETA, 100L)));
        List<Company> companies = List.of(
                new Company(ACME, ACME, 10, 3 * SHARES + 100),
                new Company(BETA, BETA, 20, 3 * SHARES + 100));
        return new StockExchange(companies, traders);
    }

    private OrderResult place(long trader, String company, Side side, long price, long qty) {
        return exchange.place("r" + requestIds.incrementAndGet(), trader, company, side, price, qty);
    }

    private List<ExchangeEvent> recordEvents() {
        List<ExchangeEvent> events = new CopyOnWriteArrayList<>();
        exchange.subscribe(events::add);
        return events;
    }

    @Test
    void anaBobCarl_should_giveTheSharesToBobOnly_and_settlePortfolios() {
        place(ANA, ACME, Side.ASK, 10, 100);

        OrderResult bob = place(BOB, ACME, Side.BID, 10, 100);
        OrderResult carl = place(CARL, ACME, Side.BID, 10, 100);

        assertEquals(1, bob.trades().size());
        assertEquals(100, bob.trades().get(0).qty());
        assertEquals(OrderState.DONE, bob.order().state());
        assertTrue(carl.trades().isEmpty());
        assertEquals(OrderState.OPEN, carl.order().state());

        PortfolioView ana = exchange.portfolio(ANA);
        assertEquals(CASH + 1_000, ana.cash());
        assertEquals(SHARES - 100, ana.shares().get(ACME));
        assertEquals(0L, ana.reservedShares().get(ACME));
        PortfolioView bobView = exchange.portfolio(BOB);
        assertEquals(CASH - 1_000, bobView.cash());
        assertEquals(0, bobView.reservedCash());
        assertEquals(SHARES + 100, bobView.shares().get(ACME));
        PortfolioView carlView = exchange.portfolio(CARL);
        assertEquals(CASH - 1_000, carlView.cash());
        assertEquals(1_000, carlView.reservedCash());
        assertEquals(SHARES, carlView.shares().get(ACME));

        BookSnapshot book = exchange.book(ACME);
        assertEquals(carl.order().id(), book.bids().get(0).id());
        assertTrue(book.asks().isEmpty());
        assertEquals(10, book.lastPrice());
        assertEquals(1, exchange.history(ACME).size());
    }

    @Test
    void place_should_tradeAtRestingPrice_and_refundBuyer_when_bidAboveAsk() {
        place(ANA, ACME, Side.ASK, 10, 100);

        OrderResult bob = place(BOB, ACME, Side.BID, 12, 100);

        assertEquals(10, bob.trades().get(0).price());
        assertEquals(CASH - 1_000, exchange.portfolio(BOB).cash());
        assertEquals(0, exchange.portfolio(BOB).reservedCash());
        assertEquals(CASH + 1_000, exchange.portfolio(ANA).cash());
    }

    @Test
    void place_should_rejectWithoutStateChange_when_inputInvalidOrUnknown() {
        List<ExchangeEvent> events = recordEvents();
        PortfolioView before = exchange.portfolio(ANA);

        assertEquals(Reject.UNKNOWN_TRADER, place(99, ACME, Side.BID, 10, 1).reason());
        assertEquals(Reject.UNKNOWN_COMPANY, place(ANA, "NOPE", Side.BID, 10, 1).reason());
        assertEquals(Reject.INVALID_INPUT, place(ANA, ACME, Side.BID, 0, 1).reason());
        assertEquals(Reject.INVALID_INPUT, place(ANA, ACME, Side.BID, -5, 1).reason());
        assertEquals(Reject.INVALID_INPUT, place(ANA, ACME, Side.ASK, 10, 0).reason());
        assertEquals(Reject.INVALID_INPUT, place(ANA, ACME, Side.ASK, 10, -1).reason());
        assertEquals(Reject.INVALID_INPUT, place(ANA, null, Side.BID, 10, 1).reason());
        assertEquals(Reject.INVALID_INPUT, place(ANA, ACME, null, 10, 1).reason());
        assertEquals(Reject.INVALID_INPUT, place(ANA, ACME, Side.BID, Long.MAX_VALUE / 2, 3).reason());
        assertEquals(Reject.INVALID_INPUT, exchange.place(null, ANA, ACME, Side.BID, 10, 1).reason());
        OrderResult rejected = place(99, ACME, Side.BID, 10, 1);
        assertFalse(rejected.ok());
        assertNull(rejected.order());
        exchange.close();

        assertEquals(before, exchange.portfolio(ANA));
        assertTrue(exchange.book(ACME).bids().isEmpty());
        assertTrue(exchange.book(ACME).asks().isEmpty());
        assertTrue(events.isEmpty());
        assertTrue(exchange.transactionLog().events().isEmpty());
    }

    @Test
    void place_should_rejectInsufficientFundsAndShares_withoutReserving() {
        assertEquals(Reject.INSUFFICIENT_FUNDS, place(DAN, ACME, Side.BID, 10, 101).reason());
        assertEquals(Reject.INSUFFICIENT_SHARES, place(DAN, ACME, Side.ASK, 10, 101).reason());

        PortfolioView dan = exchange.portfolio(DAN);
        assertEquals(1_000, dan.cash());
        assertEquals(0, dan.reservedCash());
        assertEquals(0L, dan.reservedShares().get(ACME));
    }

    @Test
    void place_should_returnFirstResult_when_requestIdRepeated() {
        OrderResult first = exchange.place("req-1", BOB, ACME, Side.BID, 9, 10);

        OrderResult again = exchange.place("req-1", BOB, ACME, Side.BID, 9, 10);

        assertSame(first, again);
        assertEquals(1, exchange.book(ACME).bids().size());
        assertEquals(90, exchange.portfolio(BOB).reservedCash());
    }

    @Test
    void place_should_rejectDuplicateRequest_when_sameIdDifferentParams() {
        exchange.place("req-1", BOB, ACME, Side.BID, 9, 10);

        OrderResult other = exchange.place("req-1", BOB, ACME, Side.BID, 9, 11);

        assertEquals(Reject.DUPLICATE_REQUEST, other.reason());
        assertEquals(1, exchange.book(ACME).bids().size());
        assertEquals(90, exchange.portfolio(BOB).reservedCash());
    }

    @Test
    void place_should_rememberRejectedResult_but_notInvalidInput() {
        assertEquals(Reject.INSUFFICIENT_FUNDS, exchange.place("dan-1", DAN, ACME, Side.BID, 10, 101).reason());
        assertEquals(Reject.INSUFFICIENT_FUNDS, exchange.place("dan-1", DAN, ACME, Side.BID, 10, 101).reason());
        assertEquals(Reject.INVALID_INPUT, exchange.place("dan-2", DAN, ACME, Side.BID, 0, 1).reason());

        assertTrue(exchange.place("dan-2", DAN, ACME, Side.BID, 10, 1).ok());
    }

    @Test
    @Timeout(60)
    void sameRequestIdSentConcurrently_should_createOneOrder() throws Exception {
        int rounds = 200;
        for (int round = 0; round < rounds; round++) {
            String requestId = "dup-" + round;
            CountDownLatch go = new CountDownLatch(1);
            List<OrderResult> results = Collections.synchronizedList(new ArrayList<>());
            List<Thread> senders = new ArrayList<>();
            for (int t = 0; t < 4; t++) {
                Thread sender = new Thread(() -> {
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        return;
                    }
                    results.add(exchange.place(requestId, BOB, ACME, Side.BID, 1, 1));
                });
                senders.add(sender);
                sender.start();
            }
            go.countDown();
            for (Thread sender : senders) {
                sender.join(10_000);
            }
            assertEquals(4, results.size());
            for (OrderResult result : results) {
                assertSame(results.get(0), result);
            }
        }
        assertEquals(rounds, exchange.book(ACME).bids().size());
        assertEquals(rounds, exchange.portfolio(BOB).reservedCash());
    }

    /** One trader, two companies, two locks: only the trader lock stops a double spend. */
    @Test
    @Timeout(60)
    void twoBuysOf800With1000Cash_should_acceptExactlyOne_acrossCompanies() throws Exception {
        for (int round = 0; round < 200; round++) {
            try (StockExchange fresh = newExchange()) {
                CountDownLatch go = new CountDownLatch(1);
                List<OrderResult> results = Collections.synchronizedList(new ArrayList<>());
                List<Thread> buyers = new ArrayList<>();
                for (String company : List.of(ACME, BETA)) {
                    Thread buyer = new Thread(() -> {
                        try {
                            go.await();
                        } catch (InterruptedException e) {
                            return;
                        }
                        results.add(fresh.place(company + "-buy", DAN, company, Side.BID, 10, 80));
                    });
                    buyers.add(buyer);
                    buyer.start();
                }
                go.countDown();
                for (Thread buyer : buyers) {
                    buyer.join(10_000);
                }

                assertEquals(1, results.stream().filter(OrderResult::ok).count());
                assertEquals(1, results.stream().filter(r -> r.reason() == Reject.INSUFFICIENT_FUNDS).count());
                assertEquals(200, fresh.portfolio(DAN).cash());
                assertEquals(800, fresh.portfolio(DAN).reservedCash());
            }
        }
    }

    @Test
    void modify_should_routeToTheOrdersEngine_and_rereserve() {
        place(ANA, BETA, Side.BID, 5, 10);
        long id = place(BOB, ACME, Side.BID, 9, 10).order().id();

        OrderResult result = exchange.modify(BOB, id, 0, 11, 10);

        assertTrue(result.ok());
        assertEquals(1, result.order().version());
        assertEquals(11, exchange.book(ACME).bids().get(0).price());
        assertEquals(110, exchange.portfolio(BOB).reservedCash());
        assertEquals(CASH - 110, exchange.portfolio(BOB).cash());
    }

    @Test
    void modify_should_reject_when_staleNotOwnerUnknownOrClosed() {
        long id = place(BOB, ACME, Side.BID, 9, 10).order().id();

        assertEquals(Reject.VERSION_CONFLICT, exchange.modify(BOB, id, 3, 11, 10).reason());
        assertEquals(Reject.NOT_OWNER, exchange.modify(ANA, id, 0, 11, 10).reason());
        assertEquals(Reject.NOT_FOUND, exchange.modify(BOB, 9_999, 0, 11, 10).reason());
        assertEquals(Reject.UNKNOWN_TRADER, exchange.modify(99, id, 0, 11, 10).reason());
        assertEquals(Reject.INVALID_INPUT, exchange.modify(BOB, id, 0, 0, 10).reason());
        assertEquals(Reject.INVALID_INPUT, exchange.modify(BOB, id, 0, Long.MAX_VALUE, 10).reason());
        exchange.cancel(BOB, id);
        assertEquals(Reject.ORDER_CLOSED, exchange.modify(BOB, id, 1, 11, 10).reason());

        assertEquals(CASH, exchange.portfolio(BOB).cash());
    }

    @Test
    void modify_should_trade_when_newPriceCrosses() {
        place(ANA, ACME, Side.ASK, 10, 50);
        long id = place(BOB, ACME, Side.BID, 8, 50).order().id();

        OrderResult result = exchange.modify(BOB, id, 0, 10, 50);

        assertEquals(1, result.trades().size());
        assertEquals(OrderState.DONE, result.order().state());
        assertEquals(CASH - 500, exchange.portfolio(BOB).cash());
        assertEquals(SHARES + 50, exchange.portfolio(BOB).shares().get(ACME));
        assertEquals(CASH + 500, exchange.portfolio(ANA).cash());
    }

    @Test
    void cancel_should_releaseReservation_and_rejectSecondCancel() {
        long bid = place(BOB, ACME, Side.BID, 9, 10).order().id();
        long ask = place(ANA, ACME, Side.ASK, 15, 30).order().id();

        assertTrue(exchange.cancel(BOB, bid).ok());
        assertTrue(exchange.cancel(ANA, ask).ok());

        assertEquals(CASH, exchange.portfolio(BOB).cash());
        assertEquals(0, exchange.portfolio(BOB).reservedCash());
        assertEquals(SHARES, exchange.portfolio(ANA).shares().get(ACME));
        assertEquals(0L, exchange.portfolio(ANA).reservedShares().get(ACME));
        assertTrue(exchange.book(ACME).bids().isEmpty());
        assertEquals(Reject.ORDER_CLOSED, exchange.cancel(BOB, bid).reason());
        assertEquals(Reject.NOT_FOUND, exchange.cancel(BOB, 9_999).reason());
        assertEquals(Reject.UNKNOWN_TRADER, exchange.cancel(99, bid).reason());
    }

    @Test
    void events_should_reachSubscribersInGlobalSeqOrder_onTheDispatcherThread() {
        List<ExchangeEvent> events = recordEvents();
        AtomicReference<String> threadName = new AtomicReference<>();
        exchange.subscribe(e -> threadName.set(Thread.currentThread().getName()));

        place(ANA, ACME, Side.ASK, 10, 100);
        place(BOB, ACME, Side.BID, 10, 100);
        exchange.close();

        assertEquals(5, events.size());
        assertTrue(events.get(0) instanceof ExchangeEvent.OrderCreated);
        assertTrue(events.get(1) instanceof ExchangeEvent.BookUpdated);
        assertTrue(events.get(2) instanceof ExchangeEvent.OrderCreated);
        assertTrue(events.get(3) instanceof ExchangeEvent.TradeExecuted);
        assertTrue(events.get(4) instanceof ExchangeEvent.BookUpdated);
        for (int i = 0; i < events.size(); i++) {
            assertEquals(i + 1L, events.get(i).globalSeq());
        }
        assertEquals(EventBus.THREAD_NAME, threadName.get());
    }

    @Test
    void unsubscribe_should_stopDelivery() {
        List<ExchangeEvent> events = new CopyOnWriteArrayList<>();
        Consumer<ExchangeEvent> listener = events::add;
        exchange.subscribe(listener);
        exchange.unsubscribe(listener);

        place(ANA, ACME, Side.ASK, 10, 100);
        exchange.close();

        assertTrue(events.isEmpty());
        assertEquals(2, exchange.transactionLog().events().size());
    }

    @Test
    void transactionLog_should_holdEveryEvent_and_matchHistory() {
        place(ANA, ACME, Side.ASK, 10, 30);
        place(CARL, ACME, Side.ASK, 10, 40);
        place(BOB, ACME, Side.BID, 11, 50);
        place(ANA, BETA, Side.BID, 19, 5);
        exchange.close();

        TransactionLog log = exchange.transactionLog();
        assertEquals(exchange.history(ACME), log.trades(ACME));
        assertEquals(2, log.trades(ACME).size());
        assertEquals(2, log.events(BETA).size());
        assertEquals(log.events().size(), log.events(ACME).size() + log.events(BETA).size());
    }

    @Test
    void readMethods_should_handleUnknownIds_likeTheStub() {
        assertEquals(new BookSnapshot("NOPE", 0, 0, List.of(), List.of()), exchange.book("NOPE"));
        assertTrue(exchange.history("NOPE").isEmpty());
        assertEquals(0, exchange.portfolio(99).cash());
        assertTrue(exchange.portfolio(99).shares().isEmpty());
    }

    @Test
    void companies_should_keepSeedOrder() {
        try (StockExchange seeded = new StockExchange(new SeedData())) {
            assertEquals(SeedData.TICKERS, List.copyOf(seeded.companies()));
            assertEquals(1_000, seeded.book("ACME").lastPrice());
            assertEquals(SeedData.STARTING_CASH_CENTS, seeded.portfolio(1).cash());
        }
    }

    @Test
    void constructor_should_rejectDuplicateCompany() {
        Company acme = new Company(ACME, ACME, 10, 0);

        assertThrows(IllegalArgumentException.class, () -> new StockExchange(List.of(acme, acme), List.of()));
    }

    @Test
    void close_should_stopDispatcher_and_beIdempotent() throws Exception {
        CountDownLatch delivered = new CountDownLatch(1);
        AtomicReference<Thread> dispatcher = new AtomicReference<>();
        exchange.subscribe(e -> {
            dispatcher.set(Thread.currentThread());
            delivered.countDown();
        });
        place(ANA, ACME, Side.ASK, 10, 1);
        assertTrue(delivered.await(5, TimeUnit.SECONDS));

        exchange.close();
        exchange.close();

        assertFalse(dispatcher.get().isAlive());
    }

    /**
     * Eight traders place, modify and cancel at random over the five seed companies. Afterwards
     * cash and shares are conserved, nothing is negative, no book is crossed, every reservation
     * is backed by an open order, and the listener saw every globalSeq exactly once, in order.
     */
    @Test
    @Timeout(60)
    void concurrentTrading_should_keepInvariants_and_deliverEveryEventInOrder() throws Exception {
        SeedData seed = new SeedData();
        exchange.close();
        exchange = new StockExchange(seed);
        AtomicLong expectedSeq = new AtomicLong(1);
        AtomicLong outOfOrder = new AtomicLong();
        exchange.subscribe(e -> {
            if (e.globalSeq() != expectedSeq.getAndIncrement()) {
                outOfOrder.incrementAndGet();
            }
        });
        Map<String, Long> prices = new HashMap<>();
        for (Company company : seed.companies()) {
            prices.put(company.id(), company.initialLastPrice());
        }
        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        List<Thread> workers = new ArrayList<>();
        for (int t = 1; t <= 8; t++) {
            long trader = t;
            Thread worker = new Thread(() -> {
                Random random = new Random(42 + trader);
                List<OrderView> mine = new ArrayList<>();
                try {
                    start.await();
                    for (int i = 0; i < 2_000; i++) {
                        String company = SeedData.TICKERS.get(random.nextInt(SeedData.TICKERS.size()));
                        long base = prices.get(company);
                        long price = base - base / 20 + random.nextInt((int) (base / 10) + 1);
                        int action = random.nextInt(10);
                        if (action < 7 || mine.isEmpty()) {
                            Side side = random.nextBoolean() ? Side.BID : Side.ASK;
                            OrderResult r = exchange.place("t" + trader + "-" + i, trader, company, side,
                                    price, 1 + random.nextInt(20));
                            if (r.ok()) {
                                mine.add(r.order());
                            }
                        } else {
                            OrderView o = mine.get(random.nextInt(mine.size()));
                            if (action == 7) {
                                exchange.cancel(trader, o.id());
                            } else {
                                long newPrice = o.price() - o.price() / 20 + random.nextInt((int) (o.price() / 10) + 1);
                                OrderResult r = exchange.modify(trader, o.id(), o.version(), newPrice,
                                        o.qty() + random.nextInt(10));
                                if (r.ok()) {
                                    mine.set(mine.indexOf(o), r.order());
                                }
                            }
                        }
                    }
                } catch (Throwable e) {
                    failures.add(e);
                }
            }, "trader-" + t);
            workers.add(worker);
            worker.start();
        }
        start.countDown();
        for (Thread worker : workers) {
            worker.join(60_000);
            assertFalse(worker.isAlive(), "worker still running: possible deadlock");
        }
        exchange.close();

        assertTrue(failures.isEmpty(), () -> String.valueOf(failures));
        assertEquals(0, outOfOrder.get());
        assertEquals(exchange.transactionLog().events().size(), expectedSeq.get() - 1);
        AccountService accounts = exchange.accounts();
        assertEquals(seed.totalCash(), accounts.totalCash());
        Map<Long, Long> openBidCash = new HashMap<>();
        Map<String, Long> openAskShares = new HashMap<>();
        for (String company : exchange.companies()) {
            assertEquals(seed.totalShares(company), accounts.totalShares(company), company);
            assertEquals(exchange.history(company), exchange.transactionLog().trades(company), company);
            BookSnapshot book = exchange.book(company);
            if (!book.bids().isEmpty() && !book.asks().isEmpty()) {
                assertTrue(book.bids().get(0).price() < book.asks().get(0).price(), company + " is crossed");
            }
            for (OrderView bid : book.bids()) {
                assertTrue(bid.remaining() > 0);
                openBidCash.merge(bid.traderId(), bid.price() * bid.remaining(), Long::sum);
            }
            for (OrderView ask : book.asks()) {
                assertTrue(ask.remaining() > 0);
                openAskShares.merge(ask.traderId() + "|" + company, ask.remaining(), Long::sum);
            }
            for (Trade trade : exchange.history(company)) {
                assertTrue(trade.qty() > 0);
            }
        }
        for (TraderAccount trader : seed.traders()) {
            PortfolioView view = exchange.portfolio(trader.id());
            assertTrue(view.cash() >= 0 && view.reservedCash() >= 0);
            assertEquals(openBidCash.getOrDefault(trader.id(), 0L), view.reservedCash(), "trader " + trader.id());
            for (String company : exchange.companies()) {
                assertTrue(view.shares().get(company) >= 0);
                assertEquals(openAskShares.getOrDefault(trader.id() + "|" + company, 0L),
                        view.reservedShares().get(company), "trader " + trader.id() + " " + company);
            }
        }
    }
}

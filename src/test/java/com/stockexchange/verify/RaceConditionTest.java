package com.stockexchange.verify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.stockexchange.engine.Exchange;
import com.stockexchange.model.OrderResult;
import com.stockexchange.model.OrderState;
import com.stockexchange.model.Reject;
import com.stockexchange.model.Side;
import com.stockexchange.model.Trade;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Races from the concurrency examples, each repeated thousands of times with all parties released by
 * one latch. One exchange per test; every round cleans up after itself so the next starts from an
 * empty book, and the invariant checker runs at the end.
 */
class RaceConditionTest {

    private static final String CO = "ACME";
    private static final String CO2 = "BETA";
    private static final long PRICE = 1_000;

    @Test
    @Timeout(60)
    void twoBuyersRacingForOneAsk_should_tradeExactly100Shares() {
        int rounds = 10_000;
        TestExchanges.Market market = TestExchanges.market(CO, 3, 2_000_000_000L, 1_000_000);
        try (Exchange ex = market.open(); Race race = new Race(2)) {
            for (int r = 0; r < rounds; r++) {
                ex.place("ask-" + r, 1, CO, Side.ASK, PRICE, 100);
                String round = "-" + r;
                List<OrderResult> bids = race.run(
                        () -> ex.place("bidA" + round, 2, CO, Side.BID, PRICE, 100),
                        () -> ex.place("bidB" + round, 3, CO, Side.BID, PRICE, 100));

                long traded = bids.stream().flatMap(b -> b.trades().stream()).mapToLong(Trade::qty).sum();
                assertEquals(100, traded, "round " + r);
                int winners = 0;
                for (OrderResult bid : bids) {
                    assertTrue(bid.ok(), "round " + r + ": " + bid.reason());
                    if (bid.order().state() == OrderState.DONE) {
                        winners++;
                    } else {
                        assertEquals(100, bid.order().remaining());
                        assertTrue(ex.cancel(bid.order().traderId(), bid.order().id()).ok());
                    }
                }
                assertEquals(1, winners, "round " + r);
            }
            assertEquals(100L * rounds, ex.history(CO).stream().mapToLong(Trade::qty).sum());
            InvariantChecker.check(ex, market.traders());
        }
    }

    @Test
    @Timeout(60)
    void twoSellersRacingForOneBid_should_notDoubleMatch() {
        int rounds = 5_000;
        TestExchanges.Market market = TestExchanges.market(CO, 3, 2_000_000_000L, 1_000_000);
        try (Exchange ex = market.open(); Race race = new Race(2)) {
            for (int r = 0; r < rounds; r++) {
                OrderResult bid = ex.place("bid-" + r, 1, CO, Side.BID, PRICE, 100);
                String round = "-" + r;
                List<OrderResult> asks = race.run(
                        () -> ex.place("askA" + round, 2, CO, Side.ASK, PRICE, 100),
                        () -> ex.place("askB" + round, 3, CO, Side.ASK, PRICE, 100));

                assertEquals(1, asks.stream().mapToLong(a -> a.trades().size()).sum(), "round " + r);
                for (OrderResult ask : asks) {
                    if (ask.order().state() == OrderState.OPEN) {
                        assertTrue(ex.cancel(ask.order().traderId(), ask.order().id()).ok());
                    }
                }
                assertTrue(ex.book(CO).bids().isEmpty(), "bid " + bid.order().id() + " filled once and gone");
            }
            InvariantChecker.check(ex, market.traders());
        }
    }

    /**
     * One trader, 1000 cash, two buys of 800 at once. The orders go to different companies, so two
     * different company locks are held and only the account's own check-and-reserve can stop both.
     */
    @Test
    @Timeout(60)
    void twoParallelBuysOf800With1000Cash_should_acceptExactlyOne() {
        int rounds = 5_000;
        TestExchanges.Market market = TestExchanges.market(List.of(CO, CO2), 1, 1_000, 10);
        try (Exchange ex = market.open(); Race race = new Race(2)) {
            for (int r = 0; r < rounds; r++) {
                String round = "-" + r;
                List<OrderResult> results = race.run(
                        () -> ex.place("x" + round, 1, CO, Side.BID, 100, 8),
                        () -> ex.place("y" + round, 1, CO2, Side.BID, 100, 8));
                assertExactlyOneAccepted(ex, results, Reject.INSUFFICIENT_FUNDS, r);
            }
            assertEquals(1_000, ex.portfolio(1).cash());
            InvariantChecker.check(ex, market.traders());
        }
    }

    /** Same company: the company lock alone serializes the two reservations. */
    @Test
    @Timeout(60)
    void twoParallelBuysOf800SameCompany_should_acceptExactlyOne() {
        int rounds = 2_000;
        TestExchanges.Market market = TestExchanges.market(CO, 1, 1_000, 10);
        try (Exchange ex = market.open(); Race race = new Race(2)) {
            for (int r = 0; r < rounds; r++) {
                String round = "-" + r;
                List<OrderResult> results = race.run(
                        () -> ex.place("x" + round, 1, CO, Side.BID, 100, 8),
                        () -> ex.place("y" + round, 1, CO, Side.BID, 100, 8));
                assertExactlyOneAccepted(ex, results, Reject.INSUFFICIENT_FUNDS, r);
            }
            InvariantChecker.check(ex, market.traders());
        }
    }

    /**
     * Shares belong to one company, so two sells of the same shares always meet under one company lock.
     * This guards the engine's reserve-before-rest step rather than the account lock.
     */
    @Test
    @Timeout(60)
    void twoParallelSellsOf8With10Shares_should_acceptExactlyOne() {
        int rounds = 5_000;
        TestExchanges.Market market = TestExchanges.market(CO, 1, 1_000, 10);
        try (Exchange ex = market.open(); Race race = new Race(2)) {
            for (int r = 0; r < rounds; r++) {
                String round = "-" + r;
                List<OrderResult> results = race.run(
                        () -> ex.place("x" + round, 1, CO, Side.ASK, 50_000, 8),
                        () -> ex.place("y" + round, 1, CO, Side.ASK, 50_000, 8));
                assertExactlyOneAccepted(ex, results, Reject.INSUFFICIENT_SHARES, r);
            }
            assertEquals(10, ex.portfolio(1).shares().get(CO));
            InvariantChecker.check(ex, market.traders());
        }
    }

    /** Full fill vs. modify: either the trade wins and the modify sees a closed order, or the modify wins and nothing trades. */
    @Test
    @Timeout(60)
    void modifyDuringFullFill_should_neverBeHalfApplied() {
        int rounds = 5_000;
        TestExchanges.Market market = TestExchanges.market(CO, 2, 2_000_000_000L, 1_000_000);
        int tradeFirst = 0;
        try (Exchange ex = market.open(); Race race = new Race(2)) {
            for (int r = 0; r < rounds; r++) {
                long askId = ex.place("ask-" + r, 1, CO, Side.ASK, PRICE, 100).order().id();
                String round = "-" + r;
                List<OrderResult> results = race.run(
                        () -> ex.place("bid" + round, 2, CO, Side.BID, PRICE, 100),
                        () -> ex.modify(1, askId, 0, PRICE + 500, 100));
                OrderResult bid = results.get(0);
                OrderResult modify = results.get(1);
                assertTrue(bid.ok());
                if (modify.ok()) {
                    assertTrue(bid.trades().isEmpty(), "round " + r + ": traded at the old price after modify");
                    assertEquals(PRICE + 500, modify.order().price());
                    assertTrue(ex.cancel(1, askId).ok());
                    assertTrue(ex.cancel(2, bid.order().id()).ok());
                } else {
                    assertEquals(Reject.ORDER_CLOSED, modify.reason(), "round " + r);
                    assertEquals(1, bid.trades().size());
                    assertEquals(100, bid.trades().get(0).qty());
                    tradeFirst++;
                }
            }
            InvariantChecker.check(ex, market.traders());
        }
        System.out.println("modifyDuringFullFill: trade first in " + tradeFirst + " of " + rounds + " rounds");
    }

    /** Partial fill vs. modify: a fill bumps the version, so a modify that loses the race gets VERSION_CONFLICT. */
    @Test
    @Timeout(60)
    void modifyDuringPartialFill_should_getVersionConflictOrApplyCleanly() {
        int rounds = 5_000;
        TestExchanges.Market market = TestExchanges.market(CO, 2, 2_000_000_000L, 1_000_000);
        try (Exchange ex = market.open(); Race race = new Race(2)) {
            for (int r = 0; r < rounds; r++) {
                long askId = ex.place("ask-" + r, 1, CO, Side.ASK, PRICE, 100).order().id();
                String round = "-" + r;
                List<OrderResult> results = race.run(
                        () -> ex.place("bid" + round, 2, CO, Side.BID, PRICE, 40),
                        () -> ex.modify(1, askId, 0, PRICE + 500, 100));
                OrderResult bid = results.get(0);
                OrderResult modify = results.get(1);
                if (modify.ok()) {
                    assertTrue(bid.trades().isEmpty(), "round " + r);
                    assertTrue(ex.cancel(2, bid.order().id()).ok());
                } else {
                    assertEquals(Reject.VERSION_CONFLICT, modify.reason(), "round " + r);
                    assertEquals(40, bid.trades().get(0).qty());
                    assertEquals(60, ex.book(CO).asks().get(0).remaining());
                }
                assertTrue(ex.cancel(1, askId).ok());
            }
            InvariantChecker.check(ex, market.traders());
        }
    }

    @Test
    @Timeout(60)
    void sameRequestIdFromEightThreads_should_createOneOrder() {
        int rounds = 2_000;
        int width = 8;
        TestExchanges.Market market = TestExchanges.market(CO, 1, 2_000_000_000L, 0);
        try (Exchange ex = market.open(); Race race = new Race(width)) {
            for (int r = 0; r < rounds; r++) {
                String requestId = "dup-" + r;
                List<Callable<OrderResult>> tasks = new ArrayList<>();
                for (int i = 0; i < width; i++) {
                    tasks.add(() -> ex.place(requestId, 1, CO, Side.BID, PRICE, 10));
                }
                Set<Long> ids = new HashSet<>();
                for (OrderResult result : race.run(tasks)) {
                    assertTrue(result.ok(), "round " + r + ": " + result.reason());
                    ids.add(result.order().id());
                }
                assertEquals(1, ids.size(), "round " + r + ": " + ids);
                assertEquals(1, ex.book(CO).bids().size(), "round " + r);
                assertEquals(PRICE * 10, ex.portfolio(1).reservedCash(), "reserved once");
                assertTrue(ex.cancel(1, ids.iterator().next()).ok());
            }
            OrderResult differs = ex.place("dup-0", 1, CO, Side.BID, PRICE + 1, 10);
            assertFalse(differs.ok());
            assertEquals(Reject.DUPLICATE_REQUEST, differs.reason());
            InvariantChecker.check(ex, market.traders());
        }
    }

    /** Two asks at one price; buyers hammer them at once. The earlier ask must be filled completely first. */
    @Test
    @Timeout(60)
    void earlierAskAtSamePrice_should_fillFirstUnderContention() {
        int rounds = 500;
        int buyers = 10;
        TestExchanges.Market market = TestExchanges.market(CO, 2 + buyers, 2_000_000_000L, 1_000_000);
        try (Exchange ex = market.open(); Race race = new Race(buyers)) {
            for (int r = 0; r < rounds; r++) {
                int seen = ex.history(CO).size();
                long first = ex.place("A-" + r, 1, CO, Side.ASK, PRICE, 100).order().id();
                long second = ex.place("B-" + r, 2, CO, Side.ASK, PRICE, 100).order().id();
                for (int wave = 0; wave < 2; wave++) {
                    List<Callable<OrderResult>> tasks = new ArrayList<>();
                    for (int b = 0; b < buyers; b++) {
                        long trader = 3 + b;
                        String id = "bid-" + r + "-" + wave + "-" + b;
                        tasks.add(() -> ex.place(id, trader, CO, Side.BID, PRICE, 10));
                    }
                    race.run(tasks);
                }
                List<Trade> trades = ex.history(CO).subList(seen, ex.history(CO).size());
                assertEquals(20, trades.size(), "round " + r);
                boolean secondStarted = false;
                for (Trade t : trades) {
                    if (t.askOrderId() == second) {
                        secondStarted = true;
                    } else if (t.askOrderId() == first && secondStarted) {
                        fail("round " + r + ": later ask traded before the earlier one was filled");
                    }
                }
                assertTrue(ex.book(CO).asks().isEmpty() && ex.book(CO).bids().isEmpty(), "round " + r);
            }
            InvariantChecker.check(ex, market.traders());
        }
    }

    private static void assertExactlyOneAccepted(Exchange ex, List<OrderResult> results, Reject loser, int round) {
        OrderResult ok = null;
        int rejected = 0;
        for (OrderResult result : results) {
            if (result.ok()) {
                ok = result;
            } else {
                assertEquals(loser, result.reason(), "round " + round);
                rejected++;
            }
        }
        assertEquals(1, rejected, "round " + round);
        assertTrue(ok != null && ex.cancel(1, ok.order().id()).ok(), "round " + round);
    }
}

package com.stockexchange.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.stockexchange.model.PortfolioView;
import com.stockexchange.model.Trade;
import com.stockexchange.model.TraderAccount;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class AccountServiceTest {

    private static final String ACME = "ACME";
    private static final String BETA = "BETA";

    private static AccountService accounts(long cash, long shares) {
        List<TraderAccount> traders = new ArrayList<>();
        for (long id = 1; id <= 4; id++) {
            traders.add(new TraderAccount(id, cash, Map.of(ACME, shares, BETA, shares)));
        }
        return new AccountService(traders);
    }

    private static Trade trade(long buyer, long seller, long price, long qty) {
        return new Trade(1, 1, ACME, 10, 20, buyer, seller, price, qty, 0);
    }

    @Test
    void reserveCash_should_moveAvailableToReserved() {
        AccountService accounts = accounts(1000, 0);

        assertTrue(accounts.reserveCash(1, 800));

        PortfolioView view = accounts.view(1);
        assertEquals(200, view.cash());
        assertEquals(800, view.reservedCash());
    }

    @Test
    void reserve_should_rejectWithoutChange_when_notEnoughAvailable() {
        AccountService accounts = accounts(1000, 50);

        assertFalse(accounts.reserveCash(1, 1001));
        assertFalse(accounts.reserveShares(1, ACME, 51));
        assertFalse(accounts.reserveShares(1, "NOPE", 1));

        PortfolioView view = accounts.view(1);
        assertEquals(1000, view.cash());
        assertEquals(0, view.reservedCash());
        assertEquals(50L, view.shares().get(ACME));
        assertEquals(0L, view.reservedShares().get(ACME));
    }

    @Test
    void reserveCash_should_notReuseReservedCash() {
        AccountService accounts = accounts(1000, 0);

        assertTrue(accounts.reserveCash(1, 800));

        assertFalse(accounts.reserveCash(1, 800));
        assertTrue(accounts.reserveCash(1, 200));
    }

    @Test
    void twoBuysOf800With1000Cash_should_acceptExactlyOne() throws Exception {
        for (int round = 0; round < 500; round++) {
            AccountService accounts = accounts(1000, 0);

            int accepted = race(() -> accounts.reserveCash(1, 800));

            assertEquals(1, accepted);
            assertEquals(200, accounts.view(1).cash());
            assertEquals(800, accounts.view(1).reservedCash());
        }
    }

    @Test
    void twoSellsOf80With100Shares_should_acceptExactlyOne() throws Exception {
        for (int round = 0; round < 500; round++) {
            AccountService accounts = accounts(0, 100);

            int accepted = race(() -> accounts.reserveShares(1, ACME, 80));

            assertEquals(1, accepted);
            assertEquals(20L, accounts.view(1).shares().get(ACME));
            assertEquals(80L, accounts.view(1).reservedShares().get(ACME));
        }
    }

    @Test
    void release_should_returnReservedToAvailable() {
        AccountService accounts = accounts(1000, 100);
        accounts.reserveCash(1, 600);
        accounts.reserveShares(1, ACME, 70);

        accounts.releaseCash(1, 400);
        accounts.releaseShares(1, ACME, 70);

        PortfolioView view = accounts.view(1);
        assertEquals(800, view.cash());
        assertEquals(200, view.reservedCash());
        assertEquals(100L, view.shares().get(ACME));
        assertEquals(0L, view.reservedShares().get(ACME));
    }

    @Test
    void release_should_throwAndChangeNothing_when_moreThanReserved() {
        AccountService accounts = accounts(1000, 100);
        accounts.reserveCash(1, 100);

        assertThrows(IllegalStateException.class, () -> accounts.releaseCash(1, 101));
        assertThrows(IllegalStateException.class, () -> accounts.releaseShares(1, ACME, 1));

        assertEquals(900, accounts.view(1).cash());
        assertEquals(100, accounts.view(1).reservedCash());
    }

    @Test
    void settle_should_moveCashAndShares_when_tradeAtBuyerLimit() {
        AccountService accounts = accounts(10_000, 100);
        accounts.reserveCash(1, 10 * 100);
        accounts.reserveShares(2, ACME, 100);

        accounts.settle(trade(1, 2, 10, 100), 10);

        PortfolioView buyer = accounts.view(1);
        PortfolioView seller = accounts.view(2);
        assertEquals(9_000, buyer.cash());
        assertEquals(0, buyer.reservedCash());
        assertEquals(200L, buyer.shares().get(ACME));
        assertEquals(11_000, seller.cash());
        assertEquals(0L, seller.shares().get(ACME));
        assertEquals(0L, seller.reservedShares().get(ACME));
    }

    @Test
    void settle_should_refundPriceImprovement_when_buyerLimitAboveTradePrice() {
        AccountService accounts = accounts(10_000, 100);
        accounts.reserveCash(1, 12 * 100);
        accounts.reserveShares(2, ACME, 100);

        accounts.settle(trade(1, 2, 10, 100), 12);

        PortfolioView buyer = accounts.view(1);
        assertEquals(10_000 - 10 * 100, buyer.cash());
        assertEquals(0, buyer.reservedCash());
        assertEquals(10_000 + 10 * 100, accounts.view(2).cash());
    }

    @Test
    void settle_should_consumeOnlyThisFill_when_orderPartlyFilled() {
        AccountService accounts = accounts(10_000, 100);
        accounts.reserveCash(1, 12 * 100);
        accounts.reserveShares(2, ACME, 30);

        accounts.settle(trade(1, 2, 10, 30), 12);

        PortfolioView buyer = accounts.view(1);
        assertEquals(12 * 70, buyer.reservedCash());
        assertEquals(10_000 - 12 * 100 + 2 * 30, buyer.cash());
    }

    @Test
    void settle_should_changeNothing_when_reservationMissing() {
        AccountService accounts = accounts(10_000, 100);
        accounts.reserveCash(1, 10 * 100);

        assertThrows(IllegalStateException.class, () -> accounts.settle(trade(1, 2, 10, 100), 10));

        assertEquals(1_000, accounts.view(1).reservedCash());
        assertEquals(100L, accounts.view(1).shares().get(ACME));
        assertEquals(100L, accounts.view(2).shares().get(ACME));
        assertEquals(10_000, accounts.view(2).cash());
    }

    @Test
    void settle_should_reject_when_tradeAboveBuyerLimit() {
        AccountService accounts = accounts(10_000, 100);

        assertThrows(IllegalArgumentException.class, () -> accounts.settle(trade(1, 2, 11, 1), 10));
    }

    @Test
    void settle_should_netOut_when_buyerIsSeller() {
        AccountService accounts = accounts(10_000, 100);
        accounts.reserveCash(1, 10 * 5);
        accounts.reserveShares(1, ACME, 5);

        accounts.settle(trade(1, 1, 10, 5), 10);

        PortfolioView view = accounts.view(1);
        assertEquals(10_000, view.cash());
        assertEquals(0, view.reservedCash());
        assertEquals(100L, view.shares().get(ACME));
        assertEquals(0L, view.reservedShares().get(ACME));
    }

    @Test
    void settle_should_addCompany_when_buyerHeldNone() {
        AccountService accounts = new AccountService(List.of(
                new TraderAccount(1, 1_000, Map.of()),
                new TraderAccount(2, 0, Map.of(ACME, 10L))));
        accounts.reserveCash(1, 100);
        accounts.reserveShares(2, ACME, 10);

        accounts.settle(trade(1, 2, 10, 10), 10);

        assertEquals(10L, accounts.view(1).shares().get(ACME));
        assertEquals(0L, accounts.view(1).reservedShares().get(ACME));
    }

    @Test
    void view_should_beACopy() {
        AccountService accounts = accounts(1000, 100);
        PortfolioView before = accounts.view(1);

        accounts.reserveCash(1, 500);
        accounts.reserveShares(1, ACME, 10);

        assertEquals(1000, before.cash());
        assertEquals(100L, before.shares().get(ACME));
        assertThrows(UnsupportedOperationException.class, () -> before.shares().put(ACME, 1L));
    }

    @Test
    void totals_should_stayConstant_after_reserveReleaseAndSettle() {
        AccountService accounts = accounts(10_000, 100);
        accounts.reserveCash(1, 12 * 40);
        accounts.reserveShares(2, ACME, 40);
        accounts.settle(trade(1, 2, 10, 40), 12);
        accounts.reserveCash(3, 700);
        accounts.releaseCash(3, 300);

        assertEquals(4 * 10_000, accounts.totalCash());
        assertEquals(4 * 100, accounts.totalShares(ACME));
        assertEquals(4 * 100, accounts.totalShares(BETA));
        assertEquals(0, accounts.totalShares("NOPE"));
    }

    @Test
    void unknownTraderOrBadAmount_should_throw() {
        AccountService accounts = accounts(1000, 100);

        assertFalse(accounts.hasTrader(99));
        assertThrows(IllegalArgumentException.class, () -> accounts.reserveCash(99, 1));
        assertThrows(IllegalArgumentException.class, () -> accounts.view(99));
        assertThrows(IllegalArgumentException.class, () -> accounts.reserveCash(1, -1));
        assertThrows(IllegalArgumentException.class, () -> accounts.releaseShares(1, ACME, -1));
    }

    @Test
    void constructor_should_rejectDuplicateIdsAndNegativeBalances() {
        assertThrows(IllegalArgumentException.class, () -> new AccountService(List.of(
                new TraderAccount(1, 0, Map.of()), new TraderAccount(1, 0, Map.of()))));
        assertThrows(IllegalArgumentException.class, () -> new AccountService(List.of(
                new TraderAccount(1, -1, Map.of()))));
        assertThrows(IllegalArgumentException.class, () -> new AccountService(List.of(
                new TraderAccount(1, 0, Map.of(ACME, -1L)))));
    }

    /**
     * Pairs trade in both directions (1 buys from 2 while 2 buys from 1), so taking the two trader
     * locks in buyer-then-seller order would deadlock. A reader sums totals the whole time; they
     * must never move, because the sum holds every trader lock.
     */
    @Test
    @Timeout(60)
    void concurrentSettlement_should_notDeadlock_and_keepTotalsExactWhileRunning() throws Exception {
        AccountService accounts = accounts(1_000_000, 100_000);
        long cash = accounts.totalCash();
        long shares = accounts.totalShares(ACME);
        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> failures = new ArrayList<>();
        List<Thread> workers = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            long seed = 42 + t;
            Thread worker = new Thread(() -> {
                Random random = new Random(seed);
                try {
                    start.await();
                    for (int i = 0; i < 20_000; i++) {
                        long buyer = 1 + random.nextInt(4);
                        long seller = 1 + random.nextInt(4);
                        long limit = 10 + random.nextInt(5);
                        long price = 10 + random.nextInt((int) (limit - 9));
                        long qty = 1 + random.nextInt(10);
                        if (!accounts.reserveCash(buyer, limit * qty)) {
                            continue;
                        }
                        if (!accounts.reserveShares(seller, ACME, qty)) {
                            accounts.releaseCash(buyer, limit * qty);
                            continue;
                        }
                        accounts.settle(new Trade(i, i, ACME, 1, 2, buyer, seller, price, qty, 0), limit);
                    }
                } catch (Throwable e) {
                    synchronized (failures) {
                        failures.add(e);
                    }
                }
            }, "settler-" + t);
            workers.add(worker);
            worker.start();
        }
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicLong wrongTotals = new AtomicLong();
        Thread reader = new Thread(() -> {
            while (running.get()) {
                if (accounts.totalCash() != cash || accounts.totalShares(ACME) != shares) {
                    wrongTotals.incrementAndGet();
                }
            }
        }, "totals-reader");
        reader.start();
        start.countDown();
        for (Thread worker : workers) {
            worker.join(60_000);
            assertFalse(worker.isAlive(), "worker still running: possible deadlock");
        }
        running.set(false);
        reader.join();

        assertTrue(failures.isEmpty(), () -> String.valueOf(failures));
        assertEquals(0, wrongTotals.get());
        assertEquals(cash, accounts.totalCash());
        assertEquals(shares, accounts.totalShares(ACME));
        for (long id = 1; id <= 4; id++) {
            PortfolioView view = accounts.view(id);
            assertTrue(view.cash() >= 0 && view.reservedCash() == 0);
            assertTrue(view.shares().get(ACME) >= 0 && view.reservedShares().get(ACME) == 0);
        }
    }

    /** Runs the attempt on two threads released together and returns how many succeeded. */
    private static int race(BooleanSupplier attempt) throws InterruptedException {
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Thread thread = new Thread(() -> {
                try {
                    go.await();
                } catch (InterruptedException e) {
                    return;
                }
                if (attempt.getAsBoolean()) {
                    accepted.incrementAndGet();
                }
            });
            threads.add(thread);
            thread.start();
        }
        go.countDown();
        for (Thread thread : threads) {
            thread.join(10_000);
        }
        return accepted.get();
    }
}

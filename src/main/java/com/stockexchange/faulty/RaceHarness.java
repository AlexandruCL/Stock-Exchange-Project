package com.stockexchange.faulty;

import com.stockexchange.engine.Exchange;
import com.stockexchange.model.BookSnapshot;
import com.stockexchange.model.Company;
import com.stockexchange.model.OrderResult;
import com.stockexchange.model.OrderView;
import com.stockexchange.model.PortfolioView;
import com.stockexchange.model.SeedData;
import com.stockexchange.model.Side;
import com.stockexchange.model.Trade;
import com.stockexchange.model.TraderAccount;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.random.RandomGenerator;

/**
 * Hammers any {@link Exchange} with one thread per trader, then checks at rest what must always
 * hold: money and shares are conserved, nothing is negative, ids are unique, no order is filled
 * more than once, the book is not crossed. It works through the public API only, so the same code
 * judges the correct {@code StockExchange} and the faulty {@code RacyExchange}.
 */
public final class RaceHarness {

    /** What one run saw. An exception thrown inside the exchange counts as a symptom, not a crash of the harness. */
    public record Report(String exchange, long operations, long trades, boolean hung,
                         Map<String, Long> exceptions, List<String> violations) {

        public boolean clean() {
            return !hung && exceptions.isEmpty() && violations.isEmpty();
        }

        public void print() {
            System.out.printf("%s: %d operations, %d trades%n", exchange, operations, trades);
            if (hung) {
                System.out.println("  HUNG: workers did not finish in time");
            }
            exceptions.forEach((type, count) -> System.out.println("  exception " + type + " x" + count));
            violations.stream().limit(12).forEach(v -> System.out.println("  VIOLATION " + v));
            if (violations.size() > 12) {
                System.out.println("  ... and " + (violations.size() - 12) + " more violations");
            }
            if (clean()) {
                System.out.println("  all invariants hold");
            }
        }
    }

    private RaceHarness() {
    }

    public static Report run(String name, Exchange exchange, SeedData seed, int opsPerTrader, long randomSeed) {
        return run(name, exchange, seed, opsPerTrader, randomSeed, 15);
    }

    /**
     * {@code timeoutSeconds}: workers still running after that are reported as hung and keep running
     * (a thread looping in a corrupted TreeMap cannot be stopped), so the at-rest check of a hung run
     * is only indicative.
     */
    public static Report run(String name, Exchange exchange, SeedData seed, int opsPerTrader, long randomSeed,
                             int timeoutSeconds) {
        Map<String, Long> exceptions = new ConcurrentHashMap<>();
        Map<Long, Long> orderQty = new ConcurrentHashMap<>();
        List<Long> issuedIds = java.util.Collections.synchronizedList(new ArrayList<>());
        AtomicLong operations = new AtomicLong();

        List<TraderAccount> traders = seed.traders();
        CountDownLatch ready = new CountDownLatch(traders.size());
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(traders.size());
        for (TraderAccount trader : traders) {
            Thread worker = new Thread(() -> {
                RandomGenerator rnd = new java.util.SplittableRandom(randomSeed * 1000 + trader.id());
                List<Long> mine = new ArrayList<>();
                ready.countDown();
                try {
                    go.await();
                    for (int i = 0; i < opsPerTrader; i++) {
                        try {
                            step(exchange, seed, trader.id(), i, rnd, mine, orderQty, issuedIds);
                        } catch (RuntimeException e) {
                            exceptions.merge(e.getClass().getSimpleName(), 1L, Long::sum);
                        }
                        operations.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }, "harness-trader-" + trader.id());
            worker.setDaemon(true);
            worker.start();
        }
        boolean hung;
        try {
            ready.await();
            go.countDown();
            hung = !done.await(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            hung = true;
        }
        List<String> violations = new ArrayList<>();
        long trades = 0;
        try {
            trades = checkAtRest(exchange, seed, orderQty, issuedIds, violations);
        } catch (RuntimeException e) {
            exceptions.merge("while checking: " + e.getClass().getSimpleName(), 1L, Long::sum);
        }
        return new Report(name, operations.get(), trades, hung, new TreeMap<>(exceptions), violations);
    }

    private static void step(Exchange exchange, SeedData seed, long traderId, int i, RandomGenerator rnd,
                             List<Long> mine, Map<Long, Long> orderQty, List<Long> issuedIds) {
        Company company = seed.companies().get(rnd.nextInt(seed.companies().size()));
        int dice = rnd.nextInt(100);
        if (dice < 70 || mine.isEmpty()) {
            Side side = rnd.nextBoolean() ? Side.BID : Side.ASK;
            long price = Math.max(1, company.initialLastPrice() + rnd.nextInt(-3, 4));
            long qty = rnd.nextInt(1, 11);
            OrderResult r = exchange.place("t" + traderId + "-" + i, traderId, company.id(), side, price, qty);
            if (r.ok()) {
                mine.add(r.order().id());
                orderQty.put(r.order().id(), qty);
                issuedIds.add(r.order().id());
            }
            return;
        }
        long orderId = mine.get(rnd.nextInt(mine.size()));
        if (dice < 85) {
            exchange.cancel(traderId, orderId);
            return;
        }
        for (Company c : seed.companies()) {
            for (OrderView o : exchange.book(c.id()).bids()) {
                if (o.id() == orderId) {
                    exchange.modify(traderId, orderId, o.version(), o.price() + 1, o.qty());
                    return;
                }
            }
        }
    }

    private static long checkAtRest(Exchange exchange, SeedData seed, Map<Long, Long> orderQty,
                                    List<Long> issuedIds, List<String> violations) {
        long cash = 0;
        Map<String, Long> shares = new HashMap<>();
        for (TraderAccount t : seed.traders()) {
            PortfolioView p = exchange.portfolio(t.id());
            cash += p.cash() + p.reservedCash();
            if (p.cash() < 0 || p.reservedCash() < 0) {
                violations.add("trader " + t.id() + " has negative cash " + p.cash() + " / reserved " + p.reservedCash());
            }
            p.shares().forEach((c, q) -> {
                shares.merge(c, q, Long::sum);
                if (q < 0) {
                    violations.add("trader " + t.id() + " has negative " + c + " shares: " + q);
                }
            });
            p.reservedShares().forEach((c, q) -> {
                shares.merge(c, q, Long::sum);
                if (q < 0) {
                    violations.add("trader " + t.id() + " has negative reserved " + c + " shares: " + q);
                }
            });
        }
        if (cash != seed.totalCash()) {
            violations.add("cash not conserved: " + cash + " now, " + seed.totalCash() + " at start ("
                    + (cash - seed.totalCash()) + " cents)");
        }
        for (Company c : seed.companies()) {
            long now = shares.getOrDefault(c.id(), 0L);
            if (now != seed.totalShares(c.id())) {
                violations.add(c.id() + " shares not conserved: " + now + " now, " + seed.totalShares(c.id())
                        + " at start (" + (now - seed.totalShares(c.id())) + ")");
            }
        }
        Set<Long> seen = new HashSet<>();
        for (Long id : issuedIds) {
            if (!seen.add(id)) {
                violations.add("order id " + id + " handed to two different orders");
            }
        }
        long trades = 0;
        for (Company c : seed.companies()) {
            List<Trade> history = exchange.history(c.id());
            trades += history.size();
            Set<Long> tradeIds = new HashSet<>();
            Map<Long, Long> filled = new HashMap<>();
            for (Trade t : history) {
                if (t == null) {
                    violations.add(c.id() + " history contains a null entry");
                    continue;
                }
                if (!tradeIds.add(t.id())) {
                    violations.add(c.id() + " trade id " + t.id() + " used twice");
                }
                filled.merge(t.bidOrderId(), t.qty(), Long::sum);
                filled.merge(t.askOrderId(), t.qty(), Long::sum);
            }
            filled.forEach((orderId, qty) -> {
                Long original = orderQty.get(orderId);
                if (original != null && qty > original) {
                    violations.add("order " + orderId + " filled " + qty + " but asked for only " + original);
                }
            });
            BookSnapshot book = exchange.book(c.id());
            checkSide(c.id(), book.bids(), violations);
            checkSide(c.id(), book.asks(), violations);
            if (!book.bids().isEmpty() && !book.asks().isEmpty()
                    && book.bids().get(0).price() >= book.asks().get(0).price()) {
                violations.add(c.id() + " book is crossed at rest: bid " + book.bids().get(0).price()
                        + " >= ask " + book.asks().get(0).price());
            }
        }
        return trades;
    }

    private static void checkSide(String companyId, List<OrderView> side, List<String> violations) {
        Set<Long> seqs = new HashSet<>();
        for (OrderView o : side) {
            if (o.remaining() < 0 || o.remaining() > o.qty()) {
                violations.add(companyId + " order " + o.id() + " has remaining " + o.remaining() + " of " + o.qty());
            }
            if (!seqs.add(o.seq())) {
                violations.add(companyId + " two resting orders share seq " + o.seq());
            }
        }
    }
}

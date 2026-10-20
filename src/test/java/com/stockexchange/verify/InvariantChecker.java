package com.stockexchange.verify;

import com.stockexchange.engine.Exchange;
import com.stockexchange.model.BookSnapshot;
import com.stockexchange.model.OrderState;
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

/**
 * Safety invariants from the task division, checked through the public Exchange API only, so they
 * hold for any implementation. Call {@link #check} when no command is running: totals read portfolio
 * by portfolio and are not one atomic snapshot.
 */
public final class InvariantChecker {

    private InvariantChecker() {
    }

    public static void check(Exchange exchange, SeedData seed) {
        check(exchange, seed.traders());
    }

    /** Throws AssertionError listing every violation found. */
    public static void check(Exchange exchange, List<TraderAccount> initial) {
        List<String> violations = violations(exchange, initial);
        if (!violations.isEmpty()) {
            throw new AssertionError(violations.size() + " invariant violation(s):\n  "
                    + String.join("\n  ", violations));
        }
    }

    public static List<String> violations(Exchange exchange, List<TraderAccount> initial) {
        List<String> out = new ArrayList<>();
        Set<String> companies = exchange.companies();

        long startCash = 0;
        Map<String, Long> startShares = new HashMap<>();
        for (TraderAccount account : initial) {
            startCash += account.cash();
            account.shares().forEach((c, q) -> startShares.merge(c, q, Long::sum));
        }

        Map<Long, PortfolioView> portfolios = new HashMap<>();
        long cash = 0;
        Map<String, Long> shares = new HashMap<>();
        for (TraderAccount account : initial) {
            PortfolioView p = exchange.portfolio(account.id());
            portfolios.put(account.id(), p);
            if (p.cash() < 0 || p.reservedCash() < 0) {
                out.add("trader " + account.id() + " negative cash: " + p.cash() + " / reserved " + p.reservedCash());
            }
            cash += p.cash() + p.reservedCash();
            for (String c : companies) {
                long free = p.shares().getOrDefault(c, 0L);
                long reserved = p.reservedShares().getOrDefault(c, 0L);
                if (free < 0 || reserved < 0) {
                    out.add("trader " + account.id() + " negative " + c + ": " + free + " / reserved " + reserved);
                }
                shares.merge(c, free + reserved, Long::sum);
            }
        }
        if (cash != startCash) {
            out.add("total cash " + cash + " != initial " + startCash);
        }
        for (String c : companies) {
            long expected = startShares.getOrDefault(c, 0L);
            long actual = shares.getOrDefault(c, 0L);
            if (actual != expected) {
                out.add("total " + c + " shares " + actual + " != initial " + expected);
            }
        }

        Map<Long, Long> openBidCash = new HashMap<>();
        Map<String, Long> openAskShares = new HashMap<>();
        Map<Long, Long> cashFlow = new HashMap<>();
        Map<String, Long> shareFlow = new HashMap<>();
        for (String c : companies) {
            BookSnapshot book = exchange.book(c);
            out.addAll(bookViolations(book));
            for (OrderView o : book.bids()) {
                openBidCash.merge(o.traderId(), o.price() * o.remaining(), Long::sum);
            }
            for (OrderView o : book.asks()) {
                openAskShares.merge(o.traderId() + "|" + c, o.remaining(), Long::sum);
            }
            List<Trade> history = exchange.history(c);
            out.addAll(tradeViolations(c, history));
            for (Trade t : history) {
                long notional = t.price() * t.qty();
                cashFlow.merge(t.buyerId(), -notional, Long::sum);
                cashFlow.merge(t.sellerId(), notional, Long::sum);
                shareFlow.merge(t.buyerId() + "|" + c, t.qty(), Long::sum);
                shareFlow.merge(t.sellerId() + "|" + c, -t.qty(), Long::sum);
            }
        }

        // Cash paid equals cash received: each holding is its start plus what the trade history moved.
        for (TraderAccount account : initial) {
            long id = account.id();
            PortfolioView p = portfolios.get(id);
            long expectedCash = account.cash() + cashFlow.getOrDefault(id, 0L);
            if (p.cash() + p.reservedCash() != expectedCash) {
                out.add("trader " + id + " cash " + (p.cash() + p.reservedCash()) + " != start + trades " + expectedCash);
            }
            for (String c : companies) {
                long held = p.shares().getOrDefault(c, 0L) + p.reservedShares().getOrDefault(c, 0L);
                long expected = account.shares().getOrDefault(c, 0L) + shareFlow.getOrDefault(id + "|" + c, 0L);
                if (held != expected) {
                    out.add("trader " + id + " " + c + " " + held + " != start + trades " + expected);
                }
            }
        }

        // Reservations must back exactly the open orders: nothing leaked on cancel, modify or fill.
        for (Map.Entry<Long, PortfolioView> e : portfolios.entrySet()) {
            long trader = e.getKey();
            PortfolioView p = e.getValue();
            long expectedCash = openBidCash.getOrDefault(trader, 0L);
            if (p.reservedCash() != expectedCash) {
                out.add("trader " + trader + " reserved cash " + p.reservedCash() + " != open bids " + expectedCash);
            }
            for (String c : companies) {
                long reserved = p.reservedShares().getOrDefault(c, 0L);
                long expected = openAskShares.getOrDefault(trader + "|" + c, 0L);
                if (reserved != expected) {
                    out.add("trader " + trader + " reserved " + c + " " + reserved + " != open asks " + expected);
                }
            }
        }
        return out;
    }

    /** Sorted by price-time priority, not crossed, and holding only open orders with remaining > 0. */
    public static List<String> bookViolations(BookSnapshot book) {
        List<String> out = new ArrayList<>();
        String c = book.companyId();
        checkSide(c, book.bids(), Side.BID, out);
        checkSide(c, book.asks(), Side.ASK, out);
        if (!book.bids().isEmpty() && !book.asks().isEmpty()
                && book.bids().get(0).price() >= book.asks().get(0).price()) {
            out.add(c + " book crossed: bid " + book.bids().get(0).price() + " >= ask " + book.asks().get(0).price());
        }
        return out;
    }

    private static void checkSide(String company, List<OrderView> side, Side expected, List<String> out) {
        OrderView prev = null;
        for (OrderView o : side) {
            if (o.side() != expected || !company.equals(o.companyId())) {
                out.add(company + " order " + o.id() + " on wrong side or book");
            }
            if (o.remaining() <= 0 || o.remaining() > o.qty()) {
                out.add(company + " order " + o.id() + " remaining " + o.remaining() + " of " + o.qty());
            }
            if (o.state() != OrderState.OPEN) {
                out.add(company + " order " + o.id() + " in book with state " + o.state());
            }
            if (prev != null) {
                boolean better = expected == Side.BID ? prev.price() > o.price() : prev.price() < o.price();
                if (!better && !(prev.price() == o.price() && prev.seq() < o.seq())) {
                    out.add(company + " " + expected + " order " + o.id() + " out of price-time order");
                }
            }
            prev = o;
        }
    }

    /** Positive trades, unique ids, and globalSeq increasing in history order. */
    public static List<String> tradeViolations(String company, List<Trade> history) {
        List<String> out = new ArrayList<>();
        Set<Long> ids = new HashSet<>();
        long lastSeq = 0;
        for (Trade t : history) {
            if (t.qty() <= 0 || t.price() <= 0) {
                out.add(company + " trade " + t.id() + " qty " + t.qty() + " price " + t.price());
            }
            if (!company.equals(t.companyId())) {
                out.add(company + " history holds trade " + t.id() + " of " + t.companyId());
            }
            if (!ids.add(t.id())) {
                out.add(company + " duplicate trade id " + t.id());
            }
            if (t.globalSeq() <= lastSeq) {
                out.add(company + " trade " + t.id() + " globalSeq " + t.globalSeq() + " not increasing");
            }
            lastSeq = t.globalSeq();
        }
        return out;
    }
}

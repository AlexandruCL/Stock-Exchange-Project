package com.stockexchange.sim;

import com.stockexchange.engine.Exchange;
import com.stockexchange.model.BookSnapshot;
import com.stockexchange.model.Company;
import com.stockexchange.model.PortfolioView;
import com.stockexchange.model.SeedData;
import com.stockexchange.model.Trade;
import com.stockexchange.model.TraderAccount;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * End-of-run report through the public {@link Exchange} API: per-company trading statistics and the
 * safety invariants (money and shares conserved, nothing negative, unique and ordered trade ids,
 * books not crossed). Call it when no trader is running.
 */
public final class RunSummary {

    private final List<String> lines = new ArrayList<>();
    private final List<String> violations = new ArrayList<>();

    private RunSummary() {
    }

    public static RunSummary of(Exchange exchange, SeedData seed) {
        RunSummary summary = new RunSummary();
        summary.statistics(exchange, seed);
        summary.invariants(exchange, seed);
        return summary;
    }

    public boolean passed() {
        return violations.isEmpty();
    }

    public List<String> violations() {
        return List.copyOf(violations);
    }

    public String text() {
        return String.join(System.lineSeparator(), lines);
    }

    private void statistics(Exchange exchange, SeedData seed) {
        lines.add("---------------- run summary ----------------");
        lines.add(String.format(Locale.ROOT, "%-6s %7s %9s %10s %10s %10s %10s %9s", "company", "trades", "volume",
                "vwap", "low", "high", "last", "open b/a"));
        long totalTrades = 0;
        for (Company company : seed.companies()) {
            List<Trade> history = exchange.history(company.id());
            BookSnapshot book = exchange.book(company.id());
            long volume = 0;
            long notional = 0;
            long low = Long.MAX_VALUE;
            long high = 0;
            for (Trade trade : history) {
                volume += trade.qty();
                notional += trade.price() * trade.qty();
                low = Math.min(low, trade.price());
                high = Math.max(high, trade.price());
            }
            totalTrades += history.size();
            lines.add(String.format(Locale.ROOT, "%-6s %7d %9d %10s %10s %10s %10s %4d/%-4d", company.id(), history.size(),
                    volume, history.isEmpty() ? "-" : money(notional / volume),
                    history.isEmpty() ? "-" : money(low), history.isEmpty() ? "-" : money(high),
                    money(book.lastPrice()), book.bids().size(), book.asks().size()));
        }
        lines.add("total trades: " + totalTrades);
    }

    private void invariants(Exchange exchange, SeedData seed) {
        long cash = 0;
        List<Long> shares = new ArrayList<>(Collections.nCopies(seed.companies().size(), 0L));
        for (TraderAccount trader : seed.traders()) {
            PortfolioView p = exchange.portfolio(trader.id());
            cash += p.cash() + p.reservedCash();
            if (p.cash() < 0 || p.reservedCash() < 0) {
                violations.add("trader " + trader.id() + " has negative cash");
            }
            for (int c = 0; c < seed.companies().size(); c++) {
                String id = seed.companies().get(c).id();
                long held = p.shares().getOrDefault(id, 0L);
                long reserved = p.reservedShares().getOrDefault(id, 0L);
                if (held < 0 || reserved < 0) {
                    violations.add("trader " + trader.id() + " has negative " + id + " shares");
                }
                shares.set(c, shares.get(c) + held + reserved);
            }
        }
        lines.add(String.format(Locale.ROOT, "cash   total %,d cents, expected %,d: %s", cash, seed.totalCash(),
                check(cash == seed.totalCash(), "cash not conserved")));
        for (int c = 0; c < seed.companies().size(); c++) {
            String id = seed.companies().get(c).id();
            lines.add(String.format(Locale.ROOT, "shares %s total %,d, expected %,d: %s", id, shares.get(c), seed.totalShares(id),
                    check(shares.get(c) == seed.totalShares(id), id + " shares not conserved")));
        }
        boolean tradesOk = true;
        boolean booksOk = true;
        for (Company company : seed.companies()) {
            Set<Long> ids = new HashSet<>();
            long lastSeq = 0;
            for (Trade trade : exchange.history(company.id())) {
                if (!ids.add(trade.id()) || trade.globalSeq() <= lastSeq || trade.qty() <= 0 || trade.price() <= 0) {
                    tradesOk = false;
                    violations.add(company.id() + " bad trade " + trade);
                }
                lastSeq = trade.globalSeq();
            }
            BookSnapshot book = exchange.book(company.id());
            if (!book.bids().isEmpty() && !book.asks().isEmpty()
                    && book.bids().get(0).price() >= book.asks().get(0).price()) {
                booksOk = false;
                violations.add(company.id() + " book is crossed");
            }
        }
        lines.add("trades unique, ordered, positive: " + (tradesOk ? "OK" : "VIOLATED"));
        lines.add("books sorted and not crossed:     " + (booksOk ? "OK" : "VIOLATED"));
        lines.add("RESULT: " + (passed() ? "PASS, all invariants hold" : "FAIL, " + violations.size() + " violation(s)"));
    }

    private String check(boolean ok, String violation) {
        if (!ok) {
            violations.add(violation);
        }
        return ok ? "OK" : "VIOLATED";
    }

    private static String money(long cents) {
        return String.format(Locale.ROOT, "%d.%02d", cents / 100, cents % 100);
    }
}

package com.stockexchange.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Deterministic starting market: companies, traders, and the totals the invariant checks compare against. */
public final class SeedData {

    public static final List<String> TICKERS = List.of("ACME", "BETA", "CORE", "DELT", "EPSL");
    public static final List<Long> INITIAL_PRICES_CENTS = List.of(1_000L, 2_500L, 5_000L, 750L, 12_000L);
    public static final int TRADER_COUNT = 20;
    public static final long STARTING_CASH_CENTS = 1_000_000L * 100;
    public static final long SHARE_LOT = 100;
    public static final int LOT_SPREAD = 4;

    private final List<TraderAccount> traders;
    private final List<Company> companies;

    public SeedData() {
        List<TraderAccount> accounts = new ArrayList<>();
        for (long traderId = 1; traderId <= TRADER_COUNT; traderId++) {
            Map<String, Long> shares = new HashMap<>();
            for (int c = 0; c < TICKERS.size(); c++) {
                shares.put(TICKERS.get(c), startingShares(traderId, c));
            }
            accounts.add(new TraderAccount(traderId, STARTING_CASH_CENTS, shares));
        }
        traders = List.copyOf(accounts);

        List<Company> list = new ArrayList<>();
        for (int c = 0; c < TICKERS.size(); c++) {
            String ticker = TICKERS.get(c);
            list.add(new Company(ticker, ticker, INITIAL_PRICES_CENTS.get(c), totalShares(ticker)));
        }
        companies = List.copyOf(list);
    }

    // Trader t holds 1..LOT_SPREAD lots of company c, rotating so holdings differ between traders.
    private static long startingShares(long traderId, int companyIndex) {
        return SHARE_LOT * ((traderId + companyIndex) % LOT_SPREAD + 1);
    }

    public List<Company> companies() {
        return companies;
    }

    public List<TraderAccount> traders() {
        return traders;
    }

    public long totalCash() {
        return traders.stream().mapToLong(TraderAccount::cash).sum();
    }

    public long totalShares(String companyId) {
        return traders.stream().mapToLong(t -> t.shares().getOrDefault(companyId, 0L)).sum();
    }
}

package com.stockexchange.verify;

import com.stockexchange.engine.Accounts;
import com.stockexchange.model.PortfolioView;
import com.stockexchange.model.Trade;
import com.stockexchange.model.TraderAccount;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Strict in-memory {@link Accounts} for the cross-cutting tests, until Fabi's AccountService is merged.
 * Lock order: the caller holds the company lock, then this class locks one portfolio, or two with the
 * lower trader id first. Any release or settle that would drive a balance negative throws, so an engine
 * bookkeeping bug fails the test instead of hiding in the totals.
 */
public final class ReferenceAccounts implements Accounts {

    private final Map<Long, Portfolio> portfolios = new HashMap<>();

    public ReferenceAccounts(Collection<TraderAccount> initial) {
        for (TraderAccount account : initial) {
            portfolios.put(account.id(), new Portfolio(account));
        }
    }

    public boolean exists(long traderId) {
        return portfolios.containsKey(traderId);
    }

    @Override
    public boolean reserveCash(long traderId, long amount) {
        Portfolio p = get(traderId);
        synchronized (p) {
            if (amount < 0 || p.cash < amount) {
                return false;
            }
            p.cash -= amount;
            p.reservedCash += amount;
            return true;
        }
    }

    @Override
    public boolean reserveShares(long traderId, String companyId, long qty) {
        Portfolio p = get(traderId);
        synchronized (p) {
            if (qty < 0 || p.shares.getOrDefault(companyId, 0L) < qty) {
                return false;
            }
            p.shares.merge(companyId, -qty, Long::sum);
            p.reservedShares.merge(companyId, qty, Long::sum);
            return true;
        }
    }

    @Override
    public void releaseCash(long traderId, long amount) {
        Portfolio p = get(traderId);
        synchronized (p) {
            requireCovered(p.reservedCash, amount, "reserved cash", traderId);
            p.reservedCash -= amount;
            p.cash += amount;
        }
    }

    @Override
    public void releaseShares(long traderId, String companyId, long qty) {
        Portfolio p = get(traderId);
        synchronized (p) {
            requireCovered(p.reservedShares.getOrDefault(companyId, 0L), qty, "reserved " + companyId, traderId);
            p.reservedShares.merge(companyId, -qty, Long::sum);
            p.shares.merge(companyId, qty, Long::sum);
        }
    }

    @Override
    public void settle(Trade trade, long buyerLimitPrice) {
        Portfolio buyer = get(trade.buyerId());
        Portfolio seller = get(trade.sellerId());
        Portfolio first = trade.buyerId() <= trade.sellerId() ? buyer : seller;
        Portfolio second = first == buyer ? seller : buyer;
        synchronized (first) {
            synchronized (second) {
                long paid = trade.price() * trade.qty();
                long reservedForFill = buyerLimitPrice * trade.qty();
                String company = trade.companyId();
                requireCovered(buyer.reservedCash, reservedForFill, "reserved cash", trade.buyerId());
                requireCovered(seller.reservedShares.getOrDefault(company, 0L), trade.qty(),
                        "reserved " + company, trade.sellerId());
                buyer.reservedCash -= reservedForFill;
                buyer.cash += reservedForFill - paid;
                buyer.shares.merge(company, trade.qty(), Long::sum);
                seller.reservedShares.merge(company, -trade.qty(), Long::sum);
                seller.cash += paid;
            }
        }
    }

    /** Unknown traders get an empty view, matching StubExchange. */
    public PortfolioView view(long traderId) {
        Portfolio p = portfolios.get(traderId);
        if (p == null) {
            return new PortfolioView(traderId, 0, 0, Map.of(), Map.of());
        }
        synchronized (p) {
            return new PortfolioView(traderId, p.cash, p.reservedCash, p.shares, p.reservedShares);
        }
    }

    private Portfolio get(long traderId) {
        Portfolio p = portfolios.get(traderId);
        if (p == null) {
            throw new IllegalArgumentException("unknown trader " + traderId);
        }
        return p;
    }

    private static void requireCovered(long held, long needed, String what, long traderId) {
        if (needed < 0 || held < needed) {
            throw new IllegalStateException("trader " + traderId + ": " + what + " " + held + " < " + needed);
        }
    }

    private static final class Portfolio {
        long cash;
        long reservedCash;
        final Map<String, Long> shares;
        final Map<String, Long> reservedShares = new HashMap<>();

        Portfolio(TraderAccount account) {
            cash = account.cash();
            shares = new HashMap<>(account.shares());
            for (String company : account.shares().keySet()) {
                reservedShares.put(company, 0L);
            }
        }
    }
}

package com.stockexchange.faulty;

import com.stockexchange.engine.Accounts;
import com.stockexchange.model.PortfolioView;
import com.stockexchange.model.Trade;
import com.stockexchange.model.TraderAccount;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * FAULTY on purpose (data races and consistency). Same API and arithmetic as
 * {@code engine.AccountService}, with every lock removed. Compare method by method with the
 * correct class; see docs/faulty-data-races.md for the bug list (#1 to #4 are here).
 *
 * <p>The portfolio map itself is built once in the constructor and never changed, so reading it
 * from many threads is safe. Only the {@link Portfolio} objects inside are shared and mutable.
 */
public final class RacyAccounts implements Accounts {

    private final Map<Long, Portfolio> portfolios = new HashMap<>();

    public RacyAccounts(Collection<TraderAccount> initial) {
        for (TraderAccount account : initial) {
            portfolios.put(account.id(), new Portfolio(account));
        }
    }

    public boolean hasTrader(long traderId) {
        return portfolios.containsKey(traderId);
    }

    // BUG #1 (check-then-act): two threads both pass the check, both subtract. cash goes below
    // zero and more cash is reserved than the trader owns. Fix: do check and update under the
    // trader lock (AccountService.reserveCash).
    @Override
    public boolean reserveCash(long traderId, long amount) {
        Portfolio p = portfolio(traderId);
        if (p.cash < amount) {
            return false;
        }
        p.cash -= amount;
        p.reservedCash += amount;
        return true;
    }

    // BUG #1 again, for shares; BUG #3 on top: HashMap.merge from several threads is not atomic.
    @Override
    public boolean reserveShares(long traderId, String companyId, long qty) {
        Portfolio p = portfolio(traderId);
        if (p.shares(companyId) < qty) {
            return false;
        }
        p.shares.merge(companyId, -qty, Long::sum);
        p.reservedShares.merge(companyId, qty, Long::sum);
        return true;
    }

    @Override
    public void releaseCash(long traderId, long amount) {
        Portfolio p = portfolio(traderId);
        if (p.reservedCash < amount) {
            throw new IllegalStateException("trader " + traderId + " releases " + amount
                    + " cents but has only " + p.reservedCash + " reserved");
        }
        p.reservedCash -= amount;
        p.cash += amount;
    }

    @Override
    public void releaseShares(long traderId, String companyId, long qty) {
        Portfolio p = portfolio(traderId);
        if (p.reservedShares(companyId) < qty) {
            throw new IllegalStateException("trader " + traderId + " releases " + qty + " " + companyId
                    + " shares but has only " + p.reservedShares(companyId) + " reserved");
        }
        p.reservedShares.merge(companyId, -qty, Long::sum);
        p.shares.merge(companyId, qty, Long::sum);
    }

    // BUG #2 (lost update): "+=" is read, add, write. Two trades of different companies settle at
    // the same time on the same trader (the company locks do not exclude each other, and here
    // there are no company locks at all), and one write overwrites the other. Cash and shares are
    // created or destroyed. Fix: hold both trader locks, lower id first (AccountService.settle).
    @Override
    public void settle(Trade trade, long buyerLimitPrice) {
        if (trade.qty() <= 0 || trade.price() <= 0 || buyerLimitPrice < trade.price()) {
            throw new IllegalArgumentException("cannot settle " + trade + " at buyer limit " + buyerLimitPrice);
        }
        Portfolio buyer = portfolio(trade.buyerId());
        Portfolio seller = portfolio(trade.sellerId());
        String companyId = trade.companyId();
        long reserved = buyerLimitPrice * trade.qty();
        long paid = trade.price() * trade.qty();
        if (buyer.reservedCash < reserved || seller.reservedShares(companyId) < trade.qty()) {
            throw new IllegalStateException("reservation missing for " + trade);
        }
        buyer.reservedCash -= reserved;
        buyer.cash += reserved - paid;
        buyer.receiveShares(companyId, trade.qty());
        seller.reservedShares.merge(companyId, -trade.qty(), Long::sum);
        seller.cash += paid;
    }

    // BUG #4 (torn read): copied without a lock while settle runs, so cash and reservedCash can
    // come from different moments, and copying a HashMap while another thread changes it can throw
    // ConcurrentModificationException. Fix: copy under the trader lock (AccountService.view).
    public PortfolioView view(long traderId) {
        Portfolio p = portfolio(traderId);
        return new PortfolioView(traderId, p.cash, p.reservedCash, p.shares, p.reservedShares);
    }

    private Portfolio portfolio(long traderId) {
        Portfolio p = portfolios.get(traderId);
        if (p == null) {
            throw new IllegalArgumentException("unknown trader " + traderId);
        }
        return p;
    }

    /** BUG #3 lives here: plain fields and plain HashMaps, no lock, no volatile. */
    private static final class Portfolio {

        long cash;
        long reservedCash;
        final Map<String, Long> shares;
        final Map<String, Long> reservedShares = new HashMap<>();

        Portfolio(TraderAccount account) {
            cash = account.cash();
            shares = new HashMap<>(account.shares());
            for (String companyId : shares.keySet()) {
                reservedShares.put(companyId, 0L);
            }
        }

        long shares(String companyId) {
            return shares.getOrDefault(companyId, 0L);
        }

        long reservedShares(String companyId) {
            return reservedShares.getOrDefault(companyId, 0L);
        }

        void receiveShares(String companyId, long qty) {
            shares.merge(companyId, qty, Long::sum);
            reservedShares.putIfAbsent(companyId, 0L);
        }
    }
}

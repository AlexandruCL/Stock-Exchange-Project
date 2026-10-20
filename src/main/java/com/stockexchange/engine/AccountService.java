package com.stockexchange.engine;

import com.stockexchange.model.PortfolioView;
import com.stockexchange.model.Trade;
import com.stockexchange.model.TraderAccount;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.ToLongFunction;

/**
 * Cash and shares of every trader, available and reserved, in cents and share counts.
 * Lock order: the company lock (held by the {@code MatchingEngine} that calls in) first, then
 * trader locks, lower trader id first. A trader lock is never held while taking a company lock.
 */
public final class AccountService implements Accounts {

    /** Sorted by trader id, which is also the order in which several trader locks are taken. */
    private final Map<Long, Portfolio> portfolios;

    public AccountService(Collection<TraderAccount> initial) {
        Map<Long, Portfolio> byId = new TreeMap<>();
        for (TraderAccount account : initial) {
            if (byId.putIfAbsent(account.id(), new Portfolio(account)) != null) {
                throw new IllegalArgumentException("duplicate trader id " + account.id());
            }
        }
        portfolios = Collections.unmodifiableMap(byId);
    }

    public boolean hasTrader(long traderId) {
        return portfolios.containsKey(traderId);
    }

    @Override
    public boolean reserveCash(long traderId, long amount) {
        requireNonNegative(amount);
        Portfolio p = portfolio(traderId);
        p.lock.lock();
        try {
            if (p.cash < amount) {
                return false;
            }
            p.cash -= amount;
            p.reservedCash += amount;
            return true;
        } finally {
            p.lock.unlock();
        }
    }

    @Override
    public boolean reserveShares(long traderId, String companyId, long qty) {
        requireNonNegative(qty);
        Portfolio p = portfolio(traderId);
        p.lock.lock();
        try {
            if (p.shares(companyId) < qty) {
                return false;
            }
            p.shares.merge(companyId, -qty, Long::sum);
            p.reservedShares.merge(companyId, qty, Long::sum);
            return true;
        } finally {
            p.lock.unlock();
        }
    }

    @Override
    public void releaseCash(long traderId, long amount) {
        requireNonNegative(amount);
        Portfolio p = portfolio(traderId);
        p.lock.lock();
        try {
            if (p.reservedCash < amount) {
                throw new IllegalStateException("trader " + traderId + " releases " + amount
                        + " cents but has only " + p.reservedCash + " reserved");
            }
            p.reservedCash -= amount;
            p.cash += amount;
        } finally {
            p.lock.unlock();
        }
    }

    @Override
    public void releaseShares(long traderId, String companyId, long qty) {
        requireNonNegative(qty);
        Portfolio p = portfolio(traderId);
        p.lock.lock();
        try {
            if (p.reservedShares(companyId) < qty) {
                throw new IllegalStateException("trader " + traderId + " releases " + qty + " " + companyId
                        + " shares but has only " + p.reservedShares(companyId) + " reserved");
            }
            p.reservedShares.merge(companyId, -qty, Long::sum);
            p.shares.merge(companyId, qty, Long::sum);
        } finally {
            p.lock.unlock();
        }
    }

    /**
     * Buyer: reserved cash drops by buyerLimitPrice * qty (what was reserved for these shares),
     * the price improvement (buyerLimitPrice - price) * qty goes back to available cash, and the
     * shares arrive. Seller: reserved shares drop by qty and price * qty arrives as cash.
     * All or nothing: if either reservation is short, nothing changes and the call throws.
     */
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
        Portfolio first = trade.buyerId() < trade.sellerId() ? buyer : seller;
        Portfolio second = first == buyer ? seller : buyer;
        first.lock.lock();
        second.lock.lock();
        try {
            if (buyer.reservedCash < reserved || seller.reservedShares(companyId) < trade.qty()) {
                throw new IllegalStateException("reservation missing for " + trade);
            }
            buyer.reservedCash -= reserved;
            buyer.cash += reserved - paid;
            buyer.receiveShares(companyId, trade.qty());
            seller.reservedShares.merge(companyId, -trade.qty(), Long::sum);
            seller.cash += paid;
        } finally {
            second.lock.unlock();
            first.lock.unlock();
        }
    }

    /** Copied under the trader lock, so cash and reservations are from the same moment. */
    public PortfolioView view(long traderId) {
        Portfolio p = portfolio(traderId);
        p.lock.lock();
        try {
            return new PortfolioView(traderId, p.cash, p.reservedCash, p.shares, p.reservedShares);
        } finally {
            p.lock.unlock();
        }
    }

    /** Available plus reserved cash of all traders. */
    public long totalCash() {
        return sumOverAllTraders(p -> p.cash + p.reservedCash);
    }

    /** Available plus reserved shares of one company over all traders. */
    public long totalShares(String companyId) {
        return sumOverAllTraders(p -> p.shares(companyId) + p.reservedShares(companyId));
    }

    // Holds every trader lock at once (in id order), so the total is exact even while trades run.
    private long sumOverAllTraders(ToLongFunction<Portfolio> amount) {
        List<Portfolio> locked = new ArrayList<>(portfolios.size());
        try {
            for (Portfolio p : portfolios.values()) {
                p.lock.lock();
                locked.add(p);
            }
            long total = 0;
            for (Portfolio p : locked) {
                total += amount.applyAsLong(p);
            }
            return total;
        } finally {
            for (Portfolio p : locked) {
                p.lock.unlock();
            }
        }
    }

    private Portfolio portfolio(long traderId) {
        Portfolio p = portfolios.get(traderId);
        if (p == null) {
            throw new IllegalArgumentException("unknown trader " + traderId);
        }
        return p;
    }

    private static void requireNonNegative(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("negative amount " + amount);
        }
    }

    /** One trader's balances. Every field is read and written only while holding {@code lock}. */
    private static final class Portfolio {

        private final ReentrantLock lock = new ReentrantLock();
        private long cash;
        private long reservedCash;
        private final Map<String, Long> shares;
        private final Map<String, Long> reservedShares = new HashMap<>();

        Portfolio(TraderAccount account) {
            if (account.cash() < 0 || account.shares().values().stream().anyMatch(qty -> qty < 0)) {
                throw new IllegalArgumentException("negative starting balance for trader " + account.id());
            }
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

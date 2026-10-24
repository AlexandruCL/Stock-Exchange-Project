package com.stockexchange.faulty;

import com.stockexchange.engine.Accounts;
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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.ToLongFunction;

/**
 * FAULTY on purpose (deadlock, livelock). {@code engine.AccountService} with three switchable
 * liveness bugs, all in {@link #settle}; everything else is the correct code, so no balance is
 * ever wrong here, threads just stop making progress. See docs/faulty-deadlock-livelock.md.
 *
 * <ul>
 *   <li>{@link LockStrategy#UNORDERED}: bug L1, buyer lock then seller lock (inconsistent order).</li>
 *   <li>{@link LockStrategy#TRY_LOCK_RETRY}: bug L3, tryLock, give back, retry at once (livelock).</li>
 *   <li>{@code onSettled}: bug L2, a callback invoked while the caller holds the company lock.</li>
 * </ul>
 */
public final class FaultyAccounts implements Accounts {

    public enum LockStrategy {
        /** The correct strategy: lower trader id first. */
        ORDERED,
        UNORDERED,
        TRY_LOCK_RETRY
    }

    private final Map<Long, Portfolio> portfolios;
    private final LockStrategy strategy;
    private final long holdFirstLockNanos;
    private final Consumer<Trade> onSettled;
    private final AtomicLong retries = new AtomicLong();
    private final AtomicLong settled = new AtomicLong();

    /**
     * @param holdFirstLockNanos busy work done after taking the first lock, as a real settle would do
     *        (audit record, validation). It widens the window of L1 and L3; it does not create them.
     * @param onSettled called at the end of every settle, still inside the caller's company lock; may be null
     */
    public FaultyAccounts(Collection<TraderAccount> initial, LockStrategy strategy,
                          long holdFirstLockNanos, Consumer<Trade> onSettled) {
        this.strategy = strategy;
        this.holdFirstLockNanos = holdFirstLockNanos;
        this.onSettled = onSettled;
        Map<Long, Portfolio> byId = new TreeMap<>();
        for (TraderAccount account : initial) {
            if (byId.putIfAbsent(account.id(), new Portfolio(account)) != null) {
                throw new IllegalArgumentException("duplicate trader id " + account.id());
            }
        }
        portfolios = Collections.unmodifiableMap(byId);
    }

    /** Number of failed tryLock rounds so far (only {@link LockStrategy#TRY_LOCK_RETRY}). */
    public long retries() {
        return retries.get();
    }

    public long settledTrades() {
        return settled.get();
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

    @Override
    public void settle(Trade trade, long buyerLimitPrice) {
        if (trade.qty() <= 0 || trade.price() <= 0 || buyerLimitPrice < trade.price()) {
            throw new IllegalArgumentException("cannot settle " + trade + " at buyer limit " + buyerLimitPrice);
        }
        Portfolio buyer = portfolio(trade.buyerId());
        Portfolio seller = portfolio(trade.sellerId());
        Portfolio first = firstToLock(trade, buyer, seller);
        Portfolio second = first == buyer ? seller : buyer;
        if (strategy == LockStrategy.TRY_LOCK_RETRY) {
            settleWithTryLock(trade, buyerLimitPrice, buyer, seller, first, second);
            return;
        }
        first.lock.lock();
        try {
            work();
            second.lock.lock();
            try {
                move(trade, buyerLimitPrice, buyer, seller);
            } finally {
                second.lock.unlock();
            }
        } finally {
            first.lock.unlock();
        }
        afterSettle(trade);
    }

    // BUG L1: UNORDERED (and TRY_LOCK_RETRY) lock the buyer first, whoever has the lower id. Thread A settles
    // (buyer 2, seller 1) and holds lock 2 wanting lock 1; thread B settles (buyer 1, seller 2)
    // and holds lock 1 wanting lock 2. Fix: always the lower trader id first (strategy ORDERED).
    private Portfolio firstToLock(Trade trade, Portfolio buyer, Portfolio seller) {
        if (strategy == LockStrategy.ORDERED) {
            return trade.buyerId() < trade.sellerId() ? buyer : seller;
        }
        return buyer;
    }

    // BUG L3: nobody blocks, so there is no deadlock, but after a failed tryLock every thread
    // gives back its first lock and retries at once, in the same order and at the same pace.
    // Symmetric threads keep taking one lock each, failing on the other and releasing: they are
    // busy (CPU, retries counter grows) and make no progress. Fix: global lock order (ORDERED)
    // or random exponential backoff between retries.
    private void settleWithTryLock(Trade trade, long buyerLimitPrice, Portfolio buyer, Portfolio seller,
                                   Portfolio first, Portfolio second) {
        while (true) {
            first.lock.lock();
            try {
                work();
                if (second.lock.tryLock()) {
                    try {
                        move(trade, buyerLimitPrice, buyer, seller);
                    } finally {
                        second.lock.unlock();
                    }
                    break;
                }
            } finally {
                first.lock.unlock();
            }
            retries.incrementAndGet();
        }
        afterSettle(trade);
    }

    private void move(Trade trade, long buyerLimitPrice, Portfolio buyer, Portfolio seller) {
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

    // BUG L2: alien method call while the company lock is held (the Accounts contract says
    // "must not call listeners"). If the listener needs another company's lock, two engines can wait
    // for each other. Fix: collect events under the lock and publish them after unlocking, as
    // MatchingEngine.submit returns events and StockExchange.execute publishes them.
    private void afterSettle(Trade trade) {
        settled.incrementAndGet();
        if (onSettled != null) {
            onSettled.accept(trade);
        }
    }

    private void work() {
        if (holdFirstLockNanos > 0) {
            long end = System.nanoTime() + holdFirstLockNanos;
            while (System.nanoTime() < end) {
                Thread.onSpinWait();
            }
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

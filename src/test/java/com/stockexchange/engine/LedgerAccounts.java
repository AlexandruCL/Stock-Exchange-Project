package com.stockexchange.engine;

import com.stockexchange.model.Trade;
import java.util.HashMap;
import java.util.Map;

/** Minimal thread-safe Accounts for engine tests; stands in for Fabi's AccountService. */
final class LedgerAccounts implements Accounts {

    private final Map<Long, Long> cash = new HashMap<>();
    private final Map<Long, Long> reservedCash = new HashMap<>();
    private final Map<String, Long> shares = new HashMap<>();
    private final Map<String, Long> reservedShares = new HashMap<>();

    synchronized void addTrader(long id, long startCash) {
        cash.put(id, startCash);
        reservedCash.put(id, 0L);
    }

    synchronized void addShares(long id, String company, long qty) {
        shares.put(key(id, company), qty);
        reservedShares.put(key(id, company), 0L);
    }

    synchronized long cash(long id) {
        return cash.get(id);
    }

    synchronized long reservedCash(long id) {
        return reservedCash.get(id);
    }

    synchronized long shares(long id, String company) {
        return shares.get(key(id, company));
    }

    synchronized long reservedShares(long id, String company) {
        return reservedShares.get(key(id, company));
    }

    synchronized long totalCash() {
        long total = 0;
        for (long value : cash.values()) {
            total += value;
        }
        for (long value : reservedCash.values()) {
            total += value;
        }
        return total;
    }

    synchronized long totalShares(String company) {
        long total = 0;
        for (Map.Entry<String, Long> entry : shares.entrySet()) {
            if (entry.getKey().endsWith("|" + company)) {
                total += entry.getValue() + reservedShares.get(entry.getKey());
            }
        }
        return total;
    }

    @Override
    public synchronized boolean reserveCash(long traderId, long amount) {
        if (cash.get(traderId) < amount) {
            return false;
        }
        cash.merge(traderId, -amount, Long::sum);
        reservedCash.merge(traderId, amount, Long::sum);
        return true;
    }

    @Override
    public synchronized boolean reserveShares(long traderId, String companyId, long qty) {
        String key = key(traderId, companyId);
        if (shares.get(key) < qty) {
            return false;
        }
        shares.merge(key, -qty, Long::sum);
        reservedShares.merge(key, qty, Long::sum);
        return true;
    }

    @Override
    public synchronized void releaseCash(long traderId, long amount) {
        cash.merge(traderId, amount, Long::sum);
        reservedCash.merge(traderId, -amount, Long::sum);
    }

    @Override
    public synchronized void releaseShares(long traderId, String companyId, long qty) {
        shares.merge(key(traderId, companyId), qty, Long::sum);
        reservedShares.merge(key(traderId, companyId), -qty, Long::sum);
    }

    @Override
    public synchronized void settle(Trade trade, long buyerLimitPrice) {
        long paid = trade.price() * trade.qty();
        long refund = (buyerLimitPrice - trade.price()) * trade.qty();
        reservedCash.merge(trade.buyerId(), -(paid + refund), Long::sum);
        cash.merge(trade.buyerId(), refund, Long::sum);
        cash.merge(trade.sellerId(), paid, Long::sum);
        reservedShares.merge(key(trade.sellerId(), trade.companyId()), -trade.qty(), Long::sum);
        shares.merge(key(trade.buyerId(), trade.companyId()), trade.qty(), Long::sum);
    }

    private static String key(long trader, String company) {
        return trader + "|" + company;
    }
}

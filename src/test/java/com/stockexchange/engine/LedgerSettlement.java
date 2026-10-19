package com.stockexchange.engine;

import com.stockexchange.model.Order;
import com.stockexchange.model.Reject;
import com.stockexchange.model.Side;
import com.stockexchange.model.Trade;
import java.util.HashMap;
import java.util.Map;

/** Minimal thread-safe Settlement for engine tests; stands in for Fabi's AccountService. */
final class LedgerSettlement implements Settlement {

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
    public synchronized Reject reserve(Order order) {
        return reserveChange(order, order.price(), order.remaining());
    }

    @Override
    public synchronized Reject reserveChange(Order order, long newPrice, long newRemaining) {
        long trader = order.traderId();
        if (!cash.containsKey(trader)) {
            return Reject.UNKNOWN_TRADER;
        }
        if (order.side() == Side.BID) {
            long old = isReserved(order) ? order.price() * order.remaining() : 0;
            long need = newPrice * newRemaining;
            if (cash.get(trader) + old < need) {
                return Reject.INSUFFICIENT_FUNDS;
            }
            cash.merge(trader, old - need, Long::sum);
            reservedCash.merge(trader, need - old, Long::sum);
        } else {
            String key = key(trader, order.companyId());
            long old = isReserved(order) ? order.remaining() : 0;
            if (shares.getOrDefault(key, 0L) + old < newRemaining) {
                return Reject.INSUFFICIENT_SHARES;
            }
            shares.merge(key, old - newRemaining, Long::sum);
            reservedShares.merge(key, newRemaining - old, Long::sum);
        }
        return null;
    }

    @Override
    public synchronized void release(Order order) {
        long trader = order.traderId();
        if (order.side() == Side.BID) {
            long held = order.price() * order.remaining();
            cash.merge(trader, held, Long::sum);
            reservedCash.merge(trader, -held, Long::sum);
        } else {
            String key = key(trader, order.companyId());
            shares.merge(key, order.remaining(), Long::sum);
            reservedShares.merge(key, -order.remaining(), Long::sum);
        }
    }

    @Override
    public synchronized void settle(Trade trade, Order bid, Order ask) {
        long paid = trade.price() * trade.qty();
        long refund = (bid.price() - trade.price()) * trade.qty();
        reservedCash.merge(trade.buyerId(), -(paid + refund), Long::sum);
        cash.merge(trade.buyerId(), refund, Long::sum);
        cash.merge(trade.sellerId(), paid, Long::sum);
        reservedShares.merge(key(trade.sellerId(), trade.companyId()), -trade.qty(), Long::sum);
        shares.merge(key(trade.buyerId(), trade.companyId()), trade.qty(), Long::sum);
    }

    /** New orders are reserved only after reserve() succeeds; a fresh order has no seq yet. */
    private static boolean isReserved(Order order) {
        return order.seq() != 0;
    }

    private static String key(long trader, String company) {
        return trader + "|" + company;
    }
}
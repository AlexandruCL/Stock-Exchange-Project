package com.stockexchange.book;

import com.stockexchange.model.BookSnapshot;
import com.stockexchange.model.Order;
import com.stockexchange.model.OrderView;
import com.stockexchange.model.Side;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Open bids and asks of one company. Not thread-safe: every call happens under the company lock
 * held by {@code MatchingEngine}. Orders must be removed before their price or seq is changed,
 * because the sorted sets key on those fields.
 */
public final class OrderBook {

    private static final Comparator<Order> BID_ORDER = Comparator
            .comparingLong(Order::price).reversed()
            .thenComparingLong(Order::seq);
    private static final Comparator<Order> ASK_ORDER = Comparator
            .comparingLong(Order::price)
            .thenComparingLong(Order::seq);

    private final String companyId;
    private final TreeSet<Order> bids = new TreeSet<>(BID_ORDER);
    private final TreeSet<Order> asks = new TreeSet<>(ASK_ORDER);
    /** Every order ever placed, so a closed order can be told apart from an unknown one. */
    private final Map<Long, Order> byId = new HashMap<>();
    private long lastPrice;
    private long version;

    public OrderBook(String companyId, long initialLastPrice) {
        this.companyId = companyId;
        this.lastPrice = initialLastPrice;
    }

    public String companyId() {
        return companyId;
    }

    /** Registers the order and puts it on its side of the book. */
    public void add(Order order) {
        byId.put(order.id(), order);
        sideOf(order.side()).add(order);
    }

    /** Takes the order off the sorted side but keeps it findable by id. */
    public void remove(Order order) {
        sideOf(order.side()).remove(order);
    }

    /** Puts a previously removed, still open order back on its side. */
    public void reinsert(Order order) {
        sideOf(order.side()).add(order);
    }

    public Order find(long orderId) {
        return byId.get(orderId);
    }

    public Order bestBid() {
        return bids.isEmpty() ? null : bids.first();
    }

    public Order bestAsk() {
        return asks.isEmpty() ? null : asks.first();
    }

    public long lastPrice() {
        return lastPrice;
    }

    public void setLastPrice(long lastPrice) {
        this.lastPrice = lastPrice;
    }

    public long version() {
        return version;
    }

    public void bumpVersion() {
        version++;
    }

    public BookSnapshot snapshot() {
        return new BookSnapshot(companyId, lastPrice, version, views(bids), views(asks));
    }

    private TreeSet<Order> sideOf(Side side) {
        return side == Side.BID ? bids : asks;
    }

    private static List<OrderView> views(TreeSet<Order> side) {
        List<OrderView> views = new ArrayList<>(side.size());
        for (Order order : side) {
            views.add(order.toView());
        }
        return views;
    }
}
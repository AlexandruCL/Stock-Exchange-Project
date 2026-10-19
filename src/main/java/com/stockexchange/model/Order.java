package com.stockexchange.model;

/**
 * Live, mutable order. Engine-internal: not thread-safe, only touched under the company lock.
 * Code outside {@code book} and {@code engine} must use {@link #toView()} and never hold this object.
 */
public final class Order {

    private final long id;
    private final long traderId;
    private final String companyId;
    private final Side side;
    private long price;
    private long qty;
    private long remaining;
    private long version;
    private OrderState state = OrderState.OPEN;
    private long seq;
    private long timestampNanos;

    public Order(long id, long traderId, String companyId, Side side, long price, long qty) {
        this.id = id;
        this.traderId = traderId;
        this.companyId = companyId;
        this.side = side;
        this.price = price;
        this.qty = qty;
        this.remaining = qty;
    }

    public OrderView toView() {
        return new OrderView(id, traderId, companyId, side, price, qty, remaining, version, state, seq, timestampNanos);
    }

    public long id() {
        return id;
    }

    public long traderId() {
        return traderId;
    }

    public String companyId() {
        return companyId;
    }

    public Side side() {
        return side;
    }

    public long price() {
        return price;
    }

    public void setPrice(long price) {
        this.price = price;
    }

    public long qty() {
        return qty;
    }

    public void setQty(long qty) {
        this.qty = qty;
    }

    public long remaining() {
        return remaining;
    }

    public void setRemaining(long remaining) {
        this.remaining = remaining;
    }

    public long version() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }

    public OrderState state() {
        return state;
    }

    public void setState(OrderState state) {
        this.state = state;
    }

    public long seq() {
        return seq;
    }

    public void setSeq(long seq) {
        this.seq = seq;
    }

    public long timestampNanos() {
        return timestampNanos;
    }

    public void setTimestampNanos(long timestampNanos) {
        this.timestampNanos = timestampNanos;
    }
}

package com.stockexchange.engine;

import com.stockexchange.book.OrderBook;
import com.stockexchange.model.BookSnapshot;
import com.stockexchange.model.Company;
import com.stockexchange.model.ExchangeEvent;
import com.stockexchange.model.Order;
import com.stockexchange.model.OrderState;
import com.stockexchange.model.Reject;
import com.stockexchange.model.Side;
import com.stockexchange.model.Trade;
import com.stockexchange.model.Validation;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * Matching engine of one company: its book, fair lock, sequence counter and snapshot. Submit,
 * modify, cancel and matching run under the lock; {@link #snapshot()} and {@link #history()}
 * never lock. Events are returned in {@link MatchResult}, not published here.
 */
public final class MatchingEngine {

    private final String companyId;
    private final Accounts accounts;
    private final LongSupplier globalSeq;
    private final ReentrantLock lock = new ReentrantLock(true);
    private final OrderBook book;
    private final AtomicLong seq = new AtomicLong();
    private final AtomicLong tradeIds = new AtomicLong();
    private final List<Trade> history = new CopyOnWriteArrayList<>();
    private volatile BookSnapshot snapshot;

    /** Uses a private event counter. Fine for tests; the exchange should use the other constructor. */
    public MatchingEngine(Company company, Accounts accounts) {
        this(company, accounts, new AtomicLong()::incrementAndGet);
    }

    /** globalSeq is shared by all engines so event sequence numbers are global. */
    public MatchingEngine(Company company, Accounts accounts, LongSupplier globalSeq) {
        this.companyId = company.id();
        this.accounts = accounts;
        this.globalSeq = globalSeq;
        this.book = new OrderBook(company.id(), company.initialLastPrice());
        this.snapshot = book.snapshot();
    }

    public BookSnapshot snapshot() {
        return snapshot;
    }

    public List<Trade> history() {
        return List.copyOf(history);
    }

    /**
     * New order: reserves cash or shares, rests it in the book and matches. The caller validates
     * the trader, builds the Order with a unique id and must not have reserved anything itself.
     */
    public MatchResult submit(Order incoming) {
        if (!companyId.equals(incoming.companyId())) {
            return MatchResult.rejected(Reject.UNKNOWN_COMPANY);
        }
        if (!Validation.isValidPrice(incoming.price()) || !Validation.isValidQty(incoming.qty())) {
            return MatchResult.rejected(Reject.INVALID_INPUT);
        }
        List<ExchangeEvent> events = new ArrayList<>();
        List<Trade> trades = new ArrayList<>();
        lock.lock();
        try {
            if (book.find(incoming.id()) != null) {
                return MatchResult.rejected(Reject.DUPLICATE_REQUEST);
            }
            Reject reject = reserve(incoming);
            if (reject != null) {
                return MatchResult.rejected(reject);
            }
            incoming.setSeq(seq.incrementAndGet());
            incoming.setTimestampNanos(System.nanoTime());
            book.add(incoming);
            events.add(new ExchangeEvent.OrderCreated(globalSeq.getAsLong(), incoming.toView()));
            matchLoop(trades, events);
            publishBook(events);
            return new MatchResult(true, null, incoming.toView(), trades, events);
        } finally {
            lock.unlock();
        }
    }

    /**
     * newQty is the new total quantity, so it must exceed what is already filled. A changed price
     * or a larger quantity gets a new seq; a pure decrease keeps it. The new reservation is
     * all or nothing: only the difference is reserved or released, so a failure changes nothing.
     */
    public MatchResult modify(long traderId, long orderId, long expectedVersion, long newPrice, long newQty) {
        List<ExchangeEvent> events = new ArrayList<>();
        List<Trade> trades = new ArrayList<>();
        lock.lock();
        try {
            Order order = book.find(orderId);
            Reject reject = checkOpenOwned(order, traderId);
            if (reject != null) {
                return MatchResult.rejected(reject);
            }
            long filled = order.qty() - order.remaining();
            if (!Validation.isValidPrice(newPrice) || !Validation.isValidQty(newQty) || newQty <= filled) {
                return MatchResult.rejected(Reject.INVALID_INPUT);
            }
            if (order.version() != expectedVersion) {
                return MatchResult.rejected(Reject.VERSION_CONFLICT);
            }
            long newRemaining = newQty - filled;
            reject = rereserve(order, newPrice, newRemaining);
            if (reject != null) {
                return MatchResult.rejected(reject);
            }
            boolean keepsPlace = newPrice == order.price() && newRemaining <= order.remaining();
            book.remove(order);
            order.setPrice(newPrice);
            order.setQty(newQty);
            order.setRemaining(newRemaining);
            order.setVersion(order.version() + 1);
            if (!keepsPlace) {
                order.setSeq(seq.incrementAndGet());
            }
            book.reinsert(order);
            events.add(new ExchangeEvent.OrderModified(globalSeq.getAsLong(), order.toView()));
            matchLoop(trades, events);
            publishBook(events);
            return new MatchResult(true, null, order.toView(), trades, events);
        } finally {
            lock.unlock();
        }
    }

    public MatchResult cancel(long traderId, long orderId) {
        List<ExchangeEvent> events = new ArrayList<>();
        lock.lock();
        try {
            Order order = book.find(orderId);
            Reject reject = checkOpenOwned(order, traderId);
            if (reject != null) {
                return MatchResult.rejected(reject);
            }
            book.remove(order);
            release(order);
            order.setState(OrderState.CANCELLED);
            order.setVersion(order.version() + 1);
            events.add(new ExchangeEvent.OrderCancelled(globalSeq.getAsLong(), order.toView()));
            publishBook(events);
            return new MatchResult(true, null, order.toView(), List.of(), events);
        } finally {
            lock.unlock();
        }
    }

    /** Under the lock: trades best bid against best ask until the book no longer crosses. */
    private void matchLoop(List<Trade> trades, List<ExchangeEvent> events) {
        while (true) {
            Order bid = book.bestBid();
            Order ask = book.bestAsk();
            if (bid == null || ask == null || bid.price() < ask.price()) {
                return;
            }
            bid.setState(OrderState.IN_TRADE);
            ask.setState(OrderState.IN_TRADE);
            try {
                long qty = Math.min(bid.remaining(), ask.remaining());
                long price = bid.seq() < ask.seq() ? bid.price() : ask.price();
                bid.setRemaining(bid.remaining() - qty);
                ask.setRemaining(ask.remaining() - qty);
                bid.setVersion(bid.version() + 1);
                ask.setVersion(ask.version() + 1);
                Trade trade = new Trade(tradeIds.incrementAndGet(), globalSeq.getAsLong(), companyId,
                        bid.id(), ask.id(), bid.traderId(), ask.traderId(), price, qty, System.nanoTime());
                accounts.settle(trade, bid.price());
                book.setLastPrice(price);
                history.add(trade);
                trades.add(trade);
                events.add(new ExchangeEvent.TradeExecuted(trade.globalSeq(), trade));
            } finally {
                finishFill(bid);
                finishFill(ask);
            }
        }
    }

    private void finishFill(Order order) {
        if (order.remaining() == 0) {
            book.remove(order);
            order.setState(OrderState.DONE);
        } else {
            order.setState(OrderState.OPEN);
        }
    }

    private void publishBook(List<ExchangeEvent> events) {
        book.bumpVersion();
        snapshot = book.snapshot();
        events.add(new ExchangeEvent.BookUpdated(globalSeq.getAsLong(), companyId, book.version()));
    }

    private Reject reserve(Order order) {
        if (order.side() == Side.BID) {
            return accounts.reserveCash(order.traderId(), order.price() * order.qty())
                    ? null : Reject.INSUFFICIENT_FUNDS;
        }
        return accounts.reserveShares(order.traderId(), companyId, order.qty())
                ? null : Reject.INSUFFICIENT_SHARES;
    }

    private void release(Order order) {
        if (order.side() == Side.BID) {
            accounts.releaseCash(order.traderId(), order.price() * order.remaining());
        } else {
            accounts.releaseShares(order.traderId(), companyId, order.remaining());
        }
    }

    /** Reserves or releases only the difference between old and new, so a reject leaves the old reservation intact. */
    private Reject rereserve(Order order, long newPrice, long newRemaining) {
        if (order.side() == Side.BID) {
            long delta = newPrice * newRemaining - order.price() * order.remaining();
            if (delta > 0 && !accounts.reserveCash(order.traderId(), delta)) {
                return Reject.INSUFFICIENT_FUNDS;
            }
            if (delta < 0) {
                accounts.releaseCash(order.traderId(), -delta);
            }
        } else {
            long delta = newRemaining - order.remaining();
            if (delta > 0 && !accounts.reserveShares(order.traderId(), companyId, delta)) {
                return Reject.INSUFFICIENT_SHARES;
            }
            if (delta < 0) {
                accounts.releaseShares(order.traderId(), companyId, -delta);
            }
        }
        return null;
    }

    /** IN_TRADE is never seen here: the matcher holds the lock for the whole trade. */
    private static Reject checkOpenOwned(Order order, long traderId) {
        if (order == null) {
            return Reject.NOT_FOUND;
        }
        if (order.traderId() != traderId) {
            return Reject.NOT_OWNER;
        }
        if (order.state() == OrderState.DONE || order.state() == OrderState.CANCELLED) {
            return Reject.ORDER_CLOSED;
        }
        if (order.state() == OrderState.IN_TRADE) {
            return Reject.ORDER_IN_TRADE;
        }
        return null;
    }
}

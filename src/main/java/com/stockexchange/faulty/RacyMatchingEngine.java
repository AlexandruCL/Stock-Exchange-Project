package com.stockexchange.faulty;

import com.stockexchange.book.OrderBook;
import com.stockexchange.engine.Accounts;
import com.stockexchange.engine.MatchResult;
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
import java.util.function.LongSupplier;

/**
 * FAULTY on purpose (data races and consistency). {@code engine.MatchingEngine} with the company
 * lock and every thread-safe field removed. Bugs #5 to #8 are marked below; see
 * docs/faulty-data-races.md. Everything else is identical to the correct class.
 */
public final class RacyMatchingEngine {

    private final String companyId;
    private final Accounts accounts;
    private final LongSupplier globalSeq;
    // BUG #5: there is no company lock. submit, modify, cancel and the match loop of one company
    // run in parallel on an OrderBook (TreeSet, HashMap) that is not thread-safe.
    private final OrderBook book;
    // BUG #6: plain counters. "x++" is read, add, write, so two threads draw the same number.
    private long seq;
    private long tradeIds;
    // BUG #7: ArrayList appended from many threads (lost or null entries, AIOOBE) and a snapshot
    // field that is not volatile (a reader may keep seeing an old book).
    private final List<Trade> history = new ArrayList<>();
    private BookSnapshot snapshot;

    /** globalSeq is shared by all engines so event sequence numbers are global. */
    public RacyMatchingEngine(Company company, Accounts accounts, LongSupplier globalSeq) {
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
        return new ArrayList<>(history);
    }

    /**
     * New order: reserves cash or shares, rests it in the book and matches. The caller validates
     * the trader, builds the Order with a unique id and must not have reserved anything itself.
     */
    public MatchResult submit(Order incoming) {
        if (!companyId.equals(incoming.companyId())) {
            return rejected(Reject.UNKNOWN_COMPANY);
        }
        if (!Validation.isValidPrice(incoming.price()) || !Validation.isValidQty(incoming.qty())) {
            return rejected(Reject.INVALID_INPUT);
        }
        List<ExchangeEvent> events = new ArrayList<>();
        List<Trade> trades = new ArrayList<>();
        if (book.find(incoming.id()) != null) {
            return rejected(Reject.DUPLICATE_REQUEST);
        }
        Reject reject = reserve(incoming);
        if (reject != null) {
            return rejected(reject);
        }
        incoming.setSeq(++seq);
        incoming.setTimestampNanos(System.nanoTime());
        book.add(incoming);
        events.add(new ExchangeEvent.OrderCreated(globalSeq.getAsLong(), incoming.toView()));
        matchLoop(trades, events);
        publishBook(events);
        return new MatchResult(true, null, incoming.toView(), trades, events);
    }

    /**
     * newQty is the new total quantity, so it must exceed what is already filled. A changed price
     * or a larger quantity gets a new seq; a pure decrease keeps it. The new reservation is
     * all or nothing: only the difference is reserved or released, so a failure changes nothing.
     */
    public MatchResult modify(long traderId, long orderId, long expectedVersion, long newPrice, long newQty) {
        List<ExchangeEvent> events = new ArrayList<>();
        List<Trade> trades = new ArrayList<>();
        Order order = book.find(orderId);
        Reject reject = checkOpenOwned(order, traderId);
        if (reject != null) {
            return rejected(reject);
        }
        long filled = order.qty() - order.remaining();
        if (!Validation.isValidPrice(newPrice) || !Validation.isValidQty(newQty) || newQty <= filled) {
            return rejected(Reject.INVALID_INPUT);
        }
        if (order.version() != expectedVersion) {
            return rejected(Reject.VERSION_CONFLICT);
        }
        long newRemaining = newQty - filled;
        reject = rereserve(order, newPrice, newRemaining);
        if (reject != null) {
            return rejected(reject);
        }
        boolean keepsPlace = newPrice == order.price() && newRemaining <= order.remaining();
        book.remove(order);
        order.setPrice(newPrice);
        order.setQty(newQty);
        order.setRemaining(newRemaining);
        order.setVersion(order.version() + 1);
        if (!keepsPlace) {
            order.setSeq(++seq);
        }
        book.reinsert(order);
        events.add(new ExchangeEvent.OrderModified(globalSeq.getAsLong(), order.toView()));
        matchLoop(trades, events);
        publishBook(events);
        return new MatchResult(true, null, order.toView(), trades, events);
    }

    public MatchResult cancel(long traderId, long orderId) {
        List<ExchangeEvent> events = new ArrayList<>();
        Order order = book.find(orderId);
        Reject reject = checkOpenOwned(order, traderId);
        if (reject != null) {
            return rejected(reject);
        }
        book.remove(order);
        release(order);
        order.setState(OrderState.CANCELLED);
        order.setVersion(order.version() + 1);
        events.add(new ExchangeEvent.OrderCancelled(globalSeq.getAsLong(), order.toView()));
        publishBook(events);
        return new MatchResult(true, null, order.toView(), List.of(), events);
    }

    /** BUG #5: no lock held here. Trades best bid against best ask until the book no longer crosses. */
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
                Trade trade = new Trade(++tradeIds, globalSeq.getAsLong(), companyId,
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

    private static MatchResult rejected(Reject reason) {
        return new MatchResult(false, reason, null, List.of(), List.of());
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

    /** BUG #8: IN_TRADE is now visible to other threads, and this check-then-act is not atomic with what follows. */
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

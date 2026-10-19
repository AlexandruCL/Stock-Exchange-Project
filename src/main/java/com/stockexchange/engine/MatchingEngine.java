package com.stockexchange.engine;

import com.stockexchange.book.OrderBook;
import com.stockexchange.model.BookSnapshot;
import com.stockexchange.model.Company;
import com.stockexchange.model.ExchangeEvent;
import com.stockexchange.model.Order;
import com.stockexchange.model.OrderResult;
import com.stockexchange.model.OrderState;
import com.stockexchange.model.Reject;
import com.stockexchange.model.Trade;
import com.stockexchange.model.Validation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * Per-company books, locks and matching. Place, modify, cancel and matching run under the
 * company's fair lock; reads use a volatile snapshot or a copy-on-write history and never lock.
 * Events are returned in {@link Outcome} and must be published by the caller after the call
 * returns, i.e. outside the lock.
 */
public final class MatchingEngine {

    /** Result of one operation plus the events to publish once the lock is released. */
    public record Outcome(OrderResult result, List<ExchangeEvent> events) {
        public Outcome {
            events = List.copyOf(events);
        }
    }

    private final Settlement settlement;
    private final LongSupplier globalSeq;
    private final AtomicLong tradeIds = new AtomicLong();
    private final Map<String, CompanyState> companies = new ConcurrentHashMap<>();
    private final Map<Long, String> companyByOrder = new ConcurrentHashMap<>();

    public MatchingEngine(Collection<Company> companies, Settlement settlement, LongSupplier globalSeq) {
        this.settlement = settlement;
        this.globalSeq = globalSeq;
        for (Company company : companies) {
            this.companies.put(company.id(), new CompanyState(company));
        }
    }

    public Set<String> companies() {
        return Set.copyOf(companies.keySet());
    }

    public BookSnapshot book(String companyId) {
        CompanyState state = companies.get(companyId);
        return state == null ? null : state.snapshot;
    }

    public List<Trade> history(String companyId) {
        CompanyState state = companies.get(companyId);
        return state == null ? List.of() : List.copyOf(state.history);
    }

    /** The order's id must be unique; the caller (Exchange) builds the order. */
    public Outcome place(Order order) {
        CompanyState state = companies.get(order.companyId());
        if (state == null) {
            return rejected(Reject.UNKNOWN_COMPANY);
        }
        if (!Validation.isValidPrice(order.price()) || !Validation.isValidQty(order.qty())) {
            return rejected(Reject.INVALID_INPUT);
        }
        List<ExchangeEvent> events = new ArrayList<>();
        List<Trade> trades = new ArrayList<>();
        OrderResult result;
        state.lock.lock();
        try {
            Reject reject = settlement.reserve(order);
            if (reject != null) {
                return rejected(reject);
            }
            order.setSeq(state.seq.incrementAndGet());
            order.setTimestampNanos(System.nanoTime());
            state.book.add(order);
            companyByOrder.put(order.id(), order.companyId());
            events.add(new ExchangeEvent.OrderCreated(globalSeq.getAsLong(), order.toView()));
            match(state, events, trades);
            publishBook(state, events);
            result = new OrderResult(true, null, order.toView(), trades);
        } finally {
            state.lock.unlock();
        }
        return new Outcome(result, events);
    }

    /**
     * newQty is the new total quantity, so it must exceed what is already filled. A price change
     * or a quantity increase moves the order to the back of its price level (new seq); a decrease
     * at the same price keeps its place. A modified order may cross the book and trade at once.
     */
    public Outcome modify(long traderId, long orderId, long expectedVersion, long newPrice, long newQty) {
        CompanyState state = stateOf(orderId);
        if (state == null) {
            return rejected(Reject.NOT_FOUND);
        }
        List<ExchangeEvent> events = new ArrayList<>();
        List<Trade> trades = new ArrayList<>();
        OrderResult result;
        state.lock.lock();
        try {
            Order order = state.book.find(orderId);
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
            reject = settlement.reserveChange(order, newPrice, newRemaining);
            if (reject != null) {
                return rejected(reject);
            }
            boolean keepsPlace = newPrice == order.price() && newRemaining <= order.remaining();
            state.book.remove(order);
            order.setPrice(newPrice);
            order.setQty(newQty);
            order.setRemaining(newRemaining);
            order.setVersion(order.version() + 1);
            if (!keepsPlace) {
                order.setSeq(state.seq.incrementAndGet());
            }
            state.book.reinsert(order);
            events.add(new ExchangeEvent.OrderModified(globalSeq.getAsLong(), order.toView()));
            match(state, events, trades);
            publishBook(state, events);
            result = new OrderResult(true, null, order.toView(), trades);
        } finally {
            state.lock.unlock();
        }
        return new Outcome(result, events);
    }

    public Outcome cancel(long traderId, long orderId) {
        CompanyState state = stateOf(orderId);
        if (state == null) {
            return rejected(Reject.NOT_FOUND);
        }
        List<ExchangeEvent> events = new ArrayList<>();
        OrderResult result;
        state.lock.lock();
        try {
            Order order = state.book.find(orderId);
            Reject reject = checkOpenOwned(order, traderId);
            if (reject != null) {
                return rejected(reject);
            }
            state.book.remove(order);
            settlement.release(order);
            order.setState(OrderState.CANCELLED);
            order.setVersion(order.version() + 1);
            events.add(new ExchangeEvent.OrderCancelled(globalSeq.getAsLong(), order.toView()));
            publishBook(state, events);
            result = new OrderResult(true, null, order.toView(), List.of());
        } finally {
            state.lock.unlock();
        }
        return new Outcome(result, events);
    }

    /** Runs under the company lock. Matches best bid against best ask until the book no longer crosses. */
    private void match(CompanyState state, List<ExchangeEvent> events, List<Trade> trades) {
        OrderBook book = state.book;
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
                Trade trade = new Trade(tradeIds.incrementAndGet(), globalSeq.getAsLong(), state.companyId,
                        bid.id(), ask.id(), bid.traderId(), ask.traderId(), price, qty, System.nanoTime());
                settlement.settle(trade, bid, ask);
                book.setLastPrice(price);
                state.history.add(trade);
                trades.add(trade);
                events.add(new ExchangeEvent.TradeExecuted(trade.globalSeq(), trade));
            } finally {
                finishFill(book, bid);
                finishFill(book, ask);
            }
        }
    }

    private static void finishFill(OrderBook book, Order order) {
        if (order.remaining() == 0) {
            book.remove(order);
            order.setState(OrderState.DONE);
        } else {
            order.setState(OrderState.OPEN);
        }
    }

    private void publishBook(CompanyState state, List<ExchangeEvent> events) {
        state.book.bumpVersion();
        state.snapshot = state.book.snapshot();
        events.add(new ExchangeEvent.BookUpdated(globalSeq.getAsLong(), state.companyId, state.book.version()));
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
        return null;
    }

    private CompanyState stateOf(long orderId) {
        String companyId = companyByOrder.get(orderId);
        return companyId == null ? null : companies.get(companyId);
    }

    private static Outcome rejected(Reject reason) {
        return new Outcome(new OrderResult(false, reason, null, List.of()), List.of());
    }

    private static final class CompanyState {
        final String companyId;
        final ReentrantLock lock = new ReentrantLock(true);
        final OrderBook book;
        final AtomicLong seq = new AtomicLong();
        final List<Trade> history = new CopyOnWriteArrayList<>();
        volatile BookSnapshot snapshot;

        CompanyState(Company company) {
            this.companyId = company.id();
            this.book = new OrderBook(company.id(), company.initialLastPrice());
            this.snapshot = book.snapshot();
        }
    }
}
package com.stockexchange.verify;

import com.stockexchange.engine.Exchange;
import com.stockexchange.engine.MatchResult;
import com.stockexchange.engine.MatchingEngine;
import com.stockexchange.model.BookSnapshot;
import com.stockexchange.model.Company;
import com.stockexchange.model.ExchangeEvent;
import com.stockexchange.model.Order;
import com.stockexchange.model.OrderResult;
import com.stockexchange.model.PortfolioView;
import com.stockexchange.model.Reject;
import com.stockexchange.model.SeedData;
import com.stockexchange.model.Side;
import com.stockexchange.model.Trade;
import com.stockexchange.model.TraderAccount;
import com.stockexchange.model.Validation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Test-only Exchange over John's MatchingEngine, so the cross-cutting tests can run before Fabi's
 * StockExchange is merged. It follows the facade rules from the task division: validate, dedupe the
 * requestId, let the engine reserve, publish events after the engine call on one dispatcher thread in
 * globalSeq order. Swap it for StockExchange in {@link TestExchanges} once that class exists.
 */
public final class ReferenceExchange implements Exchange {

    private static final List<ExchangeEvent> POISON = List.of();

    private final Map<String, MatchingEngine> engines;
    private final ReferenceAccounts accounts;
    private final AtomicLong orderIds = new AtomicLong();
    private final AtomicLong globalSeq = new AtomicLong();
    private final Map<Long, String> orderCompany = new ConcurrentHashMap<>();
    private final Map<String, Request> requests = new ConcurrentHashMap<>();
    private final List<Consumer<ExchangeEvent>> listeners = new CopyOnWriteArrayList<>();
    private final BlockingQueue<List<ExchangeEvent>> outbox = new LinkedBlockingQueue<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Thread dispatcher;

    public ReferenceExchange() {
        this(new SeedData());
    }

    public ReferenceExchange(SeedData seed) {
        this(seed.companies(), seed.traders());
    }

    public ReferenceExchange(Collection<Company> companies, Collection<TraderAccount> traders) {
        accounts = new ReferenceAccounts(traders);
        Map<String, MatchingEngine> map = new LinkedHashMap<>();
        for (Company company : companies) {
            map.put(company.id(), new MatchingEngine(company, accounts, globalSeq::incrementAndGet));
        }
        engines = Map.copyOf(map);
        dispatcher = new Thread(this::dispatchLoop, "exchange-dispatcher");
        dispatcher.start();
    }

    @Override
    public Set<String> companies() {
        return engines.keySet();
    }

    @Override
    public OrderResult place(String requestId, long traderId, String companyId, Side side, long price, long qty) {
        if (!Validation.isValidId(requestId) || !Validation.isValidId(companyId) || side == null
                || !Validation.isValidPrice(price) || !Validation.isValidQty(qty)) {
            return rejected(Reject.INVALID_INPUT);
        }
        MatchingEngine engine = engines.get(companyId);
        if (engine == null) {
            return rejected(Reject.UNKNOWN_COMPANY);
        }
        if (!accounts.exists(traderId)) {
            return rejected(Reject.UNKNOWN_TRADER);
        }
        Request mine = new Request(traderId, companyId, side, price, qty, new CompletableFuture<>());
        Request first = requests.putIfAbsent(requestId, mine);
        if (first != null) {
            return first.sameParams(mine) ? first.result.join() : rejected(Reject.DUPLICATE_REQUEST);
        }
        long orderId = orderIds.incrementAndGet();
        orderCompany.put(orderId, companyId);
        try {
            MatchResult result = engine.submit(new Order(orderId, traderId, companyId, side, price, qty));
            publish(result.events());
            mine.result.complete(result.toOrderResult());
        } catch (RuntimeException e) {
            mine.result.completeExceptionally(e);
            throw e;
        }
        return mine.result.join();
    }

    @Override
    public OrderResult modify(long traderId, long orderId, long expectedVersion, long newPrice, long newQty) {
        MatchingEngine engine = engineOf(orderId);
        if (engine == null) {
            return rejected(Reject.NOT_FOUND);
        }
        MatchResult result = engine.modify(traderId, orderId, expectedVersion, newPrice, newQty);
        publish(result.events());
        return result.toOrderResult();
    }

    @Override
    public OrderResult cancel(long traderId, long orderId) {
        MatchingEngine engine = engineOf(orderId);
        if (engine == null) {
            return rejected(Reject.NOT_FOUND);
        }
        MatchResult result = engine.cancel(traderId, orderId);
        publish(result.events());
        return result.toOrderResult();
    }

    @Override
    public BookSnapshot book(String companyId) {
        MatchingEngine engine = engines.get(companyId);
        return engine == null ? null : engine.snapshot();
    }

    @Override
    public List<Trade> history(String companyId) {
        MatchingEngine engine = engines.get(companyId);
        return engine == null ? List.of() : engine.history();
    }

    @Override
    public PortfolioView portfolio(long traderId) {
        return accounts.view(traderId);
    }

    @Override
    public void subscribe(Consumer<ExchangeEvent> listener) {
        listeners.add(listener);
    }

    @Override
    public void unsubscribe(Consumer<ExchangeEvent> listener) {
        listeners.remove(listener);
    }

    /** Delivers every event already published, then stops and joins the dispatcher. */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        outbox.add(POISON);
        try {
            dispatcher.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private MatchingEngine engineOf(long orderId) {
        String companyId = orderCompany.get(orderId);
        return companyId == null ? null : engines.get(companyId);
    }

    private void publish(List<ExchangeEvent> events) {
        if (!events.isEmpty()) {
            outbox.add(events);
        }
    }

    /**
     * Engines draw globalSeq under their own lock but publish after unlocking, so batches can arrive
     * out of order. Every drawn number is published, so the loop holds events back until the next
     * expected number is present. On close, whatever is left goes out in sequence order.
     */
    private void dispatchLoop() {
        PriorityQueue<ExchangeEvent> pending = new PriorityQueue<>(
                Comparator.comparingLong(ExchangeEvent::globalSeq));
        long next = 1;
        try {
            while (true) {
                List<ExchangeEvent> batch = outbox.take();
                if (batch == POISON) {
                    List<List<ExchangeEvent>> rest = new ArrayList<>();
                    outbox.drainTo(rest);
                    rest.forEach(pending::addAll);
                    while (!pending.isEmpty()) {
                        deliver(pending.poll());
                    }
                    return;
                }
                pending.addAll(batch);
                while (!pending.isEmpty() && pending.peek().globalSeq() == next) {
                    deliver(pending.poll());
                    next++;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void deliver(ExchangeEvent event) {
        for (Consumer<ExchangeEvent> listener : listeners) {
            try {
                listener.accept(event);
            } catch (RuntimeException e) {
                // A broken listener must not stop delivery to the others.
            }
        }
    }

    private static OrderResult rejected(Reject reason) {
        return new OrderResult(false, reason, null, List.of());
    }

    private record Request(long traderId, String companyId, Side side, long price, long qty,
                           CompletableFuture<OrderResult> result) {
        boolean sameParams(Request other) {
            return traderId == other.traderId && companyId.equals(other.companyId) && side == other.side
                    && price == other.price && qty == other.qty;
        }
    }
}

package com.stockexchange.engine;

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
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The exchange behind the {@link Exchange} interface: one {@link MatchingEngine} per company,
 * one {@link AccountService}, one {@link EventBus} and one {@link TransactionLog}. Validates
 * input, routes commands to the right engine and publishes the returned events after the engine
 * has released its lock. Reads ({@link #book}, {@link #history}) never take a company lock.
 * The engines reserve and release cash and shares themselves, so this class never does.
 */
public final class StockExchange implements Exchange {

    private final Map<String, MatchingEngine> engines;
    private final AccountService accounts;
    private final EventBus bus;
    private final TransactionLog log;
    private final ConcurrentMap<Long, String> orderCompany = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, PlaceCall> requests = new ConcurrentHashMap<>();
    private final AtomicLong orderIds = new AtomicLong();
    /** Sequence numbers the current thread's engine call drew, so none is lost if the call throws. */
    private final ThreadLocal<List<Long>> drawnSeqs = ThreadLocal.withInitial(ArrayList::new);

    public StockExchange(SeedData seed) {
        this(seed.companies(), seed.traders());
    }

    public StockExchange(List<Company> companies, Collection<TraderAccount> traders) {
        this(companies, traders, new TransactionLog());
    }

    /** Takes ownership of {@code log}: {@link #close()} closes it. */
    public StockExchange(List<Company> companies, Collection<TraderAccount> traders, TransactionLog log) {
        this.accounts = new AccountService(traders);
        this.log = log;
        Map<String, MatchingEngine> byId = new LinkedHashMap<>();
        for (Company company : companies) {
            if (byId.putIfAbsent(company.id(), new MatchingEngine(company, accounts, this::drawSeq)) != null) {
                throw new IllegalArgumentException("duplicate company id " + company.id());
            }
        }
        this.engines = Collections.unmodifiableMap(byId);
        // Started last, so a constructor that throws above leaves no dispatcher thread behind.
        this.bus = new EventBus();
        bus.subscribe(log::append);
    }

    /** In the order the companies were given, so iteration is the same in every run. */
    @Override
    public Set<String> companies() {
        return engines.keySet();
    }

    /**
     * Checks input, company and trader first; these rejects change nothing and are not remembered.
     * Then the requestId is claimed with putIfAbsent: a repeat with the same parameters gets the
     * first result (waiting for it if the first call is still running), with different parameters
     * DUPLICATE_REQUEST.
     */
    @Override
    public OrderResult place(String requestId, long traderId, String companyId, Side side, long price, long qty) {
        if (!Validation.isValidId(requestId) || !Validation.isValidId(companyId) || side == null
                || !Validation.isValidPrice(price) || !Validation.isValidQty(qty) || overflows(price, qty)) {
            return rejected(Reject.INVALID_INPUT);
        }
        MatchingEngine engine = engines.get(companyId);
        if (engine == null) {
            return rejected(Reject.UNKNOWN_COMPANY);
        }
        if (!accounts.hasTrader(traderId)) {
            return rejected(Reject.UNKNOWN_TRADER);
        }
        PlaceRequest request = new PlaceRequest(traderId, companyId, side, price, qty);
        PlaceCall call = new PlaceCall(request, new CompletableFuture<>());
        PlaceCall first = requests.putIfAbsent(requestId, call);
        if (first != null) {
            return first.request().equals(request) ? awaitFirst(first) : rejected(Reject.DUPLICATE_REQUEST);
        }
        try {
            Order order = new Order(orderIds.incrementAndGet(), traderId, companyId, side, price, qty);
            OrderResult result = execute(() -> engine.submit(order)).toOrderResult();
            if (result.ok()) {
                orderCompany.put(order.id(), companyId);
            }
            call.result().complete(result);
            return result;
        } catch (RuntimeException | Error e) {
            call.result().completeExceptionally(e);
            throw e;
        }
    }

    /** newQty is the new total quantity, including what is already filled. */
    @Override
    public OrderResult modify(long traderId, long orderId, long expectedVersion, long newPrice, long newQty) {
        if (!accounts.hasTrader(traderId)) {
            return rejected(Reject.UNKNOWN_TRADER);
        }
        if (newPrice > 0 && newQty > 0 && overflows(newPrice, newQty)) {
            return rejected(Reject.INVALID_INPUT);
        }
        MatchingEngine engine = engineOf(orderId);
        if (engine == null) {
            return rejected(Reject.NOT_FOUND);
        }
        return execute(() -> engine.modify(traderId, orderId, expectedVersion, newPrice, newQty)).toOrderResult();
    }

    @Override
    public OrderResult cancel(long traderId, long orderId) {
        if (!accounts.hasTrader(traderId)) {
            return rejected(Reject.UNKNOWN_TRADER);
        }
        MatchingEngine engine = engineOf(orderId);
        if (engine == null) {
            return rejected(Reject.NOT_FOUND);
        }
        return execute(() -> engine.cancel(traderId, orderId)).toOrderResult();
    }

    /** The engine's last published snapshot. An unknown company gives an empty book, as in StubExchange. */
    @Override
    public BookSnapshot book(String companyId) {
        MatchingEngine engine = engines.get(companyId);
        return engine == null ? new BookSnapshot(companyId, 0, 0, List.of(), List.of()) : engine.snapshot();
    }

    @Override
    public List<Trade> history(String companyId) {
        MatchingEngine engine = engines.get(companyId);
        return engine == null ? List.of() : engine.history();
    }

    /** An unknown trader gives an empty portfolio, as in StubExchange. */
    @Override
    public PortfolioView portfolio(long traderId) {
        if (!accounts.hasTrader(traderId)) {
            return new PortfolioView(traderId, 0, 0, Map.of(), Map.of());
        }
        return accounts.view(traderId);
    }

    @Override
    public void subscribe(Consumer<ExchangeEvent> listener) {
        bus.subscribe(listener);
    }

    @Override
    public void unsubscribe(Consumer<ExchangeEvent> listener) {
        bus.unsubscribe(listener);
    }

    /** Delivers pending events, joins the dispatcher (with a timeout), then closes the log. Idempotent. */
    @Override
    public void close() {
        bus.close();
        log.close();
    }

    /** For invariant checks: totalCash() and totalShares(companyId). */
    public AccountService accounts() {
        return accounts;
    }

    public TransactionLog transactionLog() {
        return log;
    }

    /**
     * Runs one engine command. The engine has released its lock when the command returns, so the
     * events are published here. Sequence numbers drawn but not returned (only if the engine
     * throws) are skipped, so the dispatcher never waits for an event that will not come.
     */
    private MatchResult execute(Supplier<MatchResult> command) {
        List<Long> drawn = drawnSeqs.get();
        drawn.clear();
        MatchResult result = null;
        try {
            result = command.get();
            return result;
        } finally {
            List<ExchangeEvent> events = result == null ? List.of() : result.events();
            bus.publish(events);
            Set<Long> published = new HashSet<>();
            for (ExchangeEvent event : events) {
                published.add(event.globalSeq());
            }
            for (long seq : drawn) {
                if (!published.contains(seq)) {
                    bus.skip(seq);
                }
            }
            drawn.clear();
        }
    }

    // The first call's own exception, not a CompletionException wrapper, reaches a duplicate caller.
    private static OrderResult awaitFirst(PlaceCall first) {
        try {
            return first.result().join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            if (e.getCause() instanceof Error cause) {
                throw cause;
            }
            throw e;
        }
    }

    private long drawSeq() {
        long seq = bus.nextSeq();
        drawnSeqs.get().add(seq);
        return seq;
    }

    private MatchingEngine engineOf(long orderId) {
        String companyId = orderCompany.get(orderId);
        return companyId == null ? null : engines.get(companyId);
    }

    // price * qty is the cash a bid reserves; it must fit in a long.
    private static boolean overflows(long price, long qty) {
        return Math.multiplyHigh(price, qty) != 0 || price * qty < 0;
    }

    private static OrderResult rejected(Reject reason) {
        return new OrderResult(false, reason, null, List.of());
    }

    private record PlaceRequest(long traderId, String companyId, Side side, long price, long qty) {}

    private record PlaceCall(PlaceRequest request, CompletableFuture<OrderResult> result) {}
}

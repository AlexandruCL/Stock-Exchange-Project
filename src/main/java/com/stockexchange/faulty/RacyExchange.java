package com.stockexchange.faulty;

import com.stockexchange.engine.Exchange;
import com.stockexchange.engine.MatchResult;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * FAULTY on purpose (data races and consistency): {@code engine.StockExchange} built on
 * {@link RacyMatchingEngine} and {@link RacyAccounts}, with plain collections and counters.
 * Bugs #6 and #9 to #11 are here; see docs/faulty-data-races.md. Runs under {@code sim.Main} with
 * {@code --exchange-class com.stockexchange.faulty.RacyExchange}.
 */
public final class RacyExchange implements Exchange {

    private final Map<String, RacyMatchingEngine> engines = new LinkedHashMap<>();
    private final RacyAccounts accounts;
    // BUG #9: HashMaps written by many threads. Entries get lost (modify and cancel then answer
    // NOT_FOUND for an order that exists) and the table can be corrupted while it resizes.
    private final Map<Long, String> orderCompany = new HashMap<>();
    private final Map<String, OrderResult> requests = new HashMap<>();
    // BUG #6: plain counters again, so order ids and event numbers are not unique.
    private long orderIds;
    private long eventSeq;
    // BUG #10: a plain ArrayList of listeners, iterated while another thread may subscribe.
    private final List<Consumer<ExchangeEvent>> listeners = new ArrayList<>();

    public RacyExchange(SeedData seed) {
        this(seed.companies(), seed.traders());
    }

    public RacyExchange(List<Company> companies, Collection<TraderAccount> traders) {
        this.accounts = new RacyAccounts(traders);
        for (Company company : companies) {
            engines.put(company.id(), new RacyMatchingEngine(company, accounts, () -> ++eventSeq));
        }
    }

    @Override
    public Set<String> companies() {
        return engines.keySet();
    }

    // BUG #11 (check-then-act on the idempotency key): two threads with the same requestId both see
    // "not there yet", both place an order, and the caller gets two orders for one request.
    // Fix: requests.putIfAbsent on a ConcurrentHashMap, as StockExchange.place does.
    @Override
    public OrderResult place(String requestId, long traderId, String companyId, Side side, long price, long qty) {
        if (!Validation.isValidId(requestId) || !Validation.isValidId(companyId) || side == null
                || !Validation.isValidPrice(price) || !Validation.isValidQty(qty)) {
            return rejected(Reject.INVALID_INPUT);
        }
        RacyMatchingEngine engine = engines.get(companyId);
        if (engine == null) {
            return rejected(Reject.UNKNOWN_COMPANY);
        }
        if (!accounts.hasTrader(traderId)) {
            return rejected(Reject.UNKNOWN_TRADER);
        }
        OrderResult first = requests.get(requestId);
        if (first != null) {
            return first;
        }
        Order order = new Order(++orderIds, traderId, companyId, side, price, qty);
        OrderResult result = execute(() -> engine.submit(order)).toOrderResult();
        if (result.ok()) {
            orderCompany.put(order.id(), companyId);
        }
        requests.put(requestId, result);
        return result;
    }

    @Override
    public OrderResult modify(long traderId, long orderId, long expectedVersion, long newPrice, long newQty) {
        if (!accounts.hasTrader(traderId)) {
            return rejected(Reject.UNKNOWN_TRADER);
        }
        RacyMatchingEngine engine = engineOf(orderId);
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
        RacyMatchingEngine engine = engineOf(orderId);
        if (engine == null) {
            return rejected(Reject.NOT_FOUND);
        }
        return execute(() -> engine.cancel(traderId, orderId)).toOrderResult();
    }

    @Override
    public BookSnapshot book(String companyId) {
        RacyMatchingEngine engine = engines.get(companyId);
        return engine == null ? new BookSnapshot(companyId, 0, 0, List.of(), List.of()) : engine.snapshot();
    }

    @Override
    public List<Trade> history(String companyId) {
        RacyMatchingEngine engine = engines.get(companyId);
        return engine == null ? List.of() : engine.history();
    }

    @Override
    public PortfolioView portfolio(long traderId) {
        if (!accounts.hasTrader(traderId)) {
            return new PortfolioView(traderId, 0, 0, Map.of(), Map.of());
        }
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

    @Override
    public void close() {
        // No dispatcher thread: events are delivered inline by the calling thread.
    }

    // BUG #10: for-each over a list that subscribe() may change throws ConcurrentModificationException.
    // The listener also runs on the trading thread, in whatever order threads reach this point,
    // so listeners see globalSeq out of order (the correct EventBus re-sorts them).
    private MatchResult execute(Supplier<MatchResult> command) {
        MatchResult result = command.get();
        for (ExchangeEvent event : result.events()) {
            for (Consumer<ExchangeEvent> listener : listeners) {
                listener.accept(event);
            }
        }
        return result;
    }

    private RacyMatchingEngine engineOf(long orderId) {
        String companyId = orderCompany.get(orderId);
        return companyId == null ? null : engines.get(companyId);
    }

    private static OrderResult rejected(Reject reason) {
        return new OrderResult(false, reason, null, List.of());
    }
}

package com.stockexchange.engine;

import com.stockexchange.model.BookSnapshot;
import com.stockexchange.model.Company;
import com.stockexchange.model.ExchangeEvent;
import com.stockexchange.model.OrderResult;
import com.stockexchange.model.OrderState;
import com.stockexchange.model.OrderView;
import com.stockexchange.model.PortfolioView;
import com.stockexchange.model.Reject;
import com.stockexchange.model.SeedData;
import com.stockexchange.model.Side;
import com.stockexchange.model.Trade;
import com.stockexchange.model.TraderAccount;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Canned Exchange for compiling and running code before the real engine exists.
 * Never matches, never reserves, never publishes events; portfolios stay at their seed values.
 */
public final class StubExchange implements Exchange {

    private final Map<String, Company> companies;
    private final Map<Long, TraderAccount> traders;
    private final Map<Long, OrderView> openOrders = new HashMap<>();
    private long nextId = 1;

    public StubExchange() {
        this(new SeedData());
    }

    public StubExchange(SeedData seed) {
        companies = seed.companies().stream().collect(Collectors.toMap(Company::id, c -> c));
        traders = seed.traders().stream().collect(Collectors.toMap(TraderAccount::id, t -> t));
    }

    @Override
    public Set<String> companies() {
        return Set.copyOf(companies.keySet());
    }

    @Override
    public synchronized OrderResult place(String requestId, long traderId, String companyId, Side side,
                                          long price, long qty) {
        if (!companies.containsKey(companyId)) {
            return reject(Reject.UNKNOWN_COMPANY);
        }
        if (!traders.containsKey(traderId)) {
            return reject(Reject.UNKNOWN_TRADER);
        }
        long id = nextId++;
        OrderView order = new OrderView(id, traderId, companyId, side, price, qty, qty, 0,
                OrderState.OPEN, id, System.nanoTime());
        openOrders.put(id, order);
        return accept(order);
    }

    @Override
    public synchronized OrderResult modify(long traderId, long orderId, long expectedVersion,
                                           long newPrice, long newQty) {
        OrderView o = openOrders.get(orderId);
        if (o == null) {
            return reject(Reject.NOT_FOUND);
        }
        if (o.traderId() != traderId) {
            return reject(Reject.NOT_OWNER);
        }
        if (o.version() != expectedVersion) {
            return reject(Reject.VERSION_CONFLICT);
        }
        OrderView modified = new OrderView(orderId, traderId, o.companyId(), o.side(), newPrice, newQty, newQty,
                o.version() + 1, OrderState.OPEN, o.seq(), o.timestampNanos());
        openOrders.put(orderId, modified);
        return accept(modified);
    }

    @Override
    public synchronized OrderResult cancel(long traderId, long orderId) {
        OrderView o = openOrders.get(orderId);
        if (o == null) {
            return reject(Reject.NOT_FOUND);
        }
        if (o.traderId() != traderId) {
            return reject(Reject.NOT_OWNER);
        }
        openOrders.remove(orderId);
        return accept(new OrderView(orderId, traderId, o.companyId(), o.side(), o.price(), o.qty(),
                o.remaining(), o.version() + 1, OrderState.CANCELLED, o.seq(), o.timestampNanos()));
    }

    @Override
    public BookSnapshot book(String companyId) {
        Company company = companies.get(companyId);
        long lastPrice = company == null ? 0 : company.initialLastPrice();
        return new BookSnapshot(companyId, lastPrice, 0, List.of(), List.of());
    }

    @Override
    public List<Trade> history(String companyId) {
        return List.of();
    }

    @Override
    public PortfolioView portfolio(long traderId) {
        TraderAccount trader = traders.get(traderId);
        if (trader == null) {
            return new PortfolioView(traderId, 0, 0, Map.of(), Map.of());
        }
        return new PortfolioView(traderId, trader.cash(), 0, trader.shares(), Map.of());
    }

    @Override
    public void subscribe(Consumer<ExchangeEvent> listener) {
    }

    @Override
    public void unsubscribe(Consumer<ExchangeEvent> listener) {
    }

    @Override
    public void close() {
    }

    private static OrderResult accept(OrderView order) {
        return new OrderResult(true, null, order, List.of());
    }

    private static OrderResult reject(Reject reason) {
        return new OrderResult(false, reason, null, List.of());
    }
}

package com.stockexchange.engine;

import com.stockexchange.model.BookSnapshot;
import com.stockexchange.model.ExchangeEvent;
import com.stockexchange.model.OrderResult;
import com.stockexchange.model.PortfolioView;
import com.stockexchange.model.Side;
import com.stockexchange.model.Trade;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

public interface Exchange extends AutoCloseable {
    Set<String> companies();

    /** Idempotent per requestId: a repeat returns the first result, or DUPLICATE_REQUEST if params differ. */
    OrderResult place(String requestId, long traderId, String companyId, Side side, long price, long qty);

    /** Rejects with VERSION_CONFLICT when expectedVersion is stale. */
    OrderResult modify(long traderId, long orderId, long expectedVersion, long newPrice, long newQty);

    OrderResult cancel(long traderId, long orderId);

    BookSnapshot book(String companyId);           // public, lock-free read

    List<Trade> history(String companyId);          // public, append-only copy

    PortfolioView portfolio(long traderId);

    void subscribe(Consumer<ExchangeEvent> listener); // called outside any lock

    void unsubscribe(Consumer<ExchangeEvent> listener);

    /** Drains pending events, stops the dispatcher thread and joins it with a timeout. */
    @Override
    void close();
}

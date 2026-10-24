package com.stockexchange.faulty;

import com.stockexchange.engine.Accounts;
import com.stockexchange.engine.MatchResult;
import com.stockexchange.engine.MatchingEngine;
import com.stockexchange.model.BookSnapshot;
import com.stockexchange.model.Company;
import com.stockexchange.model.Order;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * FAULTY on purpose (performance, bug P1). The real {@link MatchingEngine}s, but every call,
 * reads included, goes through one global monitor. Nothing is ever wrong, it is just serial:
 * five companies that could trade in parallel wait for each other. Fix: one lock per company
 * (what {@code StockExchange} does), and no lock at all for reads (volatile snapshot).
 */
public final class CoarseLockEngines {

    private final Map<String, MatchingEngine> engines = new HashMap<>();

    public CoarseLockEngines(List<Company> companies, Accounts accounts) {
        for (Company company : companies) {
            engines.put(company.id(), new MatchingEngine(company, accounts));
        }
    }

    public synchronized MatchResult submit(Order order) {
        return engines.get(order.companyId()).submit(order);
    }

    public synchronized BookSnapshot book(String companyId) {
        return engines.get(companyId).snapshot();
    }
}

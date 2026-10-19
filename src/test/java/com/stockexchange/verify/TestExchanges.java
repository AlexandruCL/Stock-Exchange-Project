package com.stockexchange.verify;

import com.stockexchange.engine.Exchange;
import com.stockexchange.model.Company;
import com.stockexchange.model.SeedData;
import com.stockexchange.model.TraderAccount;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The one place the cross-cutting tests get an Exchange from. When Fabi's StockExchange is merged,
 * replace {@code new ReferenceExchange(...)} here and every test runs against the real facade.
 */
public final class TestExchanges {

    private TestExchanges() {
    }

    public static Exchange seeded() {
        return create(new SeedData().companies(), new SeedData().traders());
    }

    public static Exchange create(List<Company> companies, List<TraderAccount> traders) {
        return new ReferenceExchange(companies, traders);
    }

    /** Companies priced at 10.00, and traders 1..count each holding the same cash and the same shares of each. */
    public static Market market(List<String> companyIds, int traders, long cash, long shares) {
        Map<String, Long> holdings = new HashMap<>();
        companyIds.forEach(c -> holdings.put(c, shares));
        List<TraderAccount> accounts = new ArrayList<>();
        for (long id = 1; id <= traders; id++) {
            accounts.add(new TraderAccount(id, cash, holdings));
        }
        List<Company> companies = new ArrayList<>();
        for (String c : companyIds) {
            companies.add(new Company(c, c, 1_000, shares * traders));
        }
        return new Market(List.copyOf(companies), List.copyOf(accounts));
    }

    public static Market market(String companyId, int traders, long cash, long shares) {
        return market(List.of(companyId), traders, cash, shares);
    }

    public record Market(List<Company> companies, List<TraderAccount> traders) {
        public Exchange open() {
            return create(companies, traders);
        }
    }
}

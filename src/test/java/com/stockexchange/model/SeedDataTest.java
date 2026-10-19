package com.stockexchange.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class SeedDataTest {

    private final SeedData seed = new SeedData();

    @Test
    void companies_should_matchConfiguredTickers() {
        List<String> ids = seed.companies().stream().map(Company::id).toList();

        assertEquals(List.of("ACME", "BETA", "CORE", "DELT", "EPSL"), ids);
    }

    @Test
    void traders_should_haveUniqueIdsAndStartingCash() {
        List<TraderAccount> traders = seed.traders();

        assertEquals(20, traders.size());
        assertEquals(20, new HashSet<>(traders.stream().map(TraderAccount::id).toList()).size());
        traders.forEach(t -> assertEquals(100_000_000L, t.cash()));
    }

    @Test
    void totalCash_should_equalReadmeValue() {
        assertEquals(2_000_000_000L, seed.totalCash());
    }

    @Test
    void totalShares_should_equalReadmeValue_and_companyIssuedTotal() {
        for (Company company : seed.companies()) {
            assertEquals(5_000L, seed.totalShares(company.id()), company.id());
            assertEquals(seed.totalShares(company.id()), company.totalShares(), company.id());
        }
    }

    @Test
    void everyTrader_should_holdPositiveSharesInEveryCompany() {
        for (TraderAccount trader : seed.traders()) {
            for (Company company : seed.companies()) {
                assertTrue(trader.shares().getOrDefault(company.id(), 0L) > 0);
            }
        }
    }
}

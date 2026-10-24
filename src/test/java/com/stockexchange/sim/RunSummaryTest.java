package com.stockexchange.sim;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.stockexchange.engine.StockExchange;
import com.stockexchange.model.SeedData;
import com.stockexchange.model.Side;
import org.junit.jupiter.api.Test;

class RunSummaryTest {

    @Test
    void summary_should_pass_afterTradesOnTheRealExchange() {
        SeedData seed = new SeedData();
        try (StockExchange exchange = new StockExchange(seed)) {
            exchange.place("s", 1, "ACME", Side.ASK, 1_000, 5);
            exchange.place("b", 2, "ACME", Side.BID, 1_000, 5);
            RunSummary summary = RunSummary.of(exchange, seed);
            assertTrue(summary.passed(), summary.text());
            assertTrue(summary.text().contains("ACME"));
        }
    }

    @Test
    void summary_should_failWhenAnAccountIsMissing() {
        SeedData seed = new SeedData();
        try (StockExchange exchange = new StockExchange(seed.companies(), seed.traders().subList(0, 19))) {
            RunSummary summary = RunSummary.of(exchange, seed);
            assertFalse(summary.passed());
            assertTrue(summary.text().contains("FAIL"));
        }
    }
}

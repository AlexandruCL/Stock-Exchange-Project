package com.stockexchange.faulty;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.stockexchange.engine.StockExchange;
import com.stockexchange.model.SeedData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Not a regression test: it proves the faulty branch is faulty. The racy exchange must break an
 * invariant within a few rounds, the correct one must not break any in the same workload.
 */
class RacyExchangeTest {

    @Test
    @Timeout(180)
    void racyExchange_should_breakAnInvariantOrCrash_whileCorrectExchangeStaysClean() {
        boolean racyBroke = false;
        for (int round = 1; round <= 5 && !racyBroke; round++) {
            SeedData seed = new SeedData();
            try (StockExchange good = new StockExchange(seed)) {
                RaceHarness.Report report = RaceHarness.run("good", good, seed, 2_000, round);
                assertTrue(report.clean(), () -> "correct exchange broke: " + report);
            }
            racyBroke = !RaceHarness.run("racy", new RacyExchange(seed), seed, 2_000, round).clean();
        }
        assertTrue(racyBroke, "RacyExchange stayed clean for 5 rounds; races are probabilistic, re-run");
    }

    @Test
    void racyAccounts_should_behaveOnASingleThread() {
        SeedData seed = new SeedData();
        RacyExchange exchange = new RacyExchange(seed);
        var sell = exchange.place("s", 1, "ACME", com.stockexchange.model.Side.ASK, 1_000, 5);
        var buy = exchange.place("b", 2, "ACME", com.stockexchange.model.Side.BID, 1_000, 5);
        assertTrue(sell.ok() && buy.ok());
        assertTrue(buy.trades().size() == 1, "one thread: the bugs stay hidden, the trade happens");
        assertFalse(exchange.history("ACME").isEmpty());
    }
}

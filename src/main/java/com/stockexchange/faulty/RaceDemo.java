package com.stockexchange.faulty;

import com.stockexchange.engine.Exchange;
import com.stockexchange.engine.StockExchange;
import com.stockexchange.model.SeedData;

/**
 * Runs the same workload against the correct exchange and the racy one and prints what each
 * run broke. Usage: {@code mvn -q exec:java -Dexec.mainClass=com.stockexchange.faulty.RaceDemo
 * -Dexec.args="[rounds] [opsPerTrader]"}. The correct exchange must print "all invariants hold"
 * every round; the racy one should print violations in nearly every round (races are
 * probabilistic, so a lucky round can be clean).
 */
public final class RaceDemo {

    private RaceDemo() {
    }

    public static void main(String[] args) {
        int rounds = args.length > 0 ? Integer.parseInt(args[0]) : 3;
        int ops = args.length > 1 ? Integer.parseInt(args[1]) : 5_000;
        for (int round = 1; round <= rounds; round++) {
            System.out.println("=== round " + round + " ===");
            SeedData seed = new SeedData();
            try (Exchange good = new StockExchange(seed)) {
                RaceHarness.run("StockExchange (correct)", good, seed, ops, round).print();
            }
            Exchange racy = new RacyExchange(seed);
            RaceHarness.run("RacyExchange (faulty)", racy, seed, ops, round).print();
        }
        System.exit(0);
    }
}

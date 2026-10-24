package com.stockexchange.faulty;

import com.stockexchange.engine.Exchange;
import com.stockexchange.engine.StockExchange;
import com.stockexchange.model.SeedData;

/**
 * Runs the same seeded workload on the correct exchange and on the racy one and prints what each
 * run broke. Usage: {@code java -cp target/classes com.stockexchange.faulty.RaceDemo [rounds] [opsPerTrader]}.
 * All correct rounds run first: a racy run can leave threads spinning forever in a corrupted
 * TreeMap, and those would steal CPU from any run that follows. The correct exchange must print
 * "all invariants hold" every round; the racy one should print violations in nearly every round
 * (races are probabilistic, so a lucky round can be clean).
 */
public final class RaceDemo {

    private RaceDemo() {
    }

    public static void main(String[] args) {
        int rounds = args.length > 0 ? Integer.parseInt(args[0]) : 3;
        int ops = args.length > 1 ? Integer.parseInt(args[1]) : 5_000;
        System.out.println("##### correct exchange #####");
        for (int round = 1; round <= rounds; round++) {
            SeedData seed = new SeedData();
            try (Exchange good = new StockExchange(seed)) {
                System.out.println("--- round " + round + " ---");
                RaceHarness.run("StockExchange (correct)", good, seed, ops, round, 120).print();
            }
        }
        System.out.println("##### racy exchange #####");
        for (int round = 1; round <= rounds; round++) {
            SeedData seed = new SeedData();
            System.out.println("--- round " + round + " ---");
            RaceHarness.run("RacyExchange (faulty)", new RacyExchange(seed), seed, ops, round).print();
        }
        System.exit(0);
    }
}

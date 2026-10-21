package com.stockexchange.sim;

import com.stockexchange.engine.Exchange;
import com.stockexchange.model.SeedData;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws Exception {
        Options options = Options.parse(args);
        if (options.help()) {
            System.out.println("Usage: --seed <long> --seconds <positive integer> "
                    + "--pause-ms <positive integer> --exchange-class <Exchange implementation>");
            return;
        }
        SeedData data = new SeedData();
        Exchange exchange = createExchange(options.exchangeClass(), data);
        try (Simulation simulation = new Simulation(exchange, data, options.seed(), options.pauseMillis())) {
            Thread shutdown = new Thread(simulation::close, "simulation-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdown);
            try {
                System.out.println("Starting simulation: seed=" + options.seed() + ", traders="
                        + data.traders().size() + ", exchange=" + options.exchangeClass());
                simulation.run(Duration.ofSeconds(options.seconds()));
                System.out.println("Simulation completed.");
            } finally {
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdown);
                } catch (IllegalStateException exception) {
                    // JVM shutdown is already running the hook.
                }
            }
        }
    }

    static Exchange createExchange(String className, SeedData data) throws ReflectiveOperationException {
        Class<?> type;
        try {
            type = Class.forName(className);
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException("Exchange implementation is not available: " + className
                    + ". See docs/simulation.md for running against the test stub.", exception);
        }
        if (!Exchange.class.isAssignableFrom(type)) {
            throw new IllegalArgumentException(className + " must implement Exchange");
        }
        try {
            return (Exchange) type.getConstructor(SeedData.class).newInstance(data);
        } catch (NoSuchMethodException exception) {
            return (Exchange) type.getConstructor().newInstance();
        }
    }

    record Options(long seed, long seconds, long pauseMillis, String exchangeClass, boolean help) {
        static Options parse(String[] args) {
            long seed = 42;
            long seconds = 30;
            long pauseMillis = 100;
            String exchangeClass = "com.stockexchange.engine.StockExchange";
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < args.length; i++) {
                String option = args[i];
                if (option.equals("--help")) {
                    return new Options(seed, seconds, pauseMillis, exchangeClass, true);
                }
                if (!Set.of("--seed", "--seconds", "--pause-ms", "--exchange-class").contains(option)) {
                    throw new IllegalArgumentException("Unknown option: " + option);
                }
                if (!seen.add(option) || ++i == args.length) {
                    throw new IllegalArgumentException("Duplicate option or missing value: " + option);
                }
                String value = args[i];
                switch (option) {
                    case "--seed" -> seed = Long.parseLong(value);
                    case "--seconds" -> seconds = Long.parseLong(value);
                    case "--pause-ms" -> pauseMillis = Long.parseLong(value);
                    case "--exchange-class" -> exchangeClass = value;
                    default -> throw new IllegalArgumentException("Unknown option: " + option);
                }
            }
            if (seconds <= 0 || seconds > Long.MAX_VALUE / 1000 || pauseMillis <= 0
                    || exchangeClass.isBlank()) {
                throw new IllegalArgumentException("seconds and pause-ms must be positive; class must be nonblank");
            }
            return new Options(seed, seconds, pauseMillis, exchangeClass, false);
        }
    }
}

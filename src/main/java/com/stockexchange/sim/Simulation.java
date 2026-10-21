package com.stockexchange.sim;

import com.stockexchange.engine.Exchange;
import com.stockexchange.model.SeedData;
import com.stockexchange.model.TraderAccount;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongFunction;

/** Single-use runner; stops and joins every trader before closing the exchange. */
public final class Simulation implements AutoCloseable {
    private final Exchange exchange;
    private final List<Trader> traders;
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private boolean started;
    private boolean closed;

    public Simulation(Exchange exchange, SeedData data, long seed, long pauseMillis) {
        this(exchange, data, seed, pauseMillis, id -> new RandomTradingStrategy(data.companies(),
                RandomTradingStrategy.Profile.values()[(int) ((id - 1) % 3)]));
    }

    public Simulation(Exchange exchange, SeedData data, long seed, long pauseMillis,
                      LongFunction<TradingStrategy> strategies) {
        this.exchange = Objects.requireNonNull(exchange, "exchange");
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(strategies, "strategies");
        List<Trader> created = new ArrayList<>();
        for (TraderAccount account : data.traders()) {
            Trader trader = new Trader(account.id(), exchange, strategies.apply(account.id()), seed, pauseMillis);
            trader.setUncaughtExceptionHandler((thread, error) -> {
                failure.compareAndSet(null, error);
                stopped.countDown();
            });
            created.add(trader);
        }
        traders = List.copyOf(created);
    }

    public void run(Duration duration) throws InterruptedException {
        Objects.requireNonNull(duration, "duration");
        if (duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException("duration must be positive");
        }
        long millis = duration.toMillis();
        if (millis == 0) {
            throw new IllegalArgumentException("duration must be at least one millisecond");
        }
        synchronized (this) {
            if (started || closed) {
                throw new IllegalStateException("Simulation is single-use");
            }
            started = true;
        }
        try {
            synchronized (this) {
                if (!closed) {
                    traders.forEach(Thread::start);
                }
            }
            stopped.await(millis, TimeUnit.MILLISECONDS);
        } finally {
            close();
        }
        if (failure.get() != null) {
            throw new IllegalStateException("Trader failed", failure.get());
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        stopped.countDown();
        traders.forEach(Trader::shutdown);
        boolean interrupted = false;
        try {
            for (Trader trader : traders) {
                while (trader.isAlive()) {
                    try {
                        trader.join();
                    } catch (InterruptedException exception) {
                        interrupted = true;
                    }
                }
            }
            exchange.close();
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}

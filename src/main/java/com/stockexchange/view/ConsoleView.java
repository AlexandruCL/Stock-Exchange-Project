package com.stockexchange.view;

import com.stockexchange.engine.Exchange;
import com.stockexchange.model.BookSnapshot;
import com.stockexchange.model.ExchangeEvent;
import com.stockexchange.model.OrderView;
import com.stockexchange.model.Trade;
import java.io.PrintStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Read-only live view of the public data. The listener only formats a trade line and queues it,
 * because it runs on the exchange's dispatcher thread. All printing happens on the view's own
 * thread: queued trade lines every {@link #TRADE_FLUSH_MILLIS}, and every {@code boardPeriod} the
 * top of each book plus recent trades, read from the lock-free snapshots.
 */
public final class ConsoleView implements AutoCloseable {

    public static final int DEPTH = 5;
    public static final int RECENT_TRADES = 10;
    static final long TRADE_FLUSH_MILLIS = 100;

    private final Exchange exchange;
    private final PrintStream out;
    private final Duration boardPeriod;
    private final long originNanos = System.nanoTime();
    private final ConcurrentLinkedQueue<String> pendingTrades = new ConcurrentLinkedQueue<>();
    private final Consumer<ExchangeEvent> listener = this::onEvent;
    private ScheduledExecutorService printer;

    public ConsoleView(Exchange exchange, PrintStream out, Duration boardPeriod) {
        if (boardPeriod.isZero() || boardPeriod.isNegative()) {
            throw new IllegalArgumentException("boardPeriod must be positive");
        }
        this.exchange = exchange;
        this.out = out;
        this.boardPeriod = boardPeriod;
    }

    public synchronized void start() {
        if (printer != null) {
            throw new IllegalStateException("already started");
        }
        printer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "console-view");
            t.setDaemon(true);
            return t;
        });
        exchange.subscribe(listener);
        printer.scheduleWithFixedDelay(guarded(this::flushTrades), TRADE_FLUSH_MILLIS,
                TRADE_FLUSH_MILLIS, TimeUnit.MILLISECONDS);
        long period = boardPeriod.toMillis();
        printer.scheduleAtFixedRate(guarded(this::printBoard), period, period, TimeUnit.MILLISECONDS);
    }

    /** A periodic task that throws is cancelled silently, so log the error and keep the schedule alive. */
    private Runnable guarded(Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                e.printStackTrace(out);
            }
        };
    }

    /** Unsubscribes, stops the printer thread, then prints any trade lines still queued. */
    @Override
    public synchronized void close() {
        if (printer == null) {
            return;
        }
        exchange.unsubscribe(listener);
        printer.shutdown();
        try {
            if (!printer.awaitTermination(2, TimeUnit.SECONDS)) {
                printer.shutdownNow();
            }
        } catch (InterruptedException e) {
            printer.shutdownNow();
            Thread.currentThread().interrupt();
        }
        printer = null;
        flushTrades();
    }

    void onEvent(ExchangeEvent event) {
        if (event instanceof ExchangeEvent.TradeExecuted executed) {
            pendingTrades.add(formatTrade(executed.trade()));
        }
    }

    void flushTrades() {
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = pendingTrades.poll()) != null) {
            sb.append(line).append(System.lineSeparator());
        }
        if (sb.length() > 0) {
            out.print(sb);
            out.flush();
        }
    }

    /** One block for all companies, printed in a single call so lines from other output do not interleave. */
    void printBoard() {
        flushTrades();
        out.print(board());
        out.flush();
    }

    String board() {
        String nl = System.lineSeparator();
        StringBuilder sb = new StringBuilder();
        sb.append("---------------- board at ").append(elapsed(System.nanoTime()))
                .append(" ----------------").append(nl);
        for (String company : new TreeSet<>(exchange.companies())) {
            BookSnapshot book = exchange.book(company);
            List<Trade> history = exchange.history(company);
            sb.append(String.format("%-5s last %10s  book v%d  trades %d%n",
                    company, money(book.lastPrice()), book.version(), history.size()));
            sb.append(String.format("  %8s %10s | %-10s %8s%n", "bid qty", "bid", "ask", "ask qty"));
            for (int i = 0; i < DEPTH; i++) {
                OrderView bid = i < book.bids().size() ? book.bids().get(i) : null;
                OrderView ask = i < book.asks().size() ? book.asks().get(i) : null;
                if (bid == null && ask == null) {
                    break;
                }
                sb.append(String.format("  %8s %10s | %-10s %8s%n",
                        bid == null ? "" : bid.remaining(), bid == null ? "" : money(bid.price()),
                        ask == null ? "" : money(ask.price()), ask == null ? "" : ask.remaining()));
            }
            List<String> recent = new ArrayList<>();
            for (int i = Math.max(0, history.size() - RECENT_TRADES); i < history.size(); i++) {
                Trade t = history.get(i);
                recent.add(t.qty() + "@" + money(t.price()));
            }
            sb.append("  recent: ").append(recent.isEmpty() ? "-" : String.join(" ", recent)).append(nl);
        }
        return sb.toString();
    }

    String formatTrade(Trade t) {
        return String.format("[%s] TRADE %-5s %6d @ %10s  buyer %3d  seller %3d",
                elapsed(t.timestampNanos()), t.companyId(), t.qty(), money(t.price()), t.buyerId(), t.sellerId());
    }

    /** Time since the view was created, as seconds with milliseconds, from System.nanoTime(). */
    private String elapsed(long nanos) {
        long millis = Math.max(0, nanos - originNanos) / 1_000_000;
        return String.format("%5d.%03ds", millis / 1000, millis % 1000);
    }

    /** Cents to "1234.05" without floating point. */
    static String money(long cents) {
        String sign = cents < 0 ? "-" : "";
        long abs = Math.abs(cents);
        return String.format("%s%d.%02d", sign, abs / 100, abs % 100);
    }
}

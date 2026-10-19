package com.stockexchange.engine;

import com.stockexchange.model.ExchangeEvent;
import com.stockexchange.model.OrderView;
import com.stockexchange.model.Trade;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Append-only record of every event, filled by the {@link EventBus} dispatcher in globalSeq order.
 * Lists are guarded by this object's monitor and copied on read; that monitor is never taken under
 * a company lock. Optionally mirrors each event as one line of {@link #format} to a file.
 */
public final class TransactionLog implements AutoCloseable {

    private final List<ExchangeEvent> events = new ArrayList<>();
    private final Map<String, List<ExchangeEvent>> byCompany = new HashMap<>();
    private final Path file;
    private BufferedWriter writer;

    /** Memory only. */
    public TransactionLog() {
        this.file = null;
    }

    /** Also writes each event to {@code file}, for example {@code logs/exchange-<seed>.log}. Truncates it. */
    public TransactionLog(Path file) {
        this.file = file;
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot open transaction log " + file, e);
        }
    }

    public synchronized void append(ExchangeEvent event) {
        events.add(event);
        byCompany.computeIfAbsent(companyOf(event), id -> new ArrayList<>()).add(event);
        if (writer != null) {
            try {
                writer.write(format(event));
                writer.newLine();
            } catch (IOException e) {
                closeWriter();
                throw new UncheckedIOException("transaction log " + file + " stopped writing", e);
            }
        }
    }

    /** All events so far, in globalSeq order. */
    public synchronized List<ExchangeEvent> events() {
        return List.copyOf(events);
    }

    public synchronized List<ExchangeEvent> events(String companyId) {
        return List.copyOf(byCompany.getOrDefault(companyId, List.of()));
    }

    public synchronized List<Trade> trades(String companyId) {
        List<Trade> trades = new ArrayList<>();
        for (ExchangeEvent event : byCompany.getOrDefault(companyId, List.of())) {
            if (event instanceof ExchangeEvent.TradeExecuted executed) {
                trades.add(executed.trade());
            }
        }
        return List.copyOf(trades);
    }

    /** Flushes and closes the file, if any. The in-memory log stays readable. */
    @Override
    public synchronized void close() {
        if (writer != null) {
            try {
                writer.flush();
            } catch (IOException e) {
                throw new UncheckedIOException("cannot flush transaction log " + file, e);
            } finally {
                closeWriter();
            }
        }
    }

    public static String companyOf(ExchangeEvent event) {
        if (event instanceof ExchangeEvent.OrderCreated e) {
            return e.order().companyId();
        }
        if (event instanceof ExchangeEvent.OrderModified e) {
            return e.order().companyId();
        }
        if (event instanceof ExchangeEvent.OrderCancelled e) {
            return e.order().companyId();
        }
        if (event instanceof ExchangeEvent.TradeExecuted e) {
            return e.trade().companyId();
        }
        return ((ExchangeEvent.BookUpdated) event).companyId();
    }

    /** One line per event. Timestamps are left out, so two runs with the same seed give the same text. */
    public static String format(ExchangeEvent event) {
        if (event instanceof ExchangeEvent.OrderCreated e) {
            return line(e.globalSeq(), "ORDER_CREATED", e.order());
        }
        if (event instanceof ExchangeEvent.OrderModified e) {
            return line(e.globalSeq(), "ORDER_MODIFIED", e.order());
        }
        if (event instanceof ExchangeEvent.OrderCancelled e) {
            return line(e.globalSeq(), "ORDER_CANCELLED", e.order());
        }
        if (event instanceof ExchangeEvent.TradeExecuted e) {
            Trade t = e.trade();
            return "#" + e.globalSeq() + " TRADE_EXECUTED " + t.companyId() + " trade=" + t.id()
                    + " bid=" + t.bidOrderId() + " ask=" + t.askOrderId() + " buyer=" + t.buyerId()
                    + " seller=" + t.sellerId() + " price=" + t.price() + " qty=" + t.qty();
        }
        ExchangeEvent.BookUpdated e = (ExchangeEvent.BookUpdated) event;
        return "#" + e.globalSeq() + " BOOK_UPDATED " + e.companyId() + " version=" + e.bookVersion();
    }

    private static String line(long globalSeq, String type, OrderView o) {
        return "#" + globalSeq + " " + type + " " + o.companyId() + " order=" + o.id() + " trader=" + o.traderId()
                + " side=" + o.side() + " price=" + o.price() + " qty=" + o.qty() + " remaining=" + o.remaining()
                + " version=" + o.version() + " state=" + o.state() + " seq=" + o.seq();
    }

    private void closeWriter() {
        try {
            writer.close();
        } catch (IOException ignored) {
            // Already failing or closing; the in-memory log is still complete.
        } finally {
            writer = null;
        }
    }
}

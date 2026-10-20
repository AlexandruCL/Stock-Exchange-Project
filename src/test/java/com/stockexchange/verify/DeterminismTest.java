package com.stockexchange.verify;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.stockexchange.engine.Exchange;
import com.stockexchange.model.ExchangeEvent;
import com.stockexchange.model.OrderResult;
import com.stockexchange.model.OrderState;
import com.stockexchange.model.OrderView;
import com.stockexchange.model.SeedData;
import com.stockexchange.model.Side;
import com.stockexchange.model.Trade;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Single-threaded seeded scenario run twice: the event logs must match byte for byte. Timestamps come
 * from System.nanoTime() and are left out of the log; everything else, including globalSeq, is compared.
 */
class DeterminismTest {

    private static final int OPERATIONS = 5_000;

    @Test
    @Timeout(30)
    void sameSeed_should_produceIdenticalEventLog() {
        byte[] first = run(42).getBytes(StandardCharsets.UTF_8);
        byte[] second = run(42).getBytes(StandardCharsets.UTF_8);

        assertEquals(new String(first, StandardCharsets.UTF_8), new String(second, StandardCharsets.UTF_8));
        assertArrayEquals(first, second);
        assertTrue(new String(first, StandardCharsets.UTF_8).contains(" TRADE "), "scenario should trade");
    }

    @Test
    @Timeout(30)
    void differentSeed_should_produceDifferentEventLog() {
        assertNotEquals(run(42), run(43));
    }

    static String run(long seed) {
        SeedData data = new SeedData();
        List<String> log = new ArrayList<>();
        try (Exchange ex = TestExchanges.create(data.companies(), data.traders())) {
            ex.subscribe(event -> log.add(format(event)));
            Random rnd = new Random(seed);
            List<OrderView> open = new ArrayList<>();
            for (int i = 0; i < OPERATIONS; i++) {
                OrderResult result;
                if (open.isEmpty() || rnd.nextInt(100) < 70) {
                    int c = rnd.nextInt(SeedData.TICKERS.size());
                    long base = SeedData.INITIAL_PRICES_CENTS.get(c);
                    long price = base + (rnd.nextInt(7) - 3) * base / 100;
                    long trader = 1 + rnd.nextInt(SeedData.TRADER_COUNT);
                    Side side = rnd.nextBoolean() ? Side.BID : Side.ASK;
                    result = ex.place("r" + i, trader, SeedData.TICKERS.get(c), side, price, 1 + rnd.nextInt(30));
                } else {
                    OrderView o = open.remove(rnd.nextInt(open.size()));
                    result = rnd.nextBoolean()
                            ? ex.modify(o.traderId(), o.id(), o.version(), o.price() + rnd.nextInt(3) - 1, o.qty())
                            : ex.cancel(o.traderId(), o.id());
                }
                if (result.ok() && result.order().state() == OrderState.OPEN) {
                    open.add(result.order());
                }
            }
        }
        return String.join("\n", log);
    }

    private static String format(ExchangeEvent event) {
        if (event instanceof ExchangeEvent.OrderCreated e) {
            return e.globalSeq() + " CREATED " + order(e.order());
        }
        if (event instanceof ExchangeEvent.OrderModified e) {
            return e.globalSeq() + " MODIFIED " + order(e.order());
        }
        if (event instanceof ExchangeEvent.OrderCancelled e) {
            return e.globalSeq() + " CANCELLED " + order(e.order());
        }
        if (event instanceof ExchangeEvent.TradeExecuted e) {
            Trade t = e.trade();
            return e.globalSeq() + " TRADE " + t.companyId() + " #" + t.id() + " bid " + t.bidOrderId()
                    + " ask " + t.askOrderId() + " buyer " + t.buyerId() + " seller " + t.sellerId()
                    + " " + t.qty() + "@" + t.price();
        }
        ExchangeEvent.BookUpdated e = (ExchangeEvent.BookUpdated) event;
        return e.globalSeq() + " BOOK " + e.companyId() + " v" + e.bookVersion();
    }

    private static String order(OrderView o) {
        return o.companyId() + " #" + o.id() + " trader " + o.traderId() + " " + o.side() + " " + o.remaining()
                + "/" + o.qty() + "@" + o.price() + " v" + o.version() + " " + o.state() + " seq " + o.seq();
    }
}

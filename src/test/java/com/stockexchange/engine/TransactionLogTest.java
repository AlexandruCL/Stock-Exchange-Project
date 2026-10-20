package com.stockexchange.engine;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.stockexchange.model.ExchangeEvent;
import com.stockexchange.model.OrderState;
import com.stockexchange.model.OrderView;
import com.stockexchange.model.SeedData;
import com.stockexchange.model.Side;
import com.stockexchange.model.Trade;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TransactionLogTest {

    private static OrderView order(long id, String company, long timestampNanos) {
        return new OrderView(id, 3, company, Side.BID, 1000, 10, 10, 0, OrderState.OPEN, id, timestampNanos);
    }

    private static Trade trade(long seq, String company, long timestampNanos) {
        return new Trade(1, seq, company, 5, 4, 2, 1, 1000, 10, timestampNanos);
    }

    @Test
    void append_should_keepGlobalOrder_and_groupByCompany() {
        TransactionLog log = new TransactionLog();
        ExchangeEvent created = new ExchangeEvent.OrderCreated(1, order(1, "ACME", 0));
        ExchangeEvent other = new ExchangeEvent.BookUpdated(2, "BETA", 1);
        ExchangeEvent traded = new ExchangeEvent.TradeExecuted(3, trade(3, "ACME", 0));

        log.append(created);
        log.append(other);
        log.append(traded);

        assertEquals(List.of(created, other, traded), log.events());
        assertEquals(List.of(created, traded), log.events("ACME"));
        assertEquals(List.of(trade(3, "ACME", 0)), log.trades("ACME"));
        assertEquals(List.of(), log.events("NOPE"));
    }

    @Test
    void reads_should_returnImmutableCopies() {
        TransactionLog log = new TransactionLog();
        log.append(new ExchangeEvent.BookUpdated(1, "ACME", 1));
        List<ExchangeEvent> before = log.events();

        log.append(new ExchangeEvent.BookUpdated(2, "ACME", 2));

        assertEquals(1, before.size());
        assertThrows(UnsupportedOperationException.class, () -> before.add(before.get(0)));
    }

    @Test
    void format_should_leaveOutTimestamps() {
        ExchangeEvent early = new ExchangeEvent.TradeExecuted(7, trade(7, "ACME", 111));
        ExchangeEvent late = new ExchangeEvent.TradeExecuted(7, trade(7, "ACME", 999));

        assertEquals(TransactionLog.format(early), TransactionLog.format(late));
        assertEquals("#7 TRADE_EXECUTED ACME trade=1 bid=5 ask=4 buyer=2 seller=1 price=1000 qty=10",
                TransactionLog.format(early));
        assertEquals("#2 ORDER_CANCELLED ACME order=1 trader=3 side=BID price=1000 qty=10 remaining=10"
                        + " version=0 state=OPEN seq=1",
                TransactionLog.format(new ExchangeEvent.OrderCancelled(2, order(1, "ACME", 5))));
        assertEquals("#4 BOOK_UPDATED BETA version=9",
                TransactionLog.format(new ExchangeEvent.BookUpdated(4, "BETA", 9)));
    }

    @Test
    void fileLog_should_containOneFormattedLinePerEvent(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("logs").resolve("exchange-42.log");
        TransactionLog log = new TransactionLog(file);
        log.append(new ExchangeEvent.OrderCreated(1, order(1, "ACME", 0)));
        log.append(new ExchangeEvent.BookUpdated(2, "ACME", 1));

        log.close();

        assertEquals(log.events().stream().map(TransactionLog::format).toList(), Files.readAllLines(file));
    }

    @Test
    void sameScenarioTwice_should_writeIdenticalFiles(@TempDir Path dir) throws Exception {
        Path first = dir.resolve("first.log");
        Path second = dir.resolve("second.log");

        runScenario(first);
        runScenario(second);

        assertArrayEquals(Files.readAllBytes(first), Files.readAllBytes(second));
        assertEquals(14, Files.readAllLines(first).size());
    }

    private static void runScenario(Path file) {
        SeedData seed = new SeedData();
        try (StockExchange exchange = new StockExchange(seed.companies(), seed.traders(), new TransactionLog(file))) {
            exchange.place("a", 1, "ACME", Side.ASK, 1000, 100);
            exchange.place("b", 2, "ACME", Side.BID, 1000, 60);
            exchange.place("c", 3, "ACME", Side.BID, 1010, 60);
            long id = exchange.place("d", 4, "BETA", Side.BID, 2400, 10).order().id();
            exchange.modify(4, id, 0, 2450, 10);
            exchange.cancel(4, id);
        }
    }
}

package com.stockexchange.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.stockexchange.engine.Exchange;
import com.stockexchange.model.Side;
import com.stockexchange.verify.TestExchanges;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class ConsoleViewTest {

    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);

    private String output() {
        return bytes.toString(StandardCharsets.UTF_8);
    }

    @Test
    void money_should_formatCentsWithoutFloatingPoint() {
        assertEquals("0.00", ConsoleView.money(0));
        assertEquals("0.05", ConsoleView.money(5));
        assertEquals("10.00", ConsoleView.money(1_000));
        assertEquals("1234.56", ConsoleView.money(123_456));
        assertEquals("-1.01", ConsoleView.money(-101));
    }

    @Test
    @Timeout(10)
    void trade_should_printOneLineWithCompanyQtyPriceBuyerSeller() {
        try (Exchange ex = TestExchanges.seeded()) {
            ConsoleView view = new ConsoleView(ex, out, Duration.ofHours(1));
            view.start();
            ex.place("a", 1, "ACME", Side.ASK, 1_000, 100);
            ex.place("b", 2, "ACME", Side.BID, 1_200, 100);
            ex.close();
            view.close();
        }
        String[] lines = output().strip().split("\\R");
        assertEquals(1, lines.length, output());
        String line = lines[0];
        assertTrue(line.matches("\\[\\s*\\d+\\.\\d{3}s] TRADE ACME\\s+100 @\\s+10\\.00\\s+buyer\\s+2\\s+seller\\s+1"), line);
    }

    @Test
    @Timeout(10)
    void board_should_showTopFiveEachSideLastPriceAndLastTenTrades() {
        try (Exchange ex = TestExchanges.seeded(); ConsoleView view = new ConsoleView(ex, out, Duration.ofSeconds(1))) {
            for (int i = 0; i < 12; i++) {
                ex.place("s" + i, 1, "ACME", Side.ASK, 1_000, 1);
                ex.place("b" + i, 2, "ACME", Side.BID, 1_000, 1);
            }
            for (int i = 0; i < 7; i++) {
                ex.place("bid" + i, 3, "ACME", Side.BID, 900 - i, 1);
                ex.place("ask" + i, 4, "ACME", Side.ASK, 1_100 + i, 1);
            }
            String board = view.board();

            String acme = board.substring(board.indexOf("ACME"), board.indexOf("BETA"));
            assertTrue(acme.contains("last      10.00"), acme);
            assertTrue(acme.contains("trades 12"), acme);
            assertTrue(acme.contains("9.00 | 11.00"), acme);
            assertTrue(acme.contains("8.96 | 11.04"), acme);
            assertFalse(acme.contains("8.95"), "only " + ConsoleView.DEPTH + " levels: " + acme);
            String recent = acme.substring(acme.indexOf("recent: ") + 8).strip();
            assertEquals(ConsoleView.RECENT_TRADES, recent.split(" ").length, recent);
            for (String company : ex.companies()) {
                assertTrue(board.contains(company + " "), company);
            }
        }
    }

    @Test
    @Timeout(10)
    void board_should_printPeriodicallyOnItsOwnThread() throws Exception {
        try (Exchange ex = TestExchanges.seeded()) {
            ConsoleView view = new ConsoleView(ex, out, Duration.ofMillis(50));
            view.start();
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (output().split("board at", -1).length < 3 && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            view.close();
            assertTrue(output().split("board at", -1).length >= 3, output());
        }
    }

    @Test
    void start_should_rejectSecondStartAndCloseShouldBeIdempotent() {
        try (Exchange ex = TestExchanges.seeded()) {
            ConsoleView view = new ConsoleView(ex, out, Duration.ofSeconds(1));
            view.start();
            assertThrows(IllegalStateException.class, view::start);
            view.close();
            view.close();
        }
    }
}

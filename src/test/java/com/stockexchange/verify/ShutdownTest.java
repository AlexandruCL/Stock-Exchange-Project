package com.stockexchange.verify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.stockexchange.engine.Exchange;
import com.stockexchange.model.Side;
import com.stockexchange.view.ConsoleView;
import java.io.OutputStream;
import java.io.PrintStream;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Starts and stops the exchange and the console view 20 times and checks that no thread is left behind.
 * The simulation half of this test (trader-N threads) is added when Capa's Simulation is merged.
 */
class ShutdownTest {

    private static final List<String> OWNED_THREADS = List.of("exchange-dispatcher", "console-view");

    @Test
    @Timeout(60)
    void startAndStopTwentyTimes_should_leaveNoThreadsBehind() throws Exception {
        int before = Thread.activeCount();
        PrintStream sink = new PrintStream(OutputStream.nullOutputStream());
        for (int run = 0; run < 20; run++) {
            Exchange ex = TestExchanges.seeded();
            ConsoleView view = new ConsoleView(ex, sink, Duration.ofMillis(20));
            view.start();
            Thread[] traders = new Thread[4];
            for (int t = 0; t < traders.length; t++) {
                long trader = t + 1;
                String prefix = run + "-" + t + "-";
                traders[t] = new Thread(() -> {
                    for (int i = 0; i < 200; i++) {
                        Side side = i % 2 == 0 ? Side.BID : Side.ASK;
                        ex.place(prefix + i, trader, "ACME", side, 990 + i % 20, 1);
                    }
                });
                traders[t].start();
            }
            for (Thread trader : traders) {
                trader.join(10_000);
            }
            view.close();
            ex.close();
        }

        List<String> leftovers = awaitNoOwnedThreads(Duration.ofSeconds(5));
        assertTrue(leftovers.isEmpty(), "threads still alive: " + leftovers);
        assertTrue(Thread.activeCount() <= before + 1,
                "live threads grew from " + before + " to " + Thread.activeCount());
    }

    @Test
    @Timeout(10)
    void closeTwice_should_beHarmless() throws Exception {
        Exchange ex = TestExchanges.seeded();
        ex.close();
        ex.close();
        assertEquals(List.of(), awaitNoOwnedThreads(Duration.ofSeconds(2)));
    }

    private static List<String> awaitNoOwnedThreads(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        List<String> alive;
        do {
            alive = Thread.getAllStackTraces().keySet().stream()
                    .filter(Thread::isAlive)
                    .map(Thread::getName)
                    .filter(OWNED_THREADS::contains)
                    .collect(Collectors.toList());
            if (alive.isEmpty()) {
                return alive;
            }
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        return alive;
    }
}

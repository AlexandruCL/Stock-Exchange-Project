package com.stockexchange.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.stockexchange.engine.StubExchange;
import com.stockexchange.model.SeedData;
import org.junit.jupiter.api.Test;

class MainTest {
    @Test
    void parsesDefaultsAndOverrides() {
        assertEquals(42, Main.Options.parse(new String[0]).seed());
        Main.Options options = Main.Options.parse(new String[] {
                "--seed", "-7", "--seconds", "2", "--pause-ms", "10",
                "--exchange-class", StubExchange.class.getName()});
        assertEquals(-7, options.seed());
        assertEquals(2, options.seconds());
        assertEquals(10, options.pauseMillis());
        assertTrue(Main.Options.parse(new String[] {"--help"}).help());
    }

    @Test
    void rejectsMalformedOptions() {
        for (String[] args : new String[][] {
                {"--unknown", "1"}, {"--seed"}, {"--seconds", "0"},
                {"--pause-ms", "-1"}, {"--seed", "abc"},
                {"--seed", "1", "--seed", "2"}, {"--seconds", Long.toString(Long.MAX_VALUE)}}) {
            assertThrows(IllegalArgumentException.class, () -> Main.Options.parse(args));
        }
    }

    @Test
    void loadsExchangeAndExplainsMissingImplementation() throws ReflectiveOperationException {
        try (var exchange = Main.createExchange(StubExchange.class.getName(), new SeedData())) {
            assertEquals(5, exchange.companies().size());
        }
        assertThrows(IllegalArgumentException.class,
                () -> Main.createExchange(String.class.getName(), new SeedData()));
        assertThrows(IllegalStateException.class,
                () -> Main.createExchange("missing.Exchange", new SeedData()));
    }

    @Test
    void entryPointRunsAgainstStub() throws Exception {
        Main.main(new String[] {"--seed", "42", "--seconds", "1", "--pause-ms", "5",
                "--exchange-class", StubExchange.class.getName()});
    }
}

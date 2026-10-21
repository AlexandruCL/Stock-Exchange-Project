package com.stockexchange.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.stockexchange.engine.Exchange;
import com.stockexchange.engine.StubExchange;
import com.stockexchange.model.OrderResult;
import com.stockexchange.model.Reject;
import com.stockexchange.model.SeedData;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class RandomTradingStrategyTest {
    @Test
    void strategiesPlaceModifyAndCancelValidOrders() {
        for (RandomTradingStrategy.Profile profile : RandomTradingStrategy.Profile.values()) {
            List<String> calls = run(profile, false);
            assertEquals(30, calls.size());
            for (int i = 0; i < calls.size(); i += 3) {
                assertTrue(calls.get(i).startsWith("place"));
                assertTrue(calls.get(i + 1).startsWith("modify"));
                assertTrue(calls.get(i + 2).startsWith("cancel"));
            }
        }
    }

    @Test
    void seedIsRepeatableRegardlessOfCompanyInputOrder() {
        assertEquals(run(RandomTradingStrategy.Profile.BALANCED, false),
                run(RandomTradingStrategy.Profile.BALANCED, true));
    }

    @Test
    void rejectedPlacementsDoNotChangeFutureGeneratedOrders() {
        List<String> accepted = run(RandomTradingStrategy.Profile.BALANCED, false).stream()
                .filter(call -> call.startsWith("place")).toList();
        List<String> rejected = new ArrayList<>();
        Exchange exchange = (Exchange) Proxy.newProxyInstance(Exchange.class.getClassLoader(),
                new Class<?>[] {Exchange.class}, (proxy, method, args) -> {
                    assertEquals("place", method.getName());
                    rejected.add(method.getName() + Arrays.toString(args));
                    return new OrderResult(false, Reject.INSUFFICIENT_FUNDS, null, List.of());
                });
        RandomTradingStrategy strategy = new RandomTradingStrategy(new SeedData().companies(),
                RandomTradingStrategy.Profile.BALANCED);
        SplittableRandom random = new SplittableRandom(43);
        for (int sequence = 1; sequence <= 30; sequence++) {
            strategy.act(exchange, 1, sequence, random);
        }
        assertEquals(accepted, rejected);
    }

    private List<String> run(RandomTradingStrategy.Profile profile, boolean reversed) {
        SeedData data = new SeedData();
        var companies = new ArrayList<>(data.companies());
        if (reversed) {
            Collections.reverse(companies);
        }
        RandomTradingStrategy strategy = new RandomTradingStrategy(companies, profile);
        StubExchange stub = new StubExchange(data);
        List<String> calls = new ArrayList<>();
        Exchange exchange = (Exchange) Proxy.newProxyInstance(Exchange.class.getClassLoader(),
                new Class<?>[] {Exchange.class}, (proxy, method, args) -> {
                    calls.add(method.getName() + Arrays.toString(args));
                    OrderResult result = (OrderResult) method.invoke(stub, args);
                    assertTrue(result.ok());
                    assertTrue(result.order().price() > 0);
                    assertTrue(result.order().qty() > 0 && result.order().qty() <= 10);
                    return result;
                });
        SplittableRandom random = new SplittableRandom(43);
        for (int sequence = 1; sequence <= 30; sequence++) {
            strategy.act(exchange, 1, sequence, random);
        }
        return calls;
    }
}

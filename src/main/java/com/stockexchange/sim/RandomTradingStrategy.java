package com.stockexchange.sim;

import com.stockexchange.engine.Exchange;
import com.stockexchange.model.Company;
import com.stockexchange.model.OrderResult;
import com.stockexchange.model.OrderState;
import com.stockexchange.model.OrderView;
import com.stockexchange.model.Reject;
import com.stockexchange.model.Side;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.random.RandomGenerator;

/** One instance per trader. Prices use fixed references so scheduling cannot change generated orders. */
public final class RandomTradingStrategy implements TradingStrategy {
    public enum Profile {
        BALANCED(50), BUYER(75), SELLER(25);

        private final int buyPercent;

        Profile(int buyPercent) {
            this.buyPercent = buyPercent;
        }
    }

    private final List<Company> companies;
    private final Profile profile;
    private OrderView latest;

    public RandomTradingStrategy(List<Company> companies, Profile profile) {
        this.companies = companies.stream().sorted(Comparator.comparing(Company::id)).toList();
        this.profile = Objects.requireNonNull(profile, "profile");
        if (this.companies.isEmpty() || this.companies.stream()
                .anyMatch(c -> c.initialLastPrice() <= 0 || c.initialLastPrice() > Long.MAX_VALUE / 100)) {
            throw new IllegalArgumentException("Companies need positive, bounded reference prices");
        }
    }

    @Override
    public void act(Exchange exchange, long traderId, long sequence, RandomGenerator random) {
        Company company = companies.get(random.nextInt(companies.size()));
        Side side = random.nextInt(100) < profile.buyPercent ? Side.BID : Side.ASK;
        long percent = random.nextLong(95, 106);
        long price = Math.max(1, company.initialLastPrice() / 100 * percent
                + company.initialLastPrice() % 100 * percent / 100);
        long qty = random.nextLong(1, 11);
        OrderResult result;
        // Fixed action slots keep random consumption independent of engine responses.
        if (sequence % 3 == 2) {
            if (latest == null) {
                return;
            }
            Company reference = companies.stream().filter(c -> c.id().equals(latest.companyId()))
                    .findFirst().orElseThrow();
            long modifiedPrice = Math.max(1, reference.initialLastPrice() / 100 * percent
                    + reference.initialLastPrice() % 100 * percent / 100);
            result = exchange.modify(traderId, latest.id(), latest.version(), modifiedPrice, qty);
        } else if (sequence % 3 == 0) {
            if (latest == null) {
                return;
            }
            result = exchange.cancel(traderId, latest.id());
        } else {
            result = exchange.place("trader-" + traderId + "-" + sequence,
                    traderId, company.id(), side, price, qty);
        }
        if (result.ok()) {
            latest = result.order() != null && result.order().state() == OrderState.OPEN
                    ? result.order() : null;
        } else if (sequence % 3 == 1 || result.reason() == Reject.NOT_FOUND
                || result.reason() == Reject.ORDER_CLOSED) {
            latest = null;
        }
    }
}

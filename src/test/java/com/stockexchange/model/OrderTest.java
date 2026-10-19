package com.stockexchange.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class OrderTest {

    @Test
    void newOrder_should_startOpenWithFullRemaining() {
        Order order = new Order(7, 3, "ACME", Side.ASK, 1000, 50);

        assertEquals(OrderState.OPEN, order.state());
        assertEquals(50, order.remaining());
        assertEquals(0, order.version());
    }

    @Test
    void toView_should_copyAllFields() {
        Order order = new Order(7, 3, "ACME", Side.ASK, 1000, 50);
        order.setSeq(12);
        order.setTimestampNanos(99);

        OrderView view = order.toView();

        assertEquals(new OrderView(7, 3, "ACME", Side.ASK, 1000, 50, 50, 0, OrderState.OPEN, 12, 99), view);
    }

    @Test
    void toView_should_notChange_when_orderMutatedAfterwards() {
        Order order = new Order(7, 3, "ACME", Side.BID, 1000, 50);
        OrderView before = order.toView();

        order.setRemaining(20);
        order.setVersion(1);
        order.setState(OrderState.DONE);

        assertEquals(50, before.remaining());
        assertEquals(0, before.version());
        assertEquals(OrderState.OPEN, before.state());
    }
}

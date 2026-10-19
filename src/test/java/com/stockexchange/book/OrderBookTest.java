package com.stockexchange.book;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.stockexchange.model.BookSnapshot;
import com.stockexchange.model.Order;
import com.stockexchange.model.Side;
import org.junit.jupiter.api.Test;

class OrderBookTest {

    private static Order order(long id, Side side, long price, long seq) {
        Order order = new Order(id, 1, "ACME", side, price, 10);
        order.setSeq(seq);
        return order;
    }

    @Test
    void bestBid_should_beHighestPriceThenLowestSeq() {
        OrderBook book = new OrderBook("ACME", 100);
        book.add(order(1, Side.BID, 100, 1));
        book.add(order(2, Side.BID, 105, 2));
        book.add(order(3, Side.BID, 105, 3));

        assertEquals(2, book.bestBid().id());
    }

    @Test
    void bestAsk_should_beLowestPriceThenLowestSeq() {
        OrderBook book = new OrderBook("ACME", 100);
        book.add(order(1, Side.ASK, 100, 3));
        book.add(order(2, Side.ASK, 99, 2));
        book.add(order(3, Side.ASK, 99, 1));

        assertEquals(3, book.bestAsk().id());
    }

    @Test
    void remove_should_dropFromSideButStayFindable() {
        OrderBook book = new OrderBook("ACME", 100);
        Order bid = order(1, Side.BID, 100, 1);
        book.add(bid);

        book.remove(bid);

        assertNull(book.bestBid());
        assertNotNull(book.find(1));
    }

    @Test
    void snapshot_should_listSidesInPriorityOrderAndCarryVersion() {
        OrderBook book = new OrderBook("ACME", 100);
        book.add(order(1, Side.BID, 90, 1));
        book.add(order(2, Side.BID, 95, 2));
        book.setLastPrice(97);
        book.bumpVersion();

        BookSnapshot snapshot = book.snapshot();

        assertEquals(2, snapshot.bids().get(0).id());
        assertEquals(97, snapshot.lastPrice());
        assertEquals(1, snapshot.version());
    }
}
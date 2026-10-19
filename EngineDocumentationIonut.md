# Engine documentation (John: `OrderBook`, `MatchingEngine`)

Branch: `engine`. Scope: only `book.OrderBook` and `engine.MatchingEngine`, plus one support interface (`engine.Settlement`) that the README's locking rules force.

## Files

| File | Purpose |
|---|---|
| `book/OrderBook.java` | Sorted bids/asks of one company, id index, last price, version, snapshot builder. Not thread-safe. |
| `engine/MatchingEngine.java` | Per-company lock, sequence, book, snapshot, history. `place`, `modify`, `cancel`, `book`, `history`, `companies`. |
| `engine/Settlement.java` | New interface: what the engine needs from the account side (reserve, re-reserve, release, settle). |
| `test/.../book/OrderBookTest.java`, `test/.../engine/MatchingEngineTest.java`, `test/.../engine/LedgerSettlement.java` | Tests and an in-memory `Settlement` used only by tests. |

No existing file was edited.

## Design choices

**Who owns the lock.** README: matching, modify and cancel share one fair `ReentrantLock` per company, and portfolio updates happen inside it. `MatchingEngine` therefore owns the lock, the `OrderBook`, the per-company `AtomicLong` seq and the snapshot. `StockExchange` (Fabi) should delegate to it and not lock itself.

**Accounts via `Settlement`.** `AccountService` is Fabi's and does not exist yet, but reserve and settle must run under the company lock. I defined `Settlement` (`reserve`, `reserveChange`, `release`, `settle`); Fabi's `AccountService` implements it, taking trader locks lower id first. The engine never touches cash or shares. Reserve is check-and-reserve in one call, as the README requires.

**Book data structure.** Two `TreeSet<Order>` with comparators: bids price desc then seq asc, asks price asc then seq asc. Best order is `first()`, O(log n). Orders are removed before price or seq changes (the sets key on them) and reinserted after. A `HashMap<Long, Order>` keeps every order ever placed, so a finished order returns `ORDER_CLOSED` and an unknown one `NOT_FOUND`. Memory grows with order count; acceptable for a bounded simulation.

**Matching.** Loop while best bid price >= best ask price. Quantity is min of remainings. Price is the older order's price (lower seq). Both orders go `IN_TRADE` for the duration of the step and back to `OPEN` or `DONE` in a `finally`, so no state outside the lock is `IN_TRADE`. Each fill raises both orders' version by 1 (glossary: version bumps on partial fills). Last price is set to the trade price.

**Self-trade.** A trader's bid may match their own ask. It is not forbidden by the README; settlement nets out (same party), and the invariant holds.

**Modify.** `newQty` is the new total quantity; it must exceed the already filled amount (otherwise `INVALID_INPUT`, use cancel). Check order: `NOT_FOUND`, `NOT_OWNER`, `ORDER_CLOSED`, `INVALID_INPUT`, `VERSION_CONFLICT`, then the reservation (`INSUFFICIENT_*`). Time priority: a price change or quantity increase gets a new seq (back of the queue); a decrease at the same price keeps its place. A modify that crosses the book trades immediately. `ORDER_IN_TRADE` is never returned: a waiting modify gets the lock only after the trade ends and sees the final state.

**Cancel.** Removes the order, releases the reservation of the remaining quantity, sets `CANCELLED`, bumps the version.

**Lock-free reads.** `book()` returns a `volatile BookSnapshot` rebuilt after every completed operation (one rebuild per operation, not per trade, so readers never see a half-matched book). The book version increments once per operation. `history()` is a `CopyOnWriteArrayList` returned as `List.copyOf`. Copy-on-write makes appends O(n) under the lock; fine for thousands of trades, replace with a chunked list if it shows up in profiling.

**No I/O under lock.** The engine returns `Outcome(OrderResult, List<ExchangeEvent>)`. The caller publishes the events after the call returns. Events per operation: place = `OrderCreated`, `TradeExecuted`*, `BookUpdated`; modify = `OrderModified`, `TradeExecuted`*, `BookUpdated`; cancel = `OrderCancelled`, `BookUpdated`. Rejects return no events.

**Ids and sequence numbers.** The engine takes a ready `Order` (the caller allocates unique order ids and handles `requestId` idempotency and `DUPLICATE_REQUEST`). Trade ids come from an engine `AtomicLong`; `globalSeq` comes from an injected `LongSupplier` shared with the rest of the exchange. Event `globalSeq` values are drawn inside the lock, so per-company order is guaranteed; the event bus must deliver in `globalSeq` order if a global order is needed across companies.

**Time.** `System.nanoTime()` only, set on the order at placement and on each trade.

**Money.** `long` cents only. Overflow is not checked: price * qty stays far below `Long.MAX_VALUE` for the seed data.

## What the other owners must do

- Fabi: implement `Settlement` in `AccountService`; build `StockExchange` on `MatchingEngine` (create `Order` with unique id, `requestId` dedup, publish `Outcome.events()` after the call, `close()`). Contract for `settle`: bid/ask remaining is already reduced; refund the buyer `(bid.price() - trade.price()) * qty`.
- Cristea: `Exchange` is unchanged. If `Settlement` should live in `model` or be renamed, say so.

## Tests

`OrderBookTest` (4): priority ordering, remove vs find, snapshot. `MatchingEngineTest` (20): resting price, partial fills, price/time priority, multi-level sweep, validation and reservation rejects, cancel, modify (version conflict, closed, priority, crossing, filled limit), event order, immutability, and a 4-thread stress test (2 companies, 12,000 mixed operations, fixed seeds) that checks total cash and shares are constant, no negative balances and an uncrossed book. The thread interleaving is not deterministic; the order streams are.

Run: `mvn test` (54 tests total with the existing ones, 0 failures).

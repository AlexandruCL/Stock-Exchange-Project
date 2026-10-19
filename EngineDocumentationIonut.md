# Engine documentation (John: `OrderBook`, `MatchingEngine`)

Branch: `engine`. Source of truth: `Team_Task_Division.pdf` (John's page) and the README. Scope: `book.OrderBook`, `engine.MatchingEngine`, `engine.MatchResult`, plus one support interface `engine.Accounts`.

## Files

| File | Purpose |
|---|---|
| `book/OrderBook.java` | Sorted bids/asks of one company, id index, last price, version, snapshot builder. Not thread-safe. |
| `engine/MatchingEngine.java` | One instance per company: fair lock, book, seq `AtomicLong`, trade ids, volatile snapshot, history. API exactly as in the task PDF. |
| `engine/MatchResult.java` | `ok, reason, order, trades, events`; events are published by the caller after unlock. `toOrderResult()` converts for the facade. |
| `engine/Accounts.java` | Not in the PDF. Interface with the five `AccountService` signatures the PDF lists, so the engine compiles before Fabi's class exists. |
| tests | `OrderBookTest`, `MatchingEngineTest`, `LedgerAccounts` (in-memory `Accounts` for tests). |

No existing file was edited.

## Public API (as in the task PDF)

```java
new MatchingEngine(Company, Accounts)                       // private event counter, for tests
new MatchingEngine(Company, Accounts, LongSupplier globalSeq) // use this one in the exchange
MatchResult submit(Order incoming)
MatchResult modify(long traderId, long orderId, long expectedVersion, long newPrice, long newQty)
MatchResult cancel(long traderId, long orderId)
BookSnapshot snapshot()      // lock-free volatile read
List<Trade> history()        // copy
```

## Design choices

**Engine reserves.** The PDF's day-1 proposal: the engine calls reserve/release itself inside its lock. `submit` reserves (`reserveCash(price*qty)` for a bid, `reserveShares(qty)` for an ask) before the order enters the book; the facade only validates input and must not reserve on its own. Reject mapping: `INSUFFICIENT_FUNDS` / `INSUFFICIENT_SHARES`. Unknown trader is the facade's check.

**Accounts interface.** The PDF gives `AccountService` as a concrete class with boolean `reserveCash/reserveShares`, `releaseCash/releaseShares` and `settle(Trade, long buyerLimitPrice)`. The engine depends on an `Accounts` interface with exactly those signatures; Fabi adds `implements Accounts` (one word).

**Modify reservation is all or nothing without a gap.** Release-then-reserve would let another company's engine take the freed cash in between, and a failed re-reserve could not restore the old one. So the engine reserves or releases only the difference (new need minus old need). If the extra reserve fails nothing has changed. Releases of a decrease happen after the check passes.

**Book data structure.** Two `TreeSet<Order>` with comparators: bids price desc then seq asc, asks price asc then seq asc. Orders are removed before price/seq changes and reinserted after. A `HashMap` of all orders ever placed keeps closed orders findable (`ORDER_CLOSED` vs `NOT_FOUND`); memory grows with order count, fine for a simulation.

**Matching.** `matchLoop` as in the PDF: both orders `IN_TRADE`, n = min(remaining), price = price of the lower-seq order, decrement, version +1 on both, build `Trade`, `accounts.settle(trade, bid.price())`, update last price, then `DONE` (removed from book) or `OPEN`. State is restored in a `finally`.

**Modify.** `newQty` is the new total quantity and must exceed the filled amount (`INVALID_INPUT`, use cancel). Checks: `NOT_FOUND`, `NOT_OWNER`, `ORDER_CLOSED`, `ORDER_IN_TRADE`, `INVALID_INPUT`, `VERSION_CONFLICT`, then reservation. Changed price or larger quantity gets a new seq; a decrease at the same price keeps it. A crossing modify trades at once. `ORDER_IN_TRADE` is in the code as the PDF asks but cannot occur: a waiting modify gets the lock only after the trade ends.

**Cancel.** Remove from book, release the remaining reservation, `CANCELLED`, version +1.

**Snapshots.** Rebuilt once per command, after the whole change, stored in a volatile field; the book version increments once per command and goes into `BookUpdated`. `history()` is a `CopyOnWriteArrayList` returned as `List.copyOf`.

**Events.** submit: `OrderCreated`, `TradeExecuted`*, `BookUpdated`. modify: `OrderModified`, `TradeExecuted`*, `BookUpdated`. cancel: `OrderCancelled`, `BookUpdated`. Rejects return none. No I/O or callbacks under the lock.

**Ids.** The facade builds the `Order` with a unique id; the engine also rejects an id it already holds (`DUPLICATE_REQUEST`). Trade ids are a per-company counter (unique within a company, not across companies). Event `globalSeq` comes from the injected `LongSupplier`.

**Self-trade.** A trader's bid may match their own ask; not forbidden by the README, and the account update nets out.

**Time and money.** `System.nanoTime()` only; `long` cents; overflow of price*qty is not checked (far below the limit for the seed data).

## For Fabi

- `AccountService implements Accounts`. `settle`: refund the buyer `(buyerLimitPrice - trade.price()) * qty`. Trader lock inside, lower id first.
- Facade: one engine per company using the 3-arg constructor with a shared counter; keep an `orderId -> companyId` map (the engine finds orders in its own book); validate trader and input; dedupe `requestId` with `putIfAbsent`; do not reserve; publish `MatchResult.events()` after the call; `EventBus` must deliver in `globalSeq` order.

## For Cristea

No change to `model/*` or `Exchange`. New file `engine/Accounts.java`; `engine/MatchResult.java` is in John's file list in the PDF.

## Tests

`OrderBookTest` (4) and `MatchingEngineTest` (25): exact match, partial fill (50 @11 vs 30 and 40 @10), Ana/Bob/Carl, resting-price rule both ways, price-time priority, multi-level sweep, validation, reserve rejects, cancel, modify (wrong owner, stale version, DONE, priority rules, crossing, filled limit, failed re-reserve changes nothing), event order, snapshot/history immutability, two buyers racing for one ask (200 rounds), and a 4-thread stress test over two companies (conservation of cash and shares, no negative balance, no crossed snapshot seen by a concurrent reader, 60 s hang detection). Thread interleaving is not deterministic; the order streams are seeded. `mvn test`: 60 tests, 0 failures.

## Concurrency review against the two PDFs

| PDF issue | Covered by | Status |
|---|---|---|
| 1-2 Race / double matching | Match-and-decrement inside the company lock; test `twoBuyersRacingForOneAsk_should_fillTheAskOnlyOnce` | Covered |
| 3 Modify during trade | Modify takes the same lock; IN_TRADE never visible outside it; finished orders give `ORDER_CLOSED` | Covered (waits instead of rejecting, as README says) |
| 4 TOCTOU on money/shares | `accounts.reserveCash/reserveShares` called inside the lock | Engine side covered; `AccountService` must make check+reserve atomic |
| 5 Deadlock | One company lock, never two; trader locks only inside `Accounts`, lower id first | Engine side covered; stress test fails on a hung worker (60 s join timeout) |
| 6 Fairness | `ReentrantLock(true)` plus price-time priority with per-company seq | Covered |
| 7 Inconsistent reads | Volatile immutable snapshot swapped once per operation; reader thread in the stress test asserts no crossed snapshot | Covered, with one limit: a trade is appended to history just before the snapshot swap, so a reader can briefly see the trade in history while the book still shows the old state |
| 8 Global lock | Per-company state only | Covered |
| 9 Duplicate requests | Not in the engine | Fabi/StockExchange: `requestId` map with atomic `putIfAbsent` |
| 10 Stale view | Version check on modify | Covered |
| 11-12 Busy wait / shutdown | Not in the engine | Capa, Fabi (`close()`) |
| Pitfall: no I/O under lock | Events returned, not sent | Covered |
| Pitfall: partial fills, multiple trades | Loop in `match`, tests | Covered |

Open point: event `globalSeq` is drawn inside the lock but events are published after unlocking, so two threads on the same company can publish out of order. The event dispatcher must order by `globalSeq`.

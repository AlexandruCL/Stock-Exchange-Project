# Accounts and exchange documentation (Fabi: `AccountService`, `StockExchange`, `EventBus`, `TransactionLog`)

Branch: `fabi/accounts`. Source of truth: `Team_Task_Division.pdf` (Fabi's page), the README and `EngineDocumentationIonut.md`. Scope: `engine.AccountService`, `engine.StockExchange`, `engine.EventBus`, `engine.TransactionLog`.

## Files

| File | Purpose |
|---|---|
| `engine/AccountService.java` | Cash and shares per trader, available and reserved. One `ReentrantLock` per trader. `implements Accounts`, so John's engine calls it directly. |
| `engine/StockExchange.java` | `implements Exchange`. One `MatchingEngine` per company, input validation, `requestId` idempotency, `orderId -> companyId` routing, publishes events after the engine call. |
| `engine/EventBus.java` | Global sequence counter, one daemon dispatcher thread, listeners in a `CopyOnWriteArrayList`, delivery in `globalSeq` order, poison-pill `close()`. |
| `engine/TransactionLog.java` | Append-only list of every event, per company too, filled by the dispatcher. Optional file `logs/exchange-<seed>.log` with a deterministic line format. |
| tests | `AccountServiceTest`, `StockExchangeTest`, `EventBusTest`, `TransactionLogTest`. |

No existing file was edited.

## Public API

```java
// AccountService (signatures from the task PDF, constructor takes TraderAccount as the README says)
new AccountService(Collection<TraderAccount> initial)
boolean reserveCash(long traderId, long amount)
boolean reserveShares(long traderId, String companyId, long qty)
void releaseCash(long traderId, long amount)
void releaseShares(long traderId, String companyId, long qty)
void settle(Trade trade, long buyerLimitPrice)    // inside company lock
PortfolioView view(long traderId)
long totalCash()                                  // for invariant tests
long totalShares(String companyId)                // for invariant tests
boolean hasTrader(long traderId)                  // not in the PDF; the facade's UNKNOWN_TRADER check

// StockExchange: everything in Exchange, plus
new StockExchange(SeedData seed)
new StockExchange(List<Company> companies, Collection<TraderAccount> traders)
new StockExchange(List<Company> companies, Collection<TraderAccount> traders, TransactionLog log)
AccountService accounts()                         // for InvariantChecker
TransactionLog transactionLog()

// EventBus
long nextSeq()                                    // engines draw globalSeq here, inside their lock
void subscribe(Consumer<ExchangeEvent>) / unsubscribe(...)
void publish(List<ExchangeEvent> events)          // after the engine lock is released
void skip(long seq)                               // a drawn number that will never be published
long listenerFailures()
void close()

// TransactionLog
new TransactionLog()                              // memory only
new TransactionLog(Path file)                     // also writes one line per event
List<ExchangeEvent> events() / events(String companyId)
List<Trade> trades(String companyId)
static String format(ExchangeEvent event)
```

## Design choices

**Lock order.** Company lock first (held by the `MatchingEngine` that calls in), then trader locks. `settle` touches two traders and locks the lower trader id first. Nothing in `AccountService` ever takes a company lock, so the order cannot be reversed. This is the one comment at the top of `AccountService`.

**Reservation is one step.** Check and move happen under the trader lock. A trader can bid on two companies at the same time; the two engines hold two different company locks, so the trader lock is the only thing that stops a double spend. `StockExchangeTest` races two 800-cent bids on ACME and BETA from a trader with 1,000 cents.

**Settle.** Buyer: reserved cash drops by `buyerLimitPrice * qty` (what the engine reserved for these shares), `(buyerLimitPrice - price) * qty` goes back to available cash, shares arrive. Seller: reserved shares drop by `qty`, `price * qty` arrives as cash. Both reservations are checked before anything changes; if one is short the call throws `IllegalStateException` and nothing moved (all or nothing). A self-trade nets out.

**Misuse throws, business rejects return false.** `reserveCash/reserveShares` return `false` when available is short; the engine turns that into `INSUFFICIENT_FUNDS` / `INSUFFICIENT_SHARES`. An unknown trader, a negative amount, or releasing more than is reserved is a bug, so it throws. Available and reserved are never mixed (pitfall 2).

**Exact totals while trading.** `totalCash()` and `totalShares()` hold every trader lock at once, taken in id order (the same order as `settle`, so no deadlock). A settlement is never half-counted, so the sum is exact even while trader threads run, not only after they join.

**Facade validation order.** `place`: `INVALID_INPUT` (null `requestId`, company or side, price or qty <= 0, `price * qty` overflows `long`), then `UNKNOWN_COMPANY`, then `UNKNOWN_TRADER`, then the `requestId` check. `modify`/`cancel`: `UNKNOWN_TRADER`, overflow (`INVALID_INPUT`), unknown order id (`NOT_FOUND`), and the engine does the rest. The facade never reserves: John's engine does it inside its lock, as agreed on day 1.

**Idempotency.** `requests.putIfAbsent(requestId, (params, CompletableFuture))`. A repeat with the same parameters returns the same `OrderResult` instance, and waits for it if the first call is still running. Different parameters give `DUPLICATE_REQUEST`. Business rejects (for example `INSUFFICIENT_FUNDS`) are remembered like any result; validation rejects are not, because nothing happened. Request ids are global, not per trader.

**Ids and routing.** One `AtomicLong` for order ids, unique across companies. `orderId -> companyId` is a `ConcurrentHashMap`, written only for accepted orders.

**Event order.** `EventBus` owns the global `AtomicLong`; each engine gets `nextSeq` through John's 3-argument constructor. Engines draw numbers inside their lock but the facade publishes after unlocking, so two publishers can arrive in either order (John's open point). The dispatcher keeps a `TreeMap` of early arrivals and delivers strictly in `globalSeq` order with no gaps. To make sure a gap always closes, the facade records the numbers its thread drew during the engine call and `skip`s any that did not come back as events (only possible if the engine throws).

**Dispatcher.** One daemon thread `exchange-dispatcher` (the name Abud's `ShutdownTest` checks) drains a `LinkedBlockingQueue`. Listeners never run on a trader thread or under a lock. A `RuntimeException` from one listener is caught, counted (`listenerFailures()`) and printed as one line on stderr; the other listeners still get the event. `close()` queues a poison pill, so everything published before it is delivered first, then joins for 5 s and interrupts as a fallback. Idempotent. Events published after `close()` are dropped.

**TransactionLog.** The facade subscribes it first, so it is appended on the dispatcher thread, in `globalSeq` order, never under a company lock. `ArrayList` plus copy on read, guarded by its own monitor. A `CopyOnWriteArrayList` would copy the whole array on every append, which is quadratic under a 100,000-operation stress test. With a file, each event becomes one `format()` line, without timestamps, so two single-threaded runs with the same seed give byte-identical files. `logs/` is already in `.gitignore`. `StockExchange.close()` closes the bus first, then the log.

**Reads.** `book()` is the engine's volatile snapshot and `history()` its copy, with no lock. `portfolio()` is copied under the trader lock. An unknown company or trader gives an empty book or portfolio, as in `StubExchange`. `companies()` keeps the order the companies were given (`LinkedHashMap`); `Set.copyOf` would change its iteration order between JVM runs and break same-seed determinism for strategies that loop over companies.

## For Capa

- `Exchange exchange = new StockExchange(new SeedData());`. With a log file: `new StockExchange(seed.companies(), seed.traders(), new TransactionLog(Path.of("logs/exchange-" + simSeed + ".log")))`.
- Listeners run on the dispatcher thread, after `place` has returned. Keep them to a non-blocking `offer`.
- Use a fresh `requestId` per attempt, for example `"t" + traderId + "-" + n`. Reusing one returns the old result.
- `modify` takes the new total quantity, including what is already filled (engine rule).
- Call `close()` at the end. The dispatcher is a daemon, so it never keeps the JVM alive.

## For Abud

- `InvariantChecker.check(exchange, exchange.accounts(), seed)`. `totalCash()` and `totalShares(id)` are exact even while threads still run.
- For the determinism test: run the single-threaded scenario twice with `new TransactionLog(file)` and compare the files. Or compare `transactionLog().events()` mapped through `TransactionLog.format` after `close()`.
- An extra invariant that catches reservation leaks: each trader's `reservedCash` equals the sum of `price * remaining` over their open bids, and `reservedShares` equals the sum of `remaining` over their open asks. `StockExchangeTest` checks it.

## For John

- `AccountService implements Accounts` with your signatures unchanged. Nothing in `book` or `engine/MatchingEngine.java` was touched.
- Your open point (out-of-order publishing) is handled in `EventBus`. Mutation check: with the re-ordering removed, `StockExchangeTest.concurrentTrading...` sees out-of-order events from the real engine.

## For Cristea

No change to `model/*` or `Exchange`. Public additions beyond the PDF: `AccountService.hasTrader`, `StockExchange.accounts()` / `transactionLog()`, the `EventBus` and `TransactionLog` methods above. The `AccountService` constructor takes `Collection<TraderAccount>`, as the README already says.

## Tests

`AccountServiceTest` (19), `EventBusTest` (10), `StockExchangeTest` (21), `TransactionLogTest` (5).

- Accounts: reserve and reject without change, reserved cash is not reused, 1,000 cash with two concurrent buys of 800 (exactly one, 500 rounds) and the same for shares, release, release beyond reserved throws, settle at the limit, price improvement (limit 12, trade 10: 2 cents per share back), partial fill, missing reservation changes nothing, self-trade, buyer with no prior holding, view is a copy, totals constant, bad input. Plus a 4-thread settlement run in both directions (1 buys from 2 while 2 buys from 1) with a reader summing totals throughout: no deadlock (`@Timeout(60)`), totals never move.
- Facade with John's engine: Ana/Bob/Carl through the facade with portfolios checked, resting-price trade with refund, every invalid or unknown input rejected with no state change and no events, insufficient funds and shares, idempotency (same result instance, different parameters, remembered rejects, 4 threads sending one id, 200 rounds), the 800/1,000 race across two companies (200 rounds), modify routing and rejects, crossing modify, cancel, event order and thread, unsubscribe, log against history, unknown ids, seed order, `close()`. Plus 8 trader threads x 2,000 random place/modify/cancel over the 5 seed companies: cash and shares conserved, nothing negative, no crossed book, reservations match open orders, every `globalSeq` delivered once and in order, log trades equal history.
- EventBus: out-of-order publish, gap held until skipped, throwing listener, dispatcher thread, unsubscribe, `close()` drains and the thread ends, held-back events flushed on close, nothing after close, 8 publishers x 2,000 events in order.
- TransactionLog: grouping, copies, format without timestamps, file contents, the same scenario twice gives identical files.

`mvn test`: 115 tests, 0 failures, 3 runs in a row.

Mutation check (each in a scratch copy, as Abud's page asks):

| Logic removed | Test that fails |
|---|---|
| Trader lock released between check and reserve | `twoBuysOf800With1000Cash...` in both `AccountServiceTest` and `StockExchangeTest` |
| `settle` locks buyer then seller instead of lower id first | `concurrentSettlement...`: deadlock, 60 s timeout |
| `totalCash` sums one trader lock at a time | `concurrentSettlement...`: 3,677 wrong totals seen |
| Dispatcher delivers in arrival order | 4 `EventBusTest` tests and `StockExchangeTest.concurrentTrading...` |

## Concurrency review against the two PDFs

| PDF issue | Covered by | Status |
|---|---|---|
| 4 Check-then-act | `reserveCash/reserveShares` check and move under the trader lock; race tests through `AccountService` and through the facade across two companies | Covered |
| 5 Deadlock | Company lock then trader lock; lower trader id first in `settle`; totals take all trader locks in id order; deadlock test with a timeout | Covered |
| 7 Inconsistent reads | `PortfolioView` copied under the trader lock; `book()`/`history()` from the engine's snapshot; log copied on read | Covered |
| 9 Duplicate / lost messages | `requestId` with `putIfAbsent` of a future; global sequence numbers delivered gap-free and in order | Covered |
| 12 Shutdown | Poison pill, drain, join with a 5 s timeout, interrupt fallback; daemon dispatcher; `close()` idempotent | Covered on the exchange side; Capa calls `close()` |
| Pitfall 1: no I/O under lock | Events published and logged on the dispatcher after the engine returns; file writes on the dispatcher only | Covered |
| Pitfall 2: reserved vs available | Separate fields; release beyond reserved throws | Covered |
| John's open point: publish order | Re-order buffer in `EventBus` | Covered |

Known limits: the `requestId` and `orderId -> companyId` maps grow with every request (fine for a simulation). `place` after `close()` still trades, but its events are dropped. The log is byte-identical between runs only for single-threaded runs; with several trader threads the interleaving, and so the ids and `globalSeq` order, differs between runs. A listener that throws an `Error` (not a `RuntimeException`) stops the dispatcher.

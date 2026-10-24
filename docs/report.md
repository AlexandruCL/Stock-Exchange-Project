# Stock Exchange Simulation: verification report

Owner: Abud (`view`, cross-cutting tests, docs). State on 2026-10-24: everything is on `main` and the cross-cutting suite runs against the real `StockExchange`.

## 1. Status

| Part | Owner | On `main` | Covered here |
|---|---|---|---|
| Model, contracts, seed data | Cristea | Yes | Contract tests use them |
| `OrderBook`, `MatchingEngine` | John | Yes | Every test below runs through the real engine |
| `AccountService`, `StockExchange`, `EventBus`, `TransactionLog` | Fabi | Yes | Every test below runs through the real facade (`TestExchanges.create`) |
| Traders, strategies, `Simulation`, `Main` | Capa | Yes | `ShutdownTest`, `mvn exec:java` end to end |
| `ConsoleView`, invariant/race/stress/determinism/shutdown tests, this report | Abud | Yes | |

The tests talk to the `Exchange` interface only. `verify.TestExchanges` builds the real `StockExchange`. `verify.ReferenceExchange` is kept as an independent second implementation: John's `MatchingEngine` per company, a strict `ReferenceAccounts`, `requestId` dedupe and a dispatcher thread that delivers events in `globalSeq` order. It was what the suite was written against before `StockExchange` existed, and it is the baseline to compare with if a test ever fails on one and not the other.

## 2. Architecture

```
             trader threads (Capa: sim.Trader x N)          ConsoleView (Abud)
                 |  place / modify / cancel                    ^   |  book(), history()
                 v                                             |   |  lock-free reads
   +----------------------------------------------+            |   v
   | Exchange facade (Fabi: StockExchange)        |  events    |  volatile BookSnapshot
   |  validate -> requestId dedupe -> route       |------------+  per company
   |  orderId -> company index                    |  dispatcher thread, globalSeq order
   +----------------------------------------------+
        |              |              |   one engine per company, nothing shared
        v              v              v
   +---------+    +---------+    +---------+
   | Engine  |    | Engine  |    | Engine  |   MatchingEngine (John)
   | ACME    |    | BETA    |    | ...     |   fair ReentrantLock, OrderBook, seq counter
   +---------+    +---------+    +---------+   match loop, snapshot swap, MatchResult.events
        \              |              /
         \             v             /         called inside the company lock
          +--> AccountService (Fabi) <--+      trader lock after company lock, lower id first
               reserve / release / settle
```

Lock order: company lock, then trader lock (lower id first when two traders settle). No thread ever holds two company locks. Events are built inside the lock, published after it.

## 3. Concurrency issues and where each is handled

| # | Issue (examples PDF) | Mitigation | Implemented in | Proved by |
|---|---|---|---|---|
| 1 | Race on the order book | One fair lock per company; match-and-decrement in one critical section | `MatchingEngine.submit/modify/cancel` | `RaceConditionTest.twoBuyersRacingForOneAsk...` (10,000 rounds) |
| 2 | Double matching | Decrement inside the match loop under the lock | `MatchingEngine.matchLoop` | `RaceConditionTest.twoSellersRacingForOneBid...` (5,000 rounds) |
| 3 | Modify during trade | Modify takes the same lock; IN_TRADE never visible outside it; DONE gives ORDER_CLOSED, a fill bumps the version | `MatchingEngine.modify`, `checkOpenOwned` | `RaceConditionTest.modifyDuringFullFill...`, `modifyDuringPartialFill...` (5,000 rounds each) |
| 4 | Check-then-act on cash/shares | Check and reserve in one step, inside the trader lock | `AccountService.reserveCash/reserveShares` | `RaceConditionTest.twoParallelBuysOf800With1000Cash...` across two companies |
| 5 | Deadlock | Company lock then trader lock, lower trader id first; never two company locks | `MatchingEngine`, `AccountService.settle` | `@Timeout` on every concurrent test; `StressTest` (50 threads) |
| 6 | Starvation / fairness | `ReentrantLock(true)` plus price-time priority by per-company seq | `MatchingEngine`, `OrderBook` comparators | `RaceConditionTest.earlierAskAtSamePrice...` (10 buyers, 500 rounds) |
| 7 | Inconsistent reads | Immutable records, snapshot rebuilt after the whole change and swapped through a volatile field | `OrderBook.snapshot`, `MatchingEngine.publishBook`, model records | `StressTest` reader thread; `ExchangeContractTest.results_should_beImmutableViews...` |
| 8 | Global lock too slow | One engine and lock per company | `MatchingEngine` per company | By design (the stress run trades 5 companies in parallel) |
| 9 | Duplicate / lost messages | `requestId` with atomic `putIfAbsent`; every event has a `globalSeq` | `StockExchange.place` | `RaceConditionTest.sameRequestIdFromEightThreads...`; `StressTest` checks OrderCreated count = accepted places |
| 10 | Stale client view | `expectedVersion` on modify, VERSION_CONFLICT | `MatchingEngine.modify` | `ExchangeContractTest.modify_should_reject...`; partial-fill race |
| 11 | Busy waiting | Blocking queue plus timeout | `Trader` sleeps between orders; the `EventBus` dispatcher blocks in `take()`; `ConsoleView` is scheduled. Nothing spins | Review; the spinning variant is demonstrated on branch `faulty/deadlock-livelock` |
| 12 | Shutdown / thread leaks | Poison pill, join with timeout, executor shutdown | `EventBus.close`, `ConsoleView.close`, `Simulation.close` | `ShutdownTest` (20 start/stop cycles, no `exchange-dispatcher` or `console-view` thread left) |

## 4. Pitfalls and how we handled them

The task division names two pitfalls from section 6 of the analysis PDF. The analysis PDF is not in the repository, so its remaining pitfalls are not listed here. Add them when the PDF is available.

| Pitfall | Handling |
|---|---|
| 1. I/O, logging or listener calls under the lock | The engine returns events in `MatchResult`; the facade publishes them after the call. `ConsoleView`'s listener only queues a line; printing happens on the `console-view` thread. |
| 2. Mixing reserved and available | `ReferenceAccounts` keeps them separate and throws if a release or settle would make a reserved balance negative. `InvariantChecker` checks that reserved cash equals the sum of open bids (price x remaining) and reserved shares equal the sum of open asks, per trader. |
| Partial fills and several trades from one order | Match loop continues until the book stops crossing; covered by John's tests and by the partial-fill race. |

## 5. Tests

`InvariantChecker.check(exchange, initialAccounts)` checks, through the public API only:

- total cash (available + reserved) and total shares per company equal the starting totals
- no negative cash, reserved cash, shares or reserved shares
- every trader's holdings equal their start plus what the trade history moved (cash paid = cash received)
- reserved cash and shares exactly back the open orders in the books
- every book is sorted by price-time priority and not crossed, and holds only OPEN orders with 0 < remaining <= qty
- every trade has qty > 0 and price > 0, unique ids, and increasing `globalSeq` within its company

| Test class | What it shows |
|---|---|
| `ExchangeContractTest` (11) | Rejects without state change, reservation on place, release on cancel, modify rejects, immutable results, append-only history, event order (TradeExecuted before BookUpdated), throwing listener isolated |
| `RaceConditionTest` (9) | The races in section 3, each released by one latch and repeated 500 to 10,000 times |
| `StressTest` (1) | 50 threads, 5 companies, 100,000 seeded place/modify/cancel operations, concurrent reader, `@Timeout(60)` |
| `DeterminismTest` (2) | 5,000-operation seeded scenario run twice: event logs identical byte for byte; a different seed gives a different log |
| `ShutdownTest` (2) | 20 start/stop cycles of exchange plus view with trading threads, no thread left; double close is harmless |
| `ConsoleViewTest` (5) | Trade line format, board depth (top 5) and last 10 trades, periodic printing on its own thread, start/close rules |

### Results (2026-10-24, JDK 17, against the real `StockExchange`)

- Full suite: 161 tests, 0 failures.
- Stress run: 100,000 operations, about 77,000 accepted, about 44,000 trades. Rejections are about 21,000 ORDER_CLOSED (the order was filled before the modify or cancel arrived), about 1,600 INSUFFICIENT_SHARES and about 220 VERSION_CONFLICT. All invariants hold.
- Three consecutive runs of `StressTest`, `RaceConditionTest` and `ShutdownTest`: all green.

### Do the tests catch broken code?

Each check ran once against a throwaway copy of the source with one safeguard removed:

| Mutation | Tests that failed |
|---|---|
| `lock.lock()` / `unlock()` removed from `MatchingEngine` | 5 of 9 race tests (double match, ORDER_IN_TRADE seen by modify, priority broken) and `StressTest` (one run: reserved cash went negative; another: workers hung on the corrupted book). The other 4 race tests guard the account lock and the dedupe, see below. |
| Reservation split into check, then reserve (`ReferenceAccounts`) | `twoParallelBuysOf800With1000Cash...` |
| `requestId` dedupe as get, then put | `sameRequestIdFromEightThreads...` |

## 6. Findings for the team

1. **Fabi: the reservation race only shows across companies** (handled: `AccountService` has per-trader locks). Two buys by one trader on the same company are already serialized by the company lock, so a non-atomic `reserveCash` passes a same-company test. The real danger is the same trader buying ACME and BETA at once, under two different company locks. `AccountService` needs its own per-trader lock, and `twoParallelBuysOf800With1000Cash...` races across two companies to test exactly that.
2. **Fabi: events have to be put back in order** (handled by `EventBus`). Engines draw `globalSeq` under their own lock but publish after unlocking, so batches from two threads can arrive out of order. `EventBus.dispatch` holds events back until the next expected number arrives (or is skipped). `StressTest` fails if a listener ever sees `globalSeq` go backwards.
3. **John: the history is briefly ahead of the snapshot.** A trade is appended to the history just before the snapshot swap, so a reader can see the trade in `history()` while `book()` still shows the old state. This is harmless for the invariants (they are checked at rest), but a viewer can show it for a moment. It was already noted in John's engine documentation.
4. **ORDER_IN_TRADE never reaches a caller.** A modify waits for the lock and then sees DONE or OPEN. The races confirm this; the code path exists only because the PDF asks for it.

## 7. Known limitations

- Determinism is proved for a single-threaded scenario. With several threads the order stream per trader is reproducible, but the interleaving is not.
- Timestamps are `System.nanoTime()` and are left out of the determinism log.
- `StockExchange` rejects `price * qty` overflow (`INVALID_INPUT`); `MatchingEngine` alone does not, and the seed data is far from the limit.
- `MatchingEngine.history` is a `CopyOnWriteArrayList`: every trade copies the whole array (O(n)). It gives lock-free consistent reads and is fine at this scale; a much longer run would want a chunked append-only log.
- `Main` prints trades and boards but no final summary; the end-of-run invariants (cash and share conservation) are checked by the tests, not printed by the simulation.
- The race tests repeat thousands of times, but they cannot prove the absence of a race, only make one very likely to show. The proof is the lock order in section 2.
- The assignment's second task (faulty solutions) is not on `main`: see `faulty/data-races` and `faulty/deadlock-livelock`, and [`requirements.md`](requirements.md).

## 8. Demo script (presentation)

1. `mvn test`: show the green suite (161 tests) and the stress line (`stress: 100000 ops ... trades`).
2. `mvn exec:java -Dexec.args="--seed 42 --seconds 30"`: `ConsoleView` prints one line per trade and the board every few seconds. Point at the books changing and the last price moving.
3. Rejected modifies are normal results: a modify with an old version gets VERSION_CONFLICT, a modify of a filled order gets ORDER_CLOSED (`StressTest` prints the reject counts by reason).
4. Conservation of money and shares: `InvariantChecker` at the end of `StressTest` (total cash 2,000,000,000 cents, 5,000 shares per company, no negative balance).
5. Re-run with `--seed 42`: same orders per trader (`DeterminismTest` shows the event log is byte-identical in a single-threaded run).
6. Second task: `git switch faulty/data-races`, run `RaceDemo` and show the correct exchange passing and the racy one breaking; `git switch faulty/deadlock-livelock`, run `LivenessDemo`.

## 9. Running the tests

```
mvn test
```

Without Maven, from the project root:

```
mkdir -p out/main out/test
javac --release 17 -d out/main $(find src/main/java -name "*.java")
javac --release 17 -d out/test -cp out/main:junit-platform-console-standalone-1.10.2.jar $(find src/test/java -name "*.java")
java -jar junit-platform-console-standalone-1.10.2.jar execute -cp out/main:out/test --scan-classpath
```

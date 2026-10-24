# File map: which files each program needs

Everything is one Maven project (`pom.xml`, Java 17, JUnit 5 for tests only, no other dependency). Package root:
`src/main/java/com/stockexchange/`. To run any program, send the whole `src/main` tree plus `pom.xml`; the tables below say
which files each program really uses, so you can see what depends on what.

## 1. Which files each program needs

| Program | Entry point | Needs (besides the `model` package, which every program uses) |
|---|---|---|
| 1. Correct exchange | `sim/Main` | `sim/*`, `view/ConsoleView`, `engine/{Exchange, StockExchange, MatchingEngine, MatchResult, Accounts, AccountService, EventBus, TransactionLog}`, `book/OrderBook` |
| 2. Data races | `faulty/RaceDemo` | `faulty/{RaceHarness, RacyExchange, RacyMatchingEngine, RacyAccounts}`, plus the correct `engine/*` and `book/OrderBook` (the demo compares against `StockExchange`, and the racy engine reuses `OrderBook`) |
| 2b. Primitives | `faulty/PrimitiveDemos` | Nothing else (JDK only) |
| 3. Deadlock, livelock, performance | `faulty/LivenessDemo` | `faulty/{LivenessScenarios, FaultyAccounts, CoarseLockEngines, SpinningConsumer}`, plus `engine/{MatchingEngine, MatchResult, Accounts, AccountService}` and `book/OrderBook` |

Note for program 1: `sim/Main` does **not** import `StockExchange`. It loads the class named by `--exchange-class`
(default `com.stockexchange.engine.StockExchange`) with reflection, so the engine files are needed at run time even though the
compiler does not require them for `Main` itself. The same switch runs program 1 on the racy exchange
(`--exchange-class com.stockexchange.faulty.RacyExchange`).

## 2. Source files (`src/main/java/com/stockexchange/`)

### `model/`: shared data types (no logic, no threads, except `Order`)

| File | Purpose |
|---|---|
| `Side.java` | `BID` (buy) or `ASK` (sell) |
| `Company.java` | Record: id, name, initial last price, total shares |
| `TraderAccount.java` | Record: starting cash and shares of one trader |
| `SeedData.java` | The fixed starting market: 5 companies, 20 traders, totals the invariant checks compare with |
| `Order.java` | Mutable live order, touched only under the company lock; everyone else sees an `OrderView` |
| `OrderView.java` | Immutable copy of an order handed to callers |
| `OrderState.java` | `OPEN`, `IN_TRADE`, `DONE`, `CANCELLED` |
| `Trade.java` | Record: one executed trade (ids, buyer, seller, price, qty, global sequence number) |
| `BookSnapshot.java` | Immutable view of one company's book (bids, asks, last price, version) |
| `PortfolioView.java` | Immutable copy of one trader's cash, shares and reservations |
| `OrderResult.java` | What `place`/`modify`/`cancel` return: ok or reject reason, the order, trades |
| `Reject.java` | Reject reasons (`INSUFFICIENT_FUNDS`, `VERSION_CONFLICT`, `ORDER_CLOSED`, ...) |
| `ExchangeEvent.java` | Sealed interface and records: order created/modified/cancelled, trade executed, book updated |
| `Validation.java` | Price, quantity and id checks |

### `book/`, `engine/`: the exchange itself

| File | Purpose | Concurrency role |
|---|---|---|
| `book/OrderBook.java` | Bids and asks of one company in price-time order, id index | Not thread-safe; only used under the company lock |
| `engine/Exchange.java` | The public interface: `place`, `modify`, `cancel`, `book`, `history`, `portfolio`, `subscribe`, `close` | The contract all traders and tests use |
| `engine/StockExchange.java` | Facade: validates, deduplicates `requestId`, routes to the right engine, publishes events after unlocking | `ConcurrentHashMap.putIfAbsent`, `CompletableFuture` |
| `engine/MatchingEngine.java` | One per company: reserve, rest in book, match loop, modify, cancel, snapshot | Fair `ReentrantLock`, `AtomicLong`s, `volatile` snapshot, `CopyOnWriteArrayList` history |
| `engine/MatchResult.java` | What an engine call produced (result, trades, events to publish) | Immutable |
| `engine/Accounts.java` | Interface the engine calls: reserve, release, settle | Contract: called under the company lock, must not call listeners |
| `engine/AccountService.java` | Cash and shares of every trader; settles trades | One `ReentrantLock` per trader, lower id first |
| `engine/EventBus.java` | Delivers events to listeners on one dispatcher thread in global sequence order | `LinkedBlockingQueue`, poison pill, `AtomicLong` |
| `engine/TransactionLog.java` | Append-only record of all events, optional file | `synchronized`, never taken under a company lock |

### `sim/`, `view/`: the simulation (program 1)

| File | Purpose |
|---|---|
| `sim/Main.java` | Command line entry point, builds the exchange, runs the simulation, prints the summary |
| `sim/Simulation.java` | Starts one thread per trader, stops and joins them, closes the exchange |
| `sim/Trader.java` | One trader thread: act, sleep, repeat; stoppable by interrupt |
| `sim/TradingStrategy.java` | Interface: what a trader does in one step |
| `sim/RandomTradingStrategy.java` | Seeded strategy: place near the starting price, modify, cancel; buyer/seller/balanced profiles |
| `sim/RunSummary.java` | End-of-run statistics and invariant verdict (`RESULT: PASS`) |
| `view/ConsoleView.java` | Subscribes to events, prints one line per trade and a board every 5 s on its own thread |

### `faulty/`: the two broken solutions (programs 2, 2b, 3)

| File | Program | Purpose | Bug ids (see the faulty-* documents) |
|---|---|---|---|
| `RacyAccounts.java` | 2 | `AccountService` without locks | #1 to #4 |
| `RacyMatchingEngine.java` | 2 | `MatchingEngine` without lock, atomics or volatile | #5 to #8 |
| `RacyExchange.java` | 2 | `StockExchange` with plain `HashMap`s and counters; also an `Exchange` for `sim.Main` | #6, #9 to #11 |
| `RaceHarness.java` | 2 | Hammers any `Exchange` with 20 threads and checks the invariants | (judge) |
| `RaceDemo.java` | 2 | Main: correct rounds, then racy rounds, prints both reports | |
| `PrimitiveDemos.java` | 2b | Lost update, check-then-act, visibility, in isolation | #2, #6, #7, #11 |
| `FaultyAccounts.java` | 3 | `AccountService` with switchable lock order, `tryLock` retry and a callback | L1, L2, L3 |
| `CoarseLockEngines.java` | 3 | Real engines behind one global lock | P1 |
| `SpinningConsumer.java` | 3 | Event consumer that spins or blocks | P2 |
| `LivenessScenarios.java` | 3 | The experiments (deadlock detection with `ThreadMXBean`, livelock, throughput, CPU time) | |
| `LivenessDemo.java` | 3 | Main: prints the experiments | |

## 3. Test files (`src/test/java/com/stockexchange/`), run with `mvn test` (171 tests)

| File | What it covers |
|---|---|
| `model/{OrderTest, RecordImmutabilityTest, SeedDataTest, ValidationTest}` | Data types, immutability, seed totals |
| `book/OrderBookTest` | Price-time ordering, add/remove/reinsert |
| `engine/MatchingEngineTest` | Matching, modify, cancel, reservation, concurrent cases; uses `LedgerAccounts` |
| `engine/AccountServiceTest` | Reserve/release/settle, concurrent settlement without deadlock, exact totals while running |
| `engine/StockExchangeTest`, `EventBusTest`, `TransactionLogTest` | Facade, idempotent `requestId`, event ordering, log |
| `engine/StubExchange`, `StubExchangeTest` | A do-nothing `Exchange` used to test the simulation without an engine |
| `verify/InvariantChecker` | Safety invariants through the public API (shared by the tests below) |
| `verify/ExchangeContractTest` | Rejects, reservations, modify/cancel rules, event order |
| `verify/RaceConditionTest` | The assignment's race scenarios, 500 to 10,000 repetitions each |
| `verify/StressTest` | 50 threads, 100,000 operations, concurrent reader |
| `verify/DeterminismTest`, `ShutdownTest` | Same seed gives the same log; no thread left after close |
| `verify/TestExchanges`, `Race`, `ReferenceExchange`, `ReferenceAccounts` | Helpers: the exchange under test (the real `StockExchange`), a latch-based race runner, an independent second implementation |
| `sim/{MainTest, SimulationTest, TraderTest, RandomTradingStrategyTest, RunSummaryTest}` | Command line, lifecycle, strategy, summary verdict |
| `view/ConsoleViewTest` | Output format, depth, periodic printing |
| `faulty/RacyExchangeTest` | The racy exchange really breaks an invariant, the correct one does not |
| `faulty/LivenessScenariosTest` | The deadlocks really happen, the spinning consumer really burns CPU, balances stay right |

## 4. Build and documentation files

| File | Purpose |
|---|---|
| `pom.xml` | Maven build; `exec:java` runs `sim.Main` by default, `-Dexec.mainClass=...` selects another program |
| `.gitignore` | Ignores `target/`, `.idea/`, `.claude/`, `*.pdf` |
| `README.md` | Overview, rules, seed data, branch rules |
| `docs/requirements.md` | The assignment and where each requirement is implemented |
| `docs/running-and-analysis.md` | How to run every program and how to prove it works from the output |
| `docs/report.md` | Architecture, concurrency issues and mitigations, test evidence, limitations |
| `docs/faulty-data-races.md`, `docs/faulty-deadlock-livelock.md` | Bug catalogues, fixes, exam questions |
| `docs/simulation.md`, `docs/skeleton-status.md`, `docs/glossary.md` | Simulation details, contract history, glossary |
| `EngineDocumentationIonut.md`, `AccountsDocumentationFabian.md` | Per-component notes of the engine and accounts authors |

## 5. Minimum to send

1. `pom.xml`
2. `src/` (whole tree: `main` for the programs, `test` for the evidence)
3. `README.md` and `docs/`

Then the professor needs only JDK 17+ and Maven: `mvn -q compile` and the commands in
[`running-and-analysis.md`](running-and-analysis.md). Without Maven, from the project root (bash):

```
mkdir out
javac --release 17 -d out $(find src/main/java -name "*.java")
java -cp out com.stockexchange.sim.Main --seed 42 --seconds 10
java -cp out com.stockexchange.faulty.RaceDemo 2 3000
java -cp out com.stockexchange.faulty.LivenessDemo all
```

In PowerShell, collect the sources with `$files = (Get-ChildItem -Recurse src/main/java -Filter *.java).FullName` and run
`javac --release 17 -d out $files`.

# Stock Exchange Simulation

> **Branch `faulty/deadlock-livelock`: intentionally broken, never to be merged.** It adds
> `com.stockexchange.faulty` to demonstrate deadlock, livelock and performance bugs. Read
> [`docs/faulty-deadlock-livelock.md`](docs/faulty-deadlock-livelock.md). The correct solution is on `main`.

Trader threads buy and sell shares of several companies on one in-process exchange. Java 17, Maven, JUnit 5.

## Build and run

```
mvn compile
mvn test
mvn exec:java -Dexec.args="--seed 42 --seconds 30"
```

`Main` and the `sim` package are implemented. The default command requires the production
`com.stockexchange.engine.StockExchange`, which is not present in the skeleton yet.
For a runnable test-stub demonstration and integration instructions, see
[`docs/simulation.md`](docs/simulation.md).

## Packages and owners

| Package | Contents | Owner |
|---|---|---|
| `model` | Enums, records, `Order`, `Company`, `Validation`, `SeedData`, `ExchangeEvent` | Cristea |
| `book` | `OrderBook` | John |
| `engine` | `Exchange` (Cristea), `MatchingEngine` (John), `AccountService`, `StockExchange`, `EventBus`, `TransactionLog` (Fabi) | |
| `sim` | Traders, strategies, `Simulation`, `Main` | Capa |
| `view` | `ConsoleView` | Abud |

Contract changes (`model/*`, `engine/Exchange.java`) go to the group chat first. Cristea applies them.

Additions to the section 3 contracts:

- `Exchange extends AutoCloseable` with `void close()` (no checked exception). `Simulation` calls it at the end; `StockExchange.close()` drains and joins the event dispatcher.
- `model.TraderAccount(long id, long cash, Map<String, Long> shares)` is the starting-account type. `AccountService` takes `Collection<TraderAccount>`, usually `new SeedData().traders()`. It is separate from `sim.Trader`, which is the thread.

## Rules everyone follows

| Topic | Rule |
|---|---|
| Match condition | Bid price >= ask price. Quantity traded = min(bid.remaining, ask.remaining). The remainder stays in the book. |
| Trade price | Price of the older (resting) order, the one with the lower sequence number. |
| Priority | Bids: highest price first, then lowest sequence number. Asks: lowest price first, then lowest sequence number. Sequence numbers come from one `AtomicLong` per company. |
| Money | All prices and cash are `long` in cents. No `double` anywhere. |
| Order states | OPEN, IN_TRADE, DONE, CANCELLED. IN_TRADE exists only while the matcher holds the company lock, so outside the lock an order is never IN_TRADE. A modify that arrives during a trade waits for the lock and then sees the final state; if the order is DONE it is rejected. |
| Ongoing transaction | The time the matching engine holds the company lock. Modify and cancel take the same lock. |
| Locking | One `ReentrantLock(true)` (fair) per company. Portfolio updates for both parties happen inside that lock. Different companies never share a lock. Lock order: company lock, then trader lock (lower trader id first). |
| Reservation | On placement, inside the company lock: the buyer's cash (price * qty) moves to reserved; the seller's shares move to reserved. Check and reserve are one step. |
| No I/O under lock | No printing, listener callbacks or console logging while holding the lock. Events are collected inside the lock and published after unlocking. |
| Public reads | Readers never take the company lock. They read an immutable snapshot (volatile reference) that the engine swaps after each completed trade or book change. |
| Live orders | `model.Order` is mutable and engine-internal. Only `book` and `engine` may hold one; everything else gets an `OrderView`. |
| Safety invariant | Total cash across all portfolios (available + reserved) and total shares per company stay constant after every operation. No negative balance or holding. |
| Determinism | The simulation takes a seed. The same seed gives the same orders from each trader. All events carry a global sequence number. |
| Time source | `System.nanoTime()` inside the engine only. No `currentTimeMillis` in logic. |
| Code style | Java 17, 4-space indent, one public class per file, no wildcard imports. Comments only where logic is non-obvious. |

## Seed data

`new SeedData()` builds the starting market. All values come from constants in `model/SeedData.java`.

| Item | Value |
|---|---|
| Companies | ACME, BETA, CORE, DELT, EPSL |
| Initial last price (cents) | 1,000 / 2,500 / 5,000 / 750 / 12,000 |
| Traders | 20, ids 1 to 20 |
| Starting cash per trader | 100,000,000 cents (1,000,000.00) |
| Starting shares | Trader `t` holds `100 * ((t + c) % 4 + 1)` shares of company index `c` (100 to 400) |
| Total cash | 2,000,000,000 cents |
| Total shares per company | 5,000 |

`SeedDataTest` checks these totals. Invariant tests compare against `SeedData.totalCash()` and `SeedData.totalShares(companyId)`.

## Branches and pull requests

- One branch per member: `cristea/skeleton`, `john/engine`, `fabi/accounts`, `capa/sim`, `abud/tests`.
- Pull request into `main`, reviewed by at least one person who is not the author.
- No direct pushes to `main` after the first merge.
- Do not edit another member's package. Ask the owner or open a pull request to them.
- Bug reports include the failing test, the seed, and the stack trace or log.

## Tests and verification

`mvn test` runs everything. The cross-cutting tests are in `src/test/java/com/stockexchange/verify` and use only the `Exchange` interface:

| Class | Checks |
|---|---|
| `InvariantChecker` | Cash and shares conserved, no negatives, reservations back exactly the open orders, books sorted and not crossed, trades valid |
| `ExchangeContractTest` | Rejects, reservation, cancel, modify rules, immutable results, event order |
| `RaceConditionTest` | Latch-released races from the examples PDF, repeated 500 to 10,000 times |
| `StressTest` | 50 threads, 5 companies, 100,000 seeded operations, `@Timeout(60)` |
| `DeterminismTest` | Same seed gives a byte-identical event log |
| `ShutdownTest` | 20 start/stop cycles leave no thread behind |

They get their exchange from `TestExchanges.create(...)`. Until `StockExchange` is merged, that builds `ReferenceExchange` (test scope, over the real `MatchingEngine`); afterwards, change that one line.

`ConsoleView` (package `view`) subscribes to the exchange, prints one line per trade, and every N seconds prints the top 5 bids and asks, last price and last 10 trades of each company. It reads snapshots only and prints on its own `console-view` thread. Sample:

```
[    0.064s] TRADE ACME      20 @      10.10  buyer  10  seller  17
[    0.064s] TRADE ACME       6 @      10.00  buyer   1  seller  17
---------------- board at     0.709s ----------------
ACME  last       9.80  book v113  trades 68
   bid qty        bid | ask         ask qty
        15       9.80 | 9.90             15
        14       9.70 | 10.00             3
        24       9.70 | 10.10            22
  recent: 21@9.90 8@9.90 1@9.80 13@9.80 6@9.80 3@9.80 6@9.90 2@9.80 6@9.80 4@9.80
```

`docs/report.md` has the architecture, the issue/mitigation table, test results, known limitations and the demo script.

See `docs/glossary.md` for terms.

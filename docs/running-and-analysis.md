# Running every program and proving it works from the data

The project has **three programs** in one Maven project. Requirements: JDK 17 or newer and Maven 3.9.

| # | Program | Main class | Purpose | Expected verdict |
|---|---|---|---|---|
| 1 | Correct exchange and simulation | `com.stockexchange.sim.Main` | The assignment: traders buy and sell shares | `RESULT: PASS` |
| 2 | Faulty solution 1: data races | `com.stockexchange.faulty.RaceDemo`, `PrimitiveDemos` | Consistency bugs, side by side with the correct exchange | Correct: `all invariants hold`. Racy: violations and exceptions |
| 3 | Faulty solution 2: deadlock, livelock, performance | `com.stockexchange.faulty.LivenessDemo` | Liveness and speed bugs | `DEADLOCK`, near-zero progress, flat throughput, 100% CPU |

## 0. Build once

```
mvn -q compile
```

Every program below is started either with `java -cp target/classes <main class> <args>` (works in cmd, PowerShell
and bash) or with Maven: `mvn -q exec:java -Dexec.mainClass=<main class> -Dexec.args="<args>"`. In PowerShell quote the
property: `'-Dexec.args=...'`.

The tests (`mvn test`, 171 tests) cover all three programs; section 5 says what each test class proves.

## 1. Program 1: the correct exchange

```
java -cp target/classes com.stockexchange.sim.Main --seed 42 --seconds 10 --pause-ms 10 | tee run.log
```

(PowerShell: `... | Tee-Object run.log`.) Options: `--seed` (same seed, same orders per trader), `--seconds`, `--pause-ms`
(smaller means more orders per second), `--exchange-class`.

What you see:

1. One line per trade while it happens: `[   0.064s] TRADE ACME  20 @ 10.10  buyer 10  seller 17`.
2. Every 5 seconds a board per company: top 5 bids and asks, last price, last 10 trades.
3. At the end, after all trader threads were stopped and joined, the **run summary**:

```
---------------- run summary ----------------
company  trades    volume       vwap        low       high       last  open b/a
ACME       175       606      10.00       9.50      10.50       9.70    2/0
BETA       196       713      25.10      23.75      26.25      24.75    0/2
...
total trades: 862
cash   total 2,000,000,000 cents, expected 2,000,000,000: OK
shares ACME total 5,000, expected 5,000: OK
...
trades unique, ordered, positive: OK
books sorted and not crossed:     OK
RESULT: PASS, all invariants hold
```

### How to prove it works from this data

The assignment's rules, and the evidence for each:

| Claim | Evidence in the output | Why it proves it |
|---|---|---|
| Shares and money are never created or lost | `cash total` equals `expected` (20 traders x 100,000,000 cents) and `shares <C> total` equals 5,000 for every company, although hundreds of trades moved them between 20 threads | The sums are taken over all 20 portfolios (available + reserved) after the run. A lost update or a double settlement changes the sum (the racy exchange shows exactly that, section 2) |
| Orders only match when prices cross, at the older order's price | `low` and `high` of a company stay inside the ±5% band the traders quote (e.g. ACME 9.50 to 10.50 around 10.00); `books ... not crossed` is `OK` | A book where best bid >= best ask at rest would mean a missed match |
| A trade happens once | `trades unique, ordered, positive: OK`: ids unique, `globalSeq` strictly increasing, qty and price > 0 | A double fill would repeat or break these |
| Nobody trades without paying | No `negative cash/shares` violation | Reservation at placement makes overdraft impossible |
| Everyone sees the same market | Trade lines on screen = trades in the summary (below) | The viewer and the history come from the same event stream |

Independent re-count from the printed trade lines (does not use the program's own summary code):

```
grep -c " TRADE " run.log                                    # 862  = "total trades: 862"
grep " TRADE " run.log | awk '{n[$4]++; q[$4]+=$5; v[$4]+=$5*$7} END{for(c in n) printf "%s %d %d %.2f\n",c,n[c],q[c],v[c]/q[c]}' | sort
# ACME 175 606 10.01   <- trades, volume, vwap: match the summary table (vwap differs only by rounding)
```

(PowerShell: `(Select-String " TRADE " run.log).Count`.)

Reading the table: `vwap` is the volume-weighted average price, `last` the last trade price, `open b/a` the number of
resting bids and asks. Traders are symmetric random buyers and sellers around the starting price, so the vwap staying
close to the starting price (ACME 10.00, BETA 25.00, CORE 50.00, DELT 7.50, EPSL 120.00) is expected; a drift would hint at a
one-sided bug.

Try the sensitivity: run with different seeds (`--seed 1`, `--seed 2`); the numbers of trades differ, the `RESULT` never does.
Run with `--pause-ms 1` for roughly ten times the load; it still passes. That is the point: correctness does not depend on
timing.

### Stronger evidence: the automated suite

```
mvn test
```

| Test class | What it proves |
|---|---|
| `StressTest` | 50 threads, 5 companies, 100,000 random place/modify/cancel operations; a reader thread checks every snapshot while trading runs; all invariants hold at the end. Prints `stress: 100000 ops, ... trades, rejects {...}` |
| `RaceConditionTest` | The race scenarios of the assignment (two buyers one ask, modify during a trade, ...) released by a latch and repeated 500 to 10,000 times |
| `ExchangeContractTest` | Rejects change nothing, reservations, modify/cancel rules, events in order |
| `DeterminismTest` | Same seed, byte-identical event log in a single-threaded run |
| `ShutdownTest` | 20 start/stop cycles leave no thread behind |
| `RunSummaryTest` | The summary reports PASS on the real exchange and FAIL when an account is missing, so the verdict above is not a constant |
| `OrderBookTest`, `MatchingEngineTest`, `AccountServiceTest`, `StockExchangeTest`, `EventBusTest`, `TransactionLogTest` | Unit level, including concurrent settlement without deadlock |

A passing run only says that these interleavings were fine. The argument that no deadlock or race exists is the design
(lock order company, then trader locks by ascending id; reads through an immutable volatile snapshot); the tests make a
violation very likely to show. See `docs/report.md` section 5 for the mutation check (the tests do fail when a lock is removed).

## 2. Program 2: faulty solution 1, data races

```
java -cp target/classes com.stockexchange.faulty.RaceDemo 3 5000
```

Arguments: rounds (default 3), operations per trader (default 5000). Every round runs the **same seeded workload** (20 threads, one trader each) and checks at rest. All rounds on the correct
`StockExchange` run first, then all rounds on `RacyExchange`: a hung racy run leaves threads spinning forever, which would
steal CPU from any run after it. A racy round takes up to 15 s when it hangs (see below).

Expected output (numbers vary, the shape does not):

```
##### correct exchange #####
--- round 1 ---
StockExchange (correct): 100000 operations, 50665 trades
  all invariants hold
##### racy exchange #####
--- round 1 ---
RacyExchange (faulty): 30932 operations, 13943 trades
  HUNG: workers did not finish in time
  exception ConcurrentModificationException x1379
  exception IllegalArgumentException x3967
  VIOLATION cash not conserved: 2000049030 now, 2000000000 at start (49030 cents)
  VIOLATION ACME shares not conserved: 4991 now, 5000 at start (-9)
  VIOLATION order id 2 handed to two different orders
```

How to read it:

| Line | Meaning | Bug (see `faulty-data-races.md`) |
|---|---|---|
| `all invariants hold` for the correct exchange, every round | The harness is not just complaining about everything; the same checks pass on correct code | n/a |
| `cash not conserved ... (+49030 cents)` | A lost update on a balance created money | #2, #1 |
| `<C> shares not conserved (-9)` | Shares vanished or appeared | #2, #3 |
| `order id N handed to two different orders` | Two threads drew the same id from a plain `long++` | #6 |
| `ConcurrentModificationException`, `NullPointerException`, `NoSuchElementException` | A non-thread-safe collection was read while written | #3, #5, #7, #9, #10 |
| `IllegalArgumentException` from `settle` (`qty=0`) | The same order was filled twice | #5 |
| `HUNG` | The red-black tree of the order book was corrupted and a thread loops forever (`jstack` shows `RUNNABLE` in `TreeMap.fixAfterDeletion`) | #5 |
| `operations` lower than 100000 | Workers died from the exceptions or hung | |

The proof that the **harness is fair**: it uses only the public `Exchange` API, so the same code judges both. The proof that the
**fixes work** is the correct exchange passing in the same rounds, with the same workload. Races are probabilistic; if a round is
clean, run again (`RacyExchangeTest` allows 5 rounds).

The same racy exchange inside the real simulation: it dies within a second with `IllegalArgumentException: cannot settle Trade[... qty=0 ...]`:

```
java -cp target/classes com.stockexchange.sim.Main --seed 42 --seconds 5 --pause-ms 1 --exchange-class com.stockexchange.faulty.RacyExchange
```

The primitives one at a time (lost update, check-then-act, visibility):

```
java -cp target/classes com.stockexchange.faulty.PrimitiveDemos
```

```
--- 1. lost update, expected 4000000 ---
plain long           : 986201      <- wrong
volatile long        : 952110      <- still wrong: ++ is read, add, write
synchronized         : 4000000     <- right (also ReentrantLock, AtomicLong)
--- 2. check-then-act ---
containsKey + put (HashMap)       : 153532 'wins', ideal is 100000
putIfAbsent (ConcurrentHashMap)   : 100000
--- 3. visibility ---
plain boolean   : reader STILL SPINNING after 3 s     (JIT-dependent)
volatile boolean: reader stopped
```

Proof reading: the expected value is known in advance (8 threads x 500,000 = 4,000,000), so any other number is a bug, and
only the lines using a correct primitive hit it exactly.

## 3. Program 3: faulty solution 2, deadlock, livelock, performance

```
java -cp target/classes com.stockexchange.faulty.LivenessDemo all
```

or one scenario: `lock-order`, `callback`, `livelock`, `granularity`, `busy-wait`. About a minute for `all`.

| Scenario | What to look for | What it proves |
|---|---|---|
| `lock-order` (L1) | `DEADLOCK after N trades` and two threads, each `WAITING` for a lock `held by` the other | The JVM itself (`ThreadMXBean.findDeadlockedThreads`) found a cycle. `N` is how few trades it takes |
| `callback` (L2) | The same, but the locks are `FairSync` (the company locks), where L1 shows `NonfairSync` (the trader locks) | A listener called under a lock closes a cycle between two companies |
| `livelock` (L3) | `ORDERED` settles about 100,000 trades in 3 s; `TRY_LOCK_RETRY` settles a few hundred with hundreds of thousands of `retries`. `findDeadlockedThreads` finds nothing, because nobody is blocked | Busy and not progressing: compare the **progress counter** with the **work counter** |
| `granularity` (P1) | Table of orders per second against threads: `global lock` stays flat (about 1 million), `per-company` rises (about 2.3 million) | One lock serializes independent companies. Absolute numbers depend on the machine; the shape is the claim |
| `busy-wait` (P2) | Spinning consumer CPU time equals its wall time (2,000 ms CPU in 2,001 ms wall: a whole core); blocking consumer about 0 to 15 ms | Waiting by polling burns a core |

The proof that the fixes work is in the same output: L3 prints the correct `ORDERED` strategy's progress next to the faulty
one, P1 prints the per-company column next to the global one, and P2 prints the blocking consumer next to the spinning one.
For the deadlocks, the `ORDERED` baseline of the livelock scenario runs the same two-thread workload as L1 (same traders, same
two companies, lower id first): about 100,000 trades in 3 s and no deadlock.

The printed thread lines are what `jstack <pid>` or `jcmd <pid> Thread.print` would show for a real hang (it ends with
`Found one Java-level deadlock:`); the demo collects them itself and exits.

## 4. Quick checklist before sending the project

```
mvn -q clean test                                               # 171 tests, 0 failures
java -cp target/classes com.stockexchange.sim.Main --seed 42 --seconds 10 --pause-ms 10     # RESULT: PASS
java -cp target/classes com.stockexchange.faulty.RaceDemo 2 3000                              # correct clean, racy broken
java -cp target/classes com.stockexchange.faulty.LivenessDemo all                             # deadlock, livelock, slowdown
```

Documents to read, in order: [`requirements.md`](requirements.md) (what was asked and where it is), [`report.md`](report.md)
(architecture, issue/mitigation table, tests), [`faulty-data-races.md`](faulty-data-races.md),
[`faulty-deadlock-livelock.md`](faulty-deadlock-livelock.md) (bug catalogues and exam questions), [`glossary.md`](glossary.md).

To send the project as an archive of the committed files: `git archive -o stock-exchange.zip HEAD`.

## 5. Notes and limits

- The faulty demos are probabilistic by nature. A clean racy round or a missed deadlock within the time limit is possible; run again.
- Do not use `RacyExchange` or the `Faulty*` classes outside the demos. They are wrong on purpose.
- The summary of Program 1 checks safety invariants through the public API; it cannot prove the absence of every race. The argument for that is the lock design in `report.md`.
- The `faulty` demos leave daemon threads stuck (deadlock, livelock) until the JVM exits; the demos call `System.exit` at the end.

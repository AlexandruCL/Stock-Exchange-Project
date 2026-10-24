# Assignment coverage and concurrency summary

What the assignment asks, where it is implemented, and the vocabulary to explain it. For the full
architecture and test evidence see [`report.md`](report.md).

## 1. Topic: stock exchange (simulation variant)

We chose the **simulation** option: sellers and buyers are threads (`sim.Trader`, one per trader, 20 by default)
working on one shared in-process exchange. There is no client/server variant.

| Requirement | Where | Notes |
|---|---|---|
| Shares of several listed companies | `model.SeedData` (5 companies), one `MatchingEngine` per company | |
| Sellers offer shares at a price, buyers request shares at a price | `Exchange.place(requestId, trader, company, side, price, qty)`; `Side.ASK` / `Side.BID` | Prices in cents (`long`) |
| Match when prices cross, trade `n = min(offer, request)` | `MatchingEngine.matchLoop` | Bid price >= ask price; the rest stays in the book; trade price is the older order's price |
| Everyone sees offers, requests and the history | `Exchange.book(company)` (immutable `BookSnapshot`), `Exchange.history(company)`, `Exchange.subscribe(listener)` | Reads take no company lock |
| Modify while no transaction is running | `Exchange.modify(...)`, `MatchingEngine.modify` | A trade is the time the engine holds the company lock; modify and cancel take the same lock, so they wait and then see the final state (OPEN, DONE). `ORDER_IN_TRADE` exists as a reject code but cannot reach a caller. `expectedVersion` rejects stale edits (`VERSION_CONFLICT`) |
| Traders decide from common information | `sim.RandomTradingStrategy` reads books and places, modifies, cancels | Seeded: same seed, same orders per trader |
| Money must be correct | `AccountService` reserves cash/shares at placement and settles at trade | Total cash and total shares per company never change (checked by `InvariantChecker`) |

Run it: `mvn exec:java -Dexec.args="--seed 42 --seconds 30"`. Test it: `mvn test` (the cross-cutting suite in
`src/test/java/com/stockexchange/verify` runs against the real `StockExchange`).

## 2. Faulty solutions (second task)

Two intentionally broken variants in package `com.stockexchange.faulty` (written on the branches
`faulty/data-races` and `faulty/deadlock-livelock`, now merged). Nothing in the correct code depends on them.

| Variant | Focus | Document | Program |
|---|---|---|---|
| Data races | Missing locks, lost updates, check-then-act, unsafe publication, non-thread-safe collections | [`faulty-data-races.md`](faulty-data-races.md) | `RaceDemo`, `PrimitiveDemos`, `RacyExchange` |
| Deadlock, livelock, performance | Inconsistent lock order, callback under a lock, `tryLock` without backoff, global lock, busy waiting | [`faulty-deadlock-livelock.md`](faulty-deadlock-livelock.md) | `LivenessDemo` |

How to run each and what the output proves: [`running-and-analysis.md`](running-and-analysis.md).

## 3. Concurrency design on `main` in one table

| Problem | Mechanism | Class |
|---|---|---|
| Two threads change one order book | One fair `ReentrantLock` per company around submit, modify, cancel and the whole match loop | `MatchingEngine` |
| Two companies settle on the same trader | One `ReentrantLock` per trader; settle takes both, lower id first | `AccountService` |
| Deadlock | Total lock order: company lock, then trader locks by ascending id; never two company locks; never call out of the engine under a lock | `MatchingEngine`, `AccountService` |
| Check-then-act on cash and shares | Check and reserve in one critical section | `AccountService.reserveCash/reserveShares` |
| Unique, ordered numbers | `AtomicLong` (CAS) for order ids, per-company priority `seq`, trade ids and the global event number | `MatchingEngine`, `StockExchange`, `EventBus` |
| Readers must not block traders or see half a change | Immutable `BookSnapshot` rebuilt after the change and swapped through a `volatile` field; `history` is a `CopyOnWriteArrayList` | `MatchingEngine.publishBook` |
| Retried requests must not create two orders | `ConcurrentHashMap.putIfAbsent` on the `requestId`, a `CompletableFuture` for a concurrent duplicate | `StockExchange.place` |
| Listeners must not slow or block trading | Events collected under the lock, published after unlock to a `LinkedBlockingQueue`; one dispatcher thread re-sorts them by `globalSeq` | `EventBus` |
| Starvation | Fair company locks, price-time priority | `MatchingEngine`, `OrderBook` |
| Thread leaks, clean stop | Interrupt and join traders, poison pill for the dispatcher, join with timeout | `Simulation`, `EventBus.close` |
| Busy waiting | Blocking queue (`take()`), `sleep` between orders, scheduled printer | `EventBus`, `Trader`, `ConsoleView` |

## 4. Vocabulary

- **Data race**: two threads touch one variable, one writes, nothing orders them. **Race condition**: the result depends on timing (a data-race-free program can still have one, as in check-then-act).
- **Atomicity / visibility / ordering**: what a lock gives; `volatile` gives visibility and ordering only; `AtomicLong` gives atomic single-variable updates.
- **Happens-before**: unlock before the next lock of the same lock; volatile write before the next read; `Thread.start`/`join`; `final` fields after construction.
- **Deadlock** (needs mutual exclusion, hold and wait, no preemption, circular wait), **livelock** (busy, no progress), **starvation** (one thread never gets the resource).
- **Safe publication / immutability**: records and snapshots are never changed after they are shared, so readers need no lock.
- **Lock granularity**: coarse (simple, serial) against fine (parallel, needs a lock order). One lock per company is the middle.

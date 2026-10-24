# Faulty solution 1: data races and data consistency

Written on branch `faulty/data-races`, now part of `main`. **Intentionally broken, never use it as an `Exchange`
in real code.** The correct solution is untouched; everything here lives in `com.stockexchange.faulty` (and one test).
Second faulty solution (deadlock, livelock, performance): [`faulty-deadlock-livelock.md`](faulty-deadlock-livelock.md).
How to run and read all programs: [`running-and-analysis.md`](running-and-analysis.md).

The idea: take the correct classes, remove exactly the thread-safety, and keep every other line identical. Then a
diff against `main` is the answer to "what does the lock protect?".

| Faulty class | Correct counterpart on `main` | What was removed |
|---|---|---|
| `RacyAccounts` | `engine.AccountService` | Trader locks (`Portfolio.lock`) |
| `RacyMatchingEngine` | `engine.MatchingEngine` | Company lock, `AtomicLong`s, `CopyOnWriteArrayList`, `volatile` snapshot |
| `RacyExchange` | `engine.StockExchange` | `ConcurrentHashMap` + `putIfAbsent`, `EventBus` ordering |
| `RaceHarness`, `RaceDemo` | `StressTest` + `InvariantChecker` | (judge, works on any `Exchange`) |
| `PrimitiveDemos` | | The same bugs in 20 lines each, with the fix next to them |

## 1. Run it

```
mvn -q compile
java -cp target/classes com.stockexchange.faulty.RaceDemo 3 5000      # 3 rounds, 5000 ops per trader
java -cp target/classes com.stockexchange.faulty.PrimitiveDemos       # lost update, check-then-act, visibility
java -cp target/classes com.stockexchange.sim.Main --seed 42 --seconds 5 --pause-ms 1 \
     --exchange-class com.stockexchange.faulty.RacyExchange           # the real simulation on the broken exchange
mvn test                                                              # RacyExchangeTest proves the branch is faulty
```

`RaceDemo` runs 20 trader threads (one per trader of `SeedData`) with the same seeded workload, first for all rounds
against the correct `StockExchange`, then for all rounds against `RacyExchange` (a hung racy round leaves spinning threads
that would slow any run after it), and checks at rest what must always hold. Output of a real
run (numbers differ every run, because races are probabilistic):

```
StockExchange (correct): 100000 operations, 50665 trades
  all invariants hold
RacyExchange (faulty): 30932 operations, 13943 trades
  HUNG: workers did not finish in time
  exception ConcurrentModificationException x1379
  exception IllegalArgumentException x3967
  exception IllegalStateException x24
  exception NoSuchElementException x39
  exception NullPointerException x9
  VIOLATION cash not conserved: 2000049030 now, 2000000000 at start (49030 cents)
  VIOLATION ACME shares not conserved: 4991 now, 5000 at start (-9)
  VIOLATION order id 2 handed to two different orders
  ... and 121 more violations
```

The simulation on the racy exchange dies within a second with
`cannot settle Trade[... qty=0 ...]`: one thread filled an order to zero while another thread, in the same
company, still saw it in the book and traded it again with quantity 0 (bug #5).

## 2. Bug catalogue

| # | Where | Class of bug | Symptom seen | Fix on `main` | Primitive |
|---|---|---|---|---|---|
| 1 | `RacyAccounts.reserveCash`, `reserveShares` | Check-then-act | Two orders both pass `cash >= amount`; more reserved than owned, negative cash | `AccountService.reserveCash`: check and update inside `p.lock` | `ReentrantLock` (mutual exclusion) |
| 2 | `RacyAccounts.settle` | Lost update (read-modify-write) | `cash not conserved` (49,030 cents appeared), shares appear/disappear | `AccountService.settle`: both trader locks, lower id first | `ReentrantLock`, lock ordering |
| 3 | `RacyAccounts.Portfolio` | Non-thread-safe collection (`HashMap.merge`) | Share totals off by a few units per company | Same locks as 1 and 2 guard the maps | Confinement to a lock |
| 4 | `RacyAccounts.view` | Torn read, unsafe iteration | `ConcurrentModificationException` copying the maps | `AccountService.view` copies under the lock | Same lock for readers and writers |
| 5 | `RacyMatchingEngine` (no lock) | Missing mutual exclusion on compound state | Double fill, `qty=0` trades, `NoSuchElementException`, NPE, crossed books, **infinite loop** (see below) | `MatchingEngine`: whole submit/modify/cancel/match under one `ReentrantLock` | Mutual exclusion per company |
| 6 | `RacyMatchingEngine.seq`, `tradeIds`; `RacyExchange.orderIds`, `eventSeq` | Non-atomic increment | `order id 2 handed to two different orders`, duplicate trade ids, two orders with the same priority `seq` | `AtomicLong.incrementAndGet()` | CAS (atomic variable) |
| 7 | `RacyMatchingEngine.history`, `snapshot` | Unsafe publication, no happens-before | `null` or lost entries in history; a reader may keep an old snapshot | `CopyOnWriteArrayList`, `volatile BookSnapshot` | `volatile`, safe publication of an immutable record |
| 8 | `RacyMatchingEngine.checkOpenOwned` | State machine visible and not atomic | `IN_TRADE` is seen by other threads; modify acts on an order that was just filled | Lock held for the whole trade, so `IN_TRADE` is never observable | Mutual exclusion |
| 9 | `RacyExchange.orderCompany`, `requests` | Non-thread-safe `HashMap` | Own orders answered `NOT_FOUND` (entry lost) | `ConcurrentHashMap` | Concurrent collection |
| 10 | `RacyExchange.execute`, `listeners` | Iteration while modified; no event ordering | `ConcurrentModificationException`; listeners see `globalSeq` out of order | `EventBus`: `CopyOnWriteArrayList`, one dispatcher thread, re-sorting by `globalSeq` | Single consumer + concurrent collection |
| 11 | `RacyExchange.place` | Check-then-act on the idempotency key | One `requestId` creates two orders | `StockExchange.place`: `requests.putIfAbsent(...)` | Atomic compound operation of `ConcurrentHashMap` |

### Why the lock is needed in two places (bugs 1 to 3 vs 5)

`MatchingEngine` locks per company, so two threads can settle trades of **different** companies at the same
instant, and both touch the same trader's cash. The company lock alone cannot protect `Portfolio`. That is why the
correct design has a second level, the trader locks. `RacyAccounts` removes only that level (bugs 1 to 4) and `RacyMatchingEngine` removes only the first (bugs 5 to 8):
each level protects different data, so the correct design needs both.

### The infinite loop (bug 5, a data race can look like a deadlock)

`OrderBook` uses `TreeSet` (a red-black tree). Two threads inserting and removing at once corrupt the tree
pointers. A `jstack` of the hung run shows every worker `RUNNABLE` (not `BLOCKED`) inside
`TreeMap.fixAfterDeletion` or `OrderBook.bestBid`: the tree contains a cycle and the loop never ends. So there
is no lock and no deadlock; the CPU is busy forever. That is the difference to the second branch: a data race
corrupts state silently, a deadlock stops threads that are `BLOCKED`/`WAITING`.

### What is deliberately **not** a bug

- `RacyAccounts.portfolios` is a plain `HashMap`, but it is filled in the constructor and never changed. After the
  constructor returns, `final` field semantics make the filled map safely visible, and concurrent read-only access
  is fine. Immutability is the cheapest synchronization.
- `OrderBook` is not thread-safe on `main` either. It is correct there because it is only touched under the company
  lock. Thread safety is a property of how a class is used, not only of the class.
- `Order` objects, `Trade`, `OrderView` and the other records: records are immutable, so sharing them is safe.

## 3. Concepts to be able to explain

**Data race vs race condition.** A *data race*: two threads access the same variable, at least one writes, and
nothing orders them (no happens-before). A *race condition*: the result depends on timing. Bug 1 is a race
condition even if every access were atomic (check, then act, is two steps). Bug 6 is a data race. You can have one
without the other.

**Three things a lock or atomic gives you:** atomicity (nobody sees half of a compound action), visibility (a
write before `unlock` is seen by the next `lock`) and ordering (no reordering across the barrier). `volatile` gives
visibility and ordering for a single variable, but **not** atomicity: `PrimitiveDemos` shows `volatile long++`
losing updates just like a plain `long`.

**Happens-before edges used on `main`:** `unlock` then `lock` of the same lock; write of a `volatile` field then
read of it (the `snapshot`); `ConcurrentHashMap` operations; `Thread.start`/`join`; `final` fields after
construction.

**Check-then-act** (bugs 1, 8, 11): the fix is never "check faster", it is "make check and act one atomic step",
by a lock, a CAS, or an atomic collection method (`putIfAbsent`, `merge`, `compute`).

**Lost update** (bugs 2, 6): `x += n` is `tmp = x; tmp += n; x = tmp`. Fix: lock, `AtomicLong`, or `LongAdder` when
many threads only add and rarely read.

**Safe publication** (bug 7): an object is safely visible to another thread if it is published through a
`volatile`/`final` field, a lock, or a concurrent collection, and it is effectively immutable afterwards. The
snapshot is a record rebuilt from scratch and swapped through one `volatile` reference, so readers never take a lock
and never see a half-built book.

**Why the fine-grained design still works:** invariant "total cash is constant" is a property of *pairs* of
portfolios, so every change that touches two portfolios (settle) must hold both locks at once, and every method
that sums (`totalCash`) must hold all of them. Lock order (lower trader id first) is what stops this from
deadlocking, which is the subject of the other branch.

## 4. Questions to prepare

1. *Why is `volatile` not enough for `seq++`?* Increment is read-modify-write; `volatile` only guarantees each read
   sees the latest write, not that nothing happens between the read and the write. Use `AtomicLong`.
2. *Why `putIfAbsent` and not `containsKey` + `put` on a `ConcurrentHashMap`?* Each call is atomic, the pair is not.
   `PrimitiveDemos` part 2: 153,532 "wins" for 100,000 keys with `HashMap`, exactly 100,000 with `putIfAbsent`.
3. *Can a read-only method be racy?* Yes: `view` (bug 4) and `history()` iterate structures while writers change
   them. Readers need the same lock, or an immutable snapshot, or a copy-on-write structure.
4. *How can a data race hang the program?* Corrupted `TreeMap` (above); also `HashMap` resize corruption on older
   JDKs. It is not a deadlock: threads are `RUNNABLE`.
5. *Why not simply `synchronized` everywhere?* Correct but serializes all companies; that cost is demonstrated on
   branch `faulty/deadlock-livelock` (global-lock performance demo).
6. *Why can the tests not prove there is no race?* A passing run only says this interleaving was fine. The harness
   repeats thousands of operations with 20 threads to make a bug very likely, and the invariants (conservation,
   uniqueness, no negative balances) are checked at rest. Absence of a race comes from the design (lock hierarchy),
   not from tests.
7. *What does the fair lock (`ReentrantLock(true)`) cost and buy?* Buys FIFO hand-over (no starvation of the
   thread that submitted first, which matches price-time priority); costs throughput, because a releasing thread
   must hand the lock to the next waiter instead of letting a running thread barge in.

## 5. Honest limits of the demos

- Races are probabilistic. A round can be clean; `RaceDemo` takes several rounds and `RacyExchangeTest` allows 5.
- The visibility part of `PrimitiveDemos` depends on the JIT (a loop that reads a plain field may be compiled to
  read it once). On the machine it was written on, the reader never stopped; another JVM may behave differently.
  That is exactly why "it works on my machine" is no proof.
- Bug 4 (torn read) is described but not measured separately: the harness counts it only through exceptions.
- The faulty branch passes the 160+ existing tests because they use `StockExchange` and test stubs, not the racy classes.

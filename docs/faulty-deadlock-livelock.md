# Faulty solution 2: deadlock, livelock and performance

Written on branch `faulty/deadlock-livelock`, now part of `main`. **Intentionally broken.** First faulty solution
(data races and consistency): [`faulty-data-races.md`](faulty-data-races.md). How to run and read all programs:
[`running-and-analysis.md`](running-and-analysis.md).

In the first faulty solution the answers come out *wrong*. Here the arithmetic stays right (a single-thread test checks every strategy settles correctly);
what goes wrong is **liveness**: threads stop making progress (deadlock), keep running without making progress
(livelock), or make progress much slower than they should (performance).

Everything lives in `com.stockexchange.faulty`. The faulty classes reuse the real `MatchingEngine` and
`AccountService` code wherever possible, so a diff against `main` shows exactly the changed lines.

| Class | Bug | Correct counterpart on `main` |
|---|---|---|
| `FaultyAccounts` with `LockStrategy.UNORDERED` | **L1** deadlock: inconsistent lock order | `AccountService.settle` (lower trader id first) |
| `FaultyAccounts` with an `onSettled` callback | **L2** deadlock: alien call under the company lock | `MatchingEngine` returns events, `StockExchange.execute` publishes them after unlocking |
| `FaultyAccounts` with `LockStrategy.TRY_LOCK_RETRY` | **L3** livelock: `tryLock` and retry at once | Lock order, or random backoff |
| `CoarseLockEngines` | **P1** performance: one global lock | One `ReentrantLock` per company, lock-free reads |
| `SpinningConsumer(spin=true)` | **P2** performance: busy waiting | `EventBus` dispatcher (`LinkedBlockingQueue.take()`) |
| `LivenessScenarios`, `LivenessDemo` | The experiments and their printout | |

## 1. Run it

```
mvn -q compile
java -cp target/classes com.stockexchange.faulty.LivenessDemo all        # or: lock-order | callback | livelock | granularity | busy-wait
mvn test                                                                  # LivenessScenariosTest proves the bugs are real
```

Real output of one run (numbers differ per run and machine):

```
=== L1: deadlock, inconsistent lock order (buyer first, not lower id first) ===
DEADLOCK after 339 trades. ThreadMXBean.findDeadlockedThreads():
  lock-order-pingpong-ACME-1-2 is WAITING, waits for ReentrantLock$NonfairSync@68de145 held by lock-order-pingpong-BETA-1-2
  lock-order-pingpong-BETA-1-2 is WAITING, waits for ReentrantLock$NonfairSync@306a30c7 held by lock-order-pingpong-ACME-1-2
=== L2: deadlock, listener called while the company lock is held ===
DEADLOCK after 0 trades. ThreadMXBean.findDeadlockedThreads():
  callback-pingpong-ACME-3-4 is WAITING, waits for ReentrantLock$FairSync@2b71fc7e held by callback-pingpong-BETA-5-6
  callback-pingpong-BETA-5-6 is WAITING, waits for ReentrantLock$FairSync@27fa135a held by callback-pingpong-ACME-3-4
=== L3: livelock, tryLock and immediate retry (3 s each) ===
ORDERED (correct):  101529 trades settled
TRY_LOCK_RETRY:     249 trades settled, 297704 retries (every retry is a lock taken and given back)
=== P1: one global lock vs one lock per company (orders/second, 15 rounds of 4000 orders per thread, best of 3) ===
threads   global lock   per-company
      1        948157       1417370
      2        876602       2347771
      3       1110844       1972987
      4        965785       2418998
      5       1089228       2227173
=== P2: busy waiting vs blocking queue (CPU time of the idle consumer) ===
spinning consumer: 2000 ms CPU in 2001 ms wall
blocking consumer: 0 ms CPU in 2012 ms wall
```

Notice the `NonfairSync` in L1 (trader locks, plain `ReentrantLock()`) against `FairSync` in L2 (company locks,
`ReentrantLock(true)`): the two deadlocks are on different lock levels.

## 2. L1: deadlock from inconsistent lock order

**Where:** `FaultyAccounts.settle` with `UNORDERED`; `firstToLock` returns the buyer, whoever it is.
**Setup (`LivenessScenarios.lockOrderDeadlock`):** thread X trades traders 1 and 2 in ACME, thread Y trades the
same two traders in BETA, with the direction alternating. Sometimes X settles (buyer 2, seller 1) while Y settles
(buyer 1, seller 2):

```
X (ACME):  lock(trader 2)  ... lock(trader 1)
Y (BETA):  lock(trader 1)  ... lock(trader 2)         -> circular wait
```

The two company locks do not prevent it: ACME and BETA are different locks, by design (that is the parallelism).
No sleep or other help is needed; the window is only a few instructions wide, but in a loop it came after 11 to 339 trades in our runs.

**Fix on `main`:** `AccountService.settle` takes the lower trader id first. Every thread then acquires locks in
the same global order, so a cycle cannot exist. The same rule appears in `sumOverAllTraders` (ids ascending).

### The four Coffman conditions (all four are needed for a deadlock)

| Condition | In L1 | Break it by |
|---|---|---|
| Mutual exclusion | A trader lock is held by one thread | (not wanted: that is what makes balances correct) |
| Hold and wait | X holds lock 2 while waiting for lock 1 | Take all locks at once, or `tryLock` and release |
| No preemption | A `ReentrantLock` cannot be taken away | `tryLock(timeout)` and give up |
| Circular wait | X waits for Y, Y waits for X | **A global lock order** (the fix used here) |

Lock ordering is the cheapest fix, it costs nothing at run time. `tryLock` breaks hold-and-wait but creates the next
bug (L3).

## 3. L2: deadlock from an alien call under a lock

**Where:** `FaultyAccounts.afterSettle` calls `onSettled`, and `settle` is called by `MatchingEngine.matchLoop`
while the company lock is held. The `Accounts` interface javadoc forbids exactly this ("must not print, log or call
listeners"). **Setup (`callbackDeadlock`):** the listener is a hedger: after a trade in ACME it books a small order
in BETA and the other way round. The trader locks are ordered correctly here, so L1 is not involved.

```
X: holds company lock ACME  -> settle -> listener -> wants company lock BETA
Y: holds company lock BETA  -> settle -> listener -> wants company lock ACME
```

It deadlocks on the first trade. **Why it is dangerous:** the code that calls the listener looks innocent; the cycle
only exists because a *foreign* piece of code (written by someone else, later) was invoked while a lock was held.
You cannot reason about lock order if you do not know what you call.

**Fix on `main`:** the engine never calls out. `MatchingEngine.submit` collects events in a list and returns them
in `MatchResult`; `StockExchange.execute` publishes them to the `EventBus` after the engine lock was released; the
bus has its own dispatcher thread, so listeners never run on a trading thread at all. This is the "no I/O under
lock" rule of the README.

## 4. L3: livelock from `tryLock` without backoff

**Where:** `FaultyAccounts.settleWithTryLock`. It uses the same buyer-first order as L1, but instead of blocking
it does: take the first lock, `tryLock` the second, if that fails release the first and start over **immediately**.
Nobody blocks, so there is no deadlock, and `ThreadMXBean.findDeadlockedThreads()` returns `null`. The threads are
`RUNNABLE` and burning CPU, yet each one keeps taking its first lock exactly when the other one holds the second.

Measured: 249 trades in 3 s with 297,704 retries, against 101,529 trades for the ordered version. That is a
*near*-livelock: progress is not exactly zero because the two threads occasionally fall out of step by chance. A
perfect livelock needs perfectly symmetric threads. In production code, "only a few trades per second and a core at
100%" is what it looks like.

`holdFirstLockNanos` (20 µs of spinning after the first lock in this demo) represents work a real settle would do
there (audit record, validation). It widens the window, it does not create the bug.

**Fixes:** (1) a global lock order, as in L1, then `tryLock` is not needed; (2) if `tryLock` is needed, back off by a
**random** time that grows after each failure (exponential backoff with jitter), so symmetry is broken; (3) limit
retries and report a failure.

### Deadlock vs livelock vs starvation

| | Threads | CPU | Progress | Detected by |
|---|---|---|---|---|
| Deadlock | `BLOCKED`/`WAITING` in a cycle | idle | none | `jstack`, `ThreadMXBean.findDeadlockedThreads()` |
| Livelock | `RUNNABLE`, reacting to each other | busy | none (or almost) | Not by the JVM: look at a progress counter (here: trades per second vs retries) |
| Starvation | One thread waits forever while others run | busy | the others progress | Metrics per thread. Fair lock (`new ReentrantLock(true)`) on `main` is the answer for company locks |

A data race that corrupts a `TreeMap` (other branch) is a fourth thing: threads `RUNNABLE` and spinning, but there
is no lock and no reaction between threads.

## 5. P1: one global lock

**Where:** `CoarseLockEngines`: the real `MatchingEngine`s behind one `synchronized`, reads included. Nothing is
ever wrong, but ACME's traders wait while BETA settles a trade that has nothing to do with them.

Result (table above): with 1 thread it is the same speed; global lock stays at about 1 million orders per second
no matter how many threads, while per-company locks go up to about 2.3 million. Honest reading: not 5x, because
threads are started per round, memory allocation and 8 logical cores are shared, and the per-order work is only a few
microseconds. The direction, flat against rising, is the point. The more work per order, or the more threads, the
bigger the gap. This is Amdahl's law: the part under the global lock is serial.

Rules that `main` follows to avoid it: lock per company (`MatchingEngine`), trader locks only for the short balance
update, readers never lock (`snapshot` is an immutable record behind a `volatile` field), events published
outside the lock.

**Not a bug but a trade-off to be able to explain:** `main` uses `ReentrantLock(true)` (fair). Fair locks are a bit
slower under contention (the releasing thread must hand over to the longest waiter, which costs a park/unpark) but give
FIFO order, which matches price-time priority and prevents starvation. Trader locks are unfair because ordering
there does not matter. Another known cost on `main`: `history` is a `CopyOnWriteArrayList`, so every trade copies the
whole array (O(n)). It is simple and gives lock-free consistent reads, and is fine at this scale; a long-running
real exchange would use a chunked append-only log. That cost is also why `granularity` runs many short rounds on fresh
engines instead of one long run: in a long run the benchmark would measure array copying, not locking.

## 6. P2: busy waiting

**Where:** `SpinningConsumer(true)` waits for events with `while (queue.poll() == null) { }`. Result: the idle
consumer used 2,000 ms of CPU in a 2,001 ms run (one full core just to wait), the blocking one about 0 to 15 ms. Same events, same order.

**Fix on `main`:** `EventBus` uses `LinkedBlockingQueue.take()`: the thread is parked by the OS and woken when an
event arrives; `ConsoleView` uses a `ScheduledExecutorService`; `Trader` sleeps between orders. Spin-waiting is only
justified when the wait is shorter than a context switch (nanoseconds), and then with `Thread.onSpinWait()`.

## 7. Questions to prepare

1. *Name the four conditions of a deadlock and which one you break.* Table in section 2. We break circular wait with a
   global order (company lock before trader locks, lower trader id first, never two company locks).
2. *Why is the order "lower trader id first" enough for two traders but you also need "company before trader"?* Lock
   levels: any thread holding a trader lock only waits for a trader lock with a higher id, never for a company lock,
   so the waits-for graph always points "up" and cannot close into a cycle.
3. *Your tests passed on `main`; how do you know there is no deadlock?* From the design (a total order), plus
   `@Timeout` on every concurrent test and `StressTest` with 50 threads. Tests cannot prove absence; L1 shows how few trades
   it takes when the order is wrong, so a long stress run is a meaningful check, but the proof is the lock order.
4. *Is `tryLock` a fix for deadlock?* It removes hold-and-wait/no-preemption, so no deadlock, but without randomised
   backoff it can livelock (L3).
5. *Why did you not call listeners from the engine?* L2. Also: a slow or throwing listener would stall every other
   trader waiting for that company lock.
6. *How do you diagnose a hung program?* `jstack <pid>` or `jcmd <pid> Thread.print`: `BLOCKED`/`WAITING` and a
   "Found one Java-level deadlock" block means deadlock; `RUNNABLE` threads with a high CPU and no progress means
   livelock or a spin.
7. *Would `synchronized` methods on `MatchingEngine` and `AccountService` be correct?* Correct, but `synchronized`
   cannot be fair, cannot `tryLock`/time out, and cannot be interrupted while waiting. Per-object monitors in
   `AccountService.settle` would also have to be nested in id order, so nothing gets simpler.
8. *When is a global lock the right answer?* When the critical section is tiny, contention is low, and simplicity wins; or
   as a first version before measuring.

## 8. Honest limits

- Deadlock detection depends on timing; `LivenessScenariosTest` allows 30 s, and in practice it takes milliseconds.
- The livelock demo is a near-livelock (see section 4), and its retry count depends on the machine.
- P1 numbers are noisy and machine dependent; only the shape (flat vs rising) is the claim.
- The faulty classes are only meant to be used by the demos. They are not selectable as an `Exchange`.
- Surefire runs the whole suite in one JVM; the deadlocked daemon threads from the two deadlock tests stay stuck
  until it exits. They hold only objects local to those tests.

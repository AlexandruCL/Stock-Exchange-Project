# Simulation (Capa)

Implemented in `com.stockexchange.sim`, using Java 17 and the existing `Exchange` contract.

## Run with the available stub

```sh
mvn test
mvn test-compile exec:java -Dexec.classpathScope=test -Dexec.args="--seed 42 --seconds 5 --pause-ms 100 --exchange-class com.stockexchange.engine.StubExchange"
```

In PowerShell, quote Maven properties:

```powershell
mvn test-compile exec:java '-Dexec.classpathScope=test' '-Dexec.args=--seed 42 --seconds 5 --pause-ms 100 --exchange-class com.stockexchange.engine.StubExchange'
```

The stub accepts orders but does not match, reserve balances, or emit events. This run
exercises trader actions and shutdown; it does not demonstrate real trades or validate
the production engine's accounting invariants.

## Production integration

`Main` defaults to `com.stockexchange.engine.StockExchange`. It loads an implementation
of `Exchange` with a public constructor taking `SeedData`, or a public no-argument
constructor. The latter must initialize the same seed market. `--exchange-class` selects
another implementation or an adapter if the engine's eventual constructor differs.
No production engine or console view is supplied by the simulation package.

For direct integration, construct `Simulation(exchange, seedData, seed, pauseMillis)`
and call `run(Duration.ofSeconds(30))` inside try-with-resources. The simulation owns
the exchange: it interrupts and joins traders before calling `Exchange.close()` once.
It is single-use. External `close()`, interruption, timeout, and worker failure all
trigger cleanup. Strategy and exchange calls must eventually return or respond to
interruption; shutdown waits for workers rather than closing beneath an active call.

## Strategies and reproducibility

Each trader owns a `RandomTradingStrategy` with a balanced (50% bids), buyer (75% bids),
or seller (25% bids) profile, assigned cyclically by trader ID. Every three action slots
place an order, modify the returned open order using its version, and cancel it.
Prices are 95–105% of the company's initial price, rounded down to positive integer
cents; quantities are 1–10 shares. Rejections are normal results, not thread failures.
The exchange remains responsible for atomic validation and cash/share reservations.

Companies are sorted and each trader has its own seeded random generator. For a given
seed and trader, generated placement parameters and random draws repeat independently
of thread scheduling. Actual order IDs, fills, accepted modifications, and the number
of actions within a wall-clock duration can differ with concurrent execution. Orders
already filled are not modified or cancelled when the placement response reports them
closed. Stale versions can be rejected; cancellation uses ownership and order ID.

`TradingStrategy` allows other policies to be injected. Supply a separate stateful
strategy per trader through the `Simulation` strategy factory. Strategies receive
`Exchange`, trader ID, action sequence, and the trader's random generator; they never
use mutable model `Order` instances. A strategy should not call `Simulation.close()`
from its worker thread; the runner owns shutdown.

## CLI

- `--seed`: signed long, default 42.
- `--seconds`: positive whole duration, default 30.
- `--pause-ms`: positive pause between actions, default 100.
- `--exchange-class`: implementation class described above.
- `--help`: print usage without requiring an engine.

Tests cover seeded actions, placement/modification/cancellation, lifecycle cleanup,
worker failures, interruption, CLI validation, and execution against the stub.

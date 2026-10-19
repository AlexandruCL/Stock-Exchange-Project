# Skeleton, model and contracts: status

Owner: Cristea. Branch: `cristea/skeleton`. State on 2026-10-19: 9 commits, local only, not pushed.

## What was done

This branch gives the team a compiling project and fixed contracts. It has no business logic.

| Task | Files | Checked by |
|---|---|---|
| Maven project | `pom.xml`, `.gitignore`, `package-info.java` in `book`, `sim`, `view` | `mvn compile` |
| Model | `Side`, `OrderState`, `Reject`, `OrderView`, `Trade`, `OrderResult`, `BookSnapshot`, `PortfolioView`, `Order`, `Company`, `Validation` | `RecordImmutabilityTest`, `OrderTest`, `ValidationTest` |
| Contracts | `model/ExchangeEvent.java`, `engine/Exchange.java` | Compiles; `StubExchange` implements it |
| Stub | `src/test/.../engine/StubExchange.java` | `StubExchangeTest` |
| Seed data | `model/SeedData.java`, `model/TraderAccount.java` | `SeedDataTest` |
| Conventions | `README.md`, `docs/glossary.md` | Totals in README match `SeedDataTest` |

Test result on a fresh clone of the branch: 30 tests, 0 failures. Each of the 9 commits compiles on its own.

### Decisions to know about

`OrderResult`, `BookSnapshot`, `PortfolioView` and `TraderAccount` copy their lists and maps when constructed (`List.copyOf`, `Map.copyOf`). Changing the list you passed in does not change the record, and the record's own lists throw on `add` or `put`. Lock-free readers depend on this.

`Order` is the only mutable model class. It has to be `public` because `book` and `engine` are different packages, so the "engine only" rule is in its Javadoc, not enforced by the compiler. Code outside `book` and `engine` uses `toView()`.

Two additions to the section 3 contracts, both recorded in the README:

- `Exchange extends AutoCloseable` with `void close()`. Capa's `Simulation` calls it at shutdown; Fabi's `StockExchange` implements it by draining and joining the event dispatcher.
- `TraderAccount(id, cash, shares)` is the starting-account type. Fabi's `AccountService` constructor takes `Collection<TraderAccount>` instead of `Collection<Trader>`, so it does not clash with Capa's `sim.Trader` thread class.

Seed values, all from constants in `SeedData`:

| Item | Value |
|---|---|
| Companies | ACME, BETA, CORE, DELT, EPSL (name = ticker) |
| Starting cash | 100,000,000 cents per trader, read from the spec's "1,000,000 cents x 100" |
| Total cash | 2,000,000,000 cents |
| Shares | 100 to 400 per trader per company, 5,000 per company in total |

`Validation.isValidId` checks for null only. A blank company id still fails later as `UNKNOWN_COMPANY`.

`StubExchange` never matches orders, never reserves cash or shares, and never sends events. Portfolios stay at their seed values. It is enough to compile against and to run trader threads, not to test trading.

## How to run it now

You need JDK 17 and Maven 3.9 or newer.

On your machine, from the project folder:

```
git checkout cristea/skeleton
mvn clean test
```

Expected result: `Tests run: 30, Failures: 0, Errors: 0`, spread over 5 test classes.

To prove it works without your local files, clone the branch into an empty folder. Before pushing, clone from the local repo:

```
git clone -b cristea/skeleton D:/Stock-Exchange-Project se-check
cd se-check
mvn clean test
```

After pushing, teammates use the GitHub URL:

```
git clone -b cristea/skeleton https://github.com/AlexandruCL/Stock-Exchange-Project.git
cd Stock-Exchange-Project
mvn clean test
```

`mvn exec:java` fails for now with a missing `com.stockexchange.sim.Main`. That class comes with Capa's `sim` package.

## Next steps

1. Push the branch: `git push -u origin cristea/skeleton`.
2. Open a pull request into `main` and merge it. After this merge, nobody pushes to `main` directly.
3. Post in the group chat: the contracts are on `main`, read `README.md`, and note the two additions (`close()`, `TraderAccount`). Wait for each member to confirm.
4. Each member branches from the updated `main`: `john/engine`, `fabi/accounts`, `capa/sim`, `abud/tests`.
5. Review John's and Fabi's pull requests when they open them. Both touch your contracts.

Optional before merging: `.gitignore` excludes `*.pdf` and `.claude/`. The spec PDFs and the Java skills therefore stay out of the repo. Remove those lines if teammates should get them through git.

## What each teammate can start with

| Member | Starts from |
|---|---|
| John | `Order`, `Company`, `BookSnapshot`, `Trade`, `ExchangeEvent` for `OrderBook` and `MatchingEngine` |
| Fabi | `Exchange`, `TraderAccount`, `SeedData`, `PortfolioView` for `AccountService` and `StockExchange` |
| Capa | `Exchange` and `StubExchange` for trader threads and `Simulation` |
| Abud | `Exchange`, `StubExchange` and `SeedData` totals for `ConsoleView` and test scaffolding |

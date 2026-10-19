# Glossary

**Bid**: an order to buy shares at or below a price. Also called a request.

**Ask**: an order to sell shares at or above a price. Also called an offer.

**Order**: one bid or ask from one trader for one company. It has a price, a quantity, a remaining quantity, a version and a state (OPEN, IN_TRADE, DONE, CANCELLED). The engine keeps a mutable `Order`; everyone else sees an immutable `OrderView`.

**Trade**: one execution between a bid and an ask. Quantity is min(bid.remaining, ask.remaining), price is the resting order's price. One incoming order can produce several trades.

**Book**: the open bids and asks of one company, sorted by price then sequence number. Each company has its own book and its own lock.

**Snapshot**: an immutable copy of a book (`BookSnapshot`) or portfolio (`PortfolioView`) taken after a complete change. Readers use snapshots and never take the company lock.

**Version**: a counter on each order, incremented on every modify and partial fill. `modify` takes the version the caller last saw; a mismatch returns `VERSION_CONFLICT`.

**requestId**: a caller-chosen id on `place`. Sending the same id again returns the first result instead of creating a second order. Same id with different parameters returns `DUPLICATE_REQUEST`.

**Sequence number**: `seq` ranks orders within one company for time priority. `globalSeq` orders events across the whole exchange.

**Reserved**: cash or shares set aside when an order is placed, so they cannot back a second order. Released on cancel, consumed on trade.

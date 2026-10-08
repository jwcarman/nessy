# The direct door waits a while for a busy agent

**Status: DRAFT for James's review, 2026-10-07. NOTHING BUILT.** Three decisions are his (§2); the
recommendations beside them are mine. Two new public words need his yes before anything lands (§2.1,
§5.2). Every path and name below was checked against `main` at `6d406bdb2`.

---

## 1. The problem

`DirectHarness.ask` answers `AskOutcome.Busy` the moment it finds the agent mid-turn. A caller that
arrives a second before the previous turn ends is turned away, and has to build its own retry loop to
do the obvious thing: wait a little and try again. James (2026-10-07): "the ability to 'wait' a bit
when trying to call the direct door, rather than just immediately failing. It could allow for a
timeout and if that timeout goes past, then it's considered busy."

The goal is leniency for occasional collisions, not scheduling. Ordered or sustained contention is
what the queued door is for.

## 2. Decisions for James

### 2.1 Shape and name — PROPOSED, needs a yes

Both a harness default and a per-call override:

```java
// DirectHarnessConfig<I>
DirectHarnessConfig<I> busyWait(Duration wait);

// DirectHarness<I, O>
AskOutcome<O> ask(AgentId agent, I input);                 // uses the configured busyWait
AskOutcome<O> ask(AgentId agent, I input, Duration busyWait);
```

`busyWait` is a new public word. It deliberately avoids "patience", which is parked for the
per-attempt-versus-overall effect timeout (memory: attempt-vs-patience). Alternatives: `waitWhenBusy`,
`busyTimeout`.

### 2.2 The default — RECOMMEND zero

`Duration.ZERO`: today's behavior, `Busy` at once, for every caller that does not opt in. A non-zero
default would make every existing `ask` block without its author having chosen it.

### 2.3 What running out answers — RECOMMEND `AskOutcome.Busy`

The same arm callers already handle. No new outcome type; "busy" now means "busy for as long as you
were willing to wait".

### 2.4 Decided here unless James objects

- **No ordering among waiters.** Whoever retries first after a turn ends wins. A waiter can lose every
  race to a later caller; the timeout bounds that, and the queued door is the answer for order.
- **`arrivedAt` is when `ask` was called**, not when the wait ended. Today `beginTurn` stamps the turn
  with the instant of the locked step; it will be given the call's instant instead, so the wait shows
  in `nessy_agent_turn` as `started_at - arrived_at` — the queue wait the trajectory docs already
  describe.
- **Interrupted while waiting:** the wait ends, the thread's interrupt flag is restored, and the answer
  is `Busy`. Nothing was written.
- **A terminated agent answers `Terminated` at once**, never after a wait: waiting cannot change it.

## 3. What "busy" is, and why the wait is a retry, not a lock

The turn lock (`Locks.TURN`) is held only for short steps: read the agent, fold one command, append.
JDBC holds it as a transaction-scoped advisory lock (`JdbcRowLocks`, `pg_advisory_xact_lock`);
in-memory as a `ReentrantLock` per agent (`InMemoryLocks`). Between the steps of a running turn the
lock is free. Busy is not contention for that lock; it is the agent's state — an open turn — which
`beginTurn` sees after replaying the agent under the lock (`DefaultDirectHarness.beginTurn`,
`recoverToIdle` answering `RecoveryOutcome.Busy`). Blocking on the lock would get it at once and find
the agent still busy.

So the wait is a loop around the existing first step:

1. Take the turn lock, replay, run recovery, exactly as today. Idle: start the turn, as now.
2. Busy: release the lock, sleep, try again.
3. The deadline passes: answer `Busy`.

The lock is never held while waiting, so a waiter never slows the turn it waits for. A waiter's
attempt runs recovery like any `ask`, so it may itself close an overdue turn and take the agent.

## 4. Waking up

**Polling with backoff and jitter**, nothing else, in this change:

- First retry after 25 ms, doubling to a 1 s cap, each interval with ±20% random jitter, and never
  sleeping past the deadline (the last sleep is cut to what remains).
- Each attempt is one short locked step that replays only since the last `TurnStarted`.

Polling is correct across processes and needs no new infrastructure. An in-process wake-up (signal
waiters when a turn ends in this JVM, which the door already learns when it narrates the ending after
commit) and Postgres `LISTEN/NOTIFY` are both possible later without changing the API; neither is in
this change.

## 5. An honest deadline

### 5.1 The gap

Each attempt takes the turn lock, and today taking it waits for as long as it takes:
`InMemoryLocks` calls `lock()`, not `tryLock`; `JdbcRowLocks` runs `pg_advisory_xact_lock`, which
blocks. Steps are short, so the overshoot is bounded by about one step — but a deadline that can be
overshot is not a deadline. A waiter must not wait for the lock past its own deadline.

### 5.2 A bounded lock — PROPOSED SPI method, needs a yes

`Locks` gains one method:

```java
/**
 * Runs {@code work} under the lock if the lock can be had within {@code within}; empty if not.
 * {@code within} of zero tries once and does not wait.
 */
<T> Optional<T> withLockWithin(
    LockKind kind, AgentType type, AgentId agent, Duration within, Supplier<T> work);
```

- **In-memory:** `ReentrantLock.tryLock(within)`.
- **JDBC:** inside the same `TransactionTemplate`, `SET LOCAL lock_timeout = <within ms>` then
  `pg_advisory_xact_lock`; a `lock_not_available` (SQLSTATE `55P03`) answers empty and the transaction
  rolls back having done nothing. `SET LOCAL` scopes the timeout to this transaction only.
- **Test doubles** that implement `Locks` gain the method (as with `turns()` in 0.6.0); a default
  method is not offered, because a silent fallback to the unbounded lock would hide exactly the gap
  this closes.

The plain `withLock` is unchanged and remains what every other step uses. The wait loop calls
`withLockWithin` with the time left; empty is treated as "busy, try again" until the deadline.

## 6. Testing

- **Deterministic time.** The loop takes the harness's existing `Clock` (`DirectHarnessFactoryConfig`
  `.clock(...)`) and an engine-internal `Sleeper` (package-private, not API) so tests advance time
  without real sleeps. Jitter comes from an injectable random source, fixed in tests.
- **Behavior:**
  - zero wait: a busy agent answers `Busy` at once, with no sleep (today's behavior, pinned).
  - a turn that ends inside the wait: the waiter's turn runs; its `arrivedAt` is the call's instant.
  - a turn that outlasts the wait: `Busy`, after no more than the deadline (sleeps sum to at most the
    wait), and nothing was written for the waiter.
  - terminated agent: `Terminated` at once even with a wait.
  - interrupted waiter: `Busy`, interrupt flag restored, nothing written.
  - an overdue turn found by a waiter is recovered and the waiter's turn runs.
  - backoff: intervals 25, 50, 100 … capped at 1000 ms (jitter fixed), last sleep cut to the deadline.
- **Bounded lock:** in-memory and JDBC (`@Tag("container")`) — free lock runs the work; lock held
  elsewhere for longer than `within` answers empty within roughly `within`; `within` of zero does not
  wait; the JDBC timeout does not leak into the next transaction on the same connection.
- **Two processes** (JDBC): two harnesses on one database, one mid-turn, the other waiting — the
  waiter starts its turn after the first ends.

## 7. Out of scope

- Ordering or fairness among waiters.
- An in-process wake-up and `LISTEN/NOTIFY` (§4).
- A cap on concurrent waiters per agent.
- Waiting on the queued door (it already queues).

## 8. Modules touched

| Module | Change |
|---|---|
| `nessy-api` | `DirectHarnessConfig.busyWait`, `DirectHarness.ask(agent, input, Duration)` (names per §2.1) |
| `nessy-backend-spi` | `Locks.withLockWithin` (§5.2) |
| `nessy-backend-inmemory` | `InMemoryLocks.withLockWithin` |
| `nessy-backend-jdbc` | `JdbcRowLocks.withLockWithin` |
| `nessy-engine` | the wait loop in `DefaultDirectHarness`, `arrivedAt` from the call, `Sleeper`; test doubles of `Locks` |
| `docs/` | the two-doors and harness guides: what `busyWait` does, that it is not a queue, and the thread cost on platform threads |

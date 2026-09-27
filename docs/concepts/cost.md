# Cost

A turn spends tokens on the way to an answer, sometimes on calls that
never reach one. Three types in `nessy-api` carry that spend: `Tokens`,
one count; `Usage`, one call; `TurnStats`, a whole turn.

## `Tokens`

```java
public sealed interface Tokens {
  record Uncounted() implements Tokens {}
  record Counted(int count) implements Tokens {}
}
```

Its whole point is a distinction most nullable-`Integer` code throws away:
**a vendor saying nothing and a vendor saying zero are different facts.**
Zero cache reads means your caching is not working. No cache reads
reported means you cannot tell. A nullable `Integer` read without care, or
a total that starts at zero, collapses the second into the first — a
metric showing a measured zero is worse than a metric showing nothing,
because it looks like a finding.

`Tokens` is sealed rather than nullable so the distinction cannot be
dropped by accident. A reader has to say what it means to have no count,
because the compiler makes it.

`plus` is how a turn totals without lying: an absent count contributes
nothing and takes nothing away, so one quiet call can sit in a running
total without making the whole total unknowable.

```java
Tokens.none().plus(Tokens.of(3));   // Counted(3)
Tokens.of(2).plus(Tokens.of(3));    // Counted(5)
Tokens.none().plus(Tokens.none());  // Uncounted
```

`minus` reads a part out of a whole — what spending bought, given what it
wasted — clamped at zero rather than allowed to go negative.

On the wire, `Tokens` is a bare number or nothing at all: `Counted` writes
its count and `Uncounted` writes `null`, the same shape a nullable count
had before this type existed. The distinction it protects is a distinction
in Java; the JSON was never ambiguous.

## `Usage`

`Usage` is what one call cost, and on which model:

```java
public record Usage(
    @Nullable String model,
    Tokens inputTokens,
    Tokens outputTokens,
    Tokens cacheReadTokens,
    Tokens cacheWriteTokens,
    Tokens reasoningTokens) {}
```

The model travels with the counts rather than beside them, because tokens
without a model cannot be priced. The compact constructor enforces it: **a
counted usage must name the model it was counted on.** The model may be
absent only when nothing at all was counted — `Usage.unreported()`, for a
scripted test provider or an effect that never reached a vendor.

Construction takes nullable boxes, because that is what an SDK hands an
adapter — `new Usage(model, inputTokens, outputTokens, cacheReadTokens,
cacheWriteTokens, reasoningTokens)` accepts `@Nullable Integer` for each
count and turns an absent one into `Tokens.Uncounted` through
`Tokens.reported`. Reading gives back `Tokens`, never a box — a field a
reader has to decide what "no count" means for, the same way `Tokens`
itself does.

`inputTokens` is all input processed, cached tokens included — vendors
disagree here, so adapters normalise rather than storing each one's own
spelling. `cacheReadTokens` and `cacheWriteTokens` are a breakdown of that
input, not an addition to it. `totalTokens()` is `inputTokens.plus(outputTokens)`,
which is what is billed.

## `TurnStats`

`TurnStats` is a whole turn's tally, kept in the agent's state and rebuilt
by replay — a turn resumed after a restart does not forget that it already
spent six calls:

```java
public record TurnStats(
    Instant startedAt,
    int modelCalls,
    int toolCalls,
    int failedAttempts,
    Tokens spent,
    Tokens wasted) {}
```

**The counts deliberately do not partition.** `modelCalls` is *every* call
the turn made, and `spent` is *everything* it cost. `failedAttempts` and
`wasted` are the subsets of those that bought nothing — they are not a
different, smaller total. A turn reporting two calls when the engine made
five would be wrong exactly where it matters: reconciling against a bill
or a rate limit. Read the productive share through `productiveCalls()` and
`productiveTokens()` rather than subtracting by hand, so every reader gets
the same answer:

```java
int productiveCalls();   // modelCalls - failedAttempts
Tokens productiveTokens(); // spent.minus(wasted)
```

`startedAt` is a fact, written once when the turn opened; `elapsed(Instant
now)` is derived. A stored duration would be measured against whatever
clock happened to be running when the fold replayed, which would make
replay non-deterministic. `startedAt` replays identically forever; asking
"how long has this been open" is answered fresh, against the clock asking
the question.

A `TurnStats` cannot hold a `Usage` — a sum names no model, and a turn's
calls can be billed as different dated builds of the same alias. The two
types answer different questions: `Usage` is what one call cost and on
what; `TurnStats` is how many calls and how much, full stop.

## Reading cost off an answer

`Outcome.Answered`, `Refused` and `Failed` all carry a `TurnStats`
alongside their own content — a caller who waited for the answer is
entitled to know what it spent, without reaching into the event store to
find out. `nessy-examples/chat-web`'s `ChatController` reads it straight
off the outcome:

```java
case Outcome.Answered<String>(String said, TurnStats stats) ->
    ResponseEntity.ok(
        Map.of(
            "said", said,
            "tokens", String.valueOf(stats.spent().orZero()),
            "calls", String.valueOf(stats.modelCalls())));
```

`orZero()` is for arithmetic that has to produce a number — here, a JSON
field a page will render. A reader deciding whether to record, display or
bill a count should match on `Tokens` itself instead; that is the whole
reason the sealed type exists.

## Where next

- [Outcomes](outcomes.md), the shapes a `TurnStats` rides along on
- [Events](events.md), where each call's own `Usage` is written down
- [Turn Policy](turn-policy.md), what reads a `TurnStats` to bound a turn

# Front Ends — ask, tell, and the console

**Date:** 2026-08-23
**Status:** Ratified (James, across conversations 2026-08-22/23; verb ruling
"Go with ask/tell" 2026-08-23)
**Amends:** `2026-08-22-harness-first-design.md` §1/§6 — the agent's message
verb is `tell(...)`; `observe` retires from the public surface (the §6 line
"prose may say tell" inverts: the METHOD is tell, and internal fold
vocabulary may still say observe).
**Absorbs since shaping:** the model split (the cli preset takes a bound
`Model`), the carrier whittle (the console approver renders the flattened
`ApprovalRequest` and approves by `request.id()`).

## 1. The verbs

- **`agent.tell(observation)`** — fire-and-forget, the caller-perspective
  verb (Akka prior art). Rename of `observe`; one sweep, pre-1.0, no
  alias, no deprecation limbo. `drive()` stays as the manual pump — it is
  lifecycle, not conversation.
- **`agent.ask(observation)` → `TurnOutcome`** — a PATTERN over the plain
  Agent API, not new machinery: ask subscribes a turn observer, tells,
  drives, and reads the outcome from the event stream — because the fold
  retains NO failure residue (a failed model turn folds back to Idle
  committing nothing), the events are the only honest source.
  ```java
  sealed interface TurnOutcome {
    record Replied(String text)          implements TurnOutcome {}
    record Parked(ApprovalRequest ask)   implements TurnOutcome {}
    record Failed(String reason)         implements TurnOutcome {}
  }
  ```
  Replied carries the assistant's final text; Parked carries the approval
  request the turn suspended on; Failed carries `TurnEnded`'s reason.
  Zero new event types — `TurnObserver`'s existing vocabulary suffices.

## 2. Subscriptions

`agent.subscribe(TurnObserver)` → `Subscription extends AutoCloseable` —
the ONLY closeable in the API, because it is the only thing holding a
routing entry. Close is idempotent and never throws. The registry and the
per-id fanout live inside the harness (a web app cannot wire one global
observer; the stream is scoped to an agent id), and the fanout includes
worker-driven turns — a delivery folding days later still reaches the
subscribers of that id. Dropping a Subscription unclosed leaks one routing
entry, not a thread.

## 3. The console

`Console` is the CLI front end and owns the terminal:

- **`approver()`** — the §5a immediate-decision arm as a face: renders the
  flattened `ApprovalRequest` (`{id, call, agentType, agentId}`), reads
  y/n(+reason), answers through the desks by `request.id()`.
- **The runner** — the read-ask-print loop: reads a line, `ask(...)`s,
  renders the `TurnOutcome` (Replied prints; Parked hands off to the
  approver face and re-asks; Failed says so honestly).
- `Nessy.cli()` remains the preset door — sugar composing a harness
  (in-memory substrate, console approver, console observer) with a
  `Console`; it takes the bound `Model` per the model split. Nothing the
  preset does is unavailable by hand-wiring the same pieces.

## 4. What stays out

HTTP lives in examples — the Spring rebirth is the worked web reference;
no web machinery enters the core. No new event types. No second observer
vocabulary.

## 5. What dies

`Agent.observe(...)` (renamed), the unscoped-observer idea (never shipped,
now ruled out by shape), and the last "prose may say tell" hedge.

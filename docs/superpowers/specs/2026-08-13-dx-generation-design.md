# The DX Generation — design

**Date:** 2026-08-13
**Status:** DRAFT — pending review
**Builds on:** the essence (2026-08-11), the durable kernel (2026-08-12), and the
chat-web dogfood (2026-08-13, shipped). This generation exists because the
dogfood worked: the example app carries three comments apologizing for the
framework, and an API that makes its own examples apologize has told you what
to fix. Every item below is evidence-backed by a specific line of chat-web.

---

## 1. Purpose

Remove the friction the first real deployment surfaced, before 1.0 freezes the
grammar. Success criterion: the chat-web example, rewritten on this
generation, loses its apology comments, its `ConversationStore` injection, and
its hand-stitched `finish()` — with no loss of behavior — and the diff of that
rewrite is itself the acceptance test.

## 2. `ToolContext.progress` — the placeholder dies at the source

**Evidence:** `IssueCouponTool` emits
`new ToolProgress(context.conversationId(), "n/a", "issuing…")` under a
four-line comment explaining that a tool cannot know its own call id and the
tee will discard the field anyway.

**Change:** `GatedToolCallExecutor` constructs each call's `ToolContext`, so
the context — not the tool — owns the authoritative call id. `ToolContext`
gains:

```java
void progress(String message);
```

which emits `ToolProgress(conversationId, callId, message)` on the context's
own emitter, ids supplied entirely by the framework. The distrust rule is not
relaxed — it is made unnecessary on this path: nothing untrusted arrives, so
there is nothing to distrust. The tee's attach-authoritative-call behavior for
*directly constructed* `ToolProgress` (still public, still what
`Harness.progress` emits from afar) is unchanged.

Tool code never constructs a `ToolProgress` again; the demo tool's apology
comment is deleted, which is the point.

## 3. `agent.snapshot(id)` — the page-rebuild read

**Evidence:** `ChatController.get` injects the `ConversationStore` SPI, calls
`store.load(id)` to avoid `contextFor`'s `IllegalArgumentException` (a
browser-minted fresh id is normal, not exceptional), then `contextFor` — which
performs its own second `store.load` — and assembles status, parked calls, and
transcript by hand. Every UI over a durable agent will write this method.

**Change:** a new `api.conversation` record and one total read on `Agent`:

```java
public record ConversationSnapshot(
    ConversationStatus status, List<ParkedCall> parkedCalls, Context context) {}

ConversationSnapshot snapshot(ConversationId id);
```

- Total: an unknown id yields `(IDLE, List.of(), Context.empty())` — no throw.
- One `store.load` + one `Memory.recall` inside; the double load dies.
- `contextFor(id)` survives as the lighter read and becomes total the same
  way (empty `Context` for an unknown id); its `@throws` clause is deleted.
- The store SPI leaves application imports: a UI needs `Agent` and `Harness`,
  nothing from `spi.*`.

`Context.empty()` is added if absent (a validated empty message list is
already legal).

## 4. Park narration — `TurnEvent.ToolCallParked(call, token)`

**Evidence:** parking — the durable story's central dramatic moment — is the
one thing the per-entry observer cannot see. `ChatController.finish` pattern-
matches `RunOutcome.Parked` after `tell` returns and stitches `approval-needed`
events from `state.parkedCalls()`, ignoring `Parked.token()` entirely because
fan-out can park several calls and the singular field cannot serve the real
case. One stream, two producers.

**Change, two halves:**

- `TurnEvent` gains an eighth variant, emitted at the moment the fold parks
  the call:

  ```java
  record ToolCallParked(ToolCall call, ParkToken token) implements TurnEvent {}
  ```

  The token rides the narration deliberately: the `TurnObserver` is supplied
  by the caller of `tell`/`resume`, and that caller already receives tokens
  via `RunOutcome` — the event hands a capability to no one who lacks it. (The
  standing declared-listening channel is unchanged; `TurnEvent` never reaches
  it.)

- `RunOutcome.Parked` slims to `Parked(ConversationState state)` — the
  singular token field goes. `state.parkedCalls()` is the settled truth;
  the event is the live narration; nothing is stated twice in two shapes.

Extender switches over `TurnEvent` (chat-web's `SseEvents`) fail to compile
until they add the arm — sealed-grammar etiquette doing its job, and exactly
why this ships before 1.0.

## 5. Resume ergonomics

**Evidence:** `harness.resume` signals an unknown/settled token with
`IllegalArgumentException`, so `ApprovalController`'s 409 handler swallows
every IAE in the controller, including genuine bad-request errors (a
final-review finding). And to answer 409 synchronously the controller peeks
`store.findPark(token)` — a harness-level question answered by reaching into
the store SPI.

**Changes:**

- `UnknownParkTokenException extends RuntimeException`, in `api`, thrown by
  `resume` (and the sugar below) where `IllegalArgumentException` is thrown
  today for unknown/settled tokens. Message unchanged. IAE remains for actual
  argument errors (null-adjacent misuse), which is what it means everywhere
  else.
- `Optional<ParkedCall> peek(ParkToken token)` on `Harness` — the non-consuming
  read `progress` already performs internally, surfaced. Controllers stop
  importing the store SPI for it.
- Sugar for the ladder every HITL endpoint writes:

  ```java
  RunOutcome approve(ParkToken token);
  RunOutcome approve(ParkToken token, TurnObserver observer);
  RunOutcome deny(ParkToken token, String reason);
  RunOutcome deny(ParkToken token, String reason, TurnObserver observer);
  ```

  each delegating to `resume(token, new ToolResolution.Decided(...), …)`.
  `resume` itself is unchanged and remains the general form (a
  `ToolResolution.Completed` has no sugar; it isn't a human verdict).

## 6. The naming fix — `agent.conversation(id)`

**Evidence:** `agent.resume(conversationId)` (reopen a stored conversation)
and `harness.resume(token, …)` (answer a parked call) are different verbs
sharing one word, three lines apart in `ChatController`/`ApprovalController`.

**Change:** rename `Agent.resume(ConversationId)` to:

```java
Conversation<I> conversation(ConversationId id);
```

Noun, symmetric with `converse()`: `converse()` mints a fresh conversation,
`conversation(id)` addresses an existing one. After this, "resume" appears
exactly once in the public API and means answering a park. Rename only —
behavior, javadoc substance, and the returned handle are untouched.

## 7. The small sugar

- `Approver.parkAll()` beside `allowAll()`/`denyAll()`: returns
  `request -> Awaited.parked(ParkToken.generate())`. The durable-HITL posture
  — the UI is the approver — becomes one word, and the chat-web bean loses its
  lambda.
- `JdbcNessy.create(DataSource, ObjectMapper)` in `nessy-store-jdbc`:

  ```java
  public record JdbcNessy(JdbcConversationStore store, JdbcMemory memory) {
    public static JdbcNessy create(DataSource dataSource, ObjectMapper mapper) { … }
  }
  ```

  Both schemas bootstrap in one call; the durable pair that every real
  deployment wants arrives together. The individual `create` factories remain
  for anyone wanting one half.

## 8. The `Awaited<T>` ruling

Sonar S2326 ("T is not used in the interface") stands as a SonarCloud
**won't-fix**, not a code change and not a code suppression: the type
parameter is load-bearing grammar — it is what makes `Awaited<ToolResult>`
and `Awaited<Decision>` distinct types even though only `Ready` carries the
value — and no caller has ever needed an interface-level accessor. The ruling
is applied in the SonarCloud UI with that justification; the zero-suppression
rule (nothing in code) is intact.

## 9. Ripples

- **chat-web is rewritten on this generation in the same plan** — it is the
  acceptance test (§1). Expected diff: `IssueCouponTool` loses the apology and
  the `ToolProgress` import; `ChatController` loses the store injection, the
  IAE guard, and the approval-card stitching in `finish()`; `ApprovalController`
  loses the store injection and narrows its 409 handler to
  `UnknownParkTokenException`; `NessyConfig`'s approver line becomes
  `Approver.parkAll()` and its two store beans may become one `JdbcNessy`;
  `SseEvents` gains the `ToolCallParked` arm and emits `approval-needed`
  inline.
- **Breaking changes (pre-1.0, deliberate):** `RunOutcome.Parked` loses its
  token component; `Agent.resume(id)` is renamed. CHANGELOG documents both
  loudly; nothing else breaks.
- **Docs:** README (observability snippet's `tell` example is unaffected;
  durable section unchanged; seams table unchanged), chat-web README's wiring
  snippet (approver line), spec cross-references. The chat-web spec (2026-08-13)
  gets a pointer note, not a rewrite — its §4 endpoint table stays truthful at
  the HTTP level.
- **Tests:** each API addition lands with its own tests (snapshot totality;
  parked-event emission order relative to `done`-equivalent return; peek
  non-consumption; sugar delegation; exception type on unknown token; rename
  compile-sweep). The container smoke gains nothing — the wire contract is
  unchanged.

## 10. Deliberately not built

The Spring Boot starter (its own generation; this one sharpens what it will
automate), multi-agent park routing, `ToolResolution.Completed` sugar, any
`Awaited` API additions, transcript-view helpers in core (presentation stays
application business).

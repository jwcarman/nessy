# The DX Polish — the turn learns to finish its sentences

**Date:** 2026-08-14
**Status:** APPROVED — 2026-08-14 (designed in session from the merged DX
assessment: insider build-evidence + a fresh-eyes newcomer walk of all five
examples)
**Builds on:** everything shipped through the five-example matrix. Every
item below cites the shipped defect or thrice-paid tax that earned it a
place; nothing is speculative.

---

## 1. Purpose

Make building agentic applications on nessy easier in the ways the five
examples proved it is currently hard. The evidence standard: an item enters
this spec only if a real defect shipped because of it, or three-plus
examples independently paid the same tax. The headline item — completing
the narration grammar — was chosen because it shrinks three other items
while it lands.

## 2. The narration completes: `AssistantSaid` and `TurnEnded`

`TurnEvent` today narrates a turn's middle but not its sentences or its
ending: observers hear prose letter-by-letter (`TextDelta`) and learn the
turn ended only because `tell` returned. Consequences, all shipped: four
examples hand-rolled the same `StringBuilder` accumulation; two of them
dropped the FAILED case (both caught by final reviews); every web
controller synthesizes the `done` event by hand.

Two variants join the sealed grammar:

- **`TurnEvent.AssistantSaid(Message message)`** — a settled
  assistant-role message, emitted once per model response the fold
  absorbs, INCLUDING a response that carries only tool-use blocks and no
  prose (asking for homework is still saying something; observers wanting
  prose filter for text). The deltas were the preview; this is the
  sentence. Emitted at the same beat the `ModelResponded` fact folds —
  live narration, subject to the roster's existing at-least-once rule (a
  retried segment may re-say it; observers keying UI on it dedupe like
  `ToolCallParked` consumers do).
- **`TurnEvent.TurnEnded(ConversationStatus status, String
  failureReason)`** — the segment's closing line, emitted exactly once
  per drive ATTEMPT at every exit: quiescent completion, FAILED (with the
  reason — `failureReason` is null otherwise, mirroring
  `ConversationState`'s one sanctioned nullable), and PARKED.
  **Post-save discipline, like `ToolCallParked`:** an ending that never
  committed is never narrated. *(Amended at final review, 2026-08-14:
  the guarantee is attempt-scoped, not segment-scoped — a fence-lost
  retry that already narrated a committed PARKED ending may re-narrate;
  this is the roster's standing at-least-once rule, the same one
  `AssistantSaid` and `ToolCallParked` carry, and consumers keying UI on
  it dedupe. Suppressing the retry's ending was rejected: a retry can
  legitimately settle in a DIFFERENT terminal state, and that ending
  must not be lost.)*

**Naming, ruled at review:** `AssistantSaid` over `MessageSettled`
(actor-less, kernel jargon) and over `AgentSaid` — in `AgentTold` the
agent is the recipient; flipping it to speaker makes "Agent" a false
friend, the fact channel already chose `ModelResponded` over `AgentSaid`
for its output twin, and the payload is definitionally a
`Role.ASSISTANT` message, the same word the transcript renders.
**`AgentSaid` is banked** for a possible future outward-utterance boundary
event; it is not this event. `TurnEnded`'s echo of the provider SPI's
`ModelEvent.TurnEnded` is harmonious, not colliding: same concept, two
altitudes, always written qualified.

**Ripples, all intended:**

- `TurnEventSse` (a core-adjacent switch, no default arm) gains two arms:
  `AssistantSaid` → a new `message` wire event (`{text}` — the joined
  text blocks; emitted only when non-blank, since the wire's tool story
  is already told by the tool events) and `TurnEnded` → the existing
  **`done`** wire shape `{status, failureReason?}` — which the framework
  now emits, so chat-web's controllers DELETE their hand-synthesized
  `done` and `TurnRunner`'s outcome-handler plumbing slims to whatever
  non-wire duties remain (if none remain for a caller, the two-arg run
  stays for compatibility but the SSE path stops needing it). chat-web's
  `app.js` is wire-compatible unchanged (same `done` name and payload);
  its smoke test's assertions are again the invariant that must pass
  untouched.
- **`TurnObserverAdapter` and `TurnObserverBuilder` each gain the two
  matching per-variant hooks** (`onAssistantSaid`, `onTurnEnded`) —
  additive, no-op defaults, the composition surface staying complete over
  the grammar it composes.
- **`TurnObserver.logging(Logger logger, String prefix)`** ships as
  sugar, and is IMPLEMENTED on `TurnObserver.builder()` — the standard
  narrating observer (says-line from `AssistantSaid`, tool events, ending
  with failureReason at WARN) in ~ten builder lines, doubling as the
  builder's own dogfood. The four examples' hand-rolled observers
  collapse onto it (chat-cli keeps rendering deltas — a streaming REPL is
  what deltas are FOR).

## 3. Two guards where silence currently costs a demo its lesson

- **The memory-downgrade warning.** `AgentBuilder.build()` logs a WARN
  when memory was DEFAULTED (in-memory transcript) while the harness's
  store was EXPLICITLY CONFIGURED — the same set-vs-defaulted mismatch
  rule the parks warning uses (`HarnessBuilder` records whether
  `store(...)` was called; the harness carries that bit to the builder).
  Message names the consequence: the conversation survives restart but
  its transcript will not. The razor is untouched: memory stays
  agent-scoped, we warn rather than auto-wire.
- **The approver warning learns to read the grants.**
  `UsagePolicy.allow()` returns a canonical singleton so `build()` can
  see it; when EVERY grant is that singleton (or there are no grants),
  no approval path exists and the design-§13.1 warning does not fire.
  Custom policies remain opaque and keep the warning (fail-noisy for
  unknowns). The three examples' comment-and-silence
  `.approver(allowAll())` lines then delete — declaring an approver
  remains legal, it just stops being a tax.

## 4. `Context` learns to read itself aloud

`Context.lines()` — `record Line(String role, String text)`, one line per
message with any text (`TextBlock`s joined in order, other blocks
invisible, empty messages skipped, role lowercased): the exact
`TranscriptView` semantics that chat-web and dispatcher currently maintain
as byte-identical copies. Both examples swap to it and delete their
copies. Admission test per `Context`'s own rule: this is a read over the
context's structure, not semantics — it qualifies.

## 5. The examples pay down their own findings

- **`nessy-examples/hello`** — the root README's five-minute example as a
  runnable module: `nessy-core` + `nessy-testing`, scripted provider, no
  key, no network, no Docker — `./mvnw -q -pl nessy-examples/hello -am
  compile exec:java`. The README's snippet is corrected to match the real
  `nessy-testing` API wherever prose has drifted, and the run command
  lands directly beneath it. The headline promise becomes an artifact;
  `nessy-testing` gets its first dogfood. The matrix does not grow — hello
  is the doorstep, not a sixth trigger model.
- **Dispatcher stops colliding** (the shipped bug): hardcoded datasource
  coordinates removed in favor of service connections (order-desk's
  pattern, whose comment is copied), Postgres to host **5434**,
  `server.port: 8081`. The root README gains a one-glance port map for
  the family (5432 chat-web pg / 5433 order-desk pg / 5434 dispatcher pg /
  5672+15672 rabbit / 8080 chat-web / 8081 dispatcher / 3000+4318 lgtm).
- **`chat-cli/README.md`** — the front door for the front-door example:
  the lesson, both run commands with the first-run reactor-build note,
  no-Docker/no-database, what `java.lang.IO` is, what the console
  approval prompt looks like.
- **Order-desk demonstrates the sealed-switch `InputRenderer`** — the
  README's "recommended idiom" finally exemplified: one arm per
  `OrderEvent` variant, a comment contrasting what the model sees versus
  the default tagged-JSON.
- **chat-web's cold start stops eating 30 seconds**: the compose file
  overrides the lgtm image's healthcheck (same script, `interval: 3s`,
  `retries: 60`) with a comment naming the image-interval trap — the fix
  diagnosed live on 2026-08-14 and parked since.
- **Consistency sweep:** `banner-mode: off` everywhere, one logback file
  naming convention, the two "The fourth example" labels corrected,
  root-README examples intro checked against the final family.

## 6. Token spend becomes a metric — CUT at execution (2026-08-14)

Cut by owner ruling mid-execution: the Postgres usage ledger
(`state->'usage'` on `nessy_conversation`) remains the truthful sum, and a
second projection of the same `ModelResponded` fact was judged not to earn
its keep as a starter behavior. If a Grafana-summable series is ever
wanted, this section's design (starter-only `nessy.tokens` Micrometer
counter, `direction=input|output`, bean-conditional on `MeterRegistry`,
zero core changes) is the shape to build.

## 7. Deliberately not in this wave

Lazy provider construction (the key-at-boot posture deserves its own
design, not a drive-by), the multi-agent wall (the callback-desk
generation), migrating existing smoke tests onto `nessy-testing` (follows
once hello proves the shape), the `AgentSaid` boundary event (name
banked), the `nessy.tokens` counter (§6, cut), and any change to the fold,
the doors, or the wire's existing event names.

## 8. Breaking (pre-1.0), stated loud

1. `TurnEvent` gains two variants — core/no-default switches (the SSE
   bridge) update at compile time per the sealed-grammar etiquette;
   extender switches with `default` arms are untouched.
2. `UsagePolicy.allow()` returns a canonical singleton (behavior
   identical; identity newly meaningful to `AgentBuilder` only).
3. The SSE wire vocabulary GAINS `message` and the framework takes over
   emitting `done` — additive and shape-compatible; no existing event
   renamed.

## 9. Testing

- Kernel: emission-contract tests — `AssistantSaid` once per settled
  response including tool-use-only; `TurnEnded` exactly once per segment
  for COMPLETE, FAILED (reason carried), and PARKED; the post-save
  discipline (a fenced-save loser narrates no ending); at-least-once
  re-narration documented and asserted tolerable.
- Bridge: `TurnEventSse` arms for both; `done` emitted by the framework;
  **chat-web's smoke test passes with assertions untouched** (the
  standing invariant).
- Guards: memory-downgrade warning fires on the mismatch and only the
  mismatch; approver warning silent for all-allow grants, loud otherwise.
- `Context.lines()` unit-tested with the TranscriptView cases (both
  example copies deleted, their tests re-target the core method).
- `hello` runs in the offline default build — it IS its own test (a CI
  step executes it and greps the expected output).
- Full offline reactor + container sweep green, as always.

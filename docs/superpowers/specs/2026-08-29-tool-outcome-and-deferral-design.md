# Tool outcomes and deferral — the engine stops speaking in the tool's voice

**Date:** 2026-08-29
**Status:** DRAFT, awaiting owner review. Binding on `nessy-engine`'s tool seam and on
`nessy-api`'s `Awaited`, `ToolContext`, `ApprovalContext`, and `ApprovalOutcome`.
Subordinate to `2026-08-28-engine-extraction-design.md` and
`2026-08-28-actor-composition-design.md`.

## 0. Why

Three defects, one cause.

`AgentTools.run` returns a `String` — "Runs the tool and renders its outcome as text" —
and `GrantedTools.run` ends in `execute(...).text()`. That single call throws away
everything a tool said that was not plain text:

- **Images vanish silently.** `ResultBlock` is `sealed ... permits TextBlock, ImageBlock`,
  and `ToolResult.text()` filters to `TextBlock` and joins. A tool returning a screenshot
  returns an empty string, with no error and no warning.
- **Every tool failure is reported as a success.** `isError` is dropped by `text()`, and
  `ToolWorker.runAndRemember` then wraps the survivor in `ToolResult.ok(...)`
  unconditionally. The model is told the call succeeded and handed the error text as its
  result — and `remember(...)` writes that same `ok` into the transcript, so the lie is
  durable, not merely in flight.
- **Deferral is impossible.** A `String` return has no shape for "I will answer later", so
  `GrantedTools` turns `Awaited.Deferred` into `ToolResult.error("... this path cannot
  route yet ...")`.

`AgentTools`' own javadoc names the cause: *"Deliberately provisional. The real seam is
`Tool` in `nessy-api` ... it is replaced rather than grown."* It was a `String`-shaped shim
to lift the engine out of the watchman example. This spec pulls the shim down.

## 1. Three seams, three vocabularies

Each speaker gets exactly the words it is entitled to, enforced by the type rather than by
documentation.

| Seam | Type | May defer | May say `Failed` |
|---|---|---|---|
| `Tool.execute` | `Awaited<ToolResult>` — **unchanged** | yes | no |
| `AgentTools.run` | `Awaited<ToolOutcome>` | yes (relays) | yes |
| the answer door | `ToolOutcome` — no wrapper | **no** | yes |

**A tool cannot produce `Failed`, because `Failed` means no tool output exists** — and a
tool returning it has just spoken. A tool's own failure is `ToolResult.error(...)`:
in-band, rich, able to carry an `ImageBlock`. The tool author's API does not change.

**The answer door takes a bare `ToolOutcome`, deliberately.** Using `ToolOutcome` rather
than `Awaited<ToolOutcome>` makes *"you cannot defer a deferral"* structural: the type
cannot express a second "I'll get back to you". The door is wider than `Tool.execute` in
the other direction — a vendor reporting that a job died with no output returns `Failed`
rather than faking the tool's voice with `Returned(ToolResult.error(...))`.

## 2. `ToolOutcome` is promoted, not invented

`ToolOutcome` and its two arms already exist in `nessy-agent` and are correctly designed.
`ToolCallPhase` already destructures three cases out of them:

```java
case ToolOutcome.Returned(var result) when !result.isError() -> // ran, succeeded
case ToolOutcome.Returned(var result) ->                       // ran, reported its own failure
case ToolOutcome.Failed(var error) ->                          // no tool output exists
```

That is a real axis `isError` alone cannot express: **who is speaking.** Move the type to
`nessy-api`. Do not introduce `ToolCallOutcome`/`ToolCallResult`/`ToolCallFailure` — the
namespace already has `ToolResult` and `ToolResultBlock`, and a third `...Result` is one
too many. `Returned | Failed` already matches the house grammar (`ModelOutcome`,
`TurnOutcome`, `ApprovalOutcome`, `Awaited`).

**`ToolError` is KEPT.** An earlier draft of this spec proposed deleting it as a bare
one-field wrapper, on the grounds that `ModelOutcome.Failed` and `TurnOutcome.Failed` take a
plain `String reason`. That was backwards on both counts.

It is the only place structure about a failure can ever live. It carries a validated
invariant already (a null message throws), and §3 of this very spec invents structured
information — whether the tool's code ran — and then writes it into prose. If that status
should ever become a field rather than a sentence, `ToolError` is where it goes, and
`Failed(String reason)` would make that a breaking change at every construction site.

The consistency argument runs the other way: `ModelOutcome.Failed` and `TurnOutcome.Failed`
are the impoverished ones. Enriching them is out of scope here, but they are not the
precedent to copy.

## 3. `Failed` means "no tool output exists", and the message says why

`Failed` covers both "never ran" and "died partway". A thrown exception is
`Ready(Failed(...))` — the wait finished in-process, and no tool output exists.

**The split is deliberately NOT modelled as types.** Nothing branches on it: there is no
tool-call retry logic anywhere in the engine, no compensation, and no idempotency check
keyed on it. Adding `NotCalled | Threw` would buy a metric label and a compiler obligation
at every switch site in exchange for a decision nobody makes yet.

**The party that decides whether to retry is the model, and it reads prose.** So every
engine-authored failure message states whether the tool's code ran:

| Situation | `Awaited<ToolOutcome>` | Message states |
|---|---|---|
| Tool returns normally | `Ready(Returned(result))` | — |
| Tool returns `ToolResult.error(...)` | `Ready(Returned(result))` | the tool's own words, content preserved |
| Tool throws | `Ready(Failed(...))` | `it may have partially completed` |
| Unknown tool | `Ready(Failed(...))` | `the call was not made` |
| Malformed arguments | `Ready(Failed(...))` | `the call was not made` |
| Claim lookup fails | `Ready(Failed(...))` | `the call was not made` |
| Tool defers | `Deferred(deadline)` | — |
| Deferral expires | `Ready(Failed(...))` | `no answer by <deadline>; the call was not made` |
| Approval denied | `Ready(Failed(...))` | `denied by <who>; the call was not made` |
| Approval expires | `Ready(Failed(...))` | `nobody answered within <term>; the call was not made` |

A thrown exception is the one case that cannot be answered, which is why it says *may*.
Honest uncertainty beats a confident guess: it is the difference between a model that
retries a payment blindly and one that says "I am not sure whether that refund went
through; let me check first."

## 4. `ComputationCallback` is deleted

The callback existed for one reason, stated in `ToolContext`'s own javadoc: the computation
id *"does not exist yet when a tool runs, which is the whole point of the callback."* That
was Continuum's constraint — Continuum minted the id after the fold.

**In the actor world the return address exists before the tool is dispatched.** So:

```java
// before
Awaited.Deferred(ComputationCallback callback, Duration term)
ApprovalOutcome.Deferred(ComputationCallback callback, Duration term)

// after — "answer me by this moment", and nothing else
Awaited.Deferred(Instant deadline)
ApprovalOutcome.Deferred(Instant deadline)
```

A deferring party reads its own return address from context, tells the world, and returns.

`ToolContext.invocation` (a `ComputationId`) dies with it. It is today constructed as
`ComputationId.of(tool.name())` — identical for every invocation of a tool, forever — while
being documented as *"this execution's opaque, stable idempotency key ... stable across
every redispatch and replay."* A tool using it to deduplicate an external effect would
dedupe away every call after the first. The `ReplyToken` is the honest replacement.

## 5. `ReplyToken` — one opaque token, logical coordinates

```java
// on ToolContext and ApprovalContext
ReplyToken handle();
```

A thin opaque wrapper, typed rather than a bare `String` in keeping with `AgentId` and
`AgentType`. The holder hands it to a vendor, embeds it in a URL, or stores it; it never
parses it. Same contract `ComputationId` already documents: *"carries no extractable
structure ... read as an opaque token, never parsed."*

**It encodes logical coordinates — `(agentType, agentId, callId)` — and NEVER an actor
path.** This is the load-bearing constraint. A vendor may answer hours later, by which time
the agent has restarted or the cluster has resharded and the actor lives on a different
node. Logical coordinates survive both; a path does not. Same reasoning as `ApprovalActor`
recomputing its deadline from the persisted `askedAt` rather than storing a live timer.

**It is a bearer token.** Whoever holds it can complete the call. This matches the prior
behaviour of `ComputationId` and is accepted deliberately rather than by omission; a handle
leaked into a log or a URL is an unauthenticated door into one tool call. Signing is a
future option, not part of this spec.

The answer door, once:

```java
void answer(ReplyToken handle, ToolOutcome outcome);
```

## 6. Deferral reuses `ApprovalActor`'s proven shape

`ApprovalActor` is *"one pending approval: the question, the clock, and nothing else"* — a
term, a timer, an answer arriving from the world, relayed to `ToolCallActor`. A deferred
tool is the same shape with a different payload, so this is a second instance of proven
machinery rather than new machinery.

- **`DeferralActor`** — mirrors `ApprovalActor`: `Answer(ToolOutcome)` or `Expired`, then
  tells `ToolCallActor` and stops. Expiry settles the call as an in-band `Failed`, exactly
  as an unanswered approval denies.
- **`ToolCallRecord.deferredUntil`** — one new field: the absolute deadline. The actor waits
  `Duration.between(now, deferredUntil)` on every spawn, so a process down for an hour comes
  back with an hour less on the clock and a deadline that passed while it was down fires
  immediately on recovery. See §7.
- **`AgentActor.AnswerToolCall(callId, ToolOutcome, replyTo, headers)`** — mirrors
  `AnswerApproval`, including the `Ack` so an HTTP handler can wait for durability before
  returning 200.

## 7. Deadlines are absolute, and impossible ones are refused

A deferring party states an `Instant`, not a `Duration`. Three things follow.

**The restart arithmetic disappears.** `ApprovalActor` today reconstructs its deadline on
every spawn — `term.minus(Duration.between(call.askedAt(), now))` — storing `askedAt`
purely to make that subtraction possible. With an absolute deadline the actor stores the
deadline and computes `Duration.between(now, deadline)`. `ToolCallRecord` carries
`deferredUntil` rather than `deferredAt`, and a class of clock-arithmetic bugs stops being
writable. The recovery property is unchanged: a process down for an hour comes back with an
hour less on the clock, and a deadline that passed while it was down fires immediately.

**The relative-to-what ambiguity disappears.** A `Duration term` is measured from some
unstated moment — when the tool returned, or when the park committed, which differ by
however long the fold took. An `Instant` names the moment itself.

**Clipping becomes validation, so nobody is told something false.** This is why
`ComputationCallback` carried a `deadline` parameter: the harness silently truncated a term
and had to report what was actually granted, "so a party that asked for a year and got
seven days cannot promise a human something false." With an absolute deadline there is
nothing to truncate — a deadline beyond the harness's ceiling is an impossible value and is
REFUSED, matching how the house already treats impossible configuration:

> `HarnessConfig.maxTokens` — *"Validated against the resolved model's real context window,
> so an impossible budget fails at `create` rather than at the provider."*

The deferring party therefore always knows exactly what it was granted: what it asked for,
or an exception. No ceiling accessor exists and none is needed.

`HarnessConfig.approvalTerm` remains a `Duration` — a ceiling is naturally relative — and is
what a requested deadline is validated against.

## 8. Blast radius

`AgentTools`, `GrantedTools`, `ToolWorker`, `ToolCallActor`, `AgentActor`,
`ToolCallRecord`, plus `WatchmanTools.boundTo` in the example and the `AgentTools` doubles
in `LocalRoutingTest` and `PekkoHarnessFactoryTest`. In `nessy-api`: `Awaited`,
`ToolContext`, `ApprovalContext`, `ApprovalOutcome`, the promoted `ToolOutcome` and `ToolError`, the new
`ReplyToken`, and the deleted `ComputationCallback`.

`nessy-agent` is not modified. It is the runtime being replaced; its `ToolOutcome` and
`ToolError` are promoted to `nessy-api` rather than duplicated, and the `nessy-agent` copies
go when the module does.

## 9. Non-goals

- Retiring `AgentTools` entirely for the real `Tool` seam wired through `HarnessConfig`.
  This spec fixes the shim's shape; replacing it is its own work.
- The degenerate `ToolContext` in `GrantedTools` — empty `arguments`, and
  `ToolEventListener.noop()` silently swallowing `progress()`. Both are real and both are
  separate.
- Threading `O` through the engine. Everything below `Harness` is hardcoded to `String`.
- The ephemeral reconnect buffer for `subscribe`.
- Signing `ReplyToken`.

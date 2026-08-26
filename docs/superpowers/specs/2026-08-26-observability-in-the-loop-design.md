# Observability belongs in the loop — an amendment to §3

*2026-08-26. Amends `2026-08-26-agentic-o11y-design.md` §3 ("One stream — the
fold's output, at the harness") and retires the JDBC-instrumentation decision
taken in the watchman soak-fix round. Status: awaiting James's review.*

## 0. What the soak found

Twenty minutes of running the watchman against a real model, a real Postgres
and a real collector produced a finding no test could: **the trace list was
199 JDBC root spans and 2 HTTP spans, and the agent's own rounds had been
pushed out of it entirely.** In the rounds that did survive, every
`connection`/`query`/`result-set` span hung flat off Spring's scheduled-task
span rather than under the `create_memory` and `search_memory` observations
they belonged to.

The cause is not the JDBC library. It is §3.

§3 ruled that the observability bridge is a **subscriber on the harness fact
stream**. A subscriber reacts to a fold that has *already happened*. It can
therefore only ever:

- start and stop a span around nothing, hand-passing a parent it looked up;
- never be the *current* observation, because there is no work in progress to
  be current during;
- and so never let anything beneath it — a JDBC statement, an HTTP call inside
  an approver, a nested provider SDK — attach to it.

Everything awkward in the o11y generation traces back to that one property.
The three engine counters had to be faked as zero-duration spans (and then
demoted to span events) because a subscriber cannot record a counter. The
memory spans needed a decorator threaded in separately because a subscriber
cannot see a call it is not told about. And `chat` and `execute_tool` were
carved out as exceptions from the start — put *inside* the executors —
because the data they need exists nowhere else. The design was already a
hybrid; the soak showed the hybrid leaking.

James, 2026-08-26: *"I thought we instrumented it in the loop and not from the
outside before."* Correct — the retired `EngineObservations` did, and the
reason it worked is the reason this amendment exists.

## 1. The distinction §3 missed

**Spans wrap work. The stream watches facts.** These are different jobs and
only one of them is a subscription.

- A **fact** is something that happened: an event was applied, a transition
  committed, a delivery was dropped. Facts are exactly what the stream
  carries, and what a projection (the pending-approvals table), engine-health
  narration, or an audit consumer wants. The stream stays, unchanged, for all
  of that. It is the right shape for its job.
- A **span** measures work while the work is happening. It wants to be opened
  before, closed after, and — where the work runs on one thread — to be
  *current* for the duration so that anything the work does can nest inside
  it.

§3 used a fact subscription to produce spans. That is the error.

## 2. Two kinds of span, and only one can hold a scope

Not everything can be scope-based, and pretending otherwise would be the
mirror of the mistake being corrected here.

**Work-scoped spans** cover work that begins and ends on one thread. These
open a Micrometer scope, are current for their duration, and nest naturally:

| span | where |
|---|---|
| `chat` | `ProviderModelCallExecutor.stream` (already in the loop) |
| `execute_tool` | `RegistryToolCallExecutor.run` (already in the loop) |
| **`nessy.approval.seek`** *(new)* | `RegistryToolCallExecutor.seek` — building the request, the action contributor, each enricher, the approver call |
| **`nessy.fold`** *(new)* | `DefaultAgent.commit` and `DeliveryWorker.fold` — load, handle, remember, CAS save |
| `search_memory` / `create_memory` | `ObservingMemory` (already in the loop; gains a scope) |

**Each of the two decision spans records what was decided**, so a trace answers
*what happened* and not merely *how long it took*:

- `nessy.approval.seek` carries `nessy.approval.outcome` — one of `approved`,
  `denied`, `deferred` — mapped from the sealed `ApprovalOutcome`/`Approval`
  grammar with no default arm, so a new variant fails the build here. It also
  carries `gen_ai.tool.name` and `gen_ai.tool.call.id`, and `error.type` when
  the approver or an enricher throws (which the executor already turns into a
  denial; the span says so rather than reporting a bare success).
- `execute_tool` gains `nessy.tool.outcome` — `returned`, `failed`, or
  `deferred` — beside the `nessy.tool.deferred` boolean it already carries.
  The boolean answers "is a wait coming"; the outcome answers "what did the
  body do", and the two differ on the failure paths added when a tool throws
  after deferring.

The values are the same vocabulary the wait spans already use
(`nessy.approval.answer`, `nessy.tool.outcome`), so one Grafana filter spans
the decision and the wait it opened.

**Lifetime spans** cover something that outlives a thread, a stack, and
possibly the process. These cannot hold a scope and must be hand-parented,
exactly as they are today:

| span | starts | ends |
|---|---|---|
| `invoke_agent` (a segment) | a fold with no open segment | the fold into `Idle` or a park |
| `nessy.approval.wait` | `ApprovalDeferred` folded | `ApprovalAnswered` folded — possibly days later, possibly in another process |
| `nessy.tool.wait` | `ToolDeferred` folded | `ToolFinished` folded |

The waits are the honest limit: a human takes three days, the answer arrives
in a different JVM, and no scope survives that. They stay as they are — and
they were always the part of the roster that genuinely belonged to the fold,
because the fold is where their beginning and end become facts.

So the corrected rule is not "everything moves in-loop." It is: **work-scoped
spans are opened around the work; lifetime spans are stamped by the fold.**
The segment is the parent of everything, looked up by `AgentId` as it is now.

## 3. What this buys

- **The fold span answers the question the JDBC library was imported for.**
  Its duration *is* the store write plus the reduce plus the remembrance. A
  slow CAS shows up as a slow fold, in the round it happened, under the
  segment. No third-party dependency.
- **Stale retries become legible.** A retried fold is a second `nessy.fold`
  span in the same round, not a floating counter. The counter stays as a span
  event for aggregation, but the trace shows what actually happened.
- **`nessy.approval.seek` closes a real hole.** Today an approver that calls
  Slack, a policy service, or a rules ladder is a gap inside `invoke_agent`
  with nothing in it. The whole premise of the approval design is that the
  approver may do arbitrary work; it should be measurable, and anything it
  calls should nest inside it.
- **Third-party instrumentation starts working.** With scopes open around the
  fold and the memory calls, a datasource library (or an HTTP client library
  inside an approver) attaches beneath them correctly. We are not shipping
  one — see §4 — but an application that adds one now gets a tree rather than
  a flood.

## 4. The JDBC decision retires

`net.ttddyy.observation:datasource-micrometer-spring-boot`, added to
`nessy-examples/watchman` in the soak-fix round, is removed. Measured on a
live run: 390 `query`, 199 `connection` and 199 `result-set` spans against a
handful of rounds; 198 of those `connection` spans were *roots*; none carried
a `db.*` attribute; and none nested under the memory observations they were
added to explain.

It failed because there was no scope to attach to (§0), so it is not a
condemnation of the library — with §2's scopes it would nest. It is removed
because the fold and memory spans answer the question at a hundredth of the
volume, and because per-statement SQL detail is an application's choice to
make deliberately, not a default the framework ships.

The watchman README's Grafana section says this: the fold span is where store
latency lives; add a JDBC library only if you need per-statement detail, and
note it will attach under the fold.

## 5. What does not change

- The **fact stream** and `HarnessObserver` — unchanged, and still the
  producer for the pending-approvals projection, the default narrator, and
  engine health. This amendment removes one consumer from it, not the stream.
- **Attribute and metric names** — the semconv roster stands as amended
  (three duration metrics, `gen_ai.usage.*` with cache subsets, the memory
  operations). The new spans take `nessy.*` names because semconv has no verb
  for "decide an approval" or "fold an event", the same reasoning that keeps
  the two waits ours.
- **Containment** — an observation must never break a fold, a turn, or a tool.
  Opening a scope adds a `close()` that must be in a `finally` and inside the
  same guard as everything else.
- **The one seam** — `HarnessConfig.observationRegistry(...)`. Nothing new is
  configured.

## 6. Tests

- A fold that writes to a store which itself records an observation shows that
  observation as a **child** of `nessy.fold` — the scope is real, not
  decorative. This is the test that would have caught the flat JDBC spans.
- `nessy.approval.seek` wraps enricher and approver execution: an approver
  that sleeps shows that duration on the seek span, and an observation the
  approver records nests inside it.
- All three seek outcomes are pinned — an allowing approver yields
  `approved`, a denying one `denied`, one that calls `context.defer()`
  `deferred` — and a throwing approver yields `denied` with `error.type`
  set, matching the fail-closed behaviour the executor already has.
- `execute_tool` carries `returned` for a normal tool, `failed` for one that
  throws, and `deferred` for one that called `ToolContext.defer()`.
- A CAS conflict produces two `nessy.fold` spans in one round.
- The waits are unchanged and their existing tests stand.
- Containment: a handler that throws on start or stop of each new span leaves
  the turn's outcome, the tool result and the fold unaffected.
- No standalone root spans are produced by any of the above.

## 7. Open for James

1. The `nessy.fold` and `nessy.approval.seek` names — both are `nessy.*`
   because semconv has no equivalent verb; say if you want different words.
2. Whether `search_memory`/`create_memory` opening a scope is wanted, or
   whether the fold's scope is enough (the memory call happens inside the
   fold, so a JDBC statement would nest under the fold either way; the
   memory scope only adds a level).

## 8. Rejected

- **Moving the waits in-loop.** They outlive the thread and the process; a
  scope cannot span three days.
- **Keeping the JDBC library and fixing the flood with sampling.** Sampling a
  flood still buys nothing when the spans carry no `db.*` attributes and do
  not nest.
- **Recording counters through a `MeterRegistry`** to avoid the span-event
  shape. That would be a second seam, and James ruled one.

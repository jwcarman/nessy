# Nessy on the actor model

**Status:** DECIDED 2026-08-27 by James — *"Strong yes."*

**Supersedes:** `2026-08-26-deferral-by-callback-design.md` in full, and the
tool-call state machine it produced (Tasks A2/B/C1, commits `ca8b7e73`, `76898fa8`,
`a491b08c`, `1e0c4239`, `d81c5150`, `5b760093`). Those remain on `main` and remain
correct; they are simply not where this is going.

**Evidence:** four spikes on `main` — `24a0fd08`, `f1654cc2`, `be501484`,
`268ebf2c` — plus reports in `.superpowers/sdd/pekko-spike-report.md`.

---

## 1. The decision

Nessy is built on the **actor model**, with **Apache Pekko** as the runtime.

The argument that decided it is not that Pekko can do what we need. It is that
**the actor model deleted code we had already written by hand.**

`SpikeCallPhase` — a sealed hierarchy with an admission matrix and per-variant
re-fire rules — was *deleted* in `be501484`. Three things went with it:

- the admission matrix became **behaviour transitions the compiler checks**;
  "awaiting approval" is a behaviour with one `onMessage` case, not a map entry
  someone must decide is admissible
- `outstanding()` collapsed to `calls.stream().filter(c -> !c.settled())`
- answering an approval stopped writing agent state at all

Per-call state went from `(id, tool, argument, SpikeCallPhase)` to
`(id, tool, argument, outcome-or-null)`.

That is most of a week's work dissolving, because it existed only to make **one
document hold N concurrent lifecycles** — which is the problem actors already solve.
We were hand-rolling an actor system, badly.

## 2. What was proven, not argued

- **Runtime independence is enforced by the build.** `AgentActor` compiles in a
  module with no `pekko-cluster` dependency; the cluster module imports it and
  changes only how an id becomes something you can `tell`. Shared contracts run in
  both tiers — 11 green local, 5 green sharded.
- **Restart recovery works.** Park on an approval, terminate the ActorSystem, start
  a new one, answer, complete. In both tiers.
- **Self-healing.** A turn killed mid-model-call resumes itself on the next start
  with nobody sending it anything (`rememberEntities` + eventsourced store).
- **The real watchman ported.** Two turns against a local model, parked, survived a
  full restart, denial through a freshly started system, round completed. 12.6s.
- **Traces hold.** W3C `traceparent` in the envelope, re-opened on receipt; one
  trace id across four thread pools, asserted by a test.

## 3. The composition

**Entities — durable, one per id:**

- `AgentActor` — owns the turn and its phase. **The only actor that persists.**

**Ephemeral children:**

- `ToolCallActor` — one per invocation, keyed by tool call id. Owns its own
  lifecycle, timer and retries. Stops when it has an outcome.
- `ApprovalActor` — one per approval. Owns the request, the deadline and the answer.

**Workers — pooled, stateless:**

- model calls, tool execution, request preparation

### Rulings

**No `TurnActor`.** Only one turn runs at a time per agent, so a turn actor adds a
layer with no concurrency to exploit and no state the agent does not already hold.
`AgentActor` *is* "one per agent, holding the current turn"; `Idle` is "no turn in
flight." This changes only if agents ever need concurrent turns.

**Tool calls are ephemeral children, not persistent entities.** The parent must
persist "in flight" regardless, so a persistent child stores the same fact twice in
two places that can disagree. Children also keep locality (they move with their
agent under sharding) and leave nothing behind to retire.

**Supervision is `stop`, never `restart`.** A restarted tool call is a tool that may
run twice. Supervision's only job is to notice a child died; the *decision* —
retry or fail — belongs to the agent, which alone holds the attempt count and the
declared `maxAttempts`.

**Failures the agent must reason about arrive as messages, not supervision events.**
So the model call is not supervised by the agent at all: it runs behind a desk, and
its failure comes back as `ModelFailed` and folds like any other outcome.

**The child tells the parent, then stops itself.** Ordering ceremony buys nothing —
a crash before the parent's commit re-runs the call either way, and the child holds
nothing the message does not carry.

## 4. Typing

**Sealed interfaces, not type parameters.** `EntityTypeKey.create(X.class, …)` and
`ServiceKey.create(…)` need class literals; generics would mean fighting erasure in
exactly the plumbing meant to be type-safe.

The user's observation vocabulary is a **sealed interface**, enforced at the `tell`
boundary. `ObservationRenderer<O>` — `List<ContentBlock> render(O)` — is the entire
insulation between the user's world and ours.

**Below that line the vocabulary is closed and ours.** The actor never sees a user
type, so `Behavior<AgentCommand>` is unparameterized, switches are exhaustive, and
serialization covers a closed set with discriminators we control.

**Consequence:** rendering at the `tell` boundary means the backlog holds
`List<ContentBlock>`, so `SubstrateBacklog`'s `Codec<O>` requirement disappears —
one fewer thing users must supply, one fewer place user types reach the wire. The
trade is that a renderer change cannot be replayed over old observations.

**Users never write actors.** Tools, grants, a renderer, a vocabulary. Pekko is
invisible above that line — which means its costs are ours to carry, not theirs to
learn, and replacing it later would not be an API change.

**Watch for leakage:** if `Tool<T>`'s signature has to change, or tool authors need
to know about dispatchers or supervision, the abstraction has leaked and this
calculus shifts.

## 5. Persistence

**`DurableStateBehavior`**, whose revision-number CAS is the `Versioned<AgentPhase>`
mechanism we already built independently.

**Substrate stays**, and not for portability — **it is the extension point.** Pekko's
persistence is per-entity recovery state, not a general store; there is no
"store this by key" door. Delete Substrate and the next feature that needs to
persist anything has nowhere to go. Pekko Projections also write *into* a store you
provide, so read models need it regardless.

The split is write-side/read-side, not duplication:

- **Pekko** — entity recovery state
- **Substrate** — projections, transcripts, read models, whatever comes next

**Serialization is ours.** A `SerializerWithStringManifest` over our codec: no
`pekko-serialization-jackson`, no `jackson-module-scala`, no version range against
our pinned `com.fasterxml.jackson.databind` 2.22, no `JacksonObjectMapperFactory`.
Our discriminators, our bytes, our tests. **Encryption comes with it** — codec 0.5.0+
supports it and we are pinned to 0.4.0, three releases back.

### Claim-check, and what is never persisted

**Facts** — the assistant's reply, tool results, observations, human decisions — go
in the append-only transcript; state references them **by id**.

**Derived things are never persisted at all, not even as ids.** The assembled
context is recalled from the transcript at call time. Persisting it would duplicate
the transcript, bloat every revision, and let recovery replay a stale assembly.
`AwaitingModel` carries nothing and must not start.

**Ordering:** write the transcript entry **first**, then the state referencing it.
A crash between leaves an orphan entry (harmless) rather than a dangling reference
(broken).

**This makes "never trim the transcript" a hard requirement** — which is also what
the resumable subscription needs (`seq` as offset, `Last-Event-ID` to resume). Three
things want the same design, which is usually a good sign.

## 6. Durability rules

**A message must be durable if it carries a fact that exists nowhere else. An
instruction re-derivable from persisted state need not be.**

| durable | plain `tell` |
|---|---|
| an observation arriving | agent → tool call: "run this" |
| a human's decision | tool call → worker: "execute this" |
| a tool result | anything recovery re-derives |
| a model response | |

Plain `tell` is at-most-once and its mailbox is in memory. Durability is opt-in per
flow via `ProducerController` + `EventSourcedProducerQueue`. Classic "durable
mailboxes" were removed from Akka in 2.3; this is the replacement.

**Facts arriving from outside must be persisted *before* they are acknowledged.**
The spike's tidiest result — that answering an approval touches no state — did not
survive the port: it loses denials on a crash after a 200. `ask` + `thenReply`, and
the HTTP handler waits.

**Anything that only works on one node is a bug, not a simplification.** Multi-node
is a Pekko configuration decision, not a design exclusion. Correctness must not
depend on the simpler deployment. This is why context propagation is manual
(the OTel agent's breaks across remoting) and why tests must inject duplication,
loss, and entity relocation rather than proving the happy path on one box.

## 7. What this deletes

`ComputationCallback`; `Deferred(callback, term)` on both `Awaited` and
`ApprovalOutcome`; `DeferApproval`/`DeferToolCall`; `DeferringApproval`/
`DeferringResult`; §9a's mandatory cell 1; the fold-before-callback ordering ruling;
the dropped-park gap; `outstanding()`; `StalenessPolicy` as a recovery trigger.

**Why:** the actor waiting for the answer *is* the callback — and a better one,
because it has an address, state, a timer, and unlike a closure it is
reconstructible from persistence. The `Deferring…` phases existed *only* because a
closure was not. Answers route by `(agentId, callId)`, so nothing is minted and
nothing is handed out.

**Continuum** dies for approvals — the actor is the thing waiting, and the deadline
is recomputed from the persisted ask time. It survives for `long_job`, where a
memoised outcome remains the only answer to "it ran and died."

## 8. Costs, honestly

- **Pekko ships no Spring integration.** The glue is permanently ours.
- **Two JVM shutdown hooks.** Pekko installs one, Boot installs one, unordered —
  Pekko can pull the ActorSystem out from under Spring on a clean Ctrl-C. Fix:
  `run-by-jvm-shutdown-hook = off` plus `SmartLifecycle` below the web server's
  phase. **Every Pekko-on-Spring app needs this.**
- **Two config systems** (HOCON and `application.yml`) and two connection pools.
- **`Map<String,String> trace` on ~15 command signatures** — the price of manual
  context propagation.
- **Our failure modes become partly Pekko's.** At 2am you debug shard allocation.
- **A pool router bounds concurrent message *processing*, not in-flight *work*.**
  A pool of 4 will start 400 overlapping async tools. Use work pulling where
  backpressure is the point; confirm *after* the answer arrives.

## 9. Open

1. ~~**The audit trail is a capability regression.**~~ **ANSWERED 2026-08-27 by
   observation, not argument.** Durable state does overwrite revisions, but the
   decision is already claim-checked into the append-only transcript. Observed live
   after denying a real approval on the ported watchman:

   ```json
   {"turn":"tool-result","callId":"845087294","tool":"prune_images",
    "text":"denied by watchman: Denied - prune -af would delete the images this stack runs on"}
   ```

   Approver identity and reason survive; `/recent` is a transcript query, not a lost
   capability. **Still missing: a timestamp on the entry** — ordering would have to
   come from the journal's own sequence. Add one if `/recent` is built.
2. **Silent stalls have no signal.** Observed live: 22 hours, ~1,100 rounds, zero
   errors, no assistant output at all — invisible because nothing counts
   "rounds that produced nothing." A model call that neither returns nor fails
   within its deadline must be an event.
3. **Suspend/resume as a test case.** Laptop sleep is the cheap local version of
   "the process is alive but the world moved on." Reproducible, and nastier than it
   looks.
4. **The desk's queue is unbounded.** Work pulling backpressures the producer;
   agents cannot block, so the queue lives in the desk.
5. **Tier 1's single-writer is per-JVM only.** Two replicas against one database
   would both drive the same agent. An operational constraint to enforce, and
   exactly what tier 2 lifts.
6. **Codec is three versions behind** (0.4.0 → 0.7.0); encryption arrived in 0.5.0.
7. **Pekko 2.0 timing.** M4 core is released-quality but `pekko-persistence-jdbc`
   sits at M1. 1.7.0 is the stable place to build.

## 10. Not solved, by anyone

If the thread running `restart_container` dies halfway, nothing — not leases, not
heartbeats, not dispatch records, not supervision — knows whether the container was
restarted.

Temporal does not solve it either; it declares it the activity's problem and hands
you an idempotency key, stating the guarantee as *"the Activity will be observed as
completed exactly once. However, the Activity may be executed multiple times."*

`maxAttempts`, declared per grant, is our version of that contract. It does not make
a tool safe. It lets the tool's owner say whether it is.

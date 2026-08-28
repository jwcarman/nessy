# Principles and findings

**Status:** carried forward, 2026-08-28. Not a design. This is the residue of fifty-six
specification documents and several weeks of exploration — the rules that keep binding and the
facts we paid to measure — kept so that the specifications which replace those fifty-six can
stand on something without repeating it.

**How to use it.** Every rule here earned its place by something going wrong, and the story is
usually the load-bearing part: a rule without its origin gets "simplified" away by the next
person who reads it. Where a finding has a number, the number is the point. "We measured" beats
"we believe", and most of these were believed incorrectly first.

---

## 1. Principles

### 1.1 Facts versus derived

**Persist what exists nowhere else. Never persist what can be recomputed.**

The assistant's reply, tool results, observations, human decisions — these are facts, and they go
in the append-only transcript. State references them **by id**.

The assembled model context is *derived*. Persisting it would duplicate the transcript, bloat
every revision, and let recovery replay a stale assembly. A phase waiting on the model carries
nothing and must not start.

**Ordering is not negotiable:** write the fact **first**, then the state that references it. A
crash between the two leaves an orphan entry, which is harmless. The other order leaves a
dangling reference, which is broken.

### 1.2 Idempotence beats atomicity

Wherever two writes cannot be made atomic, make the second one *safe to repeat* instead.

This is why re-folding is a no-op: a stale or redelivered event meets a phase that has moved past
admitting it, and is dropped with a WARN. At-least-once delivery is therefore safe without
atomicity between ack and fold. It is also why adopting Continuum needed no transaction seam —
our reducer was already idempotent.

The general move: **derive keys deterministically from their inputs** rather than minting fresh
ones. Then redoing the work produces the same key, and idempotence-by-key makes the repeat free.
A two-store operation that cannot be atomic becomes correct by being deterministic.

### 1.3 At most once by result, not by id

Route answers by **address** — `(agentId, callId)` — which the call has had since it was created.
Accept the first answer for a call with no result; drop anything after a result, with a WARN.

Nothing is minted and nothing is handed out. This removes two failure modes at once: an answer
arriving before the park is recorded becomes *redundant* rather than a hang, and a dropped park
stops being a gap.

**Corollary:** nothing may depend on the park having been recorded. Anything the park would carry
— a deadline, a frozen request — can only be something we would *like* to show, never something
the machine needs.

### 1.4 The grant principle

**The host declares what a tool may do.** Not the tool.

We do not author MCP tools, so they cannot declare anything on their own behalf, and the MCP
specification is explicit that clients **MUST** treat tool `annotations` as untrusted unless the
server is trusted. The host bears the consequences, so the host makes the declaration, and the
grant is already where the host says what a tool may do.

Unknown and remote tools default to the conservative answer.

### 1.5 `maxAttempts` is a contract, not a guarantee

If the thread running a side-effecting tool dies halfway, **nothing knows whether the effect
happened.** Not leases, not heartbeats, not dispatch records, not supervision.

This has no solution, and it is worth stating precisely so that no design claims to fix it.
Temporal does not solve it either; it declares it the activity's problem and hands you an
idempotency key. Their guarantee is worth keeping verbatim:

> "Temporal guarantees that the Activity will be observed as completed exactly once. However, the
> Activity may be executed multiple times."

`maxAttempts`, declared per grant, is our version. **It does not make a tool safe. It lets the
tool's owner say whether it is.**

### 1.6 Single-node-only is a bug, not a simplification

Multi-node is a configuration decision, not a design exclusion. Correctness must never depend on
the simpler deployment.

Two concrete consequences we have already paid for:

- **Context propagation is manual**, carried in the message, because thread-local propagation does
  not survive remoting. `ContextSnapshot` is strictly less work for an in-JVM hop and was rejected
  for exactly this reason — it is a thread-local capture, not a serialisable carrier.
- **Tests must inject duplication, loss, and entity relocation**, rather than proving the happy
  path on one box.

### 1.7 Sealed interfaces, not type parameters

`EntityTypeKey.create` and `ServiceKey.create` need **class literals**. A generic type is raw
there, which is an unchecked warning, and this repository does not permit suppressing warnings.

So each actor nests its own non-generic message type. This loses nothing, because each actor
already owns its own protocol, and it buys exhaustive switches and a closed serialisation set with
discriminators we control.

The user's observation vocabulary is a sealed interface too, enforced at the `tell` boundary.
Below that boundary the vocabulary is closed and ours.

### 1.8 A fallback that hides a misconfiguration is worse than no fallback

Earned three times in a single afternoon, in three different tools, with one shape:

- `getIfAvailable(() -> Tracer.NOOP)` silently degraded to a no-op tracer when Boot's tracing
  autoconfiguration had not run. **Every span in the application became a no-op. The application
  ran normally and all tests passed.** Nothing failed; the traces simply stopped existing.
- `curl` without `-L` returns the **empty body** of a 302. So "has `</html>`", "has a login form",
  and "has any links" all read identically to "nothing to do". Two versions of a script did
  nothing for half an hour while appearing healthy.
- `mvn test-compile` reported BUILD SUCCESS over **stale test classes** that referenced a deleted
  type. Only `clean` surfaced it.

**The rule:** if a check cannot distinguish success from nothing, it is not a check. When
degrading, say so loudly. When verifying, verify the artifact — read the jar, read the exported
span, read the row — not the exit code.

### 1.9 New public concepts need an explicit yes

A public type, abstraction, or vocabulary word that has not been explicitly discussed requires
sign-off **before** it lands. Surfacing it inside a plan or a dispatch brief does not count as
discussion; it has to be raised by name, as a question.

*Origin:* `ToolGrant.Judgment`, invented un-discussed inside the action-wave plan to dodge an
erasure problem, ruled a bad design, and executed in the context-pipeline reform.

### 1.10 Claim-checked notifications are hints, not data

A notification carries a **reference** — an id, a sequence number — never a payload. Subscribers
fetch what they want.

The consequence is worth stating because it makes an entire class of problem disappear: **a
dropped notification costs latency, not correctness.** A slow consumer catches up by reading the
journal from its offset. Backpressure stops being a correctness question and becomes a tuning
question.

### 1.11 Never suppress a warning

No `@SuppressWarnings`, no `// noqa`, no `eslint-disable`. Fix the underlying issue.

The single narrow exception is `@SuppressWarnings("deprecation")` where a specification mandates a
deprecated API, and every such suppression names the spec contract that requires it.

---

## 2. Measured findings

### 2.1 Storage and state

| finding | measurement |
|---|---|
| Transcript embedded in durable state | grew **14×** in 64 minutes (1,709 → 24,151 → 34,141 bytes); every revision rewrote the whole document |
| Transcript moved to an append-only journal | state flat at **16 bytes** across 100+ revisions |
| `SubstrateJournalStore.head()` read every entry | full scan; **20 buffers, 49 kB** of payload |
| `head()` as an abstract SPI method with a `MAX(seq)` override | **index-only scan, 3 buffers, 0 bytes** of payload |
| Journal growth, live | **~327 bytes/entry payload, ~1.4 kB all-in** with indexes; ~5 entries per round |

**The `head()` ruling generalises:** the SPI method was made **abstract, not `default`**. A default
implementation would have let a future backend silently inherit the O(n) bug. We are the only
implementers, so there is nothing to be backwards-compatible with.

**Entry count is predictable; entry size is not.** Our tools return a few hundred bytes; a tool
returning a log file returns megabytes. Any retention cap must be size-aware, not merely
count- or time-based.

### 2.2 Context and prompts

| finding | measurement |
|---|---|
| Unbounded prompt growth | **180 turns, 69,453 bytes** in every prompt |
| After adopting `Memory` | bounded at **57 messages** |

### 2.3 Concurrency

**A pool router bounds concurrent message *processing*, not in-flight *work*.** A pool of 4 will
start **400 overlapping** async tool calls, because each worker hands the work to another thread
and returns to its mailbox immediately.

Where backpressure is the actual requirement, use **work pulling** and confirm *after* the answer
arrives. That makes the number of workers the number of concurrent calls — no semaphore, no
counter, no configuration.

### 2.4 Observability

| finding | measurement |
|---|---|
| Orphan spans before wrapping operations properly | **278** |
| After | **6** |
| Full round trace, actor runtime | **11 spans, one trace id**, across four thread pools |
| Observations refused while parked on one approval, BEFORE the backlog | **26 of 31 rounds** (later 32 of 38) |
| Observations refused while parked, AFTER the backlog | **0 of 8 rounds** — they coalesce and wait |
| Ticks waiting behind a parked turn | **5 collapsed into 1 entry**, holding the first tick's `receivedAt` and the latest tick's content |
| Agent state while parked, 3 claims held | **356 bytes at revision 15** — arguments live in claims, not the document |

The last row is the number that justifies a durable backlog. The refusal message was honest —
*"it is not queued and it is not coming back"* — but the transcript showed the observations'
**text** was durably captured; what was destroyed was the **trigger**. That distinction reshaped
the ingest design.

**Consecutive `user-message` entries** appeared in the journal, one per refused observation, with
no assistant turn between them — a malformed context, and the reason `remember` belongs at the
*start of a turn* rather than at ingest. After the change: **zero** consecutive pairs across a
15-entry journal.

**A bug NO test caught, found only by running (2026-08-28).** With the backlog shipped and every
review clean, a six-round soak produced exactly ONE `user-message`. Every model call after the first
ran against an identical stale context — `in=1222 out=1`, unchanged round after round — because
`Coalescer.byKey` used the coalescing key as the backlog entry id, and the `Remembrance` key derived
from that id. Every coalesced arrival therefore carried the key `obs:k:rounds`, and
idempotence-by-key silently swallowed all of them after the first. Two individually defensible
decisions composing into total data loss, with nothing thrown and a green suite.

The lesson is not "coalescing is hard". It is that **the soak asserted the absence of a bad thing
(refusals) and not the presence of a good one (observations recorded)**. Zero refusals and zero
errors looked like success while the agent did nothing at all. Count what should happen, not only
what should not.

### 2.5 Runtime and integration

- **Pekko and Boot install unordered JVM shutdown hooks.** Pekko can pull the ActorSystem out from
  under Spring on a clean Ctrl-C. Every Pekko-on-Spring application needs
  `run-by-jvm-shutdown-hook = off` plus a `SmartLifecycle` below the web server's phase.
- **Dependency injection into typed actors is a non-problem.** The Behavior factory *is* the
  injection point. The entire `SpringExtension` / `IndirectActorProducer` genre exists to work
  around Pekko Classic's reflective instantiation, which Typed does not have.
- **Maven resolves by nearest definition.** A direct `test`-scoped declaration beats a transitive
  `compile`-scoped one, which removed `opentelemetry-api` from the runtime classpath entirely and
  disabled all tracing. Scope `runtime` keeps a dependency off the *compile* classpath — which is
  how you enforce "this module must not import that API" — while still shipping it.
- **SLF4J with no binding falls back to NOP.** The first soak produced a running process and an
  empty log file.

### 2.6 JDBC dialect hazards

Measured against real databases, for the JDBC Substrate:

- **CockroachDB is indistinguishable from PostgreSQL** by JDBC metadata.
- **Yugabyte hijacks `postgresql` URLs.**
- `SKIP LOCKED` parses everywhere, which means parsing is not evidence that it works.

---

## 3. Tried and rejected, with reasons

Keeping these prevents re-litigation.

**Reflection** — ripped out 2026-08-16. A future attempt would have to satisfy what that removal
documented.

**Kamon for cluster-wide tracing** — it works, and it propagates across hops with zero trace
fields in the message. Rejected because **the Kanela javaagent is not optional; it is the
mechanism**, its `Context` and our `ObservationRegistry` ignore each other entirely, and its
`kamon.trace.sampler` defaults to `"adaptive"`, which silently dropped every span. ~13 MB, of
which Kanela alone is 10.4 MB.

**`ContextSnapshot` for actor-hop propagation** — strictly less work than a header carrier for an
in-JVM hop, and it carries MDC too. Rejected because it is a thread-local capture, not a
serialisable carrier, so it cannot cross remoting (§1.6).

**A tracing envelope wrapping the command** — `Envelope(Command payload, headers)` keeps tracing
out of every record's signature. Rejected because it makes carrying context *optional* at each
send site, and a send that forgets produces an orphan span rather than a compile error. Headers
live on the sealed message interface instead; fifteen extra record components is the price, and it
is cheaper than one silently broken trace.

**A `default` SPI method for `head()`** — see §2.1.

**`seonWKim/spring-boot-starter-actor`** — use Apache's own components.

---

## 4. Open findings, not yet acted on

Carried so they are not lost with the specifications that recorded them.

**SPI:**

- Nothing applies the token budget: `limitTokens` has **no production caller**, and
  `ModelSettings.contextWindow` is validated and **read nowhere**.
- `limitTokens` **over-evicts** — `pairSafeCut(0)` takes the largest cut, keeping 57 of 342
  messages against a 300 budget.
- `recall()` shows nothing of the in-flight turn mid-round.
- `OpenAiProviderConfig.provider(String)` is **package-private**, so `gen_ai.provider.name`
  misreports every OpenAI-compatible endpoint. `apiKey` and `baseUrl` are public; the asymmetry
  has no reason.
- `Context.lines()` renders tool traffic as blanks.
- `Remembrance` keys are **global and forever** — idempotence-by-key silently swallowed a reissued
  id and hung a round.
- The `gen_ai.*` instrumentation lives in `ProviderModelCallExecutor`, not with the model, so any
  runtime that is not the harness re-implements it or goes without.

**Runtime:**

- **Silent stalls have no signal.** Observed: 22 hours, ~1,100 rounds, zero errors, no assistant
  output at all — invisible because nothing counts "rounds that produced nothing". A model call
  that neither returns nor fails within its deadline must be an event.
- **Answering an approval after the round has moved on returns HTTP 500**
  (`IllegalStateException: no round is waiting on this agent`). The idempotence guard covers "call
  already decided within the working phase" but not "round has advanced". The approvals page is a
  snapshot, so stale clicks are guaranteed.
- **Suspend/resume is an untested case.** Laptop sleep is the cheap local version of "the process
  is alive but the world moved on".
- **A gated tool proposed every round makes a continuous agent effectively single-shot.** Without a
  backlog, human-in-the-loop and continuous operation are incompatible.
- **Codec is three versions behind** (0.4.0 → 0.7.0); encryption arrived in 0.5.0.

**Testing:**

- **Tests that construct the tracing stack directly never exercise the framework's wiring.** A full
  green suite asserted a perfect span tree while the shipped application emitted nothing at all.
  Any test for wiring must go through the real bean graph, or it is testing itself.
- Exception-assertion lambdas contain exactly **one** invocation that can throw (S5778).
- Assert emptiness **before** any all/none-match assertion on the same collection, so the predicate
  cannot pass vacuously (S5841).

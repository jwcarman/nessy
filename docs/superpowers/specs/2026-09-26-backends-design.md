# Backends: one unit of configuration per door, and nothing to get wrong in between

**Status: APPROVED IN SHAPE, LANDING IN STEPS — the shape is James's; the sizing is this record's.**
The preparatory steps have landed (the plural rename `4ea77f9e`, the package restructure
`a0fa98a2`, `nessy-backend-spi` `07c275e3`, the dependency inversion `1a9ee75b`, `Failure` staying
put `dde22239`, the backlog interfaces' first cut `e0eb76cd`, and the lock SPI with both
implementations `0e25584f`/`d678e8dd`/`391d536d`). The backend interfaces, the three JDBC/in-memory
backends, the factory cutover, the TCK, the backend modules and the module/DDL split have not. Every
fact about the working tree was re-verified on 2026-09-26 after `391d536d`, with the direct door's
per-step locking in flight (the direct-door files are read for structure and never cited by line).
Every signature under "the design" is a proposal unless it says otherwise.

**Before trusting a green build.** `nessy.excludedGroups` defaults to `live,container`, and every
test in `engine.jdbc` plus `DurableDirectHarnessTest` carries `@Tag("container")`, so a plain
`./mvnw clean verify` runs none of them; `DurableDirectHarnessTest.termination_is_durable` was
failing invisibly under that default until it was found and fixed. The gate for any step in this
record that touches JDBC — which is most of them — is `-Dnessy.excludedGroups=live`. The locks
record's status block has the full finding, including which container-backed tests are *not*
tagged and so do run in a plain build.

**What this record owns.** The backend interfaces and their three implementations (§4–§5), the
three backlog interfaces and `Agents` (§4), the factory cutover and what each config loses (§6),
the `Effects` extraction and the `Backlogs<I>` lift (§8b–§8c), the TCK (§8e), the backend modules,
the module split with its DDL split and the retirement of `nessy-spi` (§5, §9). The order in which
all of it lands is stated once, in the locks record's §13a; §9 here says only which of those
steps are this record's. The lock SPI, `JdbcRowLocks`, the direct door's per-step locking and
recovery are the locks record's and are cited, not restated.

Date: 2026-09-26. Adjacent to `2026-09-25-locks-as-plumbing-design.md`, whose §11 measured the
queued door's JDBC coupling and recommended this follow-on. Three rulings made while this record was
being written are folded in rather than appended: the store types take the plural name and lose the
word "Store" (§3, landed), there is no single `JdbcBackend` (§5), and a backend's contract states the
door's semantics while "shared" and "durable" are two properties of an implementation, named by its
type and not by a flag (§4a). Rulings made since, folded in the same way: `Agents` is approved
(§4); the three backlog interfaces are `Coalescing<I>`, `Backlog<I>` and `Agents` (§4); the TCK is
extracted from the JDBC tests (§8e); the backend implementations get modules of their own before the
door modules exist (§5, §9); and `nessy-lease` keeps its name (§10).

---

## 1. The premise: JDBC-versus-in-memory is all-or-nothing

James asked whether the choice between JDBC and in-memory stores is effectively one choice rather
than several. It is, and the evidence is in the tree.

**Events and payloads must match.** An `AgentEvent` carries no content. `TurnStarted`,
`InferenceAnswered` and `ActionsRequested` each hold a `PayloadRef`, and the content behind it
lives in the payload store (`backend.event.AgentEvent`: "No payloads. Every one of these carries
identifiers, status, a human decision or a count -- and a `PayloadRef` where content would
otherwise be"). So durable events over in-memory payloads is, after a restart, a story that is a
list of references to content that no longer exists. `Payloads.get` answers `Resolved.Missing`,
and `EngineFixture.content` says what that means: "in this engine a missing payload is always a
fault." The agent cannot be reconstituted, and nothing warns until something asks for the words.
The reverse — in-memory events over durable payloads — is not corrupt, merely pointless: content
nobody will ever reference again, accumulating in a table. This is the both-stores-or-neither rule
from the JDBC substrate work (`2026-09-04-durable-runtime-design.md`, and again at
`2026-09-24-inline-inference-and-the-turn-executor-design.md` §"Constraint, and it is the existing
both-stores-or-neither rule arriving again"). It has been ruled twice; a configuration that lets it
be broken is a configuration that lets a ruling be broken by accident.

**The queued door's outbox and its events must match too.** The door's premise is not
durability (§4a says what it is); but whatever the backing, the outbox and the story have to be
the same backing, because a row in one names a state in the other. Durable events over an
in-memory outbox: after a restart the story says `Inferring` and the row that would have brought
the agent back is gone — no watchdog, no deadline recovery on this door, an agent stuck until
somebody notices. An in-memory story over a durable outbox: the row comes due, is performed, and
its outcome is folded into an agent that has no history, which the fold ignores by phase — a
queue that faithfully delivers into nothing. Corrupt one way, pointless the other, exactly as with
events and payloads.

**Locks are the exception, and the dangerous one.** In-memory locks over JDBC stores are correct
in one process and silently wrong in two: each process believes it holds the agent, both run a
turn, and the second `append` — if the seqs collide — raises `Conflict`; if they interleave
across a phase boundary, nothing raises anything. `DirectHarnessAutoConfiguration` does exactly
this today, still — re-verified after `391d536d`: its factory bean is
`@ConditionalOnBean(DataSource.class)`, builds `JdbcAgentEvents` and `JdbcPayloads` from that
`DataSource`, and wires `.locks(locks.getIfAvailable(InMemoryLocks::new))` — durable stores,
process-local locks, unless an application happened to declare a `Locks` bean. `JdbcRowLocks`
exists now (`d678e8dd`) and the queued factory constructs one, but the direct auto-configuration
does not; and fixing that default would fix the default, not the surface: a caller could still hand
`DirectHarnessFactoryConfig` any three things it likes. The cutover (§6) is what closes it.

So a config offering `.events(x).payloads(y).locks(z)` — and the store-shaped queued config §11 of
the locks record was heading toward, `.effects(w).backlogs(v)` on top — sells a combinatorial
surface where nearly every combination is wrong and one of the wrong ones is silent. Of the eight
combinations of {events, payloads, locks} × {JDBC, memory}, two are right, five fail loudly or
pointlessly, and one (JDBC stores, memory locks) fails only in production, only under load, and
only in a way that looks like the agent said two things at once.

---

## 2. The shape James asked for, and the name

James: "Can we have one 'core' unit of configuration for each harness type that combines all of
the stores necessary? ... They would contain the stuff required to run the different harness types
and then you stack on top of that simply the things that are truly yours as a user (tools and stuff
like that)?" And: "if you're creating a DefaultDirectHarness, you'd hand it a DirectBackend object
... I can see us having a few different implementations of these (memory, postgres, and file
system) with a TCK to make sure they work!"

That is the whole design. One object per door that is everything the door needs from underneath —
chosen together, so that they agree — and a factory that holds one and configures the
application's own concerns on top of it.

**The type is `Backend`, and the alternatives had history against them.** `Engine` would be a
third sense of a word that already names the module (`nessy-engine`) and the fold; the day before
this record, `93594541` renamed `EngineConfig` to `QueuedHarnessFactoryConfig` for exactly that
ambiguity ("'Engine' was the wrong word twice over: it named a configuration rather than an engine,
and nessy-engine holds both doors, so it never said which one this configured"). `Substrate` is the
most precise word for "the thing the stores are made of" — and it was a real SPI in this codebase,
with a 550-line TCK, ripped out on 2026-09-01 (`f6b0c341..98e58593`) for being a generic key-value
shape that made the code enforce its own design. Reusing the word would teach a dead design to
every reader who remembers it. `Stores` and `Storage` were rejected because the set is not all
storage: it includes `Locks`, and for the queued door the transaction boundary those locks own,
which are coordination. A name that lies about a third of its contents is worse than a generic
one. `Backend` is generic, it is already the informal word this project's own notes use for the
concept (the "store backend fit" note, "one real backend and one test double, held to a TCK"), and
it has never named a type here.

---

## 3. The names of the parts: plural, and "Store" goes (landed: `4ea77f9e`)

Ruled while this record was in draft and landed as the first step, 48 files, all mechanical. The
reasoning is kept; the "today" column below is the tree before that commit. James: "man, this plural thing keeps showing up. Why not
make the type AgentEvents (get rid of store), Payloads, Effects, etc? All of our getters are
called the plural name and then we have 'store' in the name of the type."

**The house convention is already plural, and `*Store` is the outlier.** Measured across every
`src/main` in the reactor: `Locks`, `Schemas`, `TurnHistories`, `ToolCallHistories`, `Tools`,
`Replies`, `Listeners`, `Traces`, `Transcripts`, `ToolCalls`, `JdbcAgents`, `JdbcEpisodes`,
`JdbcSummaries`, `JdbcLeases`, `InMemoryLocks`, `InMemoryPayloads`, and the inner `Backlogs<I>` in
`DefaultQueuedHarness`. Against that, the whole `*Store` population is `PayloadStore`,
`AgentEventStore`, `JdbcPayloadStore`, `JdbcAgentEventStore`, `InMemoryAgentEventStore`,
`JdbcEffectStore`, `EffectStore` in the engine, and `IntentStore`/`JdbcIntentStore`
(`nessy-approval/intent`) and `PlanStore`/`JdbcPlanStore` (`nessy-planning`) outside it.

**The smoking gun is in one interface.** `PayloadStore` is implemented by `JdbcPayloadStore` and by
`InMemoryPayloads` — verified, `InMemoryPayloads.java:34` and `JdbcPayloadStore.java:55` — so the
same concept is spelled two ways in the same codebase, and the in-memory one is the spelling the
rest of the house uses.

The renames, which also make every backend getter agree with the type it returns:

| today | after |
|---|---|
| `PayloadStore` (then `nessy-spi`, now `backend.payload` in `nessy-backend-spi`) | `Payloads` |
| `JdbcPayloadStore` | `JdbcPayloads` |
| `InMemoryPayloads` | unchanged — already right |
| `AgentEventStore` (`engine.core`, since moved to `nessy-backend-spi`, §8a) | `AgentEvents` |
| `JdbcAgentEventStore` | `JdbcAgentEvents` |
| `InMemoryAgentEventStore` | `InMemoryAgentEvents` |
| the interface extracted from `JdbcEffectStore` (§8b) | `Effects` |
| `JdbcEffectStore` | `JdbcEffects` |

**The collision the sketch did not anticipate.** There is an existing `EffectStore` class,
distinct from `JdbcEffectStore`. Verified: `engine/store/EffectStore.java` is a concrete class
constructed as `new EffectStore(agentType, handlers, effectRows)` — one per harness, holding the
agent type and the `EffectHandlers` so that `insert(agentId, effect, at, traceContext)` can look up
the terms and compute the deadline, and delegating everything else to the rows. It is not the row
store; it is one agent type's view of it. Two things cannot both be called `Effects`. The wrapper
is the thing every javadoc in the area already calls **the outbox** — "The outbox: what the agent
owes the outside world" (`JdbcEffectStore` class javadoc), "an outbox behind" (`DefaultQueuedHarness`),
"The outbox. Rows are written in the transition's transaction and performed later" (the schema),
and the whole of `2026-08-27-inbox-outbox-design.md`. So the wrapper becomes `Outbox`, and the
rows it sits on become `Effects`. That is not a new vocabulary word; it is the word the code has
been using in prose for a month, promoted to a type name. It is listed in §10 for a yes all the
same, because it lands on the design vocabulary.

**Nothing stored carries the word.** Checked: no `@JsonSubTypes` name, no span name, no metric
name, no column, no table, no `nessy-schema.sql` identifier, no string literal in `nessy-engine`
or `nessy-spi` main, and no property key contains "Store" (the only hits are the English word in
SQL comments). The wire format under `@JsonTypeInfo(Id.NAME)` names arms, not classes, so a class
rename does not touch a stored byte. The rename is a pure compile-time change.

**Sizing.** Files naming each type, main and test, excluding worktrees: `PayloadStore` 20,
`AgentEventStore` 14, `EffectStore` 11, `JdbcPayloadStore` 6, `JdbcAgentEventStore` 6,
`InMemoryAgentEventStore` 4, `JdbcEffectStore` 3 — with overlap, something under forty files, all
mechanical. The three test classes named for the types (`JdbcAgentEventStoreTest`,
`JdbcPayloadStoreTest`, `InMemoryAgentEventStoreTest`) follow. No `docs/` page outside
`superpowers/` names any of them, so the docs are untouched. `IntentStore` and `PlanStore` are
outside this record: they are other modules' types, the ruling reads as the engine's storage
vocabulary, and each is a one-commit rename whenever wanted.

**It was its own step, and the first one.** A pure rename is a diff a reviewer can verify by
reading the file list, and everything after it — the move to `nessy-backend-spi`, the extraction,
the interfaces — lands under the final names rather than being renamed twice. It sat before the
package restructure (`a0fa98a2`), so that the restructure moved files that already had their
names. The restructure's result: `engine.harness.direct` and `engine.harness.queued` hold the two
doors; `engine.jdbc` holds `JdbcAgentEvents`, `JdbcPayloads`, `JdbcEffects`, `JdbcBacklog`,
`JdbcAgents` and `JdbcRowLocks`; `engine.inmemory` holds `InMemoryAgentEvents`, `InMemoryPayloads`,
`InMemoryLocks` and `ListBacklog`; `engine.store` holds only what is backend-neutral (`Outbox`,
`Attempt`, `StorageCodec`, `TurnHistories`, `TurnHistory`, `ToolCallHistories`).

---

## 4. Two interfaces, no `extends`, and the real signatures

James rejected `QueuedBackend extends DirectBackend` outright: "I am okay if it doesn't extend!
That's just being cute." The reason is the one that shaped `HarnessConfig`: `DirectHarnessConfig`
and `QueuedHarnessConfig` both extend a self-typed `HarnessConfig<SELF>` that holds only what both
share, and neither extends the other, because "a queued config is a kind of direct config" is a
claim nothing needs. The same is true here. Nothing ever takes a `DirectBackend` and hopes to be
handed a queued one; an inheritance chain buys nothing today and runs out of luck the day a third
door appears with a subset that is not a superset of either.

**The sketch, and where it was wrong.** The brief sketched `events()`, `payloads()`, `locks()` on
both, plus `EffectRows effects()` and `<I> Backlogs<I> backlogs(Codec<I> codec)` on the queued
one. Derived from what the two factories actually construct, the real shape is:

```java
// in nessy-backend-spi, beside backend.event, backend.payload and backend.lock (ruled: the
// module; the package within it is unstated and is not a question anybody has raised)

/** Everything the direct door needs from underneath, chosen together so that they agree. */
public interface DirectBackend {
  AgentEvents events();
  Payloads    payloads();
  Locks       locks();
}

/** Everything the queued door needs from underneath. Not a DirectBackend, by ruling. */
public interface QueuedBackend {
  AgentEvents events();
  Payloads    payloads();
  Locks       locks();
  Agents      agents();
  Effects     effects();
  <I> Backlogs<I> backlogs(TypeRef<I> inputType);
}
```

`AgentEvents`, `Payloads` and `Locks` are already in `nessy-backend-spi` (`07c275e3`, `0e25584f`);
`Agents`, `Effects`, `Backlogs<I>` and the two backend interfaces are not yet.

Four corrections to the sketch, each measured:

1. **`Effects`, not `EffectRows`, and it is the plural ruling that decides it** (§3). The rows
   are the store; `Attempt` — already a public record in `engine.store` — is what a claim hands
   back.
2. **The backlog seam takes a `TypeRef<I>`, not a `Codec<I>`, and the reason is encryption.**
   `DefaultQueuedHarnessFactory.create` builds the backlog as
   `new JdbcBacklog<>(jdbc, codecs.create(config.inputType()), type, agent)`, where `codecs` is
   the factory's own `CodecFactory` — Jackson with the application's `StorageCodec` (compression,
   encryption) applied after it. The codec for an input type is therefore not the caller's to
   make: a caller handing in a `Codec<I>` of its own would write backlog rows that bypass the
   storage transform, and the one table the schema itself flags as holding raw user text ("the one
   place content sits in a control-plane table") would be the one table left unencrypted. With
   `storage(Codec<byte[]>)` moving to backend construction (§6), the backend is the only thing that
   can build the right codec, so the harness hands it the type and nothing else.
   `DefaultQueuedHarnessConfig.inputType()` already returns exactly a `TypeRef<I>`.
3. **Three backlog interfaces, and `Agents` — APPROVED.** The tree after `e0eb76cd` has two
   levels: `api.Backlog<I>` (`append`, `prepend`, `replaceAll`, `size`, `dropOldest`, `all`,
   `rewrite` — what a `BacklogPolicy.coalesce` may do, and all it may do) and
   `engine.backlog.BacklogManagement<I> extends Backlog<I>`, which adds `take()`, `seal()` and
   `terminated()`; `JdbcBacklog<I>` and `ListBacklog<I>` implement the latter, and the inner
   `Backlogs<I>` in `DefaultQueuedHarness` returns it. That split already stops a policy taking
   from the backlog, which was its point. Two things are wrong with it, verified:

   - **The name reads backwards.** `BacklogManagement` is the backlog; `Backlog` is the subset a
     policy sees. James's ruling: the api-side interface is **`Coalescing<I>`** — what a policy may
     do — approved WITH a TODO recording that he dislikes the name (§10); the engine-side one is
     **`Backlog<I>`**, extending it and adding `take()`.
   - **Two of the three methods `BacklogManagement` adds are `nessy_agent` statements.**
     `JdbcBacklog` names `nessy_agent_backlog` eleven times and `nessy_agent` twice — `terminated()`
     is `SELECT terminated_at IS NOT NULL FROM nessy_agent ...` and `seal()` is `UPDATE nessy_agent
     SET terminated_at = COALESCE(terminated_at, now())` followed by the delete. So a backend
     implementer providing a queue of waiting inputs is today required to implement agent
     termination. That is why `Agents` matters more than when it was first raised: **`Agents`** —
     `ensure(type, id)`, `terminated(type, id)`, `seal(type, id)` — owns `nessy_agent` in full, in
     `nessy-backend-spi`, and `JdbcBacklog` loses its two `nessy_agent` statements so that each
     store owns exactly one table. `JdbcAgents` (`engine.jdbc`) is already reduced to `ensure`
     (`391d536d`); `Agents` is that class's interface with the two methods the backlog was
     carrying for it. `seal()` on the harness becomes `int abandoned = backlog.size();
     backlog.rewrite(List.of()); agents.seal(type, id);` inside the same locked step, which keeps
     the invariant `seal` guards ("an agent cannot be left ended with work still queued behind
     it") because the lock's transaction holds both.

   Then `Backlogs<I>.forAgent(AgentType, AgentId)` returns the engine's `Backlog<I>`, and the queued
   door holds no concrete store type at all — James's ruling in the locks record ("I don't want
   the queued door to do direct JDBC at all"). This is the first of this record's remaining steps
   (locks record §13a).
4. **No transaction on the interface, on purpose.** The brief listed "a transaction boundary" as
   one of the queued backend's contents. It is, but it is not a method: `JdbcRowLocks.withLock`
   owns the transaction (landed, `d678e8dd`) — ensure the row in its own committed transaction,
   then begin, `FOR UPDATE`, run the work, commit — and the locks record's §8a measurement 1 shows
   that every `JdbcClient` statement over the same `DataSource` inside that work joins it. So the transaction is a property of `locks()`, and a backend that
   pairs `JdbcRowLocks` with JDBC stores gets "events, effects and backlog in one unit of work"
   for free, while a backend that pairs `InMemoryLocks` with in-memory stores gets exclusion and no
   atomicity — which, as §8c of the locks record says, is what in-memory has always meant here.

**The sharing does not live in the implementation.** The first draft of this record, following
the brief, had one `JdbcBackend implements DirectBackend, QueuedBackend` and an in-memory class
implementing only `DirectBackend`. That is overtaken by §5. The cost of two independent
interfaces is three signatures declared twice, which is the price of not asserting a relationship;
if a neutral supertype ever earns its place, the thing that would earn it is the TCK (§8e) wanting
to run one shared suite for events, payloads and locks against every backend, and extracting it
then would be evidence-driven rather than anticipated. It is not designed here.

### 4a. What the contract says, and the two properties it does not: shared and durable

A draft of this record asked whether `QueuedBackend` should carry the word "durable" — in its
javadoc or its name — so that nobody wires an in-memory one into a service and waits hours for an
approval that died with the process. James's answer came in two steps, and the second reframes the
first.

**Durability is not the essence of the queued door.** James: "the idea there is fire-and-forget
semantics and we allow queueing of the inputs over time." The door's own javadoc in `nessy-api`
already says exactly that and nothing else: "**It always accepts.** Telling an agent something
cannot fail and cannot be refused: what arrives goes in the queue, and the queue is what makes the
agent's own pace nobody else's problem. Nothing comes back, because there is nothing a caller could
do with it -- by the time the turn runs, whoever spoke has gone." So the **contract** — what a
`QueuedBackend` implementation must honour and what a TCK can test — is the door's promise: `tell`
returns immediately and always accepts; inputs queue over time and coalesce per the harness's
`BacklogPolicy`; the outcome arrives later, through the callback, not the caller. All of that holds
over a map. Durability is not in it.

**Then: "durability is the thing that makes it work in a distributed environment really well"**,
and, asked about the two axes, "yes, it's sharable and durable." A database gives both at once,
which is why they blur; they are different properties and only one of them is about distribution.

| | **shared** — every instance sees the same rows | **durable** — survives a restart |
|---|---|---|
| `JdbcDirectBackend`, `JdbcQueuedBackend` | yes | yes |
| `InMemoryDirectBackend` (and an in-memory queued one, if ever written) | no | no |
| a filesystem backend on local disk | no | yes |

*Shared* is what lets instance B's poller claim an effect instance A's `tell` wrote, and what lets
a row lock exclude across machines. *Durable* is what lets an approval parked on a person be
answered after a deploy. Each backend implementation in this record states its row in its class
javadoc; the table is the whole of the rule.

**The in-memory backend's real hazard, restated.** Its problem in a distributed deployment is not
that it forgets — a single instance that forgets on restart is a legitimate configuration, and the
console runs on one. It is that two instances would each run their own turns for the same agent,
each believing it held that agent, because nothing either holds is visible to the other. That is
the `InMemoryLocks` hazard the locks record documents at `DirectHarnessAutoConfiguration`'s
`locks.getIfAvailable(InMemoryLocks::new)` and §1 above measures, now stated as a property of a
whole backend rather than of one class: an in-memory backend is *unshared*, and unshared is the
property that breaks exclusion, before durability is ever asked about.

**The filesystem quadrant is the useful one in this framing.** Local disk is fully durable and not
shared at all — so a filesystem `QueuedBackend` would buy the less useful half of what a database
gives and would still be useless distributed: instance B cannot claim a file on instance A's disk,
and a file lock excludes across processes on one machine only. That is a sharper argument against
building one than the file-locking difficulty §8e records, and it is independent of it; both stand.

**No capability flags.** A `boolean shared()` / `boolean durable()` — or a `Set<Capability>` —
that the starter or a harness could query is machinery for a problem three implementations do not
have. The type names carry it: `InMemoryQueuedBackend` is self-describing, `JdbcQueuedBackend` is
self-describing, and a reader who has to be told by a method what the class name already says is
not helped by the method. The javadoc says the rest. If a fourth implementation ever sits in a
quadrant its name cannot say, that is the day to reconsider, with evidence.

**Bounding is a policy concern, and already backend-agnostic.** `BacklogPolicy.bounded(int)` bounds
a JDBC table exactly as it bounds a map — it reads `backlog.size()` and calls
`backlog.dropOldest(...)` through the `Backlog<I>` interface and knows nothing about rows — so an
in-memory backend does not need to invent a bound of its own, and `keepAll()` over JDBC carries the
same unbounded exposure relocated to disk. Verified: `bounded` computes `waiting - max + 1`, drops
that many oldest, then appends. So it **drops the oldest rather than applying backpressure**, and
that is a named trade rather than a defect: it keeps `tell` "returns immediately" and "always
accepts" by sacrificing an input, and its javadoc already calls this "bounded loss." Real
backpressure would trade the first promise for the third — `tell` would block or refuse to keep
every input — and the door would no longer be fire-and-forget. A backend cannot make that choice
for the harness, and it should not try.

**Prose this record's work has to rewrite**, found by reading the queued door's javadocs for a
justification in terms of durability rather than fire-and-forget-plus-queueing:

- `DefaultQueuedHarness`'s class javadoc, as `391d536d` left it: "**Nothing here holds a lock
  while a model is called.** `Locks#withLock` takes the agent, folds a command, writes the events
  and the effects it decided on, and commits -- the lock absorbs the transaction ... That is the
  whole reason this door exists and the whole reason it can wait hours for a person to approve
  something." The first sentence is true and stays; the "Transactions are explicit" paragraph is
  already gone with the `TransactionTemplate`. "The whole reason this door exists" is still the
  lock's transaction, which is the JDBC backend's property and not the door's; the door exists for
  fire-and-forget and queueing, and "wait hours" is what a shared, durable backend adds. That
  sentence is rewritten in the cutover step.
- `QueuedHarnessFactory`'s javadoc (`nessy-api`) names the transaction template (gone) and the
  stores (becoming a backend). Rewritten in the same step.
- `QueuedHarness`'s javadoc is already the contract as ruled and is untouched. Its "Everything an
  agent type needs -- its codec, its renderer, its transactions, the callback ... is behind an
  implementation a caller cannot reach" mentions transactions as one of the hidden things, which
  stays true: they are hidden inside `locks()`.

Nothing in `nessy-api` justifies the queued door by durability. The one place that did is the
engine's own class, and it is on the list.

---

## 5. No single `JdbcBackend`: two JDBC backends, and the tables split with them

James: "I don't think I want one JdbcBackend, dude. That feels just icky. What if someone doesn't
want to use direct or queued at all? We are dragging it around like that (and creating tables we
don't need/want)."

His reason is measurable. The engine's `nessy-schema.sql` declares six tables, and
`Schemas.initialize(dataSource)` creates all of them. Which classes touch which, by `grep` over
`nessy-engine/src/main` after `391d536d`:

| table | named by | door |
|---|---|---|
| `nessy_agent_event` | `JdbcAgentEvents` only | both |
| `nessy_payload` | `JdbcPayloads` (and a comment in the event store) | both |
| `nessy_lock` | `JdbcRowLocks` | both — shared on purpose, so the doors exclude each other under `Locks.TURN` |
| `nessy_agent` | `JdbcAgents` (`ensure`), `JdbcBacklog` (`terminated`, `seal`) | **queued only** |
| `nessy_agent_effect` | `JdbcEffects` | queued only |
| `nessy_agent_backlog` | `JdbcBacklog` | queued only |

`nessy_agent` was the one to check, and the answer is that the direct door does not need it at
all. Neither `JdbcAgentEvents` nor `JdbcPayloads` nor any file under `engine/harness/direct` names
it or `JdbcAgents`; `nessy_agent_event` and `nessy_payload` carry no foreign key to it (the
`Schemas` javadoc forbids cross-module keys, and within the module only `nessy_agent_effect`
references `nessy_agent`); the direct door's `terminate` is an event (`AgentEvent.Terminated`),
not a `terminated_at` column. So the direct schema is **three tables**, and a console application
that only ever calls `ask` is today handed an agent registry, an outbox and a backlog it will never
write a row to. (The `nessy_agent` comment block still says "Taken with SELECT ... FOR UPDATE",
which nothing does since `391d536d`; it is rewritten with the split.)

**So: `JdbcDirectBackend` and `JdbcQueuedBackend`, separately.** Each constructs exactly the
stores its door needs, over one `DataSource`:

```java
public final class JdbcDirectBackend implements DirectBackend {
  public JdbcDirectBackend(DataSource dataSource);
  public JdbcDirectBackend(DataSource dataSource, PlatformTransactionManager transactions,
                           Codec<byte[]> storage);
  // events = JdbcAgentEvents, payloads = JdbcPayloads, locks = JdbcRowLocks
}

public final class JdbcQueuedBackend implements QueuedBackend {
  // the same two constructors
  // + agents = JdbcAgents, effects = JdbcEffects, backlogs = JdbcBacklog per (type, agent)
}
```

An application that serves both doors over one database constructs both, over the same
`DataSource`; they share tables and a `JdbcClient` is stateless, so there is nothing to keep in
step. The `PlatformTransactionManager` is what `JdbcRowLocks(DataSource,
PlatformTransactionManager)` already takes, threaded through: Boot passes the container's, the
one-argument constructor mints a `JdbcTransactionManager` for a caller with nothing to hand in.
Today neither factory passes one — the queued factory constructs `new JdbcRowLocks(dataSource)`
with a comment saying the config is `DataSource`-only until it is backend-shaped — so the backend
is where the manager first reaches the lock.

**The backends get modules before the doors do — ruled.** `JdbcDirectBackend` and
`JdbcQueuedBackend` live in a **`nessy-backend-jdbc`** module and `InMemoryDirectBackend` in
**`nessy-backend-inmemory`**, both under the `nessy-backend/` family beside `nessy-backend-spi`;
`Schemas` moves out of `nessy-spi` into the JDBC one, and `nessy-spi` — which since `07c275e3`
holds `Schemas` and nothing else, and whose pom description says it is waiting for exactly this —
retires. That is the order in the locks record's §13a: backends, then backend modules and the
`Schemas` move, then the DDL split, then the door modules. Whether the JDBC classes in
`engine.jdbc` and `engine.inmemory` move into those modules wholesale, or only the backend classes
do with the stores staying in the engine, is decided by the dependency direction: the backend
modules depend on the engine's store interfaces, never the reverse.

**The DDL splits too, and the mechanism already fits.** `Schemas.LOCATION` is
`classpath*:nessy-schema.sql` — every jar root, gathered by `PathMatchingResourcePatternResolver`,
in no promised order, which is why seven modules (`nessy-engine`, `nessy-lease`, `nessy-planning`,
three memory modules, `nessy-approval/intent`) each ship their own without knowing about each
other. The split is therefore a file move, not a mechanism change: the shared tables
(`nessy_agent_event`, `nessy_payload`, `nessy_lock`) in one file, the three queued tables
(`nessy_agent`, `nessy_agent_effect`, `nessy_agent_backlog`) in another, at a different jar root.
`nessy_agent_effect`'s foreign key to `nessy_agent` stays inside one file, so the "no module's
tables reference another's" rule holds. `Schemas.initialize` does not change at all.

**Which means the schema split IS the module split, and cannot precede it.** Two files cannot
share a name at one jar root, and the convention is that the name is the opt-in ("Boot looks for
`schema.sql`, so ours never runs uninvited, and our loader never runs the application's"). Until a
second jar exists to carry the queued tables, a direct-only application keeps getting six tables.
This record does not invent an interim — a second resource name that `Schemas` would have to learn,
or a backend that initialises its own DDL behind the application's back — because either would be
a mechanism to delete a week later. It does the preparation: the queued tables move to the end of
the engine's `nessy-schema.sql` under a heading that says they are the queued door's, so the move
is a cut and a paste. **Which jar carries the queued file is not settled** (§10): with the backend
modules landing first, `nessy-backend-jdbc` holds both JDBC backends but can ship only one
`nessy-schema.sql` at its root, so the queued tables ride either in `nessy-engine-queued` beside
the door, or in a JDBC backend module per door. The second payoff of the split stands whichever
way: the jar you add is the tables you get.

**An existing deployment.** `CREATE TABLE IF NOT EXISTS` never alters and never drops, so a
database that already carries the six tables keeps them and the split statements are a no-op
against it; a direct-only deployment that wants the three queued tables gone drops them by hand.
James has said the live databases that would care do not exist, and the compose Postgres for
development is disposable by standing rule, so the clean split is available and this record takes
it.

**The shared JDBC machinery, without a shared backend type.** Both JDBC backends build the same
three things: `JdbcClient.create(dataSource)`, a `CodecFactory` that is Jackson with the storage
transform after it, and `JdbcRowLocks`. The codec composition is the only one longer than a line,
and it is already written three times in the tree (`DefaultQueuedHarnessFactory`,
`DirectHarnessAutoConfiguration` in a simpler form, `EngineFixture`):
`storage.map(t -> StorageCodec.of(t).after(jackson)).orElse(jackson)`. Its home is a static on the
type that already exists for the purpose — `StorageCodec.codecs(Optional<Codec<byte[]>>)` or the
like — a mechanical internal, not a concept. Nothing else is shared that a package-private helper
in `engine.store` cannot hold. No `AbstractJdbcBackend`, no `JdbcBackends` facade: two classes
that each `new` three stores is the honest size of the overlap.

**And `InMemoryDirectBackend`.** The three `InMemory*` classes wired together —
`InMemoryAgentEvents(clock)`, `InMemoryPayloads()`, `InMemoryLocks()` (destriped, `0e25584f`) —
with a `Clock` constructor because the event store stamps `writtenAt` from one. They are in
`engine.inmemory` since `a0fa98a2`, with `ListBacklog<I>`, which is already an in-memory
`BacklogManagement`. There is no `InMemoryQueuedBackend` in this record: §8b says what one would
have to be, and §8e says what would justify it — though `ListBacklog` means one of its four parts
already exists.

---

## 6. Factories hold one, and what each config loses and keeps

James: "we would have a DefaultDirectHarnessFactory that has a DirectBackend object that it uses
to create every harness (user defined stuff is configured on top of that). Likewise, we'd have
DefaultQueuedHarnessFactory with a QueuedBackend field."

```java
DefaultDirectHarnessFactory.of(backend, config -> config.provider(…).clock(…).listener(…));
DefaultQueuedHarnessFactory.of(backend, config -> config.inference(…).replyTokens(…));
```

with the `List<Customizer<…>>` overloads kept for the container, which is what hands a list. The
factory reads the backend's stores once at construction and holds them exactly as it holds them
today; the field types change from concrete to interface and the source changes from the config to
the backend. Measured against the two configs as they stand:

| config | loses | keeps |
|---|---|---|
| `DirectHarnessFactoryConfig` | `locks(Locks)`, `events(AgentEvents)`, `payloads(Payloads)` and the three `required*` readers | `provider`, `schemas`, `mapper`, `clock`, `observations` (landed with `534602bb`), `listener`, `feature`, `harness` |
| `QueuedHarnessFactoryConfig` | `dataSource(DataSource)` and `requiredDataSource()`; `storage(Codec<byte[]>)` | `inference(provider, options)`, `listener`, `observations`, `traceCarrier`, `replyTokens` |

The brief's reading was right on both rows, and both rows are still what the two configs
declare after `391d536d`. Two things to note about what moves.

**`storage(Codec<byte[]>)` — encryption at rest — moves to backend construction**, because it is a
property of where bytes land rather than of a harness. The `QueuedHarnessFactoryConfig` javadoc
already says so in different words: "Fixed for the life of the data: rows written under one
transform are unreadable under another, which is the same fact as an encryption key." A key is a
fact about a database, and the object that names the database is the backend. This also closes a
gap: the direct door has no `storage` setter today at all, so a direct harness over JDBC writes
plaintext beside a queued harness writing ciphertext to the same `nessy_payload`. With the
transform on `JdbcDirectBackend`, both doors over one database are given the same one.

**`DefaultDirectHarnessFactory.inMemory(provider, schemas, mapper)`** — three callers (`Repl`,
chat-cli's `Chat`, `DirectHarnessLiveTest`) — becomes sugar over
`of(new InMemoryDirectBackend(), c -> c.provider(provider).schemas(schemas).mapper(mapper))`.
Whether it stays as a static or the three callers spell the backend is open (§10); the record leans
toward keeping it, because "everything in one process and nothing written down" is a sentence a
CLI wants to say in one call.

**The Boot starter.** `DirectHarnessAutoConfiguration` builds a `JdbcDirectBackend` from the
`DataSource` and the container's `PlatformTransactionManager` unless a `DirectBackend` bean is
declared; `QueuedHarnessAutoConfiguration` the same with `JdbcQueuedBackend` and `QueuedBackend`;
the `StorageCodec` bean the queued auto-configuration already looks for is handed to both
backends. The `ObjectProvider<Locks>` the direct auto-configuration consults today goes: a `Locks`
bean overriding one third of a backend is the combinatorial door this record closes, and an
application with its own lock has its own backend (§10, closed).

---

## 7. What stays out, so the interfaces do not accrete

A backend holds what is **stored**. Anything **derived** from what is stored is a projection and
belongs to whoever derives it.

- **`TurnHistories`** is a projection over events and payloads. `EngineFixture` builds it as one
  lambda — `(type, id) -> new EventStreamHistory(events, new Transcript(payloads.forAgent(id)), id)`
  — and `DefaultQueuedHarnessFactory.histories()` builds the identical lambda with the comment
  "Projected from the events rather than read from a table of its own: the story IS the events, and
  a second shape of it would be a second thing to keep in step." It stays on the factory, and the
  direct factory can grow the same method from the same two stores.
- **`EventStreamToolCalls(events, payloads)`** and **`Transcript(payloads.forAgent(id))`** — the
  same kind of thing, built per handler and per history, not stored.
- **`Replies`** (`DefaultReplies`) is reachable from the queued factory and is not a store: it is
  a routing desk keyed by `ReplyTokens`, holding a registry of `(Outbox, callback, payloads)` per
  agent type. It reads the outbox it is bound to; it is not the outbox.
- **`providerName()`** on the direct factory is the provider's, not storage's.
- **`ReplyTokens`** seals a token with a key; it is configuration, and stays where it is.

Nothing else reachable from either factory is derived-not-stored. The list is short because the
factories are already careful about it.

---

## 8. Prerequisite work, verified and sized

### 8a. The event grammar to `nessy-backend-spi` — landed, and the TODO had named the wrong blocker

Landed in `07c275e3` (`nessy-backend/nessy-backend-spi` created: `backend.event.AgentEvent`,
`ActionRequest`, `AgentEvents`; `backend.payload.Payloads`; `backend.lock.Locks`, later joined by
`LockKind`; narration to `nessy-api`; `Schemas` left in `nessy-spi`), `1a9ee75b` (`nessy-api`
depends on nothing of ours; `nessy-backend-spi` depends on `nessy-api` and `nessy-inference-spi`)
and `dde22239` (`Failure` stays in `nessy-inference-spi`, and its javadoc says so). The
`AgentEventStore` TODO this section once quoted — "it cannot go there yet: it is typed on
`AgentEvent`, which carries a `Failure`, which lives in the SPI -- so the move waits on `Failure`
being lifted to `nessy-api`" — is deleted with the type.

**The finding worth keeping: the stated blocker was not the blocker.** Measured before the move:
the SPI module already depended on `nessy-api`, `nessy-api` then depended on `nessy-inference-spi`,
and `PayloadStore` already imported an inference-SPI type, so an interface typed on something that
carried a `Failure` compiled where the TODO said it could not. What actually stopped the store
moving was that **`AgentEvent` itself was in `engine.core`**, dragging `ActionRequest` with it —
the store could not go where its element type could not follow. So the move was the event
vocabulary plus the store, and `Failure` never had to move: `dde22239` records that decision in
the type's own javadoc, and the 09-24 record's "`Failure` to `nessy-api`" item is closed as not
needed. The consequence that had to be said in the commit was said: the sealed event vocabulary is
now public SPI, which is what an event store implementor needs, and it means the fold's facts are
something a backend in another module can read and write.

**Where the modules stand now.** `nessy-api` has no dependency on any Nessy module. `nessy-spi`
holds `spi.store.Schemas` and nothing else; `nessy-engine` keeps it at compile scope only because
its tests and several downstream modules reach `Schemas` through it (the pom comment says so), and
it retires when `Schemas` moves to `nessy-backend-jdbc` (§5). `nessy-lease` depends on
`nessy-backend-spi` for `Locks` and on `nessy-spi` for `Schemas`.

### 8b. Extracting `Effects` from `JdbcEffects` — the honest reading

Verified after the rename: there is still no interface. `Outbox` (`engine.store`) is a class taking
`(AgentType, EffectHandlers, JdbcEffects rows)`; `JdbcEffects` (`engine.jdbc`) is a `@Component`
class over a `JdbcClient` and a `CodecFactory`. Everything that touches the outbox —
`DefaultQueuedHarness`, `EffectDispatcher`, `DefaultReplies`, and the test subclasses that extend
the wrapper precisely because there is nothing else to stub — goes through the wrapper, and the
wrapper holds the concrete class.

**The surface that would become the interface**, from `JdbcEffects`' public methods:

```java
public interface Effects {
  void insert(AgentType type, AgentId agent, AgentEffect effect, Duration timeout,
              EffectOutcome undispatchable, Instant deadline, String traceContext, Instant at);
  List<Attempt> markRunning(AgentType type, Instant now, int batchSize);   // the claim
  List<Attempt> runningFor(AgentType type, AgentId agent);
  boolean complete(UUID effectId, int attemptsMade);                        // fenced delete
  boolean reschedule(UUID effectId, int attemptsMade, Instant at);          // fenced update
  AgentEffect   effectOf(Attempt attempt);                                  // decode, lazily
  EffectOutcome failureOf(Attempt attempt);                                 // decode, lazily
}
```

Seven methods, of which the last two are codec calls that live here only so that the two blobs
on a row decode independently ("a row whose effect cannot be read can still say what to tell the
waiting agent"). `Attempt` stays a record of bytes plus `(effectId, agentId, attemptsMade,
deadline, traceContext)`.

**How much of this is PostgreSQL rather than storage.** The brief asked this straight, and the
answer, having read the class properly, is: less than it looks, and the reason is in how the
callers use it.

- **The claim (`MARK_RUNNING`).** One statement: `UPDATE ... SET status = RUNNING,
  attempts_made = attempts_made + 1, actionable_at = LEAST(now + timeout, deadline) WHERE effect_id
  IN (SELECT ... WHERE agent_type = ? AND status IN (PENDING, RUNNING) AND actionable_at <= now
  ORDER BY actionable_at FOR UPDATE SKIP LOCKED LIMIT n) RETURNING ...`. Its *contract* is: take up
  to n due rows of one type, oldest first, that nobody else is holding; mark them running, count
  the attempt, set the next due time to the smaller of "when this attempt stops being believed"
  and "when the agent stops waiting"; and hand back what was taken. `SKIP LOCKED` is how Postgres
  makes concurrent claimers not block on each other; `LEAST` is arithmetic; `RETURNING` is
  claim-and-read in one round trip. None of the three is the contract. An in-memory
  implementation is one `synchronized` method over a map; the `now` is passed in by the dispatcher
  from its own `Clock`, so the store has no clock of its own to get wrong.
- **The fence (`complete`, `reschedule`).** `DELETE ... WHERE effect_id = ? AND status = RUNNING
  AND attempts_made = ?` and the matching `UPDATE`. The contract is compare-and-act on
  `(status, attempts_made)`; the class javadoc says why a delete rather than a status ("a single-row
  delete is first-wins, so it fences two performers of the same effect for free"). A map with a
  compare-and-remove does the same.
- **What the callers do NOT rely on — and this is the finding.** The fence is not transactional
  with the fold. `EffectDispatcher` performs, then `callback.deliverOutcome(...)` (its own
  transaction, inside the harness), then `retire` → `complete` on autocommit; `DefaultReplies`
  delivers, then `complete`, and treats a lost fence as "harmless -- the call is discharged either
  way, and whatever holds the row now will find the fold already ignores what it delivers." The
  dispatcher's comment is explicit: "The outcome is folded first: if that commits and this crashes,
  the row comes due again, is performed again, and the fold recognises the redelivery and ignores
  it." So the outbox's correctness rests on **per-statement atomicity and the fold's idempotence**,
  not on a transaction spanning claim and completion. That is exactly the property that lets the
  contract be stated without SQL.
- **The one place the JDBC implementation does lean on a transaction** is `insert`, which
  `Outbox.insert` documents as "Called from inside the fold's transaction, so the effect commits
  with the state change that owed it or not at all." Since `391d536d` that transaction is
  `JdbcRowLocks.withLock`'s. An in-memory backend has no such
  thing, so a throw between `events.append` and `effects.insert` would leave an in-memory agent
  in `Inferring` with no row to bring it back — the queued door's recovery is the outbox watchdog,
  and there is no row for it to watch. Inserting into a map does not throw, so this is a
  theoretical hole; it is still a difference in guarantee that the interface's javadoc must state
  rather than let a reader infer equivalence.

**Verdict: `Effects` is not "Postgres with extra steps."** It is a small work-queue contract —
claim-with-exclusivity, lease-by-deadline, fence-by-attempt — that Postgres happens to implement
in three statements. An in-memory implementation is honest and short. **A filesystem
implementation is where the claim gets big**: claim-exclusivity across *processes* means
reimplementing `FOR UPDATE SKIP LOCKED` with file locks, and "oldest first, up to n, skipping what
others hold" over a directory of files is a real piece of engineering rather than a port. The
brief's instinct was right in direction and wrong in target — the in-memory queued backend is
smaller than it looks; the filesystem queued backend is much larger than the filesystem direct one
(§8e).

**Sizing.** The extraction touches `JdbcEffects` (implements the interface; loses `@Component`,
which nothing scans for — the factory constructs it), `Outbox` (field type),
`DefaultQueuedHarnessFactory` (field type), and the test subclasses, which can then stub `Effects`
instead of extending the wrapper. Roughly six files; the rename is already done.

### 8c. Lifting `Backlogs<I>` out of `DefaultQueuedHarness`

An interface of one method in the harness's body, package-private, returning
`BacklogManagement<I>` (since `e0eb76cd`; it returned the concrete `JdbcBacklog<I>` before). It
becomes a public interface returning the engine's `Backlog<I>` (§4 item 3, after the rename), and
the `seal`/`terminated` calls become `Agents` calls. The harness's constructor and the factory's
lambda are the only two sites. Small, and dependent on the three interfaces existing.

### 8d. The interfaces, the three backends, and the factories

`DirectBackend`, `QueuedBackend`, `Agents`, `Effects`, `Backlogs<I>` (§4); `JdbcDirectBackend`,
`JdbcQueuedBackend`, `InMemoryDirectBackend` (§5); the two factories' `of(backend, …)` overloads
and the six setters that go (§6); both auto-configurations; `EngineFixture` (constructs a
`JdbcQueuedBackend` over its container's `DataSource` and passes it, and can drop its own
duplicate readers if it reads them off the backend instead); the three `inMemory` callers (`Repl`, chat-cli's `Chat`,
`DirectHarnessLiveTest`); the three memory-module tests that construct `QueuedHarnessFactoryConfig`
with a `DataSource` (`EpisodeSummarizerTest`, `HeadSummarizerFoldTest`, `HeadSummarizerTest`);
`DefaultDirectHarnessTest` and `DurableDirectHarnessTest`, which set the three stores on the
direct config (the durable one still wires `InMemoryLocks`, which the cutover corrects to the
backend's `JdbcRowLocks`); chat-web's `agentLocks` bean, whose `Locks.TURN` entry goes. The
largest single step, and the one whose diff is mostly wiring.

### 8e. The TCK, and what it is for

**It is extracted from the JDBC tests, not the in-memory ones — ruled.** The invariants that matter
are the ones where JDBC is hard: the claim under concurrency, the fence on `attempts_made`, a lock
that waits, an ensure that does not (`JdbcRowLocksTest`'s
`a_brand_new_key_refuses_promptly_rather_than_waiting_on_the_ensure` is exactly the kind of
sentence a TCK is for). A TCK grown from the in-memory tests would be one JDBC passes trivially,
which is the `EngineFixture` trap in another form: "A test on H2 would pass against exactly the
bugs that matter." So the suite is lifted out of `JdbcRowLocksTest`, `JdbcAgentEventsTest`,
`JdbcPayloadsTest`, `JdbcBacklogTest` and the dispatcher tests that exercise `JdbcEffects`, made
abstract over a backend, and the in-memory backend is then held to it. That also settles which
tests the suite inherits the `@Tag("container")` from: the JDBC run of it is container-tagged and
the in-memory run is not, so a plain build still runs the contract against something.

**What it asserts, concretely.** For any `DirectBackend`: `append` with a stale `expectedLast`
raises `AgentEvents.Conflict` and writes nothing; `writtenAt` is monotone non-decreasing along a
stream and throws `IllegalArgumentException` for a seq that was never written; a payload put
through the claim check comes back `Found` with equal blocks, twice-put content is one reference,
and `forAgent` scopes so that another agent's reference resolves `Missing`; `tryWithLock` from a
second thread while the first holds is `Ignored`, and `withLock` from a second thread actually
waits and runs after the first releases rather than being refused. For any `QueuedBackend`, all of
that plus: `markRunning` from k concurrent claimers over n due rows hands out each row exactly once;
a claimed row's `actionable_at` is `min(now + timeout, deadline)` — measured by claiming a row
whose deadline is nearer than its timeout and finding it due again at the deadline, not after; each
claim increments `attemptsMade` by one; `complete` and `reschedule` with a stale `attemptsMade`
return false and change nothing; `runningFor` returns only RUNNING rows of that agent; an
`ensure`d agent is idempotent and `seal` makes `terminated` true. Every assertion is a sentence
from a javadoc in `engine.store` today, turned executable.

**It is the only honest justification for an in-memory outbox.** A TCK exists to pin semantics in
executable form and to prove the interface is implementable by something that is not Postgres —
not to make tests faster. The genuinely fast tests already live at the fold level, where
`AgentStateTest` needs no store at all, and `EngineFixture` starts one container per class
statically, so the per-test cost of Postgres is already amortised. `EngineFixture`'s own javadoc
says why the semantics must be tested against the real thing:

> **Real PostgreSQL, not H2.** The queries this engine rests on are PostgreSQL's: `FOR UPDATE
> SKIP LOCKED` for claiming work, `LEAST` for capping a deadline, and parameters that PostgreSQL
> refuses as a bare `Instant` while H2 accepts them happily. A test on H2 would pass against
> exactly the bugs that matter.

The same sentence applies to running the engine's tests against an in-memory outbox: they would
pass against exactly the bugs that matter. So the in-memory queued backend, if it is ever written,
is written *to pass the TCK* and used *by the TCK* to show that `Effects` is a contract rather
than a description of one table. It is not a test double for the engine. The precedent is
`2026-08-15-jdbc-dialects-tck-design.md`'s `nessy-store-tck`, which was designed and never built,
and the substrate's 550-line `SubstrateContract`, which was built and deleted with the substrate;
the lesson from both is that a TCK earns its keep only when there is a second implementation to
hold to it, and the second implementation earns its keep only when the TCK exists. Whether the
suite is a module or a test-jar, and its name, is open (§10).

**A filesystem `QueuedBackend` is far harder than a filesystem `DirectBackend`.** Events as an
append-only file per agent and payloads as content-addressed files are natural, and a file lock per
`(kind, type, agent)` gives `Locks` — that is a weekend. The outbox is not: claim-exclusivity
across processes over a directory means file locking that reimplements `FOR UPDATE SKIP LOCKED`,
and "due rows of one type, oldest first" means either scanning every file or maintaining an index
that can go stale. James's "memory, postgres, and file system" is right for the direct door and
should be read as "memory and postgres" for the queued one until somebody wants the third badly
enough to build the queue — and §4a adds the independent reason that even a built one would be
durable without being shared, which is the half a queue does not need.

---

## 9. Sequencing

The order is stated once, in the locks record's §13a, and not repeated here. Of it, this record's
steps are, in that order: the three backlog interfaces (`Coalescing` / `Backlog` / `Agents`); the
`Effects` extraction; the `Backlogs<I>` lift; `DirectBackend` / `QueuedBackend` in
`nessy-backend-spi`; `JdbcDirectBackend` / `JdbcQueuedBackend`; the cutover (§6); the in-memory
backend and then the TCK (§8e); `nessy-backend-jdbc` + `nessy-backend-inmemory` with the `Schemas`
move and `nessy-spi`'s retirement; the DDL split, which is the module split (§5); and the door
modules with their two starters. The direct door's per-step locking, in flight as this is written,
precedes all of it; the docs come last.

What this record needed from the locks record has landed: the restructure put the `InMemory*`
classes and the JDBC classes where the backends want them (`a0fa98a2`); `JdbcRowLocks` exists for
both JDBC backends to hold (`d678e8dd`); and the queued door onto the SPI (`391d536d`) removed the
minted `TransactionTemplate`, which was the last reason `QueuedHarnessFactoryConfig` needed a bare
`DataSource` for anything but building stores, and reduced `JdbcAgents` to `ensure`, which
`Agents` absorbs. The only ordering constraint this record adds is the one the module split
imposes on itself: the DDL split cannot precede a second jar, so the backend modules land before
it and the door modules after.

## 10. Open questions for James

Closed since the first draft, and no longer asked: whether `QueuedBackend`'s contract carries
"durable" (§4a: no; shared and durable are properties of an implementation, named by its type);
`Outbox` as the wrapper's name (landed `4ea77f9e`); `Agents` as a public interface (approved, §4);
the packages for the SPI types (`backend.event`, `backend.payload`, `backend.lock` in
`nessy-backend-spi`, landed `07c275e3`; the backend interfaces go in the same module); the
interim DDL (none — the split waits for the second jar, §5); the starter honouring a standalone
`Locks` bean (it stops: the cutover removes `locks(Locks)` from the direct config, so a custom
lock is a custom `DirectBackend`, §6); and `nessy-lease` keeping its name (the locks record §12).

Genuinely open:

1. **The TCK's exact shape** — a `nessy-backend-tck` module that backends depend on in test scope
   (the 08-15 record's shape, never built), or a test-jar; and how it inherits the container tag
   (§8e). Extracting it from the JDBC tests is decided; the packaging is not.
2. **Whether `nessy-lease` moves under the `nessy-backend/` family**, keeping its name and package.
   It implements a `nessy-backend-spi` interface, which argues for the move; it is not a backend,
   which argues against.
3. **The placeholder name `Coalescing`** for the api-side backlog interface (§4 item 3). Approved
   with a TODO recording that James dislikes it; a better word for "what a policy may do to the
   queue" replaces it whenever one arrives.
4. **Which jar ships the queued tables' DDL** (§5). `nessy-backend-jdbc` can ship one
   `nessy-schema.sql`; either the queued file rides in `nessy-engine-queued` beside the door, or
   the JDBC backend module splits per door. Not decided by anything said so far, and it only has to
   be decided at the DDL step.
5. **`DefaultDirectHarnessFactory.inMemory(...)`** — keep as sugar over `InMemoryDirectBackend`, or
   delete and have `Repl`, chat-cli and the live test spell the backend. The record leans keep;
   nobody has ruled. Minor, and decided in the cutover step.

# Backends: one unit of configuration per door, and nothing to get wrong in between

**Status: PROPOSED — the shape is James's; the sizing is this record's.** Every fact about the
working tree was measured on branch `fold-swap` on 2026-09-26, with `DirectHarness<I, O>` in
flight in the working tree (the four direct-door files are read for structure and never cited by
line). Every signature under "the design" is a proposal unless it says otherwise.

Date: 2026-09-26. Adjacent to `2026-09-25-locks-as-plumbing-design.md`, whose §11 measured the
queued door's JDBC coupling and recommended a follow-on, whose §14 Q7 asked whether to widen, and
whose §13b module split this record must land BEFORE (§9). It answers Q7 with a shape James gave
rather than the store-shaped config §11 sketched. Two rulings made while this record was being
written are folded in rather than appended: the store types take the plural name and lose the word
"Store" (§3), and there is no single `JdbcBackend` (§5).

---

## 1. The premise: JDBC-versus-in-memory is all-or-nothing

James asked whether the choice between JDBC and in-memory stores is effectively one choice rather
than several. It is, and the evidence is in the tree.

**Events and payloads must match.** An `AgentEvent` carries no content. `TurnStarted`,
`InferenceAnswered` and `ActionsRequested` each hold a `PayloadRef`, and the content behind it
lives in the payload store (`engine/core/AgentEvent.java`: "No payloads. Every one of these carries
identifiers, status, a human decision or a count -- and a `PayloadRef` where content would
otherwise be"). So durable events over in-memory payloads is, after a restart, a story that is a
list of references to content that no longer exists. `PayloadStore.get` answers `Resolved.Missing`,
and `EngineFixture.content` says what that means: "in this engine a missing payload is always a
fault." The agent cannot be reconstituted, and nothing warns until something asks for the words.
The reverse — in-memory events over durable payloads — is not corrupt, merely pointless: content
nobody will ever reference again, accumulating in a table. This is the both-stores-or-neither rule
from the JDBC substrate work (`2026-09-04-durable-runtime-design.md`, and again at
`2026-09-24-inline-inference-and-the-turn-executor-design.md` §"Constraint, and it is the existing
both-stores-or-neither rule arriving again"). It has been ruled twice; a configuration that lets it
be broken is a configuration that lets a ruling be broken by accident.

**The queued door is durable by definition.** Its premise, in its own javadoc, is that "a
transaction takes the agent, folds a command, writes the events and the effects it decided on, and
commits. Performing those effects happens afterwards and elsewhere." Work outlives the call that
submitted it — that is "the whole reason this door exists and the whole reason it can wait hours
for a person to approve something." An in-memory outbox is a queue that dies with the process, and
an approval parked in it is an approval nobody will ever be able to answer.

**Locks are the exception, and the dangerous one.** In-memory locks over JDBC stores are correct
in one process and silently wrong in two: each process believes it holds the agent, both run a
turn, and the second `append` — if the seqs collide — raises `Conflict`; if they interleave
across a phase boundary, nothing raises anything. `DirectHarnessAutoConfiguration` does exactly
this today. Verified: its factory bean is `@ConditionalOnBean(DataSource.class)`, builds
`JdbcAgentEventStore` and `JdbcPayloadStore` from that `DataSource`, and wires
`.locks(locks.getIfAvailable(InMemoryLocks::new))` — durable stores, process-local locks, unless an
application happened to declare a `Locks` bean. The locks record fixes the default (§13, "Line 96
— `locks.getIfAvailable(InMemoryLocks::new)` becomes a `JdbcRowLocks` over the `DataSource`"), but
it fixes the default, not the surface: a caller can still hand `DirectHarnessFactoryConfig` any
three things it likes.

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

## 3. The names of the parts: plural, and "Store" goes

Ruled while this record was in draft. James: "man, this plural thing keeps showing up. Why not
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
| `PayloadStore` (`nessy-spi`) | `Payloads` |
| `JdbcPayloadStore` | `JdbcPayloads` |
| `InMemoryPayloads` | unchanged — already right |
| `AgentEventStore` (`engine.core`, moving to `nessy-spi`, §8a) | `AgentEvents` |
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

**It is its own step, and the first one** (§9). A pure rename is a diff a reviewer can verify by
reading the file list, and everything after it — the move to `nessy-spi`, the extraction, the
interfaces — should land under the final names rather than be renamed twice. It sits before the
package restructure of the locks record's §13a step 2, so that the restructure moves files that
already have their names.

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
package org.jwcarman.nessy.engine.store;          // or engine.backend — §10 Q3

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
3. **`Backlogs<I>` returns `Backlog<I>`, and `Agents` is the fourth thing the sketch left out.**
   The inner `Backlogs<I>` in `DefaultQueuedHarness` returns the concrete `JdbcBacklog<I>` rather
   than the `Backlog<I>` interface (`nessy-api`), and it has to, because the harness calls two
   things that are not on `Backlog<I>`: `seal()` and `terminated()`. Both are `nessy_agent`
   queries that live on the backlog class for convenience — `JdbcBacklog` names `nessy_agent`
   twice and `nessy_agent_backlog` eleven times. The locks record's §11a already splits
   `JdbcAgents` into "ensure the row exists" and "has this agent been told to end", and moves
   the lock out of it. Finishing that thought: `Agents` owns `nessy_agent` in full —
   `ensure(type, id)`, `terminated(type, id)`, `seal(type, id)` — and `JdbcBacklog` loses its two
   `nessy_agent` statements, so that each store owns exactly one table. `seal()` on the harness
   becomes `int abandoned = backlog.size(); backlog.rewrite(List.of()); agents.seal(type, id);`
   inside the same locked step, which keeps the invariant `seal` guards ("an agent cannot be left
   ended with work still queued behind it") because the lock's transaction holds both. Then
   `Backlogs<I>.forAgent(AgentType, AgentId)` can return the `nessy-api` interface, and the queued
   door holds no concrete store type at all — which was James's ruling in the locks record ("I
   don't want the queued door to do direct JDBC at all"). `Agents` is the name `JdbcAgents`
   already implies; it is nonetheless a new public interface and is asked for in §10.
4. **No transaction on the interface, on purpose.** The brief listed "a transaction boundary" as
   one of the queued backend's contents. It is, but it is not a method: the locks record's §8a
   makes `JdbcRowLocks.withLock` own the transaction — begin, `FOR UPDATE`, run the work, commit —
   and measurement 1 there shows that every `JdbcClient` statement over the same `DataSource`
   inside that work joins it. So the transaction is a property of `locks()`, and a backend that
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

---

## 5. No single `JdbcBackend`: two JDBC backends, and the tables split with them

James: "I don't think I want one JdbcBackend, dude. That feels just icky. What if someone doesn't
want to use direct or queued at all? We are dragging it around like that (and creating tables we
don't need/want)."

His reason is measurable. The engine's `nessy-schema.sql` declares five tables, and
`Schemas.initialize(dataSource)` creates all of them. Which classes touch which, by `grep` over
`nessy-engine/src/main`:

| table | named by | door |
|---|---|---|
| `nessy_agent_event` | `JdbcAgentEventStore` only | both |
| `nessy_payload` | `JdbcPayloadStore` (and a comment in the event store) | both |
| `nessy_agent` | `JdbcAgents`, `JdbcBacklog` | **queued only** |
| `nessy_agent_effect` | `JdbcEffectStore` | queued only |
| `nessy_agent_backlog` | `JdbcBacklog` | queued only |

`nessy_agent` was the one to check, and the answer is that the direct door does not need it at
all. Neither `JdbcAgentEventStore` nor `JdbcPayloadStore` nor any file under `engine/direct` names
it or `JdbcAgents`; `nessy_agent_event` and `nessy_payload` carry no foreign key to it (the schema's
own `Schemas` javadoc forbids cross-module keys, and within the module only `nessy_agent_effect`
references `nessy_agent`); the direct door's `terminate` is an event (`AgentEvent.Terminated`),
not a `terminated_at` column. So the direct schema is **two tables today**, and three once the
locks record's `nessy_lock (kind, agent_type, agent_id)` lands — that one is shared, because
cross-door exclusion (locks record §3) depends on both doors locking the same row. A console
application that only ever calls `ask` is today handed an agent registry, an outbox and a backlog
it will never write a row to.

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
step. The `PlatformTransactionManager` is the locks record's §8a ruling for `JdbcRowLocks`,
threaded through: Boot passes the container's, the one-argument constructor mints a
`JdbcTransactionManager` for a caller with nothing to hand in.

**The DDL splits too, and the mechanism already fits.** `Schemas.LOCATION` is
`classpath*:nessy-schema.sql` — every jar root, gathered by `PathMatchingResourcePatternResolver`,
in no promised order, which is why seven modules (`nessy-engine`, `nessy-lease`, `nessy-planning`,
three memory modules, `nessy-approval/intent`) each ship their own without knowing about each
other. The split is therefore a file move, not a mechanism change: the shared tables
(`nessy_agent_event`, `nessy_payload`, `nessy_lock`) stay in the file `nessy-engine` ships; the
three queued tables (`nessy_agent`, `nessy_agent_effect`, `nessy_agent_backlog`) go into the file
`nessy-engine-queued` ships, at its own jar root. `nessy_agent_effect`'s foreign key to
`nessy_agent` stays inside one file, so the "no module's tables reference another's" rule holds.
`Schemas.initialize` does not change at all.

**Which means the schema split IS the module split, and cannot precede it.** Two files cannot
share a name at one jar root, and the convention is that the name is the opt-in ("Boot looks for
`schema.sql`, so ours never runs uninvited, and our loader never runs the application's"). Until
`nessy-engine-queued` exists (locks record §13b), a direct-only application over the single
`nessy-engine` jar keeps getting six tables. This record does not invent an interim — a second
resource name that `Schemas` would have to learn, or a backend that initialises its own DDL behind
the application's back — because either would be a mechanism to delete a week later. It does the
preparation: the queued tables move to the end of the engine's `nessy-schema.sql` under a heading
that says they are the queued door's, so that §13b's move is a cut and a paste. And it adds a
second payoff to §13b's list beside `@ConditionalOnClass`: the jar you add is the tables you get.

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
`InMemoryAgentEvents(clock)`, `InMemoryPayloads()`, `InMemoryLocks()` — with a `Clock` constructor
because the event store stamps `writtenAt` from one. The locks record's §13a step 2 moves the three
to `engine.store`; the backend goes beside them. There is no `InMemoryQueuedBackend` in this
record: §8b says what one would have to be, and §8e says what would justify it.

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
| `DirectHarnessFactoryConfig` | `locks(Locks)`, `events(AgentEventStore)`, `payloads(PayloadStore)` and the three `required*` readers | `provider`, `schemas`, `mapper`, `clock`, `listener`, `feature`, `harness` — and `observations`, which the locks record's §14 Q3 adds |
| `QueuedHarnessFactoryConfig` | `dataSource(DataSource)` and `requiredDataSource()`; `storage(Codec<byte[]>)` | `inference(provider, options)`, `listener`, `observations`, `traceCarrier`, `replyTokens` |

The brief's reading was right on both rows. Two things to note about what moves.

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
Whether it stays as a static or the three callers spell the backend is §10 Q6; the record leans
toward keeping it, because "everything in one process and nothing written down" is a sentence a
CLI wants to say in one call.

**The Boot starter.** `DirectHarnessAutoConfiguration` builds a `JdbcDirectBackend` from the
`DataSource` and the container's `PlatformTransactionManager` unless a `DirectBackend` bean is
declared; `QueuedHarnessAutoConfiguration` the same with `JdbcQueuedBackend` and `QueuedBackend`;
the `StorageCodec` bean the queued auto-configuration already looks for is handed to both
backends. The `ObjectProvider<Locks>` the direct auto-configuration consults today goes: a `Locks`
bean overriding one third of a backend is the combinatorial door this record closes, and an
application with its own lock has its own backend (§10 Q5).

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

### 8a. `Failure` to `nessy-api`, `AgentEvents` to `nessy-spi` — and the TODO conflates two moves

`AgentEventStore`'s javadoc, verbatim:

> **TODO -- this belongs in `nessy-spi`**, beside `PayloadStore`. It cannot go there yet: it is
> typed on `AgentEvent`, which carries a `Failure`, which lives in the SPI -- so the move waits on
> `Failure` being lifted to `nessy-api`, which the design record already has planned for other
> reasons.

The other plan exists: `2026-09-24-inline-inference-and-the-turn-executor-design.md`, follow-up
item 2 — "`Failure` moves `nessy-spi` → `nessy-api`. `nessy-spi` depends on `nessy-api`, not the
reverse, so `AgentEvent.TurnFailed` cannot carry `Failure` today. It describes an outcome
applications branch on, so it belongs up. Verify the `@JsonTypeInfo(Id.NAME)` wire format is
undisturbed by the package move rather than assuming it." (Its "nessy-spi" means the inference SPI;
`Failure` is `org.jwcarman.nessy.inference.Failure` in `nessy-inference/spi`.)

**Measured, the stated blocker is not a blocker.** `nessy-spi` depends on `nessy-api`
(`nessy-spi/pom.xml`), `nessy-api` depends on `nessy-inference-spi` (`nessy-api/pom.xml`), and
`nessy-spi`'s own `PayloadStore` already imports `org.jwcarman.nessy.inference.block.Block`. An
interface in `nessy-spi` typed on something that carries an inference-SPI `Failure` compiles today.
What actually stops `AgentEventStore` moving is that **`AgentEvent` itself is in `engine.core`**,
and it drags `ActionRequest` (same package, in `ActionsRequested`) with it. The store cannot go
where its element type cannot follow. So there are two moves here, and they are independent:

1. **`Failure` to `nessy-api`** — wanted by the 09-24 record so that an *api* type can carry it,
   not by this one. Blast radius: 18 files import it by name (five `nessy-engine` main, three
   `nessy-engine` test, two per inference adapter across four adapters, one `nessy-memory/summarizing`
   test, one autoconfigure test), plus `InferenceResult.Fault` and two tests in the same package
   that use it without an import and would gain one. `@JsonTypeInfo(use = Id.NAME)` with four
   named subtypes means nothing stored names the package; the 09-24 record's "verify rather than
   assume" stands and is one round-trip test. Small, mechanical, and not this record's to
   schedule — it is listed because the TODO says it is the gate and it is not.
2. **`AgentEvent` and `ActionRequest` to `nessy-spi`, and `AgentEvents` with them.** `AgentEvent`
   imports `PayloadRef` (api), `Failure`, `Seq`, `TurnId` and `CallId` (inference SPI), and
   references `AgentState` only in javadoc (two `{@link}`s, which become `{@code}`). `ActionRequest`
   imports `CallId` and `ToolName`. All reachable from `nessy-spi` today. Blast radius: 37 files
   name `AgentEvent` (main and test; `EngineFixture`, `AgentStateTest`, every store and history
   test, chat-web's `ApprovalStreams` outside the engine), 13 name `ActionRequest`, 14 name
   `AgentEventStore`. Every one is an import line. The harder question is not the diff but the
   claim: moving the sealed event vocabulary to `nessy-spi` makes it **public SPI**, which is what
   an event store implementor needs and what the TODO asks for, and it means the fold's facts are
   now something a filesystem backend in another module can read and write. That is the intended
   consequence and it should be said in the commit.

So the TODO should be read as: "move `AgentEvent`, `ActionRequest` and this interface to
`nessy-spi`; `Failure` is fine where it is for that purpose." The record proposes package
`org.jwcarman.nessy.spi.store` for the interface beside `Payloads`, and `spi.event` for the two
event types — the package is §10 Q3.

### 8b. Extracting `Effects` from `JdbcEffectStore` — the honest reading

Verified: there is no interface. `EffectStore` is a class taking
`(AgentType, EffectHandlers, JdbcEffectStore rows)`; `JdbcEffectStore` is a `@Component` class
over a `JdbcClient` and a `CodecFactory`. Everything that touches the outbox — `DefaultQueuedHarness`,
`EffectDispatcher`, `DefaultReplies`, and two test subclasses (`DispatcherFailureTest.Effects`,
`MisroutedReplyTest.Rows`, which extend the wrapper precisely because there is nothing else to
stub) — goes through the wrapper, and the wrapper holds the concrete class.

**The surface that would become the interface**, from `JdbcEffectStore`'s public methods:

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
  `Outbox.insert` (today `EffectStore.insert`) documents as "Called from inside the fold's
  transaction, so the effect commits with the state change that owed it or not at all." Under the
  locks record, that transaction is `JdbcRowLocks.withLock`'s. An in-memory backend has no such
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

**Sizing.** The extraction touches `JdbcEffectStore` (implements the interface; loses
`@Component`, which nothing scans for — the factory constructs it), `EffectStore` → `Outbox`
(field type), `DefaultQueuedHarnessFactory` (field type), and the two test subclasses, which can
then stub `Effects` instead of extending the wrapper. Roughly six files plus the rename.

### 8c. Lifting `Backlogs<I>` out of `DefaultQueuedHarness`

A `@FunctionalInterface` of one method in the harness's body, package-private, returning the
concrete `JdbcBacklog<I>`. It becomes a public interface in `engine.store` returning `Backlog<I>`
(§4 item 3), and the `seal`/`terminated` calls on the concrete type become `Agents` calls. The
harness's constructor and the factory's lambda are the only two sites. Small, and dependent on
`Agents` existing.

### 8d. The interfaces, the three backends, and the factories

`DirectBackend`, `QueuedBackend`, `Agents`, `Effects`, `Backlogs<I>` (§4); `JdbcDirectBackend`,
`JdbcQueuedBackend`, `InMemoryDirectBackend` (§5); the two factories' `of(backend, …)` overloads
and the six setters that go (§6); both auto-configurations; `EngineFixture` (constructs a
`JdbcQueuedBackend` over its container's `DataSource` and passes it, and can drop its own
duplicate readers if it reads them off the backend instead); the three `inMemory` callers; the
three memory-module tests that construct `QueuedHarnessFactoryConfig` with a `DataSource`
(`EpisodeSummarizerTest`, `HeadSummarizerFoldTest`, `HeadSummarizerTest`); `DefaultDirectHarnessTest`
and `DurableDirectHarnessTest`, which set the three stores on the direct config. The largest
single step, and the one whose diff is mostly wiring.

### 8e. The TCK, and what it is for

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
suite is a module or a test-jar, and its name, is §10 Q7.

**A filesystem `QueuedBackend` is far harder than a filesystem `DirectBackend`.** Events as an
append-only file per agent and payloads as content-addressed files are natural, and a file lock per
`(kind, type, agent)` gives `Locks` — that is a weekend. The outbox is not: claim-exclusivity
across processes over a directory means file locking that reimplements `FOR UPDATE SKIP LOCKED`,
and "due rows of one type, oldest first" means either scanning every file or maintaining an index
that can go stale. James's "memory, postgres, and file system" is right for the direct door and
should be read as "memory and postgres" for the queued one until somebody wants the third badly
enough to build the queue.

---

## 9. Sequencing against the locks record

The locks record's §13a has landed its first five items by SHA (`e5bde878`, `90a0fde8`,
`62fe03a2`, `8575a90a`, `5be127c2`) and has `DirectHarness<I, O>` in flight; eight numbered steps
remain, with the module split (§13b) last. This record's work threads through them. Written so the
order stands without either author:

- **The rename (§3) — immediately, before §13a step 2.** It stands alone, it is a file-list
  review, and the restructure should move files under their final names. One commit.
- **The `nessy-spi` move (§8a) — with or right after the rename**, and before §13a step 2 for
  the same reason: the restructure's table of "today / after" packages should not have a row that
  a later step deletes. Does not depend on `Failure` moving (§8a); does not block on anything.
- **§13a steps 2–5 land as written** — the restructure, the handlers step, the lock SPI, and the
  queued door onto the SPI. This record needs all four: step 2 puts the `InMemory*` classes where
  `InMemoryDirectBackend` wants them; step 4 creates `JdbcRowLocks`, which both JDBC backends
  hold; step 5 removes the minted `TransactionTemplate`, which is the last reason
  `QueuedHarnessFactoryConfig` needs a bare `DataSource` for anything but building stores, and
  turns `JdbcAgents.lock` into `ensure`, which `Agents` absorbs.
- **The `Effects` extraction, `Agents`, and the `Backlogs<I>` lift (§8b, §8c) — after step 5.**
  They touch `DefaultQueuedHarness`, `EffectDispatcher` and `DefaultReplies`, the same files step 5
  rewrites around `withLock`; landing them after keeps step 5's diff about the lock and nothing
  else. This is the overlap in `engine.store` the brief flagged, and "after" is how it resolves.
- **The interfaces, the three backends, and the factories (§8d) — after that, and before §13a
  step 6** if possible, so that the direct door's per-step locking (step 6) is written against a
  backend it reads its `Locks` from rather than a config setter that is about to go. If step 6 is
  ready first, it lands first and §8d re-plumbs one field; neither order breaks the other.
- **The TCK (§8e) — after §8d**, when there are interfaces to hold implementations to.
- **§13a step 7 (docs)** absorbs this record's changes: the two auto-configuration javadocs, the
  `docs/concepts/storage.md` description of the stores, and the Spring guide's
  "add a `Locks` bean" paragraph, which is overtaken (§6).
- **§13a step 8, the module split, LAST** — and this record adds two reasons to its list: the
  DDL split is only real once the queued tables have a jar of their own (§5), and
  `JdbcQueuedBackend`, `JdbcEffects`, `JdbcBacklog`, `JdbcAgents` and `Outbox` are exactly the
  classes §13b already sends to `nessy-engine-queued`, so this record changes what is public and
  what lives where before the poms are cut rather than after.

In one line: rename → spi move → §13a 2–5 → extraction + `Agents` + lift → backends + factories →
TCK → §13a 6–7 → §13b.

---

## 10. Open questions for James

1. **`Outbox` as the name of the per-agent-type wrapper** (§3), so that the row store can be
   `Effects`. The word is already the design vocabulary's ("inbox-outbox" record, three javadocs,
   the schema); it has never been a type name. Yes?
2. **`Agents` as a public interface** (§4 item 3) — `ensure`, `terminated`, `seal` over
   `nessy_agent` — so that `JdbcBacklog` stops reaching into a second table and `Backlogs<I>` can
   return the `nessy-api` `Backlog<I>`. It finishes the locks record's §11a split rather than
   starting something new, but it is a name on the API surface and is asked.
3. **Packages.** `DirectBackend`/`QueuedBackend` and the three implementations in `engine.store`
   beside the stores they assemble, or an `engine.backend` of their own; and `AgentEvent`/`ActionRequest`
   in `spi.store` beside `Payloads` and `AgentEvents`, or in an `spi.event`. The record leans
   `engine.store` and `spi.store` — one package for "everything that talks to the engine's
   storage" — and notes that the locks record's §14 Q1 asks the same question about `JdbcRowLocks`.
4. **The schema split waits for the module split** (§5), with the queued tables moved to the end
   of the engine's file under a heading in the meantime. The alternative — an interim second
   resource name that `Schemas` learns and then forgets — was rejected here as a mechanism with a
   one-week life. Agreed, or is the interim worth having so a direct-only application stops
   getting six tables before §13b?
5. **The starter stops honouring a standalone `Locks` bean** (§6). Today an application can
   declare `Locks` and have the direct auto-configuration use it over JDBC stores; after this
   record, a custom lock means a custom `DirectBackend` (three lines, delegating two stores and
   substituting the third). This closes the one silent combination on purpose. Yes?
6. **`DefaultDirectHarnessFactory.inMemory(...)`** — keep as sugar over `InMemoryDirectBackend`,
   or delete and have `Repl`, chat-cli and the live test spell the backend?
7. **The TCK's shape** — a `nessy-backend-tck` module that backends depend on in test scope (the
   08-15 record's shape, never built), or a test-jar of `nessy-engine`. And whether it is in scope
   for this record's landing or a follow-on once `JdbcDirectBackend` and `InMemoryDirectBackend`
   both exist to be held to it. The record says follow-on: two implementations of `DirectBackend`
   are enough to justify it and one of `QueuedBackend` is not.
8. **The doubt, stated rather than smoothed.** §8b found that the outbox's correctness rests on
   per-statement atomicity plus the fold's idempotence, not on a transaction, which is what makes
   `Effects` a contract rather than a table. But the *queued door's* correctness still rests on
   `insert` being inside the fold's transaction, and that transaction is a property of
   `JdbcRowLocks`, not of `QueuedBackend`. So `QueuedBackend` can be implemented in memory and pass
   a TCK, and still not be a *queued door* in the sense the door's javadoc promises, because the
   promise is durability and the interface cannot say so. Is that acceptable — an interface whose
   in-memory implementation exists to prove the contract and is documented as not a deployment —
   or should `QueuedBackend`'s javadoc, or its name, carry the word "durable" so that nobody wires
   the in-memory one into a service and waits hours for an approval that died with the process?

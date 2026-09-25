# The starter, organised by what an adopter chooses

**Status: DESIGN. §3 is on the branch. Nothing from §4 onward is built. The rulings in §5 and §6
were made 2026-09-25; the questions in §10 await James, and §10.1 blocks tools from
auto-installing.**

Date: 2026-09-25. Follows `2026-09-25-one-core-two-doors-design.md`, which made the two doors
peers in the engine; this makes them peers in Spring Boot, and makes one `Customizer<C>` the one
way anything is extended, in the engine, the adapters and the starter alike. Supersedes §2 and §3
of `2026-08-13-spring-boot-starter-design.md` (the module shape and the autoconfiguration graph).
The ruling that the starter takes a bean and never discovers one (2026-08-31) stands and is built
on.

---

## 1. The question

An application adopting Nessy under Boot decides three things, and decides them independently:

| choice | the answer is |
|---|---|
| which door(s) | direct, queued, or both |
| which vendor answers | one `InferenceProvider` |
| whether Nessy should invent an agent | almost always no |

The auto-configuration graph does not have those three seams. It has the seams of the order in
which things were written: the queued door first, with a free agent inside it; the vendors next,
each on its own; the embedders; and the direct door, added after the engine made it a peer. So the
third choice is welded to the first -- the queued door's class is also the class that builds an
agent from properties, and demands a model and a system prompt to do it -- and the second choice
is made by the alphabet, because three vendor classes each say "build me if nobody else has" and
Boot sorts them by name.

Every observed pain is one of those two welds showing:

- chat-cli and `Repl.ReplBootstrap` exclude `NessyAutoConfiguration` to escape the free agent's
  demands, and in excluding it lose `NessyProperties` and `NessySchema`, which everything else
  leans on. `PromptAutoConfiguration` re-enables the properties record for exactly this reason,
  and the direct door now does the same (§3).
- With two vendor keys exported, `anthropicInferenceProvider` is the one bean built, whatever the
  `.imports` file says. Measured with an `ApplicationContextRunner` over the three vendor classes:
  OpenAI + Anthropic, or all three, yields exactly `anthropicInferenceProvider`.
- The listener collector and the startup report are written once per door, each wrapped in a
  `@ConditionalOnMissingBean` so two doors do not do it twice. A third factory would copy them a
  third time; the embedder factory already exists and has neither.
- chat-web declares a `DirectHarness<String>`. The free harness is conditional on a missing
  `QueuedHarness<String>`, a different type, so chat-web also builds a second, inert queued
  harness of agent type `chat` with every `Tool` bean bound and ungated -- the `SendEmailTool`
  among them, whose whole point in the hand-declared harness is that it needs an approver.
- The direct door falls back to `InMemoryLocks` when no `Locks` bean exists: sixty-four stripes
  shared by every agent, where a collision reads as "that agent is busy". chat-web found this by a
  false busy and declares its own `JdbcLeases` bean to escape it.

And one pain the starter cannot fix from its side, because it is the engine's: there is nothing
for a Boot application to *contribute* to a factory or a harness except by declaring the whole
thing itself. chat-web's harness bean is forty lines, nine of which wire a notebook, a plan and
episodes into the tool list and the ambient sources
(`ChatConfiguration.java:175-187`) -- the same nine lines chat-cli writes again. That is what §6
is for.

## 2. What survives

Every bean backs off (`@ConditionalOnMissingBean`), so an application that declares one is
choosing it explicitly and the starter steps aside. Vendors gate on their own module's class
(`@ConditionalOnClass`), so adding `nessy-inference-openai` is the whole statement of intent.
`ConditionalOnConfiguredProperty` treats blank as unset, which is what a `${VAR:}` placeholder
needs. `NessyProperties` carries its own defaults in its compact constructor. `Observed.wrap`
wraps every collaborator the engine is handed, once. Schema initialisation is opt-out. The
reply-token warning says loudly what an ephemeral key costs. The embedding family already orders
its three vendors explicitly. None of that changes.

## 3. On the branch already

Two patches, both recorded as patches.

`DirectHarnessAutoConfiguration` carries `@EnableConfigurationProperties(NessyProperties.class)`
itself, as `PromptAutoConfiguration` already did, so its startup report no longer needs the queued
door's class to have run. That turned `nessy-console` green (`ReplTest`, `ReplRunsTest`) and
`./mvnw -o clean verify` passes across the reactor.

Both factory beans are now `@ConditionalOnMissingBean` **against the interface** --
`DirectHarnessFactory.class` (`DirectHarnessAutoConfiguration.java:73`) and
`QueuedHarnessFactory.class` (`NessyAutoConfiguration.java:122`) -- rather than the concrete
class. The mechanism was confirmed before the fix: the predicted type of an uncreated
factory-method bean is its declared return type, so chat-cli's `DirectHarnessFactory harnesses(...)`
(`Chat.java:111`) did not match a condition on `DefaultDirectHarnessFactory`, and chat-cli has a
`DataSource` (`spring-boot-docker-compose` + PostgreSQL), so the starter built a second factory
and then demanded `NessySchema` from the auto-configuration chat-cli excludes.

What the patches do not do: the direct factory still injects `NessyAutoConfiguration.NessySchema`,
so an application with a `DataSource` that excludes the queued door and declares no factory of
its own still cannot start. Nothing tests that shape today, which is why the break reached the
console before anything in `nessy-spring-boot` noticed. Only the base layer in §4 removes the
coupling; once the properties and the schema live where nothing demands anything, no door has to
re-enable them.

## 4. The target structure

One autoconfigure jar, one starter, and auto-configurations layered by what they need rather than
by when they arrived. Each layer is `after` the one above it and conditional only on that layer's
beans and its own classpath. Everything the starter builds, it builds through a
`Customizer<C>`, and every `Customizer<C>` bean in the context for that `C` is applied (§5.1).

### 4a. Base: demands nothing

`NessyAutoConfiguration` keeps its name and sheds both doors. It enables `NessyProperties` and
contributes what every door and every vendor lean on:

| bean | condition |
|---|---|
| `NessySchema` | `@ConditionalOnBean(DataSource.class)`; runs the DDL when `nessy.initialize-schema` says so |
| `ReplyTokens` | `@ConditionalOnMissingBean`; ephemeral with the warning, or from the configured keys |
| `InferenceReport` | always; it reports "no provider" as a warning rather than failing |
| the `TokenUsageHandler` registration on the `ObservationRegistry` | when a `MeterRegistry` exists |
| `PropagatingTraceCarrier` | when a `Tracer` and a `Propagator` exist |

`nessy.enabled=false` stays here and still switches everything off, because every other layer is
`after` this one and conditional on its beans. No property is required to start. An application
with the jar on its classpath and nothing configured gets a report saying no provider is
configured, and nothing else.

### 4b. Inference: one class, one bean, one decision

`InferenceAutoConfiguration` replaces the three vendor classes. It references the vendor modules
by name inside and contributes exactly one `InferenceProvider`,
`@ConditionalOnMissingBean(InferenceProvider.class)`, chosen by the rule in §5.3. The
`ObservedInferenceProvider.wrap` happens here, once, on whichever vendor won. The per-vendor
construction (`openai.base-url`, xAI's fixed URL and semconv name, the application's `JsonMapper`)
moves in unchanged, each through its adapter's `create(Customizer<XProviderConfig>)`, and every
`Customizer<OpenAiProviderConfig>` bean the application declares is applied after the starter's
own settings -- which is how an application adds a header or a timeout to the vendor client
without declaring the provider itself.

The embedding family keeps its three classes and its explicit `after=` order. A `Voyage` key
exists for one reason and the order says so; nothing there is decided by accident.

### 4c. Doors: peers, each on its own, each built through its customizers

`DirectHarnessAutoConfiguration` and `QueuedHarnessAutoConfiguration`, both `after` the base and
the inference layer, both:

- `@ConditionalOnClass` on their factory class,
- `@ConditionalOnBean(DataSource.class)` and `@ConditionalOnBean(InferenceProvider.class)` on the
  factory bean, so a door that cannot be built is simply absent rather than a startup failure for
  an application that never wanted it -- the report already says when no provider is configured,
- `@ConditionalOnMissingBean` against the interface (§3).

The factory bean is one `create` call: the starter's own settings first (`DataSource`, provider,
observations, trace carrier, storage codec, `ReplyTokens`, the lease default of §5.4), then every
`Customizer<DirectHarnessFactoryConfig>` or `Customizer<EngineConfig>` bean in order, on the same
config. The starter never constructs a store, a lock or a codec inline: today
`DirectHarnessAutoConfiguration.java:74-75` builds `JdbcAgentEventStore` and `JdbcPayloadStore`
by hand and line 79 hardcodes `InMemoryLocks`, and both go, because a factory config that takes a
`DataSource` decides those itself, exactly as `EngineConfig` already does for the queued door
("nothing here is the engine's own plumbing").

Neither door reads `nessy.system-prompt` or `nessy.type`. What an agent is for is said when a
harness is created, in code, which is what every example already does. The queued factory takes
`nessy.model` and `nessy.max-tokens` for its `InferenceOptions` because `EngineConfig.inference`
requires them; that is the only property a door reads.

`TurnHistories` and `Replies` stay with the queued door, from its factory. `ReplyTokens` moves to
the base because it is a property-derived value, not a door's.

### 4d. Cross-cutting, written once, as customizers

The listener collector stops being a `SmartInitializingSingleton` per door. It is one
`Customizer<DirectHarnessConfig<?>>`-shaped bean and one for the queued side in the base, each of
which attaches every `NarrationListener` bean to the harness being configured -- `listener(...)`
is already on both harness configs. The two doors need no shared type to make that work, and a
third factory joins by having customizers of its own config type. (How a customizer that applies
to every `I` is typed against a generic config is the one mechanical question here; see §5.1 on
nested generics.)

The report is in the base and nowhere else. No door carries a `@ConditionalOnMissingBean` guard
against the other door having done the same thing.

### 4e. Prompt and narration

Unchanged in substance. `PromptAutoConfiguration` drops its own `@EnableConfigurationProperties`
and its `before = NessyAutoConfiguration`, since the base owns the record and nothing in the base
consumes a `SystemPromptSource` any more. `OdysseyNarrationAutoConfiguration` orders `before` the
base rather than before the queued door, so its narrator exists when the collector customizer
runs.

## 5. Rulings

### RULED 2026-09-25: one `Customizer<C>`, everywhere

James: "We need to be doing everything consistently with the customizer approach."

```java
package org.jwcarman.nessy.api;

@FunctionalInterface
public interface Customizer<C> {
  void customize(C configurable);
}
```

One generic interface in `nessy-api`, replacing every customizer concept in the codebase. The
bean type says what it customizes: `Customizer<EngineConfig>`,
`Customizer<DirectHarnessConfig<String>>`, `Customizer<OpenAiProviderConfig>`. There is nothing
to name.

**Void return, ruled.** Every config in the codebase is a mutable builder returning `this` for
chaining, so a returned value would be ignored at every call site, and a customizer that returned
one would invite an immutable config that none of the others match.

**Three shapes considered and rejected on the way:**

- Four named interfaces (`DirectHarnessFactoryCustomizer`, `DirectHarnessCustomizer`,
  `QueuedHarnessFactoryCustomizer`, `QueuedHarnessCustomizer`), with the rest of the engine's
  configs to follow. Rejected: twenty-two configs would mean twenty-two top-level names, each a
  public type to be asked about and kept in step with its config.
- A nested `XConfig.Customizer` per config. Rejected as "a bit much" (James): the same count of
  types, relocated, and each generic config would carry its own type parameter through its nested
  interface.
- A shared factory-level type for listeners (`Narrated`). Retired earlier; a customizer per
  config is the seam, and it is the same seam everywhere.

**Auto-detection is measured, not assumed.** A throwaway `ApplicationContextRunner` probe against
this project's Spring 7 / Boot 4 (deleted since; nothing in the repo) established four things:
two `Customizer<AlphaConfig>` beans are collected as exactly two; a `Customizer<BetaConfig>` bean
does not leak into the Alpha lookup; **nested generics discriminate** -- `Customizer<Boxed<String>>`
collects exactly one and does not pick up `Customizer<Boxed<Integer>>`; and injection by
`ObjectProvider<Customizer<AlphaConfig>>` as a `@Bean` method parameter collects only the
matching beans, with no `ResolvableType` at the call site.

The third result is a feature and is used as one: a `Customizer<DirectHarnessConfig<String>>`
bean is applied to text-observation harnesses and not to a `DirectHarnessConfig<Order>`. A
feature module for text agents says so in its bean type, and nothing has to filter.

Why not the JDK's `Consumer<C>`, which Spring resolves just as well: it is a type with no meaning.
An unrelated `Consumer<EngineConfig>` bean in the context would be indistinguishable from a
customizer and would be applied as one. `Customizer` is the word that says "this is meant for
the config", and the auto-wiring depends on that word meaning only that.

### RULED 2026-09-25: `create(Customizer<Config>)` is the one construction shape

The implementation owns its own static `create`; the config is handed to the constructor; the
config never builds anything:

```java
public static DefaultDirectHarnessFactory create(Customizer<DirectHarnessFactoryConfig> customizer) {
  var config = new DirectHarnessFactoryConfig();
  customizer.customize(config);
  return new DefaultDirectHarnessFactory(config);
}
```

This is the opposite of what the adapters do today: `OpenAiInferenceProvider.create` applies the
customizer and then calls a package-private `OpenAiProviderConfig.build()`, so the construction
logic -- four branches of it -- lives in the value object. Three reasons the dependency points the
other way:

- A config describes; it does not construct. The thing that knows how to build a provider is the
  provider.
- `DefaultDirectHarnessFactory`'s six-argument public constructor (locks, events, payloads,
  provider, schemas, mapper) collapses to one parameter, and that is what gives a
  `Customizer<DirectHarnessFactoryConfig>` something to customize. Today there is nothing.
- `build()` goes away entirely, which the design of record already wants.

One consequence, recorded so it is not discovered later: **the implementation copies what it
needs in its constructor and does not hold the config.** A caller who mutates the config after
`create` returns would otherwise be mutating a live factory.

**Scope.** Everything. The twenty-two raw `Consumer<T>` public surfaces, verified by grep on
2026-09-25:

| module | surface |
|---|---|
| `engine.direct` | `DefaultDirectHarnessFactory.create`; `DefaultDirectHarnessConfig.inference`, `.tool`, `.approver`, `.context` |
| `engine.harness` | **`DefaultQueuedHarnessFactory(Consumer<EngineConfig>)`, a public constructor**; `DefaultQueuedHarnessConfig.inference`, `.effects`, `.tool`, `.approver`, `.context` |
| `engine.embedding` | `DefaultEmbedderFactory.create` |
| `engine.extraction` | `DefaultExtractorFactory.create` |
| `engine.schema` | `VictoolsInputSchemaGenerator(Consumer<SchemaGeneratorConfigBuilder>)`, a public constructor |
| `memory.episodic` | `JdbcEpisodes.create`, `EpisodeSummarizer.create` |
| `memory.summarizing` | `HeadSummarizer.create` |
| `approval.policy`, `approval.policy.opa` | `PolicyApprover.create`, `OpaPolicyEngine.create` |
| `console` | `ReplConfig.tool`, `ReplConfig.harness` |
| `api` | `Customizers.withDefaults()` returns `Consumer<T>`; becomes `Customizer<T>` |

And the nine named customizer interfaces that already exist, which are deleted in favour of
`Customizer<TheirConfig>`: `OpenAiProviderCustomizer`, `AnthropicProviderCustomizer`,
`GeminiProviderCustomizer`, `BedrockProviderCustomizer`, the four `*EmbedderCustomizer` types, and
`ReplCustomizer`. The adapters were the pattern's origin and are still brought to the rule,
because "consistently" is the ruling; a codebase with one generic customizer and nine named ones
would be teaching two designs.

That second half is the larger part of the work and the one with the widest call-site churn:
every `create(c -> ...)` in every example, test and auto-configuration keeps its lambda unchanged,
but every place that names the interface -- a `@Bean` returning one, a test double implementing
one, a javadoc `{@link}` -- moves. The lambda sites are the majority and do not change, which is
why it is still incrementally doable: see §8 step 2.

### RULED 2026-09-25: the free agent is deleted

The `QueuedHarness<String>` built from `nessy.type`, `nessy.system-prompt` and every `Tool` bean
goes, and with it `requireModel`, `resolveSystemPrompt` as a startup demand, and the
`SystemPromptSource` fallback. `nessy.system-prompt` and `-file` stay as properties an application
may read, because chat-web reads them into its own harness; nothing in the starter reads them.

Reasoning. Its only user is the documentation page. All three examples declare their own harness,
because the first thing any real agent needs -- an approver on the one tool that reaches outside --
is a decision the starter cannot make. And it was the reason two applications had to exclude the
class that also carried the properties. A default that every reference application works around,
and that builds an ungated agent in the one application that forgot to work around it, is not a
convenience.

With customizers the free agent is also unnecessary as a convenience: an application that wants
"a harness from properties" is one `Customizer<DirectHarnessConfig<String>>` bean away from it,
written where a reader can find it.

### RULED 2026-09-25: the provider is chosen, or the application is refused

One auto-configuration, one bean, one decision:

| keys configured | explicit choice | result |
|---|---|---|
| none | -- | no bean; the report warns |
| exactly one | -- | that vendor |
| several | names one of them | that vendor |
| several | absent | **fail at startup**, naming the keys found and the property that settles it |
| any | names a vendor with no key, or one whose module is absent | fail, saying which |

An application that declares its own `InferenceProvider` bean is not in this table; the whole
class backs off. The property arbitrates only among the starter's own candidates, and only when
there is more than one. That is what keeps the 2026-08-31 ruling intact: the starter still takes a
bean and reads no process environment behind the application's back; a property in
`application.yaml` is written down where a reader can find it, which is the property that ruling
was protecting.

Reasoning. Refusing to guess is what `ModelDiscovery` already does for non-Spring applications
("two credential sets → refuses to guess and names `NESSY_PROVIDER`"), and the house rule is that
an unanswered question is a no. Warning after the fact, as the report does today, describes a
missing decision rather than making one: the symptom it warns about is a 404 from a vendor nobody
meant to call, and the only person who reads a warning is the one already debugging it. The
reverted attempt at a `nessy.provider` property failed for a reason never established; whatever it
was, repeating a conditional across three classes kept three deciders, and this design has one.

The property name is a recommendation, not a ruling: `nessy.inference.provider`, values `openai`,
`anthropic`, `gemini`, `xai`, matching the semconv provider names the adapters already report
(with `x_ai` accepted for xAI, since that is what `providerName()` returns). See §10.

### Decided without a ruling needed

**Locks default to `JdbcLeases`.** The direct door only builds a factory when a `DataSource`
exists, and a database is exactly the case where `JdbcLeases(DataSource, kind, ttl)` is available;
`nessy-engine` already depends on `nessy-lease`. So `DirectHarnessFactoryConfig`'s default over a
`DataSource` is a lease keyed by the agent, and `InMemoryLocks` stays for the terminal, through
`DefaultDirectHarnessFactory.inMemory`. An application that wants something else says so in a
`Customizer<DirectHarnessFactoryConfig>`, or declares a `Locks` bean, which the starter hands to
the config before the customizers run. This deletes chat-web's `agentLocks` bean
(`ChatConfiguration.java:85`). Ten minutes is the TTL, the value chat-web arrived at, because a
direct turn can wait five on a person; it becomes a property only if somebody needs to change it.

**`nessy-console` depends on the starter, not the autoconfigure jar directly.** A library that
drags every auto-configuration onto its consumers' classpath is why chat-cli had to exclude
classes it never asked for. This matters less once nothing demands properties, and it is still the
wrong dependency to publish.

## 6. Feature auto-install: the payoff

With `Customizer<DirectHarnessConfig<String>>` and `Customizer<QueuedHarnessConfig<String>>` as
bean types, a feature module can contribute one. Putting `nessy-memory-notebook` on the classpath
then installs the notebook's four tools and its index as an ambient source on every text harness
the starter's factories create; the plan module installs `update_plan` and the plan ambient; the
episodic module installs `begin_episode`, `recall_episode`, its index and its summaries. That is
the nine lines chat-web writes today (`ChatConfiguration.java:175-187`), and chat-cli writes
again, deleted.

Each feature's auto-configuration is `@ConditionalOnClass` on its own store and
`@ConditionalOnBean(DataSource.class)`, contributes the store bean `@ConditionalOnMissingBean`,
and contributes the customizer. The customizer is the whole of the integration; the store's
constructor and the tool factories are untouched. A feature whose tools take a typed observation
declares its customizer over that type and is applied to nothing else, by §5.1's nested-generics
result.

### 6a. A required API change: the customizer must read the agent type

`JdbcNotebook(DataSource, AgentType)` and `JdbcPlanStore(DataSource, AgentType)` are scoped per
agent type, and `DirectHarnessConfig` and `QueuedHarnessConfig` expose `agentType(...)` as a
setter only (`DirectHarnessConfig.java:40`, `QueuedHarnessConfig.java:41`). A feature customizer
cannot build the right store without knowing which agent it is being installed on, so the configs
gain a getter for the agent type, and the feature's stores are made per agent type as harnesses
are created rather than once as beans. It follows that a bean customizer must run **after** the
application's own lambda has set the type, which is the ordering question in §6d.

### 6b. Implicit tools change what the model can do

A jar on the classpath silently gives the model new callable tools, and a tool the system prompt
never mentions is dead weight the model may still call. Two things follow. Every feature
auto-configuration has an opt-out (`nessy.features.notebook.enabled=false`, in the pattern of
`nessy.enabled`). And the feature's prompt fragment travels with it: the notebook module already
knows how to say "your notes appear as an index; recall one in full; never invent an id", because
chat-web and chat-cli both copy that paragraph into their prompts today. Where a contributed
fragment goes -- appended to the harness's system prompt, offered as a `PromptVariableSource` the
application's template can place, or both -- is the open question that blocks this section
(§10.1). Tools do not auto-install until it is answered, because tools without their paragraph
are the worse default.

### 6c. Per-agent-type selection

Every harness a door creates gets every feature, unless a feature can say which agent types it is
for. A watchman does not want a notebook. The first cut is the opt-out per feature per
application; a per-agent-type filter (`nessy.features.notebook.agent-types=chat`) is the obvious
second, and it needs the getter in §6a to work at all. Recorded so the first cut is not mistaken
for the design.

### 6d. Ordering

Bean customizers run **before** the application's own lambda, so an explicit call at the creation
site always wins -- Boot's convention for every `*Customizer` it ships. That is right for harness
customizers, where "the application said `.tool(...)` last" should be the last word.

It is not obviously right at the factory level. A `Customizer<DirectHarnessFactoryConfig>` that
replaces the `DataSource` or the `Locks` is infrastructure the application may want to win *over*
a creation-site default, and a feature that must see the final agent type (§6a) needs to run
after the lambda that set it. So the two levels may want opposite orders, or harness customizers
may need a two-phase shape (before the lambda, and after it). This is flagged, not decided; the
resolution is in §10.

## 7. The module split is orthogonal

The split approved in principle -- `engine-core` / `engine-direct` / `engine-queued` /
`engine-embedding`, `nessy-postgres` for the JDBC stores, contracts in `nessy-spi`, a starter per
door -- is the only shape where the classpath is the whole answer to "which door", which is the
Boot idiom. It is not required by anything in this record and is not ready to be cut.

Evidence, from the engine's package graph. `engine.direct` imports `agent`, `core`, `history`,
`inference`, `narration` and `tool` at one hop; `history.EventStreamHistory` imports
`engine.store`, `tool.DefaultReplies` imports `engine.effect` and `engine.store`, and
`inference.ContextAssembler` imports `engine.store`. The direct auto-configuration itself
constructs `JdbcAgentEventStore` and `JdbcPayloadStore` from `engine.store`. So the transitive
closure of the direct door is most of the engine, and the split is preceded by moving the JDBC
classes and the effect coupling out of what the direct door reaches. That is an engine-hygiene
project with its own record. Nothing here waits on it, and nothing here gets harder when it lands:
the layered classes in §4 become the contents of the per-door jars unchanged, and `Customizer<C>`
lives in `nessy-api`, which every jar already has.

What the split would fix that this record does not: a direct-only application carries the queued
classes. That costs nothing at runtime, because the queued factory is built only over a
`DataSource` and a provider, both of which a direct-only application also has; the factory is
cheap and spends nothing until a harness is created from it.

## 8. Order of work

Each step leaves the reactor green and is worth merging alone.

1. **Split the base from the queued door, and delete the free agent.** `NessyAutoConfiguration`
   becomes §4a; `QueuedHarnessAutoConfiguration` takes the factory, `TurnHistories` and
   `Replies`; the direct door drops its own `@EnableConfigurationProperties` and its `NessySchema`
   dependency on the other door's class; `PromptAutoConfiguration` drops its re-enable. Unblocks:
   chat-cli and `Repl` stop excluding anything; the report is written once; chat-web stops
   building an inert second harness; the §3 stopgap is subsumed. Add the test the branch lacked:
   each door with the other excluded, with and without a `DataSource`.
2. **`Customizer<C>` lands, and everything moves onto it.** Incrementally, in this order, each
   commit compiling:
   - `Customizer<C>` in `nessy-api`, and `Customizers.withDefaults()` returning it. Nothing else
     changes; nothing yet uses it.
   - The two factories: `DefaultQueuedHarnessFactory.create(Customizer<EngineConfig>)` replaces
     the public constructor, and `DefaultDirectHarnessFactory.create(Customizer<DirectHarnessFactoryConfig>)`
     replaces the six-argument constructor, with `inMemory` kept. These two are on the critical
     path for step 4; nothing else in this step is.
   - The remaining engine surfaces, one config type per commit, callers moved in the same commit.
     Every `create(c -> ...)` lambda is unchanged; only the parameter type in the signature moves.
   - The nine named interfaces, one adapter per commit: the interface deleted, `create` retyped,
     `build()` inverted into the provider's constructor per §5.2, the config's package-private
     `build` removed. Their lambda call sites -- every test, every auto-configuration -- do not
     change. What changes is the handful of places naming the interface, which a compile finds.
   - `ReplConfig` and `ReplCustomizer` last, with the console's tests.
   A single sweep would be one commit touching every module and every example, and a review of it
   would find nothing a compile did not. The incremental route is preferred because each commit
   is one config type and reads as such.
3. **One inference layer.** `InferenceAutoConfiguration` replaces the three vendor classes with
   the §5.3 table; the report loses its key-counting warning because the condition it warned
   about no longer starts. Independent of steps 1 and 2; ordered here only so the report moves
   once. Unblocks: the provider-selection bug closes.
4. **The starter applies every `Customizer<C>` bean.** The doors build their factories through
   `Customizer<DirectHarnessFactoryConfig>` / `Customizer<EngineConfig>` beans and create
   harnesses through the harness-config customizers; the inference layer applies
   `Customizer<XProviderConfig>` beans; the listener collector becomes a customizer in the base;
   the lease default lands in `DirectHarnessFactoryConfig` and chat-web's `agentLocks` goes.
   Needs only the first two commits of step 2. Unblocks: everything in §6.
5. **The agent-type getter, then feature auto-install.** The getter is a one-line API change on
   two interfaces and lands alone. Then the notebook module's auto-configuration, as the pilot,
   with its opt-out and its prompt fragment; plan and episodic follow once §10.1 and §10.2 are
   answered on the pilot. chat-web and chat-cli shrink to their approver and their date tool.
6. **Docs and the console's dependency.** `docs/guides/spring-boot.md` and `package-info.java`
   are rewritten to describe §4 through §6; `nessy-console` depends on the starter.

Step 1 is the one that matters if only one lands. Step 2 is the long one and the one with no
design risk; it can proceed in parallel with steps 1 and 3 on separate surfaces, and only its
first two commits are on the critical path.

## 9. Not being done

- **Named customizer interfaces, anywhere.** Retired by §5.1, including the nine that exist. One
  generic type is the design; the bean type carries the meaning.
- **A shared factory type for listeners.** Retired by §5.1. A customizer per config is the seam;
  a second seam for one method would be the inconsistency this record ends.
- **A public `build()` anywhere.** §5.2 inverts the last ones; the design of record already
  forbade the alternative, and the engine is being brought to the rule, not the rule reopened.
- **Starters per vendor.** Adding `nessy-inference-openai` already tells the autoconfigure jar
  everything a vendor starter would, and a starter per vendor does nothing about two vendors on
  one classpath, which is the actual problem.
- **Starters per capability.** With §6, a feature module's jar *is* its installation; an
  artifact whose only content is "also add this jar" adds a name and nothing else.
- **A property that names the doors.** Both factories may exist; an application creates harnesses
  from the one it wants. A `nessy.doors` word would be new vocabulary for a distinction the code
  already makes by which factory a `@Bean` method asks for.
- **Discovery in the starter.** Ruled out 2026-08-31 and not reopened; §5.3 reads properties an
  application wrote, never the shell that launched it.
- **The module split.** §7; its own project.
- **Changing `InMemoryLocks` itself.** Striping is correct for a terminal with one conversation.
  The wrong part was choosing it for a server, and §5.4 fixes the choice.

## 10. Open, for James

1. **Where a feature's prompt fragment goes** (§6b): appended to the system prompt, offered as a
   `PromptVariableSource`, or both. This is the one question blocking tools from auto-installing,
   and the most important in the document: everything from §6 onward waits on it.
2. **Ordering at the factory level, and the agent-type phase** (§6d): bean customizers before the
   creation-site lambda is the recommendation for harnesses; whether factory customizers run
   after, and whether a feature that needs the final agent type gets a second phase, is asked
   rather than assumed.
3. **The provider property's name.** Recommended `nessy.inference.provider` with values
   `openai | anthropic | gemini | xai`. Alternatives considered: `nessy.provider` (the reverted
   attempt's name; too broad now that embedding providers exist) and `nessy.inference.vendor`
   (accurate, but the code says provider everywhere).
4. **The direct factory config's name.** `DirectHarnessFactoryConfig` is used throughout this
   record as the counterpart of `EngineConfig`; whether it should instead be a second
   `EngineConfig`-style name is a naming question on one new public type, asked because it is
   new.

Retired since the previous draft, with nothing to decide: "Engine" versus "Factory" on a
factory-level customizer pair, and names for the other eighteen customizers. There are no such
names.

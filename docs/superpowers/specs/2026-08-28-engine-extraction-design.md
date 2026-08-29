# Engine Extraction: Nessy becomes Pekko-driven

**Status:** ruled 2026-08-28 by James. Implements the composition design
(`2026-08-28-actor-composition-design.md`) as a real module.

## 1. What this is, and what it is not

The Pekko port stopped being a spike on 2026-08-27 — *"we love this and let's rewrite Nessy to use
Pekko as its engine."* This spec says how.

**It is not an API rewrite.** Three times while designing this, a door we set out to invent turned
out to already exist:

| what we designed | what was already there |
|---|---|
| `HarnessFactory.create(Class<O>, customizer)` | `Nessy.harness(Class<O>, HarnessCustomizer<O>)` |
| agent-scoped turn subscription | `TurnEvent`, `TurnObserver`, `Subscription` |
| per-agent model selection by name | `ModelProvider.model(String id)` |

That is the strongest evidence available that the surface is right and the engine is what needs
replacing. The migration is therefore an **implementation swap behind an interface**, not a
redesign — which is also why it can be done with main green the entire way.

## 2. Modules

```
nessy-api      Agent, Tool, Harness, HarnessFactory, HarnessConfig,
               HarnessCustomizer, ObservationRenderer, Coalescer,
               TurnEvent, TurnObserver, Subscription        — no SPI types, no Pekko
nessy-spi      Memory, Model, ModelProvider, Substrate       — the pluggables
nessy-engine   PekkoHarnessFactory, AgentActor, TurnActor,
               ToolInvocationActor, Claims, Backlog          — the only module that knows Pekko
nessy-agent    DELETED (final task)
```

`nessy-engine` carries no `-pekko` suffix. **A suffix promises a second engine we do not intend to
write**, and this codebase's most expensive recurring defect is exactly that: an API promise nothing
keeps (§6).

### 2.1 The rule that keeps `nessy-api` clean

**`nessy-api` names no SPI type, and everything reachable through the harness doors lives in it.**

The apparent conflict — `HarnessConfig` today has `Memory`, `Model`, `Substrate` on its surface, and
`nessy-spi` depends on `nessy-api`, so api cannot name them — dissolves once you separate two things
that were conflated:

| | supplied where | example |
|---|---|---|
| **infrastructure**, shared by every agent | the factory's constructor | `ActorSystem`, `Substrate`, `ModelProvider`, `ObjectMapper`, `Clock` |
| **vocabulary**, chosen per agent | `HarnessConfig` | renderer, coalescer, tools, system prompt, model NAME |

`Memory` leaves the surface entirely: the factory manufactures one per agent over the shared
`Substrate`, which is what `Function<String, Memory> memoryFactory` already does internally.

**Model selection is a name, not a `Model`.** An agent may name a model; absent that, the provider's
default is used. `ModelProvider.model(String id)` already resolves it. This keeps `Model` out of api
while preserving per-agent model choice — a watchman on something cheap, a planner on something
expensive.

## 3. `Harness` becomes an interface

`Harness<O>` is a `final class` today. For a Pekko harness to BE a `Harness`, it becomes an
interface — and that single change is what converts "rewrite Nessy" into "add a second
implementation, then delete the first."

```java
public interface HarnessFactory {
  default Harness<String> create(HarnessCustomizer<String> customizer) {
    return create(String.class, cfg -> { cfg.observationRenderer(<text renderer>); customizer.customize(cfg); });
  }
  <O> Harness<O> create(Class<O> observationType, HarnessCustomizer<O> customizer);
}
```

Three details settled at authoring time:

- **`<O>` is declared on the method**, not the interface — an interface-level parameter would make
  the `String` default impossible.
- **`HarnessCustomizer<O>`, not `Consumer<HarnessConfig<O>>`.** The named type already exists; two
  names for one function is precisely how `ObservationRenderer` got duplicated on 2026-08-28.
- **The default sets the text renderer BEFORE applying the customizer**, so a caller can override it.

`Class<O>` is load-bearing, not ceremony: `EntityTypeKey` and `ServiceKey` take class literals, and
the backlog codec needs the type. `HarnessConfig` already carries `Codec<O> backlogCodec` with a
default, so a non-`String` observation is already persistable.

### 3.1 Lifecycle

`shutdown()` stops the actors the harness spawned and **never terminates an `ActorSystem` it was
handed**. A harness that kills its caller's system is a very unfun bug to find inside a Boot app.

A factory handed an existing system also cannot be its guardian — the guardian behavior is fixed at
system creation — so top-level spawning goes through `SpawnProtocol` or a named parent the factory
owns. This decides the constructor signature; settle it before writing the door.

## 4. Migration order

Both engines coexist, then one is deleted. Main stays green at every step, and every step is
bisectable.

1. **`nessy-engine` exists**, empty but wired into the reactor and the BOM.
2. **The port's engine moves in** from `nessy-examples/watchman-pekko` — actors, `Backlog`,
   `Coalescer`, `Claims`. The example keeps only its Spring wiring and its tools.
3. **`Harness` becomes an interface**; `nessy-agent`'s class becomes one implementation of it.
   `nessy-agent` still passes its own tests. Nothing else moves.
4. **`HarnessFactory` and the config split** — infrastructure to the constructor, vocabulary to
   `HarnessConfig`. `Nessy.harness(...)` becomes a thin delegate to the default factory so existing
   callers keep compiling.
5. **`PekkoHarnessFactory`** implements the door.
6. **watchman-pekko runs on `nessy-engine`** through `HarnessFactory`, and `soak.sh` passes —
   including its "parked at least once" assertion, without which the run is vacuous.
7. **`nessy-agent` is deleted** in one commit.

Step 6 is the real gate. The suite went green past four separate defects on 2026-08-28; the soak
caught what the suite could not.

## 5. Rulings carried in from the open list

Settled here rather than left to discovery mid-implementation:

1. **Merge timestamp (§9.1)** — a coalesced survivor inherits the **oldest** `receivedAt`. Queue
   position stays honest, and a busy topic cannot look eternally fresh to a staleness policy.
2. **Attempt counts (§9.2)** — retry stays with the **turn**, not the invocation actor. An ephemeral
   actor restarting at attempt zero could exceed `maxAttempts` indefinitely across process bounces,
   and making attempts durable would add a second source of truth (composition §10).
3. **Blocking I/O (§9.3)** — `Substrate` calls run on a **dedicated blocking dispatcher** with
   `pipeToSelf` for the result; the agent stashes arrivals while a take is in flight. Invisible with
   one agent, classic starvation with many, since the model and tool workers share the dispatcher.
   Retrofitting this later means touching every actor, so it is decided before the first one is
   written.

## 6. The debt this must not inherit

**Four API promises are kept only by `ProviderModelCallExecutor`**: capability negotiation, `gen_ai.*`
instrumentation, the token budget, and `contextWindow`. The port requests `PROMPT_CACHING`,
`OpenAiModelProvider` deliberately omits it, and nothing anywhere compares the two.

The cause is structural, not sloppiness. `ModelSettings(maxTokens, capabilities, contextWindow)`
holds what a caller **requests**; a resolved `Model` never states what it **supports**. There is
nowhere to compare, which is also why `contextWindow` is nullable and users must guess a number the
model already knows.

Resolving a model by name (§2.1) is the first moment anything holds both the request and the model,
so it is where this closes. **Requires James's sign-off before landing** — it adds a method to an
SPI interface:

- `Model` reports its actual capabilities and context window.
- The factory compares on resolve, and an unknown model name fails at `create()` — not forty
  minutes later when an observation arrives.

Also deferred pending sign-off: **`ToolResult` carrying `List<ContentBlock>`** (§9.4). MCP's
`CallToolResult` content is an array and Anthropic's `tool_result` accepts images; text-only drops
an image silently, which is the wrong answer. It makes the type recursive and forces
OpenAI-compatible providers to flatten non-text blocks.

## 7. Out of scope

The **product spec** — `Agent`, `Tool`, `Memory`, `Approver` described without mentioning Pekko —
is downstream of this work and gets its own document. This spec is about where code lives; that one
is about what Nessy is.

## 8. Lines still to draw — the deferred api migration

**Ruled 2026-08-28 by James** (*"whatever the quickest path forward. I want proper lines drawn on
everything eventually so we need to move what doesn't belong later to clean it up"*).

§2's module table is the destination, not the next step. Moving the harness doors into `nessy-api`
turned out to be blocked behind vocabulary still tangled with the engine being deleted, so the
migration is deferred until `nessy-agent` is gone — and recorded here so it is paid rather than
forgotten.

### 8.1 What blocked it

`Harness`'s transitive closure inside `nessy-agent` is **21 types**, and they are two different
things wearing one coat:

| | types |
|---|---|
| **Vocabulary that belongs in `nessy-api`** | `Agent`, `AgentId`, `TurnOutcome`, `ApprovalDesk`, `CompletionDesk` |
| **The scheduler engine's reducer machinery, deleted with it** | `AgentEvent`, `AgentPhase`, `AgentTransition`, `Effect`, `ModelOutcome`, `ModelResponseId`, `ToolCallEvent`, `ToolCallPhase`, `ToolCallTransition`, `ToolError`, `ToolOutcome`, `Routing`, `ApprovalRouting`, `ContinuumIds`, `AgentPhaseStore` |

The pull runs through the desks: `ApprovalDesk` reaches `AgentPhaseStore`, which drags the whole
event/phase model. Moving the doors to api today would haul the engine we are deleting into the
vocabulary module — the exact inversion `LayeringTest` exists to prevent.

**Sequencing this after the deletion is strictly cheaper: 15 of those 21 types cease to exist**, so
the extraction shrinks to the six that were always vocabulary.

### 8.2 The interim shape

`nessy-engine` owns its door types (`HarnessFactory`, `HarnessConfig`, `HarnessCustomizer`, and its
own `Harness`). `nessy-agent` keeps its own until it is deleted. This is knowingly two types named
`Harness` for the duration.

They are not duplicates of one design. The engine's door names no `ApprovalDesk` or `CompletionDesk`
— in the actor engine an approval goes through the `Approver` and its own actor (composition spec
§7), never a desk — so the engine's door is genuinely a different shape, not the same shape written
twice.

### 8.3 The debt, itemised

To be paid once `nessy-agent` is deleted:

1. **`Agent`, `AgentId`, `TurnOutcome` move to `nessy-api`** — pure vocabulary with no engine tie.
2. **The engine's `Harness`, `HarnessConfig`, `HarnessCustomizer`, `HarnessFactory` move to
   `nessy-api`**, satisfying §2.1: infrastructure to the factory's constructor, vocabulary on the
   config. Only then is there one `Harness`.
3. **`ApprovalDesk` / `CompletionDesk`: decide, do not drift.** Either they become api interfaces
   with engine-side implementations, or the actor engine's Approver route replaces them outright.
   The interim shape ducks this question; it must be answered, not inherited.
4. **`AgentActor.userMessage` returns to package-private** (deferred from Task 2b, where a module
   boundary separated it from its white-box tests).
5. **`Coalescer`, `BacklogItem`, `StalenessPolicy` move to `nessy-api`** — user-facing vocabulary
   currently sitting in the engine.
6. **`LayeringTest` grows a case for `nessy-engine`**, so the boundary that is checked mechanically
   for api and spi is checked for the engine too.

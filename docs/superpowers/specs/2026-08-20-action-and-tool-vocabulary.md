# The Action Wave — authorization speaks the industry's language, tools shed their ceremony

**Date:** 2026-08-20
**Status:** RATIFIED in conversation (owner). Binding amendments to
`2026-08-16-authorization-design.md` (§0 anchor, §2, §5, §7) and to the core tool vocabulary.
Subordinate to `2026-08-18-agent-as-scope-design.md` and `2026-08-20-durable-computation.md`.

## 0. Why

Two forces. First, terminology: in every mainstream authorization model the thing being attempted
is the **action** (XACML's request is subject/action/resource/environment; AWS IAM's `Action:`;
Cedar's `permit(principal, action, resource)`) — and in those same systems **"effect" means the
verdict** (`Effect: Allow | Deny`). Nessy's "the tool's effect flows to the policy" reads
backwards to anyone from that world. Second, trust: the effect statement was baked into the
`Tool`, so governing a third-party tool (an MCP toolbox tool) meant wrapping it in a class — the
"trusted, developer-authored statement" was authored by whoever wrote the remote server.

## 1. Effect becomes action, and the speaker moves to the grant

The 2026-08-16 anchor sentence — "the authorization of a tool call begins with the tool's own
statement of its effect" — is amended: **authorization begins with the grant's statement of the
action.** A grant declares four parts, welded at construction: the tool, the **action
contributor**, the enrichers, and the policy.

- `ActionContributor<I, A>` — `A actionOf(I input)` — produces the action from the bound input.
  It is NOT an enricher (an enricher consumes the action and deposits assessments; the
  contributor produces it). It carries `displayName()`/`named(...)` exactly as `Enricher` does,
  for the report.
- The judgment closure runs: bind input by class token → contributor renders the action →
  deposit it under the well-known `AuthzContext.ACTION_KEY` (§4.2 of agent-as-scope: new facts
  are keys) → enrichers in order (each still receives the action as its typed parameter) →
  policy judges. The action is rendered exactly once per evaluated call; the fail-closed stage
  message renames from "effect stage: " to "action stage: ".
- Rung 0/1 grants get the default contributor `input -> String.valueOf(input)` — the approver
  is still guaranteed to see something. Rung 2/3 welds `<I, A>` inside the typed factory:
  `grant(tool, contributor, enrichers, policy)`, plus a no-enricher door
  `grant(tool, contributor, policy)`.
- **`EffectfulTool` is deleted and `Tool.effect(T)` is deleted.** A tool is name, description,
  input record, execution, and completion requirement. Authorization never appears in the tool
  API again. Third-party tools become governable without wrapping: the application states what
  the call means, per grant.
- Renames ripple: `ToolGrant.Judged.effect()` → `action()`; `ApprovalRequest.effect()` →
  `action()`; `Enricher<E>`/`UsagePolicy<E>` re-document their parameter as the action type
  `A`. `PolicyDecision` keeps its name — it is the verdict, and it is already unambiguous.
- **The report survives by declaration, not reflection.** `AuthorizationReport`'s
  `EffectfulTool`-interface reflection is deleted; a `GrantStory` reports the contributor's
  `displayName()` (or that the grant uses the default `String.valueOf` contributor). Type
  reflection on an erased lambda is not attempted.

Named non-goal: nessy does not adopt the full principal/action/resource request triple. `A` is
whatever record the org wants; a structured action may embed its resource.

## 2. The standard risk shape

A well-known slot plus one good default shape, so standard policies become shippable —
opinions, not mandates (an org with its own model deposits its own type under its own key).

- `RiskLevel` — `VERY_LOW, LOW, MODERATE, HIGH, VERY_HIGH` (NIST SP 800-30's five qualitative
  levels; declaration order is severity order).
- `record RiskAssessment(RiskLevel likelihood, RiskLevel impact, List<String> factors)` with
  `RiskLevel severity()` computed by the NIST-style combination matrix (the matrix is in the
  implementation plan and is part of this ruling).
- `RiskFactors` — String constants seeding the factor vocabulary from MCP tool annotations,
  the closest thing agent tooling has to a standard risk vocabulary: `destructive`,
  `irreversible`, `external-world`, `read-only`, plus `spends-money` and `touches-pii` as
  nessy's own additions. Factors are open strings, deliberately — org vocabulary, not a sealed
  grammar.
- `AuthzContext.RISK_KEY` (typed `RiskAssessment`) with a `risk()` convenience accessor,
  beside `PRINCIPAL_KEY`/`DECLARED_INTENT_KEY`.
- **The threshold policy** ships as the canonical consumer:
  `RiskPolicies.threshold(RiskLevel approveAt, RiskLevel denyAt)` — severity below `approveAt`
  allows, below `denyAt` requires approval, at-or-above denies. An absent assessment fails
  closed (Deny naming the empty slot). This is the one-line policy every deployment actually
  wants, composable with any org's assessor enricher.

## 3. Intent reborn

Only `DECLARED_INTENT_KEY` survived the distillation; the bolt-on returns, rebuilt against the
new machine, in `nessy-agent` (`org.jwcarman.nessy.agent.intent`):

- `record Intent(String declaration)` — the model's untrusted claim, free text.
- `IntentStore` SPI (pre-scoped, like `Memory`): `record(Intent)`, `Optional<Intent> latest()`;
  `InMemoryIntentStore` reference implementation.
- `IntentTool` — the tool the model calls to declare intent before acting (IMMEDIATE, always
  allowed by design — it is the claim channel, not an effectful act).
- `IntentEnricher` — deposits the latest declaration under `DECLARED_INTENT_KEY`. The claim is
  untrusted by definition (2026-08-16 §7); policies weigh it accordingly.

## 4. The principal kit

The slot existed; the kit did not. `Enrichers.principal(Supplier<?> resolver)` — a named
enricher depositing the resolved principal under `PRINCIPAL_KEY`. Nessy still never imposes an
identity shape; authorization here is never authentication — the resolver hands over an
already-authenticated identity.

## 5. `Tool.of` — the config factory

First-party tools stop requiring a class. Per the dsl-coherence law (no public `build`/`builder`
anywhere — `NoPublicBuildersTest`), the factory takes a named customizer and returns the
finished thing, mirroring the `TurnObserver`/`TurnObserverConfig`/`TurnObserverCustomizer`
precedent exactly:

```java
var tool = Tool.of(CreateAccount.class, t -> t
    .name("create-account")                       // defaults to kebab-case of the record name
    .description("Create a new bank account.")    // mandatory — it is for the model
    .executes(cmd -> bankSvc.createAccount(cmd.name(), cmd.type())));
```

- Handler doors, exactly one required: `executes(Function<T, ?>)`,
  `executes(BiFunction<T, ToolContext, ?>)`, and `defers(BiConsumer<T, ToolContext>)` — the
  deferring door returns `Awaited.deferred()` and sets `requiredCompletion() = DURABLE`
  automatically, so a deferring tool cannot forget to declare itself.
- Return rendering: a `String` passes through as `ToolResult.ok`; a `ToolResult` passes as-is;
  anything else JSON-serializes. A thrown exception is the existing in-band failure.
- `requires(CompletionPolicy)` overrides the completion requirement.
- The verb is `executes`, never `action` — the tool *executes*, the grant states the *action*,
  the policy renders a *decision*. Three words, three concepts.
- The `Tool` interface underneath is unchanged; the class-based route remains for complex tools.

## 6. The sealed tool-event channel

`EventEmitter.emit(Object)` and the `ToolProgress` wire record are deleted. The channel had one
real shape and a `String.valueOf` fallback — an untyped funnel in a sealed-grammar codebase.

- Speakers keep specific methods: `ToolContext.progress(String)` stays; future events each get
  their own method.
- Listeners get the fixed vocabulary: `sealed interface ToolEvent` (sole member today:
  `record Progress(String message)`) and `ToolEventListener { void on(ToolEvent event); }` as
  `ToolContext`'s second component. The executor narrates by exhaustive switch; the stringly
  fallback dies at compile time.
- The channel is sealed, deliberately: everything emitted must be interpreted by the harness.
  Open-ended facts for authorization already have their open bag (`AuthzContext` keys); a tool
  that wants to log, logs.
- `ToolProgress.toolCallId` dissolves — it existed only because the channel was untyped; the
  executor already holds the call.

## 7. Sequencing

One plan, six tasks in dependency order: (1) the sealed event channel (ToolContext settles
first), (2) the action grammar in core, (3) the agent-side action fallout (ApprovalRequest,
chokepoint, demos move their effect overrides to grant contributors), (4) risk + principal kit,
(5) `Tool.of`, (6) intent rebirth + the governed-turn flagship (intent declared, risk assessed,
threshold policy requires approval, desk approves, tool runs — the whole gate in one narrated
demo through the autonomous host).

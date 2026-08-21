# The Action Wave Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rename the authorization statement from *effect* to *action* with the speaker moved to the grant (`ActionContributor`), ship the risk/intent/principal enricher kit, the `Tool.of` config factory, and the sealed `ToolEvent` channel.

**Architecture:** Six tasks in dependency order per the spec's §7: the event channel settles `ToolContext` first; then the action grammar in core; the agent-side fallout; the risk+principal kit; `Tool.of`; and intent rebirth closed by a governed-turn flagship demo through the autonomous host.

**Tech Stack:** Java 25, Maven multi-module, JUnit 5 + AssertJ, hand-rolled fakes only (`ScriptedModelProvider`, `PumpedExecutor`, `RecordingTurnObserver`, `RecordingAgentObserver` in `nessy-agent/src/test/.../support/`).

**Spec:** `docs/superpowers/specs/2026-08-20-action-and-tool-vocabulary.md` (ratified; amends `2026-08-16-authorization-design.md`). Also binding: `2026-08-18-agent-as-scope-design.md` §4.2/§4.3/§10.9 and the dsl-coherence law pinned by `nessy-core`'s `NoPublicBuildersTest`.

## Global Constraints

- No `@SuppressWarnings` ever; all casts by class token. No star imports. No mocking libraries.
- camelCase prose test names; S5778 (ONE throwing invocation per assertion lambda, setup outside); S5841 (non-emptiness before any all/none-match predicate).
- No public `build(...)` or `builder(...)` anywhere `NoPublicBuildersTest` scans (nessy-core, providers, model-env, testing, tool-mcp) — `Tool.of` follows the factory+customizer pattern (`TurnObserver.observe`/`TurnObserverConfig`/`TurnObserverCustomizer` is the precedent to mirror).
- The action is rendered exactly once per evaluated call; the rung-0 `UsagePolicy.Static` fast path renders nothing and assembles nothing.
- Fail-closed stage naming: "action stage: ", "enricher stage <name>: ", "policy stage: " — the chokepoint's catch prefixes "authorization failed: ".
- Suspension invisible; denial in-band — unchanged invariants.
- Layering unchanged: durable imports nothing above; core never imports agent; do not weaken `LayeringTest`/`ZoneBoundariesTest`/`NoPublicBuildersTest`.
- Before every commit: `./mvnw license:format -Plicense -q` then `./mvnw spotless:apply -q`. Final check per task: `./mvnw -q clean verify` fully green, no API key, no network.

---

### Task 1: The sealed tool-event channel

**Files:**
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/api/tool/ToolEvent.java`
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/api/tool/ToolEventListener.java`
- Modify: `nessy-core/src/main/java/org/jwcarman/nessy/api/tool/ToolContext.java`
- Delete: `nessy-core/src/main/java/org/jwcarman/nessy/api/event/EventEmitter.java`, `.../event/ToolProgress.java` (and the `api/event` package if then empty)
- Modify: `nessy-agent/.../agent/tool/RegistryToolCallExecutor.java` (exhaustive narration; `narrateProgress` fallback dies)
- Test: `ToolContextTest`, `RegistryToolCallExecutorTest`, `McpToolboxTest` construction sites

**Interfaces (Produces):**

```java
/** What a tool may tell the harness mid-execution — sealed: every event is harness-interpreted. */
public sealed interface ToolEvent {
  /** A progress heartbeat from inside a long-running tool. */
  record Progress(String message) implements ToolEvent {
    public Progress { /* null/blank guard: "message must not be blank" */ }
  }
}

@FunctionalInterface
public interface ToolEventListener {
  void on(ToolEvent event);

  /** The absent audience: accepts everything, tells no one. */
  static ToolEventListener noop() {
    return event -> {};
  }
}
```

`ToolContext` becomes `record ToolContext(ToolCall call, ToolEventListener events, CallAddress address)`; `progress(String message)` body becomes `events.on(new ToolEvent.Progress(message))` (keep the existing javadoc's spirit). In the executor, the listener handed to `ToolContext` is `event -> narrate(call, event)` where:

```java
private void narrate(ToolCall call, ToolEvent event) {
  switch (event) {
    case ToolEvent.Progress(String message) ->
        turn.on(new TurnEvent.ToolCallProgressed(call, message));
  }
}
```

No `default` arm — the sealed switch is the compile-time contract. Delete the old `narrateProgress` and its `String.valueOf` fallback; delete the `ToolProgress`/`EventEmitter` imports everywhere.

**Steps:** failing test first (`ToolContextTest`: `progress` reaches the listener as `ToolEvent.Progress`; a recording `ToolEventListener` in the test), implement, fix all construction sites (`grep -rn 'new ToolContext(' --include='*.java'` — executor, `McpToolboxTest`, `ToolContextTest`), `./mvnw -q clean verify`, commit `feat: the tool speaks a sealed vocabulary — ToolEvent replaces the untyped emitter`.

---

### Task 2: The action grammar in core

**Files:**
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/api/tool/ActionContributor.java`
- Modify: `ToolGrant.java` (5th component `contributor`, factories re-welded, `Judged.action()`, stage rename), `Tool.java` (DELETE `effect`), `UsagePolicy.java`/`Enricher.java`/`Allow.java`/`Deny.java`/`RequireApproval.java`/`PolicyDecision.java` (javadoc re-terming only), `AuthzContext.java` (+`ACTION_KEY`, `action()` accessor), `AuthorizationReport.java`/`GrantStory.java` (declaration-based story)
- Delete: `EffectfulTool.java`
- Test: `ToolGrantTest` (reshape), `AuthorizationReportTest` (reshape), new `ActionContributor` coverage inside `ToolGrantTest`

**Interfaces (Produces):**

```java
/**
 * Produces the action — the trusted statement of what one call will do — from the bound input
 * (action-wave spec §1). NOT an enricher: an enricher consumes the action and deposits
 * assessments; the contributor produces it. The grant welds it at construction, so the
 * application states the action even for third-party tools.
 */
@FunctionalInterface
public interface ActionContributor<I, A> {
  A actionOf(I input);

  default Optional<String> displayName() { return Optional.empty(); }

  static <I, A> ActionContributor<I, A> named(String displayName, ActionContributor<I, A> delegate) {
    /* mirror Enricher.named exactly: guards + delegation + displayName */
  }
}
```

`ToolGrant` becomes `record ToolGrant(Tool<?> tool, UsagePolicy<?> policy, List<Enricher<?>> enrichers, ActionContributor<?, ?> contributor, Judgment judgment)`. `Judged` renames its third component: `record Judged(PolicyDecision decision, AuthzContext context, Object action)`. Factories:

```java
/** Rung 0/1: default contributor — the approver always sees at least String.valueOf(input). */
public static ToolGrant grant(Tool<?> tool, UsagePolicy<Object> policy) { /* contributor = input -> String.valueOf(input) */ }

/** Rung 2: typed weld, no enrichers. */
public static <I, A> ToolGrant grant(Tool<I> tool, ActionContributor<? super I, A> contributor, UsagePolicy<? super A> policy) {
  return grant(tool, contributor, List.of(), policy);
}

/** Rung 2/3: I from the tool, A from the contributor — welded inside, all casts by class token. */
public static <I, A> ToolGrant grant(
    Tool<I> tool,
    ActionContributor<? super I, A> contributor,
    List<? extends Enricher<? super A>> enrichers,
    UsagePolicy<? super A> policy) {
  // judgment closure: I typed = tool.inputType().cast(input);
  //   A action = stage("action stage: ", () -> contributor.actionOf(typed));
  //   AuthzContext enriched = context.with(AuthzContext.ACTION_KEY, action);   // facts are keys
  //   for each enricher: enriched = stage("enricher stage " + label + ": ", () -> e.enrich(enriched, action));
  //   decision = stage("policy stage: ", () -> policy.evaluate(enriched, action));
  //   return new Judged(decision, enriched, action);
}
```

Stage prefix "effect stage: " renames to "action stage: " everywhere (including the stage-naming tests). `AuthzContext` gains `Key<Object> ACTION_KEY = new Key<>(Object.class, "action")` and `default Optional<Object> action() { return get(ACTION_KEY); }` — the untyped-rung deposit also happens (the default contributor's `String.valueOf` result goes under the key too, in the untyped judgment).

`GrantStory` reshapes: `record GrantStory(String toolName, boolean actionRendered, Optional<String> actionContributor, List<String> enrichers, String policy)` — `actionContributor` is the contributor's `displayName()`, empty for the default/anonymous. `render()`: `"name: action(<displayName|default>) → enricher → … → policy (identity)"`; rung-0 line unchanged (`"name: identity"`). `AuthorizationReport`: DELETE `effectTypeName` reflection entirely; the story is built from `grant.contributor().displayName()` — declaration, not reflection (spec §1). Fix `AuthorizationReportTest` accordingly (an `EffectfulTool` fixture becomes a plain tool + named contributor).

**Steps:** TDD: reshape `ToolGrantTest` first (typed weld through contributor; `ACTION_KEY` deposited and readable by an enricher via `context.action()`; ordering test intact; stage tests renamed "action stage: "; default-contributor test: rung-0 grant's judgment yields `String.valueOf(input)` as the action AND deposits it under the key), implement, then the report tests, whole-reactor verify (Task 3 owns the nessy-agent fallout — if the reactor cannot go green without touching nessy-agent files, make the MINIMAL mechanical rename edits there (`judged.action()`, imports) and leave everything semantic to Task 3), commit `feat: authorization speaks action — the grant states it, the tool never does`.

---

### Task 3: The agent-side action fallout

**Files:**
- Modify: `nessy-agent/.../spi/ApprovalRequest.java` (`effect` → `action`), `RegistryToolCallExecutor.java` (variable/javadoc re-terming; `run(...)` unchanged), `SlotApprover` javadoc if it says effect
- Modify tests: `RegistryToolCallExecutorTest` (the pinned rendered-effect assertions become rendered-action; the rung-3 enriched-context fixture moves from `EffectfulTool` to plain tool + `ActionContributor.named`), `AutonomousApprovalDemo` + `ApprovalPlayground` (their `RestartTool.effect(...)` overrides DELETE; the grant becomes `ToolGrant.grant(new RestartTool(), (RestartInput in) -> "restart " + in.target(), UsagePolicy.requireApproval())` — the typed 3-arg door), `WiringDemo`/`DurableParkDemo` if they touch effect

**Produces:** `record ApprovalRequest(CallAddress address, ToolCall call, Object action, AuthzContext context)`. The demo assertions keep the same *values* ("restart prod-eu") — only the speaker moved.

**Steps:** mechanical but semantic-bearing; TDD by updating the executor tests first; whole-reactor verify; commit `refactor: the action reaches the approver — the demos state it at the grant`.

---

### Task 4: The risk shape and the principal kit

**Files:**
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/api/tool/authorization/RiskLevel.java`, `RiskAssessment.java`, `RiskFactors.java`, `RiskPolicies.java`, `Enrichers.java`
- Modify: `AuthzContext.java` (+`RISK_KEY`, `risk()`)
- Test: `RiskAssessmentTest`, `RiskPoliciesTest`, `EnrichersTest` (new)

**Interfaces (Produces):**

```java
public enum RiskLevel { VERY_LOW, LOW, MODERATE, HIGH, VERY_HIGH }  // NIST SP 800-30; order = severity order

public record RiskAssessment(RiskLevel likelihood, RiskLevel impact, List<String> factors) {
  // guards non-null; factors List.copyOf
  /** NIST-style qualitative combination (the ruling's matrix, spec §2). */
  public RiskLevel severity() { /* table below */ }
}
```

Severity matrix (rows likelihood, columns impact) — NIST SP 800-30 Table I-2 shape, exactly this:

|  | VL | L | M | H | VH |
|---|---|---|---|---|---|
| **VL** | VL | VL | VL | L | L |
| **L** | VL | L | L | L | M |
| **M** | VL | L | M | M | H |
| **H** | VL | L | M | H | VH |
| **VH** | VL | L | M | VH | VH |

Implement as a switch or a 2D array indexed by ordinal — either is fine; the table is normative.

```java
public final class RiskFactors {  // String constants, MCP-aligned + nessy's own
  public static final String DESTRUCTIVE = "destructive";
  public static final String IRREVERSIBLE = "irreversible";
  public static final String EXTERNAL_WORLD = "external-world";
  public static final String READ_ONLY = "read-only";
  public static final String SPENDS_MONEY = "spends-money";
  public static final String TOUCHES_PII = "touches-pii";
}

public final class RiskPolicies {
  /** severity < approveAt → Allow; < denyAt → RequireApproval; ≥ denyAt → Deny.
      Absent assessment → Deny("no risk assessment deposited under RISK_KEY") — fail closed.
      Guard: approveAt must not exceed denyAt (IllegalArgumentException). */
  public static UsagePolicy<Object> threshold(RiskLevel approveAt, RiskLevel denyAt) { ... }
}

public final class Enrichers {
  /** Deposits the resolved principal under PRINCIPAL_KEY; named "principal". */
  public static Enricher<Object> principal(Supplier<?> resolver) { ... }
}
```

`AuthzContext`: `Key<RiskAssessment> RISK_KEY = new Key<>(RiskAssessment.class, "risk")`, `default Optional<RiskAssessment> risk() { return get(RISK_KEY); }`.

**Steps:** TDD — matrix boundary tests (at least: VL/VL→VL, M/M→M, H/H→H, VH/H→VH, H/VH→VH, VL/VH→L, VH/VL→VL), threshold tests (below/at both boundaries + absent-assessment fail-closed + inverted-bounds guard), principal enricher deposit + displayName. Verify, commit `feat: risk gets a standard shape — NIST severity, MCP factors, the threshold policy`.

---

### Task 5: `Tool.of` — the config factory

**Files:**
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/api/tool/ToolConfig.java`, `ToolCustomizer.java`
- Modify: `Tool.java` (add the static factory)
- Test: `ToolOfTest` (new)

**Interfaces (Produces)** — mirror the `TurnObserver.observe`/`TurnObserverConfig`/`TurnObserverCustomizer` precedent exactly (named customizer interface; config with fluent setters; NO public build anywhere — the finishing step is package-private):

```java
@FunctionalInterface
public interface ToolCustomizer<T> {
  void customize(ToolConfig<T> tool);
}

public final class ToolConfig<T> {
  public ToolConfig<T> name(String name);                       // default: kebab-case of the record's simple name
  public ToolConfig<T> description(String description);         // mandatory
  public ToolConfig<T> executes(Function<T, ?> handler);
  public ToolConfig<T> executes(BiFunction<T, ToolContext, ?> handler);
  public ToolConfig<T> defers(BiConsumer<T, ToolContext> starter); // → Awaited.deferred(); requiredCompletion=DURABLE
  public ToolConfig<T> requires(CompletionPolicy policy);       // explicit override
  // package-private finish: validates + returns the immutable Tool<T>
}

// on Tool:
static <T> Tool<T> of(Class<T> inputType, ToolCustomizer<T> customizer) { /* config, customize, finish */ }
```

Semantics (all normative):
- Kebab-case default name: `CreateAccount` → `create-account` (split on upper-case boundaries, lower-case, join with `-`; digits stay attached).
- Exactly one handler door must be set (`IllegalStateException` naming the tool otherwise: none set, or more than one).
- `description` blank/absent → `IllegalStateException("description must be provided — it is written for the model")`.
- Return rendering in the built tool's `execute`: `String s` → `ToolResult.ok(s)`; `ToolResult r` → `r`; `null` → `ToolResult.ok("done")`; anything else → `ToolResult.ok(<Jackson JSON>)` (one shared `ObjectMapper`). A thrown `RuntimeException` propagates (the executor's existing in-band catch handles it).
- `defers` sets `requiredCompletion() = DURABLE` unless `requires(...)` was called explicitly; `requires` always wins.
- The finished tool's `spec()` still derives from `Schemas.of(inputType)` via the default method — nothing overridden there.

**Steps:** TDD (`ToolOfTest`: kebab default + explicit name; description mandatory; one-handler enforcement both directions; String/ToolResult/object/null renderings; context door receives the real ToolContext and can `progress(...)`; `defers` → `Awaited.Deferred` + DURABLE + starter invoked with input and context; `requires` override). Confirm `NoPublicBuildersTest` still passes (it scans core). Verify, commit `feat: a tool in three lines — Tool.of under the dsl-coherence law`.

---

### Task 6: Intent reborn + the governed turn

**Files:**
- Create: `nessy-agent/src/main/java/org/jwcarman/nessy/agent/intent/Intent.java`, `IntentStore.java`, `InMemoryIntentStore.java`, `IntentTool.java`, `IntentEnricher.java`
- Test: `nessy-agent/src/test/.../intent/IntentToolTest.java`, and the flagship `nessy-agent/src/test/.../host/GovernedTurnDemo.java`

**Interfaces (Produces):**

```java
public record Intent(String declaration) { /* non-blank guard */ }

/** Pre-scoped, like Memory: no id parameter anywhere (agent-as-scope §3.5). */
public interface IntentStore {
  void record(Intent intent);
  Optional<Intent> latest();
}

public final class InMemoryIntentStore implements IntentStore { /* synchronized, last-write-wins */ }

/** The claim channel (authorization design §7): the model declares before it acts.
    IMMEDIATE; input record DeclareIntent(String intent); execute records + returns ok("intent recorded"). */
public final class IntentTool implements Tool<IntentTool.DeclareIntent> { ... }

/** Deposits the latest declaration under DECLARED_INTENT_KEY (as the Intent); named "intent". */
public final class IntentEnricher implements Enricher<Object> { /* ctor takes IntentStore */ }
```

Build `IntentTool` with `Tool.of` internally? No — it is a named public class (users reference it); implement the interface directly, description written for the model: "Declare what you are about to do and why, before using any other tool."

**The flagship — `GovernedTurnDemo`** (narrated, deterministic, through `Nessy.autonomous()` + `PumpedExecutor`): one scripted conversation where the model (scripted) first calls `declare-intent`, then calls `restart_prod`; the restart grant is the whole gate composed:

```java
ToolGrant.grant(
    new RestartTool(),
    ActionContributor.named("restart-statement", (RestartInput in) -> "restart " + in.target()),
    List.of(new IntentEnricher(intentStore), riskAssessor, Enrichers.principal(() -> "jcarman")),
    RiskPolicies.threshold(RiskLevel.MODERATE, RiskLevel.VERY_HIGH))
```

where `riskAssessor` is a test enricher depositing `new RiskAssessment(HIGH, HIGH, List.of(RiskFactors.DESTRUCTIVE))` (severity HIGH → RequireApproval). Assert the arc: intent tool ran and recorded; the approval request's context carries the intent claim (`declaredIntent()`), the principal ("jcarman"), the risk assessment, AND the action under `ACTION_KEY`; the turn parks; `approvals().approve(slot)` resumes; final transcript completes. Second test: risk assessor deposits `(VERY_HIGH, VERY_HIGH, …)` → severity VERY_HIGH → denied in-band with the threshold policy's reason, no approval request ever fired. Third test (cheap): no risk assessor wired → threshold fails closed → denial names the empty slot.

**Steps:** TDD per component, then the demo; whole-reactor verify; commit `feat: the governed turn — intent claimed, risk assessed, the threshold holds the door`.

---

## Model policy for this plan

| Task | Implementer | Task review |
|---|---|---|
| 1 | Sonnet | Sonnet |
| 2 | Sonnet | **Opus** (grammar reshape + welding + report) |
| 3 | Sonnet | Sonnet |
| 4 | Sonnet | Sonnet |
| 5 | Sonnet | Sonnet |
| 6 | Sonnet | Sonnet |
| Final whole-branch review | — | **Opus** |

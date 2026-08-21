# Typed Intent Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Execute the 2026-08-21 §3 amendment of the action-and-tool-vocabulary spec: sealed-interface tool inputs (`Schemas` oneOf + nessy-owned discriminator binding), the generic intent kit over both tiers (purpose string / sealed vocabulary), `IntentPolicies.requireDeclared` as policy, `UsagePolicy.allOf`, and the typed governed-turn flagship with a mismatch-denial arc.

**Architecture:** Task 1 is the ruled risk item — sealed schema + binding, general for any tool. Task 2 genericizes the kit (including the `record`→`declare` rename). Task 3 adds the two policy pieces. Task 4 is the flagship demo + docs touch.

**Tech Stack:** Java 25; victools for per-record schemas (already in nessy-api); Jackson for binding; hand-rolled fakes only.

**Spec:** `docs/superpowers/specs/2026-08-20-action-and-tool-vocabulary.md` §3 as amended 2026-08-21 (+§5, §6); `2026-08-16-authorization-design.md` §7 (the claim is untrusted).

## Global Constraints

- No `@SuppressWarnings` (checked casts by class token only — `getPermittedSubclasses()` gives `Class<?>[]`, bind via Jackson `convertValue(node, permittedClass)` which is checked-by-token); no star imports; no mocking libraries; camelCase prose test names; S5778; S5841.
- The discriminator property is `"type"`, its value the permitted record's **simple name, as written** (`Restart`, not `restart`). Missing/unknown type or malformed body → in-band failure through the existing executor catch (an `IllegalArgumentException`/`IllegalStateException` with a message naming the legal type names — the model must be able to correct from it).
- Enrichers never judge; `requireDeclared` is a policy; `allOf` is deny-biased (first Deny; else any RequireApproval; else Allow), never `UsagePolicy.Static`, empty list rejected.
- Nessy performs discriminator binding itself — vocabularies carry ZERO Jackson annotations.
- Before every commit: `./mvnw license:format -Plicense -q` then `./mvnw spotless:apply -q`; full `./mvnw -q clean verify` green per task (no API key; all `*Demo` classes pass).
- CONCURRENT-BRANCH BOUNDARY: a sonar-sweep branch is running in parallel. Do NOT touch `ToolGrant`/`ToolGrantTest` or any file outside this plan's roster; the intent stores' `record`→`declare` rename is THIS branch's job (the sweep skips them).

---

### Task 1: Sealed inputs — the schema and the binder

**Files:**
- Modify: `nessy-api/src/main/java/org/jwcarman/nessy/api/tool/Schemas.java` (sealed-interface support)
- Create: `nessy-api/src/main/java/org/jwcarman/nessy/api/tool/SealedInputs.java` (the discriminator binder)
- Modify: `nessy-agent/.../agent/tool/RegistryToolCallExecutor.java` (bind via `SealedInputs.bind` when the tool's inputType is a sealed interface, else `mapper.convertValue` as today)
- Test: `SchemasTest` (extend), `SealedInputsTest` (create), `RegistryToolCallExecutorTest` (one sealed-input tool end-to-end: good declaration binds; unknown type fails in-band with a message naming the legal types)

**Interfaces (Produces):**

```java
// Schemas: when type.isSealed() && type.isInterface() →
// { "oneOf": [ per permitted record: its object schema PLUS required property
//   "type": {"const": "<SimpleName>"} ], ... }
// (compose from Schemas.of(permittedRecord) + injected const; recurse is NOT required —
//  one level of sealing is the contract; a nested sealed member may be left to victools' default)

public final class SealedInputs {
  /** True when the class is a sealed interface nessy binds by discriminator. */
  public static boolean isSealedInput(Class<?> type);

  /**
   * Reads "type", matches a permitted record's simple name, binds the remaining properties into
   * that record via the supplied mapper. Missing/unknown "type" → IllegalArgumentException whose
   * message lists the legal type names. The returned value is checked by token against the
   * matched permitted class.
   */
  public static <T> T bind(Class<T> sealedType, JsonNode arguments, ObjectMapper mapper);
}
```

Executor: in the argument-binding step, `Object input = SealedInputs.isSealedInput(tool.inputType()) ? SealedInputs.bind(tool.inputType(), call.arguments(), MAPPER) : MAPPER.convertValue(call.arguments(), tool.inputType());` — the failure flows into the existing in-band catch.

Tests must pin: the oneOf schema contains one branch per permitted record, each with the required const `"type"`; a good payload binds to the right record with fields populated; unknown type's error message names ALL legal types; a non-sealed inputType is untouched by the new path. If victools' composition fights you, hand-compose the oneOf from per-record `Schemas.of` output — the shape above is the contract, the mechanism is implementer's judgment.

Commit: `feat: sealed inputs — the schema constrains, the binder enforces, nessy owns the discriminator`

### Task 2: The generic intent kit

**Files:**
- Modify: `nessy-spi/.../spi/intent/IntentStore.java` → `IntentStore<T>` with `declare(T)` (rename from `record`) + `Optional<T> latest()`
- Modify: `nessy-agent/.../agent/intent/InMemoryIntentStore.java` → `<T>`, `IntentTool.java` → `IntentTool<T>` (ctor `IntentTool(Class<T> vocabulary, IntentStore<T> store)`; name stays "declare-intent"; description defaults to the freeform prose for `Intent.class` and to "Declare what you are about to do, using one of the defined intent shapes, before using any other tool." otherwise; `inputType()` returns the vocabulary — sealed vocabularies thus ride Task 1's schema/binding automatically; keep a convenience `IntentTool.freeform(IntentStore<Intent>)`), `IntentEnricher.java` → `<T>` (deposits the typed latest under DECLARED_INTENT_KEY)
- Tests: all four intent test classes updated; freeform behavior identical (same tool name, same "intent recorded" result)

Note: `Intent` record (nessy-api) unchanged. `GovernedTurnDemo` updates only for the `declare(...)`/generic signatures — its assertions frozen.

Commit: `feat: one kit, two tiers — the intent kit goes generic and the store learns to declare`

### Task 3: `requireDeclared` and `allOf`

**Files:**
- Create: `nessy-api/src/main/java/org/jwcarman/nessy/api/tool/authorization/IntentPolicies.java`
- Modify: `nessy-api/src/main/java/org/jwcarman/nessy/api/tool/UsagePolicy.java` (add `allOf`)
- Test: `IntentPoliciesTest` (create), `UsagePolicyTest` (extend)

**Interfaces (Produces):**

```java
public final class IntentPolicies {
  /** Deny until a declaration of {@code vocabulary}'s type is on the context — the denial teaches:
      "declare your intent with the declare-intent tool before acting". Reads
      declaredIntent(vocabulary); absence or type-mismatch both deny. Never Static. */
  public static UsagePolicy<Object> requireDeclared(Class<?> vocabulary);
}

// on UsagePolicy:
/** Deny-biased conjunction: evaluate in order — first Deny wins; else any RequireApproval wins;
    else Allow. Rejects an empty or null-element list. The composite is never Static. */
static UsagePolicy<Object> allOf(List<UsagePolicy<Object>> policies);
static UsagePolicy<Object> allOf(UsagePolicy<Object>... policies);
```

Tests: requireDeclared denies on absence AND on wrong-type presence, allows on presence, message contains "declare-intent"; allOf ordering (first Deny's reason surfaces), RequireApproval precedence over Allow, all-Allow → Allow, empty → IllegalArgumentException, composite not instanceof Static; composition test `allOf(requireDeclared(...), RiskPolicies.threshold(...))` behaves per both.

Commit: `feat: the requirement is a policy — requireDeclared teaches, allOf composes`

### Task 4: The typed governed turn

**Files:**
- Create: `nessy-agent/src/test/java/org/jwcarman/nessy/agent/host/TypedIntentDemo.java`
- Docs: CHANGELOG entry (docs-writer NOT needed — implementer adds the entry matching format)

Narrated demo (autonomous host, PumpedExecutor, scripted provider), org vocabulary in-fixture:

```java
sealed interface OpsIntent permits Restart, Diagnose {}
record Restart(String target, String reason) implements OpsIntent {}
record Diagnose(String target) implements OpsIntent {}
```

Three arcs:
1. **The teaching loop:** model calls `restart_prod` FIRST (no declaration); grant policy `allOf(requireDeclared(OpsIntent.class), threshold(MODERATE, VERY_HIGH))` → in-band denial containing "declare-intent"; scripted model then declares `{"type":"Restart","target":"prod-eu","reason":"stuck deploy"}` via the typed IntentTool, retries, risk HIGH/HIGH → parks for approval; approve → completes. Assert: the denial text, the typed `Restart` on the ApprovalRequest context via `declaredIntent(OpsIntent.class)`, transcript completes.
2. **The mismatch tripwire:** an org consistency policy (in-fixture, rung-1 lambda pattern-matching `declaredIntent(OpsIntent.class)` against the action string) — declared `Restart("prod-eu", …)` but the model calls `restart_prod` with `target=prod-us` → denied naming both targets.
3. **The unrepresentable declaration:** scripted declare with `{"type":"Nuke",...}` → the intent tool's own result is the in-band binding error naming the legal types (Restart, Diagnose); nothing was stored (`store.latest()` empty).

Commit: `test: the typed governed turn — the vocabulary constrains, the mismatch trips, the teaching loop closes`

---

## Model policy

| Task | Implementer | Review |
|---|---|---|
| 1 | Sonnet | **Opus** (new binding path in the chokepoint's input step) |
| 2 | Sonnet | Sonnet |
| 3 | Sonnet | Sonnet |
| 4 | Sonnet | Sonnet |
| Final whole-branch | — | **Opus** |

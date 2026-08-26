# ToolContext.defer() Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A tool body obtains its computation id from `context.defer()` — created, folded and committed before the id is returned — so it can hand a callback address to an external system; the executor never creates a computation; the draft carries the typed input.

**Architecture:** `ToolContext` becomes an interface mirroring `ApprovalContext`; `ComputationToolContext` (agent) is the twin of `ComputationApprovalContext`; `RegistryToolCallExecutor` holds both `ContinuumClient`s and builds both contexts itself; `DefaultAgent.deliver` rethrows after narrating so `defer()` cannot return an id for an unrecorded wait. Retired: `DeferredToolCallPolicy`, `ComputationDeferredToolCallPolicy`, `ApprovalContexts`, `ToolExecution`, the executor's no-Continuum constructors.

**Tech Stack:** Java 25, Continuum 0.4.0, Jackson, JUnit 6, AssertJ, hand-written fakes (no mocking library).

**Spec:** `docs/superpowers/specs/2026-08-25-tool-context-defer-design.md` (binding). One amendment this plan makes to it: §7 retires `ToolExecution` entirely rather than reshaping it — the private `run` returns `Optional<ToolOutcome>` (empty = the door already recorded the wait); a public sealed type with one live arm is not worth keeping.

## Global Constraints

- No `@SuppressWarnings`; no star imports; every switch over a sealed type is exhaustive with no `default` arm.
- Exception-assertion lambdas hold exactly ONE throwing invocation (S5778); assert non-emptiness before any all/none-match (S5841).
- No mocking library — hand-written fakes, prose test style matching each module.
- No new public types. The only new public members are `ToolContext.defer()` (and the interface's accessors), `ApprovalRequest.Draft.input(Class<T>)`, and `ComputationToolContext` (agent module, wiring, twin of the existing `ComputationApprovalContext`).
- Build economics: warm scoped builds while iterating; `./mvnw -q clean verify` ONCE per task before its last commit; one Maven process at a time; `./mvnw license:format -Plicense && ./mvnw spotless:apply` before every commit.
- Continuum is always present: no nullable client, no "parking unavailable" path anywhere.

---

### Task 1: The door — api and agent in one cut

Two modules change together because `ToolContext` record → interface breaks the executor the moment it lands; there is no honest green state between them.

**Files:**
- Modify: `nessy-api/src/main/java/org/jwcarman/nessy/api/tool/ToolContext.java` (record → interface)
- Modify: `nessy-api/src/main/java/org/jwcarman/nessy/api/Awaited.java` (javadoc only)
- Modify: `nessy-api/src/main/java/org/jwcarman/nessy/api/tool/approval/ApprovalRequest.java` (`Draft` gains `input`)
- Modify: `nessy-api/src/main/java/org/jwcarman/nessy/api/tool/ToolGrant.java:123-139` (pass `input` to the draft)
- Create: `nessy-agent/src/main/java/org/jwcarman/nessy/agent/ComputationToolContext.java`
- Modify: `nessy-agent/src/main/java/org/jwcarman/nessy/agent/tool/RegistryToolCallExecutor.java`
- Modify: `nessy-agent/src/main/java/org/jwcarman/nessy/agent/DefaultAgent.java:126-138` (`deliver` rethrows)
- Modify: `nessy-agent/src/main/java/org/jwcarman/nessy/agent/host/HarnessConfig.java:484-499`
- Modify: `nessy-agent/src/main/java/org/jwcarman/nessy/agent/ComputationApprovalContext.java` (javadoc: it lets `deliver`'s throw propagate)
- Delete: `nessy-agent/.../spi/DeferredToolCallPolicy.java`, `nessy-agent/.../ComputationDeferredToolCallPolicy.java`, `nessy-agent/.../spi/ApprovalContexts.java`, `nessy-agent/.../spi/ToolExecution.java`, `nessy-agent/src/test/.../ComputationDeferredToolCallPolicyTest.java`
- Modify (javadoc references to the retired names): `nessy-agent/.../ToolInvocationId.java`, `nessy-agent/.../Agent.java` if it names the policy — grep `DeferredToolCallPolicy|ApprovalContexts|ToolExecution|executeGrantedToolNow` over `nessy-agent/src/main` and fix every hit
- Tests (see steps): `nessy-api/src/test/.../ToolContextTest.java`, `ToolOfTest.java`, `ApprovalRequestTest.java`, `ToolGrantTest.java`; `nessy-intent/src/test/.../IntentToolTest.java`; `nessy-tool-mcp/src/test/.../McpToolboxTest.java`; `nessy-agent/src/test/.../DefaultAgentTest.java` (or the nearest agent-level test), `tool/RegistryToolCallExecutorTest.java`, `tool/AuthorizationFailureWarningTest.java`, `DeferredToolOnContinuumTest.java`, `ApprovalOnContinuumTest.java`, `GrantRaceTest.java`, `AgentSubscriptionTest.java`, `host/PumpsAreNeverStarvedTest.java`, `support/TestToolClients.java`, and a new `ComputationToolContextTest.java`

**Interfaces:**
- Produces: `ToolContext { ToolCall call(); ComputationId invocation(); void progress(String); ComputationId defer(); }`; `ApprovalRequest.Draft.input(Class<T>)`; `ApprovalRequest.draft(agentType, agentId, call, input, pinned)`; `ComputationToolContext(ContinuumClient<ToolResult, Routing>, Routing, Optional<Duration>, ToolEventListener, Sink)` with package-visible `Optional<ComputationId> deferral()`; `RegistryToolCallExecutor(registry, type, id, turn, executor, approvalClient, toolClient, mapper)` — the ONLY constructor.

- [ ] **Step 1: `ToolContext` becomes an interface**

```java
package org.jwcarman.nessy.api.tool;

/**
 * What a tool learns about the invocation it is serving, plus what it can do with it — the mirror
 * of {@code ApprovalContext} (tool-context-defer spec §1.1). {@link #defer()} does the plumbing: it
 * creates the durable computation, records the wait in the scope, waits for that record to commit,
 * and only then hands back the id. By the time a tool can give the id to anyone, the phase already
 * names the wait.
 */
public interface ToolContext {

  /** The call being served. */
  ToolCall call();

  /**
   * This execution's opaque, stable idempotency key — deterministic from the call's coordinates,
   * identical across every redispatch and replay. NOT the computation id: a tool deduplicates an
   * external side effect under this, and hands out the id {@link #defer()} returns as the callback
   * address.
   */
  ComputationId invocation();

  /** Reports progress from inside a long-running tool ({@link ToolEvent.Progress}). */
  void progress(String message);

  /**
   * "The answer arrives later": creates this call's durable computation with the tool's declared
   * timeout as its deadline, folds {@code ToolDeferred}, commits, and returns the computation's id.
   * Idempotent: a second call returns the same id and creates nothing. Throws if the wait could not
   * be recorded — nothing was parked, and the tool should let the exception propagate.
   */
  ComputationId defer();
}
```

`Awaited`'s javadoc: delete the sentence beginning "Deferred carries no identity — the wiring derives…" through "…own their references." Replace with: "A tool returns {@code Deferred} only after {@code ToolContext#defer()} has recorded the wait and handed it the id; returning it without deferring, or returning {@code Ready} after deferring, is an in-band failure."

- [ ] **Step 2: The draft carries the typed input**

In `ApprovalRequest`:

```java
public static Draft draft(
    String agentType, String agentId, ToolCall call, Object input, ObjectMapper pinned) {
  return new Draft(agentType, agentId, call, input, pinned);
}
```

In `Draft`: a `private final Object input;` set in the constructor (`Objects.requireNonNull(input, "input must not be null")`), and

```java
/**
 * The bound tool input, as the record the tool author declared — the same information as {@code
 * call().arguments()}, typed. Transient: it is not part of the frozen document, because the call's
 * arguments already are. A mismatch throws {@link ClassCastException} naming both types.
 */
public <T> T input(Class<T> type) {
  Objects.requireNonNull(type, "type must not be null");
  if (!type.isInstance(input)) {
    throw new ClassCastException(
        "draft input is " + input.getClass().getName() + ", not " + type.getName());
  }
  return type.cast(input);
}
```

`ToolGrant.request` line 125 becomes `ApprovalRequest.draft(agentType, agentId, call, input, pinned)`.

Tests in `ApprovalRequestTest` (camelCase, module voice): `theDraftHandsBackTheTypedInput` (deposit from `draft.input(Restart.class).target()`, freeze, assert the fact and that the encoded JSON contains no `input` field); `theDraftRefusesTheWrongInputType` (S5778: the throwing call is `draft.input(String.class)` alone). Find the existing `ApprovalRequestTest` fixtures for a call and reuse them. Every existing `ApprovalRequest.draft(...)` call site in tests gains an `input` argument — use the call's bound object where one exists, else `Map.of()`.

- [ ] **Step 3: Test implementations of `ToolContext`**

`ToolOfTest`, `IntentToolTest`, `McpToolboxTest` and `ToolContextTest` construct the record today. Each gets a private static nested class in the same test file — no shared fixture, no new type:

```java
private record TestContext(ToolCall call, ToolEventListener events) implements ToolContext {
  @Override public ComputationId invocation() { return ComputationId.of("execution-id"); }
  @Override public void progress(String message) { events.on(new ToolEvent.Progress(message)); }
  @Override public ComputationId defer() { throw new UnsupportedOperationException("this test never defers"); }
}
```

`ToolContextTest` moves to `ComputationToolContextTest` in nessy-agent (Step 6) — delete it from nessy-api; the interface has no behaviour to test there.

- [ ] **Step 4: `ComputationToolContext`**

```java
package org.jwcarman.nessy.agent;

/**
 * The Continuum-backed door behind {@link ToolContext#defer()} (tool-context-defer spec §1.1, §2):
 * creates the tool computation with this call's routing and the tool's declared timeout, folds
 * {@link AgentEvent.ToolDeferred} through the sink — synchronously, so the phase names the wait
 * before this returns — and hands back the id. Idempotent. The twin of {@link
 * ComputationApprovalContext}. Public because {@code RegistryToolCallExecutor} builds one per call
 * from a different package; wiring, never application vocabulary.
 */
public final class ComputationToolContext implements ToolContext {

  private final ContinuumClient<ToolResult, Routing> client;
  private final Routing routing;
  private final Optional<Duration> timeout;
  private final ToolEventListener events;
  private final Sink sink;
  private ComputationId deferred;

  public ComputationToolContext(
      ContinuumClient<ToolResult, Routing> client,
      Routing routing,
      Optional<Duration> timeout,
      ToolEventListener events,
      Sink sink) { /* requireNonNull each */ }

  @Override public ToolCall call() { return routing.call(); }

  @Override public ComputationId invocation() {
    return ComputationId.of(
        new CallAddress(routing.agentType(), routing.agentId(), routing.responseId(), routing.call().id())
            .digest());
  }

  @Override public void progress(String message) { events.on(new ToolEvent.Progress(message)); }

  @Override
  public synchronized ComputationId defer() {
    if (deferred != null) {
      return deferred;
    }
    Computation created =
        timeout.map(t -> client.create(routing, t)).orElseGet(() -> client.create(routing));
    ComputationId id = ComputationId.of(created.id().value().toString());
    // Folds now, on this thread; deliver rethrows if the fold does not commit (spec §3), and then
    // nobody ever holds this id — the orphan computation expires into a dropped mismatch.
    sink.deliver(new AgentEvent.ToolDeferred(routing.call(), id));
    deferred = id;
    return id;
  }

  /** Package-visible: how the executor learns the tool deferred (spec §8.1). */
  Optional<ComputationId> deferral() { return Optional.ofNullable(deferred); }
}
```

Check `Routing`'s accessor names against `nessy-agent/.../Routing.java` and `CallAddress`'s constructor order against `CallAddress.java`; adapt. If `deferral()` cannot be package-visible from `agent.tool` (it cannot — different package), make it `public` with the same javadoc; that is the spec's stated fallback and needs no further sign-off.

- [ ] **Step 5: The executor**

Delete both extra constructors, `PARKING_UNAVAILABLE`, `APPROVAL_UNAVAILABLE`, `defaultPolicy`, `defaultApprovalContexts`, the `deferredToolCallPolicy` and `approvalContexts` fields. Single constructor:

```java
public RegistryToolCallExecutor(
    ToolRegistry registry, AgentType type, AgentId id, TurnObserver turn, Executor executor,
    ContinuumClient<Approval, ApprovalRouting> approvalClient,
    ContinuumClient<ToolResult, Routing> toolClient,
    ObjectMapper mapper)
```

`seek(...)` builds `new ComputationApprovalContext(approvalClient, routing(call, responseId), request, sink)` where it called `approvalContexts.contextFor(...)`. Add `private Routing routing(ToolCall call, ModelResponseId responseId)` returning `new Routing(type.name(), id.value(), responseId.value(), call)`.

`runTool` and `run`:

```java
@Override
public void runTool(ToolCall call, ModelResponseId responseId, Sink sink) {
  Objects.requireNonNull(responseId, "responseId must not be null");
  executor.execute(
      () -> runPastGate(call, responseId, sink)
          .ifPresent(outcome -> sink.deliver(new AgentEvent.ToolFinished(call, Optional.empty(), outcome))));
}

/** Empty means the door already recorded the wait; there is nothing to deliver. */
private Optional<ToolOutcome> runPastGate(ToolCall call, ModelResponseId responseId, Sink sink) { ... same lookup/convert/try as today, returning Optional.of(failed(...)) on the failure arms ... }

private <T> Optional<ToolOutcome> run(Tool<T> tool, Object input, ToolCall call, ModelResponseId responseId, Sink sink) {
  T typed = tool.inputType().cast(input);
  ComputationToolContext context =
      new ComputationToolContext(toolClient, routing(call, responseId), tool.timeout(), event -> narrate(call, event), sink);
  Awaited<ToolResult> outcome = tool.execute(typed, context);
  boolean deferred = context.deferral().isPresent();
  return switch (outcome) {
    case Awaited.Ready<ToolResult>(ToolResult value) when !deferred -> {
      turn.on(new TurnEvent.ToolCallCompleted(call, value));
      yield Optional.of(new ToolOutcome.Returned(value));
    }
    case Awaited.Ready<ToolResult> _ -> Optional.of(failed(call, ANSWERED_AFTER_DEFERRING));
    case Awaited.Deferred<ToolResult> _ when deferred -> Optional.empty();
    case Awaited.Deferred<ToolResult> _ -> Optional.of(failed(call, DEFERRED_WITHOUT_DEFER));
  };
}

static final String ANSWERED_AFTER_DEFERRING = "tool answered after deferring";
static final String DEFERRED_WITHOUT_DEFER = "deferring tool never called context.defer()";
```

A `RuntimeException` out of `tool.execute` — including one propagated from `defer()` — is caught by the existing `try` in `runPastGate` and answered in-band with `detailOf(e)`; keep the WARN-with-throwable logging added on the approval-lifecycle branch. Every `case` over `Awaited` above is exhaustive; guards are not `default` arms.

- [ ] **Step 6: `deliver` rethrows**

`DefaultAgent.deliver`:

```java
} catch (RuntimeException e) {
  observer.applyFailed(event, e); // narrate — then let the caller see it (tool-context-defer spec §3)
  throw e;
}
```

Update its javadoc. `ComputationApprovalContext.defer()`'s comment gains one line: "deliver rethrows if the fold does not commit, so this returns an id only for a recorded park."

- [ ] **Step 7: Wiring**

`HarnessConfig` lines 484-499 become:

```java
(scopeId, scopeTurnObserver) ->
    new RegistryToolCallExecutor(
        registry, agentType, scopeId, scopeTurnObserver, exec,
        effectiveApprovalClient, effectiveToolClient, pinned),
```

Remove the `ComputationDeferredToolCallPolicy` import and the javadoc at ~:95 that names `onDeferred`.

- [ ] **Step 8: Tests**

Migration rules, applied mechanically:
1. Every `new RegistryToolCallExecutor(...)` call in tests uses the single constructor. Where a test used the 5- or 6-arg form, build an in-memory Continuum the way `support/TestToolClients` / `TestApprovalClients` already do — read them and reuse; if a helper for "both clients over one in-memory Continuum" does not exist, add it to `support/` (test fixture, not public).
2. Tests that asserted `PARKING_UNAVAILABLE` / `APPROVAL_UNAVAILABLE` are deleted; the behaviour no longer exists.
3. Tools in tests that return `Awaited.deferred()` (`DeferredToolOnContinuumTest`, `PumpsAreNeverStarvedTest`, `RegistryToolCallExecutorTest`) now call `context.defer()` first and keep the returned id where the test needs it (a field on the fake tool). `DeferredToolOnContinuumTest.parkedToolIdFor` reads the id from the tool, not the phase, in at least one test — leave the phase-reading helper for the tests that prove the phase names it.
4. `ToolContextTest` → `ComputationToolContextTest` (nessy-agent, snake_case to match `DeliveryWorkerMismatchedDeliveryTest`, or camelCase to match `ComputationApprovalContextTest` — match the approval twin's file): progress reaches the listener; `defer()` creates one computation, folds `ToolDeferred` before returning (a recording Sink asserts the event arrived before `defer()` returned — capture a timestamp or a sequence counter), returns the same id on a second call with one creation; `invocation()` equals `CallAddress.digest()` for the same coordinates; `defer()` propagates when the sink throws and `deferral()` stays empty.

New proofs (spec §9), in `nessy-agent/src/test/.../host/` beside the existing ones:
- `ToolHandsOutItsIdBeforeReturningTest`: a tool calls `defer()`, completes the computation through `harness.completions()` on the same thread before returning `deferred()`; the turn reaches the result; the WARN log is empty (use the logging-capture pattern from `DeliveryWorkerMismatchedDeliveryTest`).
- In `RegistryToolCallExecutorTest`: `deferred()` without `defer()` → `ToolFinished` with `DEFERRED_WITHOUT_DEFER`; `ready()` after `defer()` → `ToolFinished` with `ANSWERED_AFTER_DEFERRING` and the phase's `ToolDeferred` already folded (the sink saw it).
- In `DefaultAgentTest` (or nearest): a `Memory` whose `remember` throws — `deliver` narrates `applyFailed` exactly once and rethrows. And the approval side: `ComputationApprovalContextTest` gains "defer() propagates when the sink throws and returns nothing" — must fail against the current swallow-and-return.

- [ ] **Step 9: Retired-name sweep, verify, commit**

`grep -rn "DeferredToolCallPolicy\|ComputationDeferredToolCallPolicy\|ApprovalContexts\|ToolExecution\|PARKING_UNAVAILABLE\|APPROVAL_UNAVAILABLE\|onDeferred" nessy-*/src` → zero hits. Then:

```bash
./mvnw -q clean verify
./mvnw license:format -Plicense && ./mvnw spotless:apply
git add -A nessy-api nessy-agent nessy-intent nessy-tool-mcp
git commit -m "feat: the context creates the computation — ToolContext.defer()"
```

---

### Task 2: Documentation

**Files:**
- Modify: `docs/concepts/tools.md` (Deferring: the door; the policy is gone), `docs/concepts/durable-computation.md` (tool-side ordering paragraph → "by construction"; `deliver` rethrows), `docs/guides/harness.md` (wiring: two clients, no policy seam), `docs/concepts/the-four-tiers.md` if it lists the executor's constructor, `CHANGELOG.md` (Unreleased: breaking — `ToolContext` is an interface; `Awaited.deferred()` requires `defer()`; `Draft.input`; retirements)
- Modify: `docs/superpowers/specs/2026-08-25-approval-lifecycle-design.md` §4 and §15 — two-paragraph amendment pointing at the new spec; §13 gains a line closing §13.10 (the draft carries the input)
- Modify: `docs/superpowers/specs/2026-08-25-tool-context-defer-design.md` §7 — `ToolExecution` retires entirely (this plan's amendment)

- [ ] **Step 1:** Rewrite the five pages against the code — grep before asserting any name exists; quote `ToolContext` and the `defer()` example from a real test.
- [ ] **Step 2:** Retired-name grep over `README.md docs/ nessy-*/README.md CHANGELOG.md` for the Task 1 list; only the CHANGELOG retirement list and the amendments' "what it replaced" lines may survive.
- [ ] **Step 3:** `python3 -m mkdocs build --strict`; `./mvnw -q clean verify`; license + spotless; commit `docs: a tool hands out its own id`.

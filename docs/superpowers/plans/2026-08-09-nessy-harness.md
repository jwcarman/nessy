# The Harness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reify the harness: `Nessy.harness()` (infrastructure) vs `harness.agent()` (identity), per-grant authority (`ToolGrant` + `UsagePolicy`), the `Memory` recall seam, and the context assembler with `Agent.contextFor`.

**Architecture:** Spec of record: `docs/superpowers/specs/2026-08-09-nessy-agent-harness-design-v2.md` §1.1 (services), §8.4 (Harness; typed generics are OUT OF SCOPE — own design round), §10.5 (per-grant authority), §10.9 (Memory), §10.10 (assembler). All in `nessy-core` + `nessy-testing` + `nessy-examples` touch-ups.

**Tech Stack:** unchanged (Java 25, JUnit 5 + AssertJ, Jackson, Micrometer).

## Global Constraints

Identical to the previous plan (keyless green both profiles with `-Dgpg.skip=true`, FOREGROUND builds, license/spotless before commit, no suppressions/star imports/auxiliary ctors, exhaustive core switches no default, zone rule with the now-enforcing `ZoneBoundariesTest`, mocapi prose tests, surefire XML truth). Plus:
- `Nessy.agent()` must keep working byte-for-byte (sugar over an implicit default harness).
- The grant principle is law: infrastructure ambient (harness), capability granted (per agent), authority declared (per grant). No library-contributed authority.

---

### Task 1: `Harness` — infrastructure reified

**Files:**
- Create: `org/jwcarman/nessy/Harness.java`, `org/jwcarman/nessy/HarnessBuilder.java`
- Modify: `org/jwcarman/nessy/Nessy.java` (+`harness()`), `org/jwcarman/nessy/AgentBuilder.java` (constructed FROM a harness; `Nessy.agent()` routes through an implicit default harness)
- Test: `org/jwcarman/nessy/HarnessTest.java` (new); `AgentFacadeTest` additions

**Interfaces — Produces:**

```java
Harness harness = Nessy.harness()          // infrastructure, once per application
    .provider(provider)                    // the DEFAULT model line (optional here, required by build() of an agent that doesn't override)
    .store(SessionStore)                   // default inMemory()
    .transcript(TranscriptStore)           // default none()
    .hub(EventHub)                         // default synchronous()
    .observations(ObservationRegistry)     // default NOOP
    .mapper(ObjectMapper)                  // default new ObjectMapper()
    .build();

AgentBuilder b = harness.agent();          // identity: model, systemPrompt, provider override,
                                           // contextWindow, tools/grants, approver, termination,
                                           // compaction, summarizer, contextBuilder, (memory in T3)
```

- `Harness` is a final class holding the six infrastructure pieces; `harness.agent()` returns an `AgentBuilder` pre-wired with them. Agent-level `.provider(...)` overrides the harness default; an agent with neither fails at `build()` with the existing "provider must not be null"-style error.
- `Nessy.agent()` becomes sugar: an implicit `Nessy.harness().build()` (all defaults) behind a fresh builder — behavior identical to today, verified by the untouched existing facade tests.
- `AgentBuilder`'s infrastructure setters (`store/transcript/hub/observations/mapper`) REMAIN (they override the harness for that one agent — escape hatch, javadoc'd as unusual); identity setters unchanged.
- Engine construction stays per-agent in `build()` exactly as today, reading harness infra + agent identity.

- [ ] **Step 1: failing tests.** `HarnessTest`: `two_agents_share_the_harness_substrate` (one harness, two agents; a hub subscriber sees both agents' `SessionEvent`s; both sessions land in the same `SessionStore`); `an_agent_may_override_the_harness_provider` (harness provider A, agent binds provider B via `.provider(...)`; captured requests prove B served it); `an_agent_without_any_provider_fails_at_build`; `the_implicit_default_harness_keeps_the_one_liner_working` (`Nessy.agent()` path builds and converses over a scripted provider, as today).
- [ ] **Step 2: red. Step 3: implement. Step 4: both profiles green (existing facade tests untouched). Step 5: commit** `feat: the Harness — infrastructure is ambient, reified`.

---

### Task 2: Per-grant authority — `ToolGrant`, `UsagePolicy`, `PolicyDecision` (HIGH-RISK: the authority chokepoint — Opus review)

**Files:**
- Create: `api/tool/PolicyDecision.java` (sealed), `api/tool/UsagePolicy.java`, `api/tool/ToolGrant.java`
- Modify: `AgentBuilder.java` (`tools(ToolGrant...)` overload beside `tools(Tool...)`), `spi/InProcessEngine.java` (`decide(...)` consults the call's grant), plumbing from builder to engine (a grant map alongside/inside the existing `ToolRegistry` — keep `ToolRegistry.specs()` as-is)
- Test: `api/tool/UsagePolicyTest.java`, `InProcessEngineTest` authority additions, `EndToEndTest` grant scenario

**Interfaces — Produces (spec §10.5):**

```java
public sealed interface PolicyDecision {
    record Allow() implements PolicyDecision {}
    record Deny(String reason) implements PolicyDecision {}
    record RequireApproval() implements PolicyDecision {}
}

public interface UsagePolicy {
    PolicyDecision evaluate(ToolCall call, SessionState state);   // pure

    static UsagePolicy allow() { … }
    static UsagePolicy deny(String reason) { … }
    static UsagePolicy requireApproval() { … }
}

public record ToolGrant(Tool<?> tool, UsagePolicy policy) {
    public static ToolGrant grant(Tool<?> tool) { … }   // derived default: requiresApproval() ? requireApproval() : allow()
    public ToolGrant with(UsagePolicy policy) { … }
}
```

**Semantics (the chokepoint — unchanged shape, new consultation):** the engine's `decide(state, call)`:
1. Grant lookup by tool name — not granted → existing missing-tool behavior (allow through; execution produces the model-visible error, one failure surfaced once — UNCHANGED).
2. `grant.policy().evaluate(call, state)`:
   - `Allow` → `ApprovalDecided(call, Decision.allow())` — approver NOT consulted.
   - `Deny(reason)` → `ApprovalDecided(call, Decision.deny(reason))` — flows through the existing denial path (reducer answers the call with a denial-shaped error result; verify against the actual `Decision` grammar before coding).
   - `RequireApproval` → the existing `Approver` flow, observation and all, verbatim.
3. `Tool.requiresApproval()` is consulted ONLY via `ToolGrant.grant(...)`'s derived default — the engine never reads it directly anymore (grep to be sure).
4. Explicit grant policy may loosen or tighten the floor — that's the ruled design; it happens at grant construction, nowhere else.
- `tools(Tool...)` auto-wraps each with `ToolGrant.grant(tool)` — existing callers compile and behave identically (derived default == old behavior).
- `UsagePolicy.evaluate` must be pure (javadoc'd); the engine treats a thrown RuntimeException from a policy as a deny with the exception description (fail-closed — a broken policy must not become an allow).

- [ ] **Step 1: failing tests.** `UsagePolicyTest`: factory behaviors; a contextual lambda (`approveOver`-style on an argument) returning different decisions for different calls. Engine: `an_allow_grant_skips_the_approver` (approver would throw if consulted; tool runs); `a_deny_grant_answers_the_model_without_the_approver` (result error carries the reason; approver untouched); `a_require_approval_grant_asks_the_approver` (existing path); `a_throwing_policy_fails_closed` (deny with description); `the_derived_default_matches_requires_approval` (both directions). E2E: `the_grant_line_is_the_security_statement` — same tool granted `allow()` to one agent and `requireApproval()` to another on one harness; first runs free, second hits the approver.
- [ ] **Step 2: red. Step 3: implement. Step 4: both profiles green. Step 5: commit** `feat(api): per-grant authority — capability and authority declared together`.

---

### Task 3: The `Memory` recall seam

**Files:**
- Create: `spi/memory/Memory.java`, `api/event/RecallFailed.java` (hub record, sibling style to `CompactionFailed`)
- Modify: `spi/InProcessEngine.java` (recall at request assembly, best-effort, `nessy.memory.recall` observation), `internal/EngineObservations.java` (+`recall(registry)`), `AgentBuilder.java` (`.memory(Memory)`, default `none()`), `internal/ZoneBoundariesTest` (spi.memory)
- Test: `spi/memory/MemoryTest.java`, engine additions, `nessy-testing` scripted double if the idiom calls for one (a lambda suffices — Memory is a SAM; no new double class)

**Interfaces — Produces (spec §10.9):**

```java
public interface Memory {
    List<Message> recall(Context context);   // engine-performed; I/O sanctioned; best-effort

    static Memory none() { … }               // the default — a singleton the engine may identity-skip
}
```

- Engine `requestFor(state)`: `Context projected = contextBuilder.project(state)`; if memory is not the `none()` singleton — under a `nessy.memory.recall` observation (error-marked per F2): `recalled = memory.recall(projected)`; ANY RuntimeException → observation error + `hub.emit(new RecallFailed(id, describe(e)))` + proceed with no memories (best-effort — the turn NEVER dies for memory). Request context = `Context.of(concat(recalled, projected.messages()))` — recalled messages prepend; a memory returning pair-breaking messages is caught by that `Context.of` inside the same try (→ same best-effort path).
- The compact/summarize path is NOT memory-enriched (the strategy's working set is its own business — javadoc where relevant).
- `Memory.none()` returns an empty list; the engine identity-skips it so the default path has zero new allocations/observations.

- [ ] **Step 1: failing tests.** Engine: `recall_enriches_the_request_but_never_the_ledger` (lambda memory returns one fact message; captured request context = [fact, …projected]; `reply.state().messages()` contains NO fact message); `a_failing_memory_costs_enrichment_not_the_turn` (throwing memory → RecallFailed on hub, reply completes, request had no memories); `a_pair_breaking_memory_is_a_recall_failure` (memory returns an orphan tool_result message → same best-effort path); `none_adds_nothing_and_no_observation` (TestObservationRegistry: no `nessy.memory.recall` observation when defaulted); `memory_produces_its_own_observation` (+`.hasError()` variant).
- [ ] **Step 2: red. Step 3: implement. Step 4: both profiles green. Step 5: commit** `feat(spi): the Memory recall seam — enrichment, never the turn`.

---

### Task 4: The context assembler, `Agent.contextFor`, end-to-end + docs

**Files:**
- Create: `internal/ContextAssembler.java` (the named machinery: project + recall + validate, used by BOTH the engine's requestFor and the facade)
- Modify: `spi/InProcessEngine.java` (requestFor delegates to the assembler), `org/jwcarman/nessy/Agent.java` (+`Context contextFor(SessionId)`), `README.md`, `CHANGELOG.md`, spec §14
- Test: `AgentFacadeTest` (`contextFor` scenarios), `EndToEndTest` if a facade proof is missing

**Semantics:**
- `ContextAssembler` (internal — machinery, not a seam): holds `(ContextBuilder, Memory, EventHub-for-failures, observations)`; one method `Context assemble(SessionState state)` implementing exactly Task 3's choreography. The engine's `requestFor` and `Agent.contextFor` call the SAME instance — one implementation of "what would the model see."
- `Agent.contextFor(SessionId id)`: loads the snapshot from the store (`Optional.orElseThrow` with a clear unknown-session message), assembles, returns. Javadoc: the debugging affordance — truthful without a model call. NOTE: it performs recall (I/O) — javadoc says so (it shows what a call made NOW would see, including memory's current answer).
- Docs: README gains "The harness" section near the top of the API docs — the two-builder story with the grant-line example (mirrored in `AgentFacadeTest`), the eight services in one compact list, `contextFor` mention; Context Management gains the memory paragraph (cache tradeoff per §10.9); CHANGELOG entries (Harness, grants, Memory, contextFor). Spec §14: per-grant authority gate row → ✅ cleared; sequencing → Plan 6 delivered except typed front door (still open, awaiting its design round); §8.4 note that Harness shipped un-generic (the type parameter arrives with the typed-input round — still pre-1.0).

- [ ] **Step 1: failing tests.** `contextFor_shows_exactly_what_a_call_would_see` (agent with elision + a lambda memory; drive a tool conversation; `agent.contextFor(sessionId)` equals the captured request context of a subsequent send — same projection, same memories); `contextFor_rejects_an_unknown_session`. **Step 2: red. Step 3: implement. Step 4: docs + mirrors. Step 5: both profiles green. Step 6: commit** `feat: the context assembler and Agent.contextFor — plus the harness docs`.

---

## Self-Review

**Spec coverage:** §8.4 harness reification (minus typed generics, explicitly out) → T1; §10.5 entire → T2 (chokepoint semantics, derived floor, loosen/tighten ruling, fail-closed policies); §10.9 → T3 (best-effort, hub event, observation, none() default, no-compact-enrichment); §10.10 → T4 (one assembler, two consumers, contextFor). Grant-principle Spring notes in §13.1 need no code here (starter is a later plan).

**Type consistency:** `ToolGrant.grant(tool)`/`with(policy)` (T2) is what T4's README mirror shows; `Memory.recall(Context)` (T3) consumes T1-era `Context` unchanged; `ContextAssembler.assemble` (T4) returns the same `Context` the engine already sends; `RecallFailed(SessionId, String)` mirrors `CompactionFailed` exactly.

**Placeholder scan:** none — signatures, choreography, and named scenarios throughout.

**Risk notes:** T2 is the authority chokepoint (Opus review; fail-closed rule pinned by test). T1 must not disturb the existing facade contract (existing tests are the pin). T3's engine touch reuses the compact arm's best-effort idiom — same F2 conventions.

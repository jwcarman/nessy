# Subagents v2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps
> use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Subagents are defined inside their parent's builder (`SubagentConfig`, no
`build()`), all coordination wiring internalizes, child doors surface through a parent
handle — and the loop learns repeatable parking so gated delegation with a parking
child works instead of wedging.

**Architecture:** Two independent fronts that meet in the tests. Front one (the loop):
the park lifecycle generalizes — a call may park repeatedly, one outstanding park at a
time. Front two (construction): `AgentBuilder.subagent(Consumer<SubagentConfig>)`
builds the child inside the parent, the harness keeps an internal name registry,
`AgentTools`/`CallbackRouter` leave the public API (semantics carried verbatim into
internal machinery), and `Agent.subagent(name)` returns a narrow `Subagent` doors
handle. v1 runtime semantics (child ids, park chain, `ConversationSettled`, links
storage, snapshot idempotency, sync completions) are preserved exactly.

**Tech Stack:** Java 21, JUnit 5 + AssertJ, ScriptedModelProvider.

**Spec:** `docs/superpowers/specs/2026-08-16-subagents-v2-design.md` (v1 runtime
authority: `2026-08-15-subagents-design.md`).

## Global Constraints

- `./mvnw -q clean verify` green with no API key and no network, always; builds run in
  the FOREGROUND.
- Before every commit: `./mvnw license:format -Plicense && ./mvnw spotless:apply`.
- No mocking libraries; prose test names; no star imports; no suppressions;
  S5778/S5976/S107.
- v1 runtime invariants preserved and re-proven by the migrated suite: deterministic
  child id `<parent-conversation-id>/<tool-call-id>`; snapshot short-circuit
  idempotency; complete→ok(finalText) / fail→error(reason) / park→link+parent-park;
  sync completions; throw on not-yet-registered park; silent no-op on absent link;
  forget after successful resume; activity progress pings.
- After Task 2, `grep -r "AgentTools\|CallbackRouter" --include="*.java"` outside
  `internal` and their own tests must be empty (docs and specs are historical records
  and exempt).
- Known shapes (verbatim from main): `AgentBuilder` methods `name/model/systemPrompt/
  maxTokens/capabilities/tools/approver/termination/memory/contextWindow/renderer`;
  `Parks.Park(ConversationId, ParkToken, ToolCall, String agentName)`; the loop's
  re-park refusal near `ConversationLoop` line ~558; `ConversationState` park
  transition (`parked(...)` fold) and `Resolved`-entry draining around
  `ConversationLoop` ~246-249.

---

### Task 1: Repeatable parking — the loop learns two waits

**Files:**
- Modify: `nessy-core/src/main/java/org/jwcarman/nessy/api/conversation/ConversationState.java`
  (park transition only as needed — smallest change that admits the contract)
- Modify: `nessy-core/src/main/java/org/jwcarman/nessy/internal/ConversationLoop.java`
  (the refusal site and resolution-drain bookkeeping)
- Tests: `ConversationLoopTest` + the scripted end-to-end suite (wherever the executor
  fold and settlement tests live)

**Interfaces:** no public API change — behavioral contract only (spec §4).

**Steps:**
- [ ] Read the current park/resume/drain code paths COMPLETELY before changing anything:
  the refusal at ~558, the `Resolved` drain at ~246-249, `ConversationState.parked`,
  `withParkedCalls`, and how `toolFinished` treats parked siblings. Write the pinned
  transition table into your report (state before → event → state after) for: first
  park (approval), resume-with-decision, execution park (same call, new token), replay
  re-park (same call, SAME token), and the violation (new token while one outstanding).
- [ ] Failing tests first, offline, scripted: (a) the full two-wait timeline — gated
  tool parks for approval → approve → tool executes and parks with a new token → the
  call is parked again (assert Parks holds both stamps, the new one outstanding) →
  resume the second token → turn completes; (b) replay idempotency — re-driving the
  execution that parks with the SAME outstanding token folds as a no-op (no third
  stamp, no state change); (c) a park with a NEW token while one is outstanding for
  that call fails loud (pin the exception and message); (d) the pre-existing
  single-park suites all still pass unchanged.
- [ ] Implement the smallest reducer/loop change that admits the contract: a resolved
  park is history, not a lock; the drained resolution is consumed by the execution
  regardless of outcome; one outstanding park per call enforced.
- [ ] Full verify; license + spotless; commit.

### Task 2: The construction surface — SubagentConfig, internalization, the handle

**Files:**
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/SubagentConfig.java` (public,
  final; fluent setters returning this; NO build method)
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/Subagent.java` (the doors
  handle)
- Modify: `nessy-core/src/main/java/org/jwcarman/nessy/AgentBuilder.java`
  (`.subagent(Consumer<SubagentConfig>)`), `Agent.java` (`subagent(String)`),
  `Harness.java` (internal name registry; duplicate rejection)
- Move: `spi/subagent/AgentTools.java` and `spi/subagent/CallbackRouter.java` →
  internal machinery (package `org.jwcarman.nessy.internal.subagent` or package-private
  beside the loop — follow the codebase's internal conventions); `SubagentLinks` +
  `InMemorySubagentLinks` + `package-info` STAY in `spi.subagent` unchanged.
- Tests: migrate `AgentToolsTest`'s behavioral suite to the v2 surface;
  `CallbackRouterTest`'s routing proofs migrate to the internal registry's tests; new
  `SubagentConfigTest`/handle tests.

**Interfaces (Produces):**
```java
public final class SubagentConfig {
  public SubagentConfig name(String name);                 // required
  public SubagentConfig description(String description);   // required — the delegation tool's description
  public SubagentConfig model(String model);
  public SubagentConfig systemPrompt(String systemPrompt);
  public SubagentConfig maxTokens(int maxTokens);
  public SubagentConfig tools(ToolGrant... grants);
  public SubagentConfig memory(Memory memory);
  public SubagentConfig termination(TerminationPolicy termination);
  public SubagentConfig policy(UsagePolicy policy);        // the DELEGATION tool's policy; default allow
}
public AgentBuilder<I> subagent(Consumer<SubagentConfig> config);  // on AgentBuilder
public Subagent subagent(String name);                              // on Agent; unknown -> IllegalArgumentException naming parent + child
public final class Subagent {
  public String name();
  public RunOutcome approve(ParkToken token);
  public RunOutcome deny(ParkToken token, String reason);
  public RunOutcome resume(ParkToken token, ToolResolution resolution);
  public ConversationSnapshot snapshot(ConversationId id);
  public Subagent subagent(String name);                   // tree traversal
}
```

**Steps:**
- [ ] Build-time semantics: `.subagent(cfg)` constructs the child agent from the
  harness (inheriting provider/stores/approver/observations/listeners), grants the
  delegation tool on the parent (tool name = child name, described by `description`,
  policy from `policy(...)`), wires the links store from the harness store family,
  registers parent AND child (and nested children, recursively) in the harness's
  internal registry (duplicate names across the whole harness → IllegalArgumentException
  at build), and arranges the completions wiring internally (same sync semantics).
  Missing name/description → IllegalStateException at parent build naming the field.
- [ ] The internal machinery carries v1 semantics verbatim — the migrated behavioral
  suite is the proof; assertions must not weaken.
- [ ] Lexical nesting: a `SubagentConfig` also exposes
  `subagent(Consumer<SubagentConfig>)` so trees express A→B→C; add the offline
  end-to-end nested wake-chain test (C settles → wakes B; B settles → wakes A).
- [ ] The handle: doors delegate to the child agent internally; no converse/tell
  exists on `Subagent` (API shape is the test); traversal + unknown-name errors tested.
- [ ] The grep gate from Global Constraints passes.
- [ ] Full verify; license + spotless; commit.

### Task 3: The newsroom on v2 + the gated-delegation proof

**Files:**
- Modify: `nessy-examples/newsroom/src/main/java/.../NewsroomAgents.java` (v2
  construction; delete the manual AgentTools/CallbackRouter/links wiring),
  `NewsroomRepl.java` (child doors via `writer.subagent("researcher")`), README
- Tests: `NewsroomReplSmokeTest` migrates and must not shrink; ADD the mandatory spec
  §4 end-to-end offline test (gated delegation + parking child): writer's delegation
  tool `policy(requireApproval())` → approve the delegation → researcher parks on
  `ask_question` → parent re-parks → approve the researcher → writer wakes and
  completes. Place it beside the smoke test with scripted providers.

**Steps:**
- [ ] Rewrite construction to the §1 spec shape; the REPL reaches researcher doors
  through the handle; behavior identical for the un-gated default demo flow.
- [ ] The §4 proof test (the two-wait timeline through the REAL example wiring).
- [ ] README updates (wiring section shrinks to the one code block; restart story
  re-verified textually against the new code).
- [ ] Full verify; license + spotless; commit.

### Task 4: Docs rewrite (docs-writer dispatch)

**Files:**
- Rewrite: `docs/concepts/subagents.md` around the spec §1 single code block; update
  `docs/examples/index.md`, newsroom references, and any page mentioning
  AgentTools/CallbackRouter as public API (grep the docs tree).

**Steps:**
- [ ] Truth discipline: every claim against the shipped v2 source; the re-park
  lifecycle documented as supported (two-wait timeline); v1's four-part wiring ritual
  gone from every page; omissions list updated (cycles/depth now impossible by
  construction — remove that caveat; sequential fan-out et al. remain).
- [ ] Full verify; license + spotless; commit.

# Subagents Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps
> use checkbox (`- [ ]`) syntax for tracking.

**Goal:** An agent launches agents — `AgentTools.subagent(child, description)` turns a
child `Agent` into an ordinary tool; the child's park makes the parent park; a completions
listener wakes the parent when the child settles.

**Architecture:** One new core package `spi.subagent` (Delegation, AgentTools,
SubagentLinks, CallbackRouter, the completions factory) plus one new published
notification fact (`api.ConversationSettled`) emitted by the loop at drive settlement —
the sealed fold grammar (`ConversationEvent`) is untouched. JDBC gains `JdbcSubagentLinks`
(sixth store, established pattern). Demo: `nessy-examples/newsroom` (writer delegates to
researcher; child approval park chains to the parent; durable across restart).

**Tech Stack:** Java 21, JUnit 5 + AssertJ, ScriptedModelProvider, H2 (demo persistence),
five-vendor JDBC schemas + TCK contract.

**Spec:** `docs/superpowers/specs/2026-08-15-subagents-design.md` (all rulings §0/§9).
Spec deviation pinned by this plan (spec assumed a terminal ConversationEvent exists; it
does not): settlement is a NEW notification record, not a ConversationEvent subtype.

## Global Constraints

- `./mvnw -q clean verify` green with no API key and no network, always.
- Before every commit: `./mvnw license:format -Plicense && ./mvnw spotless:apply`.
- No mocking libraries; prose test names; no star imports; no suppressions; S5778
  single-throwing-invocation lambdas; S5976 parameterized same-shape tests; S107 bundles.
- `ConversationEvent` (sealed fold grammar) and `ConversationState` (reducer) untouched.
- Run builds in the FOREGROUND — never in the background.
- Known shapes (verbatim, from main): `ParkToken(String value)` in `api`;
  `Awaited.ready(v)` / `Awaited.parked(token)` in `api`; `RunOutcome.Completed(state)` /
  `RunOutcome.Parked(state)` in `api`; Agent doors `resume(ParkToken, ToolResolution[, TurnObserver])`,
  `approve(ParkToken[, TurnObserver])`, `deny(ParkToken, String[, TurnObserver])`,
  `conversation(ConversationId)`, `converse()`, `snapshot(ConversationId)`;
  `Parks.Park(ConversationId, ParkToken, ToolCall, String agentName)`;
  `HarnessBuilder implements ListenerDeclarations<HarnessBuilder>` with generic
  `listen(Class<T>, Consumer<T>)` / `listenAsync(...)`; the loop publishes facts via its
  emitter (`ConversationLoop` lines ~372/379); `TurnEvent.TurnEnded` fires at drive
  settlement sites (~261 COMPLETE/FAILED, ~442 PARKED).

---

### Task 1: ConversationSettled — the settlement fact

**Files:**
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/api/ConversationSettled.java`
- Modify: `nessy-core/src/main/java/org/jwcarman/nessy/internal/ConversationLoop.java`
- Test: loop-level tests beside the existing loop/agent tests (ScriptedModelProvider
  drives live in nessy-testing's EndToEndTest if core can't see the scripted provider —
  follow where the executor's fold tests landed).

**Interfaces (Produces):**
- `public record ConversationSettled(ConversationId conversationId, ConversationStatus status, String failureReason, String finalAssistantText) implements ConversationScoped`
  — published (never folded) when a drive settles COMPLETE or FAILED. NOT emitted for
  PARKED (a park is a pause, not a settlement). `failureReason` null when COMPLETE;
  `finalAssistantText` is the concatenated text blocks of the last assistant message
  (empty string when none), null never.
- Javadoc states the contract: at-least-once (a re-driven replay may emit it again for
  the same settlement); listeners must be idempotent.

**Steps:**
- [ ] Failing tests: a scripted one-turn conversation emits exactly one
  ConversationSettled with status COMPLETE and the assistant's text; a conversation whose
  provider throws emits one with status FAILED and the failure reason; a PARKED drive
  emits none; a listener registered via `HarnessBuilder.listen(ConversationSettled.class, ...)`
  receives it.
- [ ] Emit through the loop's existing emitter at the settlement sites where
  `TurnEvent.TurnEnded` fires with COMPLETE/FAILED (NOT the parked site ~442). If the
  emitter's parameter type is `ConversationEvent` (not Object), generalize the emitter
  seam minimally (overload or widen — smallest change that lets a non-fold fact ride the
  same listener fan-out; do NOT touch the sealed ConversationEvent interface).
- [ ] Full verify; license + spotless; commit.

### Task 2: SubagentLinks + CallbackRouter

**Files:**
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/spi/subagent/SubagentLinks.java`
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/spi/subagent/InMemorySubagentLinks.java`
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/spi/subagent/CallbackRouter.java`
- Test: `nessy-core/src/test/java/org/jwcarman/nessy/spi/subagent/{SubagentLinksTest,CallbackRouterTest}.java`

**Interfaces (Produces):**
```java
public interface SubagentLinks {
  record Link(ParkToken parentToken, String parentAgentName) { /* requireNonNulls */ }
  Optional<Link> find(ConversationId child);
  void save(ConversationId child, ParkToken parentToken, String parentAgentName); // LWW upsert
  void forget(ConversationId child);                                              // idempotent
  static SubagentLinks inMemory() { return new InMemorySubagentLinks(); }
}
public final class CallbackRouter {
  public void register(Agent<?> agent);          // keyed by agent.name(); duplicate name -> IllegalArgumentException
  public Agent<?> route(String agentName);       // unknown name -> IllegalArgumentException naming the agent, park-stamp vocabulary
}
```

**Steps:**
- [ ] Failing tests: links save/find round-trip; save twice = last-write-wins; forget
  twice = no-op; router register/route; duplicate register throws; unknown route throws
  with the name in the message.
- [ ] Implement (in-memory link store = ConcurrentHashMap, same style as
  InMemoryPlanStore/InMemoryNotebook).
- [ ] Full verify; license + spotless; commit.

### Task 3: AgentTools.subagent + completions — the feature

**Files:**
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/spi/subagent/AgentTools.java`
  (Delegation record inside or beside it)
- Test: `nessy-core/src/test/java/org/jwcarman/nessy/spi/subagent/AgentToolsTest.java` +
  a full offline park-and-wake test where the scripted-loop tests live.

**Interfaces (Consumes):** Task 1's ConversationSettled, Task 2's SubagentLinks +
CallbackRouter, Agent doors/Awaited/ParkToken from Global Constraints.

**Interfaces (Produces):**
```java
public record Delegation(String task) {}
public static Tool<Delegation> subagent(Agent<String> child, String description)                       // links = none: park path throws IllegalStateException naming the missing links store
public static Tool<Delegation> subagent(Agent<String> child, String description, SubagentLinks links)
public static Consumer<ConversationSettled> completions(SubagentLinks links, CallbackRouter router)
// registered by the app: harnessBuilder.listen(ConversationSettled.class, AgentTools.completions(links, router))
```

**Steps:**
- [ ] Tool semantics (write failing tests first, ScriptedModelProvider throughout):
  - `name()` = `child.name()`; `description()` = the given description;
    `inputType()` = Delegation; `describe(input)` shows the task text (approval-prompt
    surface — match however existing tools express describe).
  - Child id: `new ConversationId(parentConversationId + "/" + toolCallId)` — parent id
    and call id come from ToolContext (verify the exact accessors; they exist for the
    progress channel). Test: two executes with the same call id land on the SAME child
    conversation (tell twice → child folds once, no stutter).
  - Complete → `Awaited.ready(ToolResult.ok(finalAssistantText))`; child FAILED →
    `Awaited.ready(ToolResult.error(reason))`; child parks → mint a parent ParkToken (the
    same way existing parking tools mint - see order-desk/dispatcher precedent),
    `links.save(childId, parentToken, parentAgentName-from-ToolContext-or-tool-wiring)`,
    return `Awaited.parked(parentToken)`.
  - Progress pings: pass a TurnObserver to the child's tell that calls
    `context.progress("<child-name>: ...")` on each `TurnEvent.ToolCallRequested` —
    activity pings so long delegations never look frozen (spec §9 wants per-turn; ledger
    the approximation, document honestly in javadoc).
- [ ] completions factory: a `Consumer<ConversationSettled>` (registered sync via
  `HarnessBuilder.listen`) —
  `links.find(event.conversationId())` → if present: COMPLETE →
  `router.route(link.parentAgentName()).resume(link.parentToken(), ToolResolution.Completed(ToolResult.ok(event.finalAssistantText())))`;
  FAILED → same door with `ToolResult.error(...)`; then `links.forget(childId)`.
  At-least-once: a second settlement for the same child (links already forgotten) is a
  silent no-op; a resume on an already-resolved token surfaces the doors' existing
  behavior — document, don't wrap.
- [ ] The full offline park-and-wake test: scripted parent delegates; scripted child has
  an approval-gated tool → child parks → parent parks (assert both stores); child
  `approve(token)` → child completes → completions listener resumes parent → parent
  completes with the child's text in its context. Plus the duplicate-completion replay
  (listener fired twice → second is a no-op). Plus WrongAgentException surfacing when the
  router holds a different agent under the parent's name.
- [ ] Full verify; license + spotless; commit.

### Task 4: JdbcSubagentLinks — the sixth store

**Files:**
- Create: `nessy-jdbc/src/main/java/org/jwcarman/nessy/jdbc/JdbcSubagentLinks.java`
- Create: `nessy-jdbc/src/main/resources/.../subagent-links-schema.sql` ×5 vendors
  (exactly where the five notebook/plan schemas live; SQL Server PK NONCLUSTERED if the
  key column follows the notebook's varchar sizing)
- Create: `nessy-tck/src/main/java/org/jwcarman/nessy/tck/SubagentLinksContract.java`
  (ALL @Test methods PUBLIC — cross-package @Nested discovery)
- Modify: nessy-jdbc's TCK test to add the contract nest + the five vendor container
  nests, following the notebook precedent exactly.

**Interfaces (Consumes):** Task 2's `SubagentLinks` + `Link`.

**Steps:**
- [ ] Contract: round-trip, LWW on double save, idempotent forget, absent find — public
  @Test methods, prose names.
- [ ] Table: `(child_conversation_id PRIMARY KEY, parent_token, parent_agent_name)`;
  upsert via the JdbcSummaryStore race-recovery pattern (update → write-once insert →
  dup-swallow → retry-update); complete constant SQL; schema-comment semicolons only at
  line-end (Oracle).
- [ ] Offline rollback rig per the JdbcPlanStore precedent (SQLException AND
  RuntimeException arms both roll back).
- [ ] If Docker is up (check!), run the five vendor container nests locally; if not,
  note it in the report so the controller watches CI's matrix.
- [ ] Full verify; license + spotless; commit.

### Task 5: The newsroom + the docs page

**Files:**
- Create: `nessy-examples/newsroom/` (pom + `Newsroom.java` + README.md), registered in
  `nessy-examples/pom.xml` modules
- Create: `docs/concepts/subagents.md`; Modify: `mkdocs.yml` nav, `docs/index.md` map,
  README capabilities table (one row), `docs/examples/index.md` (family of eight now)

**Interfaces (Consumes):** everything above.

**Steps:**
- [ ] Newsroom: `writer` (console REPL via ConsoleRepl, plan checklist wired) delegating
  to `researcher` via `AgentTools.subagent(...)` with a SubagentLinks store; both agents
  share a `SubjectId` and a Notebook (spec §9 continuity ruling — wire the notebook
  transformer on both); researcher has an `ask_question` tool gated
  `UsagePolicy.requireApproval()` so the child parks and the parent parks with it; REPL
  detects the Parked outcome, prints the pending question, and offers approve/deny which
  drives the researcher's door — the completions listener (registered on the harness at
  build) then wakes the writer. Durable store: mirror the dispatcher example's JDBC/H2
  file setup so the README can show the restart-mid-delegation transcript honestly.
  Provider selection via `EnvModelProviders.select()` like chat-cli. `System.exit(0)`
  after the REPL (SDK idle threads — Chat.java precedent, same comment).
- [ ] Sequential fan-out stated plainly in the README (spec §9: no parallelism implied).
- [ ] Docs page `docs/concepts/subagents.md`: the one-sentence idea (a subagent call is a
  tool call whose work is another agent's conversation), the wiring (tool + links +
  router + completions), the park chain, fresh-child-per-call + Notebook continuity,
  sequential fan-out, what v1 deliberately omits (spec §6). Truth discipline: no claims
  beyond what shipped; brand voice per the docs-writer rules (this task may be split to
  the docs-writer agent at the controller's discretion).
- [ ] Full verify (newsroom compiles + any offline tests); license + spotless; commit.

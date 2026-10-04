# The Agent's Story, Plan 1: Reading the Story — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Narration becomes a story an application can replay, project and read content from, with usage on every model call, using only what is already stored.

**Architecture:** One engine adapter turns each stored `AgentEvent` into one `Narration.Story` event; both doors use it live and the new read API uses it on replay. A listener receives a `Narrated` envelope carrying the agent and, for story events, the event's position. The read API (`AgentStories`) sits in `nessy-api` and is implemented in the engine over the backend's event and payload stores.

**Tech Stack:** Java 25, Maven (`./mvnw`), JUnit 5, AssertJ, Awaitility, Testcontainers (PostgreSQL 18), Spring JDBC as a library, Jackson 3.

**Spec:** `docs/superpowers/specs/2026-10-04-agent-story-design.md`. Read §2 to §5, §9 and §12 before any task.

## Where this plan sits

The spec is built in five plans. **This plan changes nothing in the fold and no stored event's shape.** Everything the spec needs from the fold is in later plans, each of which gets the scrutiny of spec §10c.

| Plan | Builds | Touches the fold? |
|---|---|---|
| **1 (this one)** | the adapter; `Story` / `Live` / `TurnEnding`; `Usage`, `FailureKind`, `TurnStopped`, `InferenceRetried` on narration; the envelope; `replay`, `project`, `follow`; content by turn and by key; `UsageReports` as a projection | **no** |
| 2 | stored fields carried through: the key on call events, `decidedBy`, `CallFailure`, `truncated`, `label`, `arrivedAt`, the stored `TurnFailed` → `TurnStopped` rename, the shown-deadline fix | pass-through fields, one rename |
| 3 | deferral events and the park step, `parked_at` | two new commands and events |
| 4 | the approval question, `Payloads.putDocument` | pass-through fields |
| 5 | the request manifest | pass-through field |

What plan 1 leaves for plan 2, by necessity: the call events (`CallApproved`, `CallDenied`, `CallFinished`, `CallFailed`) keep today's components, because the stored events they come from do not yet carry the key, who decided, or the failure kind. `ApprovalDeferred` and `CallDeferred` stay live signals until plan 3. `TurnStarted` has no label yet. `Answered` has no `truncated` yet.

## Global Constraints

- **No file under `nessy-engine/src/main/java/org/jwcarman/nessy/engine/core/` is edited in this plan**, and neither is `AgentEvent`, `AgentCommand`, `EffectOutcome`, `Decision` or anything under `nessy-backend/*/effect/`. A task that seems to need it stops and reports.
- New public names in `nessy-api` are exactly those in spec §12. No other type, method or vocabulary word is added there. A task that needs one stops and reports. Types this plan names in the backend SPI and the engine (`StoryEvents`, `AgentEvents.Written`, `EventAgentStories`, `StoryHub`) are internal and are as written here.
- No `@SuppressWarnings`, `// NOSONAR` or any other suppression. No star imports.
- Tests use no mocking library: hand-written fakes and stubs, as the neighbouring tests do. Test names are prose.
- An exception-assertion lambda holds exactly one call that can throw (Sonar S5778). An all-match or none-match assertion is preceded by a non-emptiness assertion on the same collection (S5841).
- Every source file carries the Apache header. Run `./mvnw spotless:apply license:format` before each commit.
- While iterating: `./mvnw -B -q -pl :<artifactId> -am test -Dtest='<Class>' -Dsurefire.failIfNoSpecifiedTests=false`. Always pass `-am`. Never run two Maven processes in one worktree.
- A task's last step is the gate: `./mvnw -B -q clean verify -Dnessy.excludedGroups=live`, and **Maven's own exit code is what is read**, written to a file under `target/` (`; echo $? > target/gate.exit`), never inferred from output.
- Commit messages end with the two attribution lines the session supplies.
- Docs describe what is, in plain American wording, with no history.

## Review Focus

Inputs the spec implies and no task's happy path exercises, most likely first. Each has a test in the task named.

1. **A listener that throws while `follow` replays** must not stop the replay or lose later events for that listener. (Task 7)
2. **Events committed while `follow` is replaying** must be heard once, in order, after the replay. (Task 7)
3. **`replay` with a limit of zero or less, or above the ceiling**: `IllegalArgumentException` for zero or less; a limit above 1,000 is capped at 1,000. (Task 4)
4. **A handler registered for a group type** (`Narration.TurnEnding`, `Narration.Story`) must hear every event of that group; today's lookup is by exact class. (Task 2)
5. **A projection that throws** must surface its own exception from `project`, and leave nothing half-applied in the store. (Task 5)

---

### Task 1: One adapter from stored events to narration (no behaviour change)

**Files:**
- Create: `nessy-engine/src/main/java/org/jwcarman/nessy/engine/narration/StoryEvents.java`
- Create: `nessy-engine/src/test/java/org/jwcarman/nessy/engine/narration/StoryEventsTest.java`
- Modify: `nessy-engine/src/main/java/org/jwcarman/nessy/engine/harness/direct/DefaultDirectHarness.java` (the private `narrate(Step, AgentEvent)` switch, about lines 848-903)
- Modify: `nessy-engine/src/main/java/org/jwcarman/nessy/engine/harness/queued/DefaultQueuedHarness.java` (the private `narrate(Step, AgentEvent)` switch, about lines 394-450)

**Interfaces:**
- Consumes: `AgentEvent` (`nessy-backend-spi`), `Narration` (`nessy-api`), both as they are on `main`.
- Produces: `public final class StoryEvents { public static List<Narration> of(AgentEvent event) }`. Task 2 changes the return type to `Narration.Story`.

This task moves code and changes no output. Its proof is that every existing narration test passes with no edit.

- [ ] **Step 1: Write the adapter's test.** One test per `AgentEvent` kind, asserting exactly what the two switches produce today:

```java
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class StoryEventsTest {

  private static final Seq SEQ = new Seq(7);
  private static final TurnId TURN = new TurnId(3);
  private static final CallId CALL = new CallId("c1");
  private static final IdempotencyKey KEY =
      IdempotencyKey.of(UUID.fromString("01999999-0000-7000-8000-000000000001"));

  @Test
  void a_turn_starting_is_told_as_turn_started() {
    assertThat(StoryEvents.of(new AgentEvent.TurnStarted(SEQ, TURN, PayloadRef.of("p"), Instant.EPOCH)))
        .containsExactly(new Narration.TurnStarted(TURN));
  }

  @Test
  void an_answer_is_told_as_answered_and_then_the_turn_ending() {
    assertThat(StoryEvents.of(new AgentEvent.InferenceAnswered(SEQ, TURN, PayloadRef.of("p"), Usage.unreported())))
        .containsExactly(new Narration.Answered(), new Narration.TurnEnded(TURN));
  }

  @Test
  void a_retried_model_call_is_not_told() {
    assertThat(StoryEvents.of(new AgentEvent.InferenceAttempted(
            SEQ, TURN, new Failure.Transient("busy"), Usage.unreported())))
        .isEmpty();
  }

  @Test
  void requested_actions_are_told_with_each_calls_id_tool_and_action() {
    AgentEvent.ActionsRequested asked =
        new AgentEvent.ActionsRequested(
            SEQ, TURN, PayloadRef.of("p"),
            List.of(new ActionRequest.ToolCall(CALL, new ToolName("lookup"), "look it up", KEY)),
            Usage.unreported());

    assertThat(StoryEvents.of(asked))
        .containsExactly(
            new Narration.ActionsRequested(
                List.of(new Narration.ActionsRequested.Call(CALL, new ToolName("lookup"), "look it up"))));
  }
}
```

Add the remaining kinds in the same shape, each asserting today's output: `InferenceRefused` → `TurnRefused(category)`, `TurnEnded(turn)`; `InferenceFailed` → `TurnFailed(failure.reason())`, `TurnEnded(turn)`; `TurnFailed` (policy) → `TurnFailed(reason)`, `TurnEnded(turn)`; `ToolApproved` → `CallApproved(callId)`; `ToolDenied` → `CallDenied(callId, reason)`; `ToolSucceeded` → `CallFinished(callId)`; `ToolFailed` → `CallFailed(callId, message)`; `Terminated` → `Terminated()`.

- [ ] **Step 2: Run it and see it fail to compile.**

Run: `./mvnw -B -q -pl :nessy-engine -am test -Dtest='StoryEventsTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure, `cannot find symbol: class StoryEvents`.

- [ ] **Step 3: Write `StoryEvents`,** moving the switch body out of `DefaultDirectHarness.narrate` unchanged:

```java
/**
 * What a stored event is told as. The one mapping from the agent's record to its narration: both
 * doors use it as events commit, so what a listener hears cannot differ by door.
 */
public final class StoryEvents {

  private StoryEvents() {}

  public static List<Narration> of(AgentEvent event) {
    return switch (event) {
      case AgentEvent.InferenceAttempted _ -> List.of();
      case AgentEvent.ActionsRequested asked ->
          List.of(
              new Narration.ActionsRequested(
                  asked.actions().stream()
                      .filter(ActionRequest.ToolCall.class::isInstance)
                      .map(ActionRequest.ToolCall.class::cast)
                      .map(call -> new Narration.ActionsRequested.Call(call.id(), call.name(), call.action()))
                      .toList()));
      case AgentEvent.ToolApproved approved -> List.of(new Narration.CallApproved(approved.callId()));
      case AgentEvent.ToolDenied denied -> List.of(new Narration.CallDenied(denied.callId(), denied.reason()));
      case AgentEvent.ToolSucceeded done -> List.of(new Narration.CallFinished(done.callId()));
      case AgentEvent.ToolFailed failed -> List.of(new Narration.CallFailed(failed.callId(), failed.message()));
      case AgentEvent.InferenceAnswered answered ->
          List.of(new Narration.Answered(), new Narration.TurnEnded(answered.turn()));
      case AgentEvent.InferenceRefused refused ->
          List.of(new Narration.TurnRefused(refused.category()), new Narration.TurnEnded(refused.turn()));
      case AgentEvent.InferenceFailed failed ->
          List.of(new Narration.TurnFailed(failed.failure().reason()), new Narration.TurnEnded(failed.turn()));
      case AgentEvent.TurnFailed ended ->
          List.of(new Narration.TurnFailed(ended.reason()), new Narration.TurnEnded(ended.turn()));
      case AgentEvent.Terminated _ -> List.of(new Narration.Terminated());
      case AgentEvent.TurnStarted started -> List.of(new Narration.TurnStarted(started.turn()));
    };
  }
}
```

- [ ] **Step 4: Replace both switches.** In each harness, `narrate(Step step, AgentEvent event)` keeps its `if (!narrator.listening()) return;` guard and its javadoc, and its body becomes `StoryEvents.of(event).forEach(step::narrate);`. The comments that explained individual cases move onto the matching case in `StoryEvents`.

- [ ] **Step 5: Run the adapter's test and every narration test.**

Run: `./mvnw -B -q -pl :nessy-engine -am test -Dtest='StoryEventsTest,Narration*Test,AfterCommitTest,ListenersTraceTest,DefaultDirectHarnessTest,HarnessLoopTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: exit 0. `git diff --stat -- '*/src/test'` shows only the new `StoryEventsTest.java`.

- [ ] **Step 6: Gate and commit.**

```bash
./mvnw spotless:apply license:format
./mvnw -B -q clean verify -Dnessy.excludedGroups=live; echo $? > target/gate.exit; cat target/gate.exit   # 0
git add -A && git commit -m "refactor: one adapter turns a stored event into its narration, for both doors"
```

---

### Task 2: The story's vocabulary

**Files:**
- Modify: `nessy-api/src/main/java/org/jwcarman/nessy/api/Narration.java`
- Create: `nessy-api/src/main/java/org/jwcarman/nessy/api/FailureKind.java`
- Modify: `nessy-api/src/main/java/org/jwcarman/nessy/api/NarrationListenerConfig.java`
- Modify: `nessy-engine/.../narration/StoryEvents.java`, `nessy-engine/.../chapter/ChapterKeeper.java` (line 138, `onTurnEnded`)
- Modify: `nessy-narration-odyssey/.../OdysseyNarrator.java` (`nameOf`), `nessy-console/.../ConsoleNarration.java`, `nessy-examples/chat-web/src/main/resources/static/app.js` (lines 146-155)
- Modify the tests that name `TurnEnded`, `Answered()`, `TurnFailed(String)`, `TurnRefused(String)` or `ActionsRequested(List)`: find them with `git grep -n 'Narration\.\(TurnEnded\|Answered\|TurnFailed\|TurnRefused\|ActionsRequested\)' -- '*/src/test'`
- Modify: `docs/guides/narration.md`, `CHANGELOG.md`

**Interfaces:**
- Consumes: `StoryEvents.of` from Task 1.
- Produces, exactly:

```java
public enum FailureKind { TRANSIENT, UNKNOWN, PERMANENT, REJECTED }

public sealed interface Narration {
  sealed interface Story extends Narration {}
  sealed interface Live extends Narration {}
  sealed interface TurnEnding extends Story { TurnId turn(); }

  record TurnStarted(TurnId turn) implements Story {}
  record TurnStopped(TurnId turn, String reason) implements TurnEnding {}
  record Terminated() implements Story {}
  record ActionsRequested(TurnId turn, List<Call> calls, Usage usage) implements Story {
    public record Call(CallId callId, IdempotencyKey idempotencyKey, ToolName toolName, String action) {}
  }
  record Answered(TurnId turn, Usage usage) implements TurnEnding {}
  record TurnRefused(TurnId turn, String category, Usage usage) implements TurnEnding {}
  record TurnFailed(TurnId turn, FailureKind kind, String reason, Usage usage) implements TurnEnding {}
  record InferenceRetried(TurnId turn, FailureKind kind, String reason, Usage usage) implements Story {}
  record CallApproved(CallId callId) implements Story {}
  record CallDenied(CallId callId, String reason) implements Story {}
  record CallFinished(CallId callId) implements Story {}
  record CallFailed(CallId callId, String message) implements Story {}

  record Thinking() implements Live {}
  record ThinkingDelta(String text) implements Live {}
  record ContentDelta(String text) implements Live {}
  record Commentary(String text) implements Live {}
  record ApprovalSought(CallId callId, String action) implements Live {}
  record ApprovalDeferred(CallId callId, String action, Instant until) implements Live {}
  record CallDeferred(CallId callId, ToolName toolName, Instant until) implements Live {}
}
```

  `Narration.TurnEnded` no longer exists. `StoryEvents.of` becomes `public static Narration.Story of(AgentEvent event)`: total and one-to-one. `NarrationListenerConfig` loses `onTurnEnded` and gains `onTurnEnding(Handler<Narration.TurnEnding>)`, `onTurnStopped(Handler<Narration.TurnStopped>)` and `onInferenceRetried(Handler<Narration.InferenceRetried>)`.

`ActionsRequested` keeps its compact constructor's `calls = List.copyOf(calls)`. The `@JsonSubTypes` list loses `turn-ended` and gains `turn-stopped` and `inference-retried`.

- [ ] **Step 1: Rewrite `StoryEventsTest` for the one-to-one mapping.** Each test asserts a single event with `isEqualTo`:

```java
  @Test
  void an_answer_ends_its_turn_and_says_what_the_call_cost() {
    Usage usage = Usage.of("a-model", 100, 20);

    assertThat(StoryEvents.of(new AgentEvent.InferenceAnswered(SEQ, TURN, PayloadRef.of("p"), usage)))
        .isEqualTo(new Narration.Answered(TURN, usage));
  }

  @Test
  void a_retried_model_call_is_told_with_its_kind_and_cost() {
    Usage usage = Usage.of("a-model", 100, 0);

    assertThat(StoryEvents.of(
            new AgentEvent.InferenceAttempted(SEQ, TURN, new Failure.Unknown("no answer"), usage)))
        .isEqualTo(new Narration.InferenceRetried(TURN, FailureKind.UNKNOWN, "no answer", usage));
  }

  @Test
  void a_turn_a_policy_stopped_is_not_a_failed_model_call() {
    assertThat(StoryEvents.of(new AgentEvent.TurnFailed(SEQ, TURN, "too many calls")))
        .isEqualTo(new Narration.TurnStopped(TURN, "too many calls"));
  }

  @ParameterizedTest
  @MethodSource("everyKind")
  void every_stored_event_is_told_as_exactly_one_story_event(AgentEvent event) {
    assertThat(StoryEvents.of(event)).isNotNull();
  }
```

  `everyKind()` supplies one instance of each of the twelve `AgentEvent` records. Add single-event tests for the remaining kinds: `InferenceRefused` → `TurnRefused(TURN, category, usage)`; `InferenceFailed` with each of the four `Failure` arms → `TurnFailed(TURN, <kind>, reason, usage)`; `ActionsRequested` → the `turn`, the `usage`, and each `Call` with its `idempotencyKey`.

- [ ] **Step 2: Write the group-handler test** (Review Focus 4) in `nessy-api/src/test/java/org/jwcarman/nessy/api/NarrationListenerConfigTest.java`:

```java
  @Test
  void a_handler_for_turn_endings_hears_each_way_a_turn_can_end() {
    List<Narration> heard = new ArrayList<>();
    NarrationListener listener =
        NarrationListener.of(c -> c.onTurnEnding((_, _, event) -> heard.add(event)));
    TurnId turn = new TurnId(1);
    List<Narration> endings =
        List.of(
            new Narration.Answered(turn, Usage.unreported()),
            new Narration.TurnRefused(turn, "safety", Usage.unreported()),
            new Narration.TurnFailed(turn, FailureKind.PERMANENT, "no", Usage.unreported()),
            new Narration.TurnStopped(turn, "limit"));

    endings.forEach(event -> listener.on(TYPE, AGENT, event));
    listener.on(TYPE, AGENT, new Narration.Thinking());

    assertThat(heard).containsExactlyElementsOf(endings);
  }
```

- [ ] **Step 3: Run both and see them fail to compile.** Expected: `cannot find symbol` for `TurnStopped`, `FailureKind`, `onTurnEnding`.

- [ ] **Step 4: Reshape `Narration`, add `FailureKind`, and make `StoryEvents` one-to-one.** The failure's kind comes from the arm:

```java
  private static FailureKind kindOf(Failure failure) {
    return switch (failure) {
      case Failure.Transient _ -> FailureKind.TRANSIENT;
      case Failure.Unknown _ -> FailureKind.UNKNOWN;
      case Failure.Permanent _ -> FailureKind.PERMANENT;
      case Failure.Rejected _ -> FailureKind.REJECTED;
    };
  }
```

  Each harness's `narrate` body becomes `step.narrate(StoryEvents.of(event));`. Keep every record's javadoc that is still true; rewrite those that are not (`Answered`, `TurnFailed`, `TurnRefused`, `ActionsRequested`), and give `Story`, `Live` and `TurnEnding` the one-line javadoc from spec §3.

- [ ] **Step 5: Make `NarrationListenerConfig` match a handler's kind by `isInstance`.** `build()` today looks handlers up by `event.getClass()`; replace the lookup with a walk over the registered kinds, in registration order, calling each handler whose kind `isInstance(event)`. Remove `onTurnEnded`; add `onTurnEnding`, `onTurnStopped`, `onInferenceRetried`.

- [ ] **Step 6: Update every consumer.**
  - `ChapterKeeper.listener()`: `onTurnEnded` → `onTurnEnding`.
  - `OdysseyNarrator.nameOf`: drop `turn-ended`; add `case Narration.TurnStopped _ -> "turn-stopped"` and `case Narration.InferenceRetried _ -> "inference-retried"`.
  - `ConsoleNarration`: its switch is exhaustive; add the two new cases (a stopped turn prints as a failed one does today; a retry prints nothing) and remove `TurnEnded`.
  - `chat-web` `app.js`: replace the `turn-ended` listener with `idle` on `turn-stopped`; `answered`, `turn-failed` and `turn-refused` already call `idle`.
  - Tests: update constructors; where a test waited for `TurnEnded`, wait for `Narration.TurnEnding`.

- [ ] **Step 7: Run the affected modules.**

Run: `./mvnw -B -q -pl :nessy-engine,:nessy-console,:nessy-narration-odyssey,:nessy-example-chat-web -am test`
Expected: exit 0.

- [ ] **Step 8: Docs.** In `docs/guides/narration.md` replace the event table with the vocabulary above, in two groups, "Story events (stored)" and "Live signals", and say a turn ends in exactly one of the four `TurnEnding` events. In `CHANGELOG.md` under `[Unreleased]` / `### Breaking changes`:

```markdown
- **Narration is reshaped into the agent's story.** `Narration` has two groups, `Story` (stored
  events) and `Live` (signals heard only as they happen). `TurnEnded` is removed: a turn ends in
  exactly one `TurnEnding` event (`Answered`, `TurnRefused`, `TurnFailed` or `TurnStopped`), and
  `NarrationListenerConfig.onTurnEnding` hears all four. A turn a policy stopped is `TurnStopped`,
  no longer `TurnFailed`. `Answered`, `TurnRefused`, `TurnFailed` and `ActionsRequested` carry
  their turn and the model call's `Usage`; `TurnFailed` carries a `FailureKind`; each requested
  call carries its `IdempotencyKey`. A retried model call is told as `InferenceRetried`.
```

- [ ] **Step 9: Gate and commit.** As Task 1 Step 6, message `feat: narration is the agent's story: story events, live signals, and usage on every model call`.

---

### Task 3: The envelope

**Files:**
- Create: `nessy-api/src/main/java/org/jwcarman/nessy/api/Narrated.java`
- Modify: `nessy-api/.../NarrationListener.java`, `NarrationListenerConfig.java`, `Narrator.java`
- Modify: `nessy-backend/spi/.../event/AgentEvents.java`, `nessy-backend/jdbc/.../JdbcAgentEvents.java`, `nessy-backend/inmemory/.../InMemoryAgentEvents.java`
- Modify: `nessy-engine/.../narration/AfterCommit.java`, `Listeners.java`; both harnesses' `append` call sites and `narrate`
- Modify every `NarrationListener` implementation and lambda: `ConsoleNarration`, `OdysseyNarrator`, watchman `ApprovalsDesk`, `ChapterKeeper`, and the tests
- Modify: `docs/guides/narration.md`, `CHANGELOG.md`

**Interfaces:**
- Produces:

```java
public record Narrated(
    AgentType agentType, AgentId agentId, Narration event, Optional<Position> position) {

  public record Position(Seq seq, Instant at) {}

  public Narrated {
    Objects.requireNonNull(agentType, "agentType must not be null");
    Objects.requireNonNull(agentId, "agentId must not be null");
    Objects.requireNonNull(event, "event must not be null");
    Objects.requireNonNull(position, "position must not be null");
    if (event instanceof Narration.Story != position.isPresent()) {
      throw new IllegalArgumentException("a story event has a position, and only a story event");
    }
  }

  public static Narrated live(AgentType agentType, AgentId agentId, Narration.Live event) { ... }
  public static Narrated story(
      AgentType agentType, AgentId agentId, Narration.Story event, Seq seq, Instant at) { ... }
}

public interface NarrationListener { void on(Narrated narrated); /* async(), none(), of(...) kept */ }
public interface NarrationListenerConfig.Handler<E extends Narration> { void on(Narrated narrated, E event); }
public interface Narrator { void narrate(Narrated narrated); /* listening(), silent(), forAgent kept */ }
```

  `AgentEvents.append(AgentType type, AgentId agent, List<AgentEvent> events, Seq expectedLast, Instant at)`: the instant every event of the batch is written at. `AfterCommit.Step` offers `narrate(Narration.Live event)` and `narrate(Narration.Story event, Seq seq, Instant at)`.

- [ ] **Step 1: Write the envelope's test** (`nessy-api/src/test/.../NarratedTest.java`): a story event without a position is refused; a live signal with one is refused; `live(...)` has no position; `story(...)` has the `seq` and `at` given.

```java
  @Test
  void a_story_event_without_a_position_is_refused() {
    Narration.Terminated event = new Narration.Terminated();
    Optional<Narrated.Position> none = Optional.empty();

    assertThatThrownBy(() -> new Narrated(TYPE, AGENT, event, none))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("a story event has a position, and only a story event");
  }
```

- [ ] **Step 2: Write the time test** in `nessy-backend/jdbc/src/test/.../JdbcAgentEventsTest.java` (a `@Tag("container")` test) and its in-memory twin: events appended with `at = 2026-01-01T00:00:00Z` report that instant from `writtenAt(type, agent, seq)`, whatever the database's clock says.

- [ ] **Step 3: Write the live-envelope test** in `nessy-engine/src/test/.../harness/direct/DefaultDirectHarnessTest.java`: with a fixed clock, one answered turn's listener hears `TurnStarted` and `Answered` each with a position whose `seq` is the stored event's and whose `at` equals `events.writtenAt(TYPE, agent, seq)`; `Thinking` has no position.

- [ ] **Step 4: Run them and see them fail** (compile errors for `Narrated`, the five-argument `append`).

- [ ] **Step 5: Implement.** `Narrated`; the listener and narrator signatures; `append` writes `written_at` from `at` in JDBC (the `INSERT` names the column) and stamps it in memory; each harness reads `Instant at = clock.instant()` once per step, passes it to `append`, and narrates each event with `step.narrate(StoryEvents.of(event), event.seq(), at)`. Live signals from the handlers go through `Narrated.live`. `Listeners` passes the envelope to each listener unchanged. `NarrationListenerConfig`'s `agentType` filter reads `narrated.agentType()`.

- [ ] **Step 6: Update every listener.** Each `on(agentType, agentId, event)` becomes `on(Narrated narrated)` and reads the three from the envelope. `OdysseyNarrator` publishes `narrated.event()` under the same names as before. If the stream's `publish` has a form that takes an SSE event id, pass a story event's `seq` as the id; if it has none, leave the call as it is and say so in the task report.

- [ ] **Step 7: Run all modules' tests** (`./mvnw -B -q test`, exit 0), then docs: one paragraph in `docs/guides/narration.md` on the envelope and its position, and a CHANGELOG breaking-change entry: "`NarrationListener.on` takes a `Narrated` envelope: the agent, the event, and for a story event its position (`seq` and time written)."

- [ ] **Step 8: Gate and commit.** Message `feat: a listener is handed an envelope with the agent and the event's position`.

---

### Task 4: Replaying a story

**Files:**
- Create: `nessy-api/.../AgentStories.java`, `AgentStory.java`
- Create: `nessy-engine/src/main/java/org/jwcarman/nessy/engine/story/EventAgentStories.java` and its test `EventAgentStoriesTest.java`
- Modify: `nessy-backend/spi/.../event/AgentEvents.java` and both implementations
- Create: `nessy-spring-boot/autoconfigure/.../AgentStoriesAutoConfiguration.java` (model it on `UsageReportsAutoConfiguration`), its entry in the `.imports` file, and a context-runner test

**Interfaces:**
- Produces:

```java
public interface AgentStories { AgentStory of(AgentType type, AgentId id); }

public interface AgentStory {
  /** Up to {@code limit} story events after {@code after}, oldest first. */
  List<Narrated> replay(Seq after, int limit);
}
```

  `AgentEvents` gains `Stream<Written> streamWrittenFrom(AgentType type, AgentId agent, Seq after)` with `record Written(AgentEvent event, Instant at)`, so a replay reads each event with its time in one query. `EventAgentStories(AgentEvents events)` is the engine's implementation. Tasks 5 to 7 add methods to `AgentStory` and constructor arguments to `EventAgentStories`.

- [ ] **Step 1: Write the failing tests** in `EventAgentStoriesTest` (in-memory backend, events appended by hand with a fixed instant):
  - `an_agent_nobody_told_anything_has_an_empty_story`
  - `a_story_is_replayed_oldest_first_with_each_events_position`: three appended events come back as three `Narrated`, each `position()` holding the event's `seq` and the appended instant
  - `a_replay_after_a_position_starts_with_the_next_event`
  - `a_replay_returns_no_more_than_its_limit`
  - `a_limit_of_zero_or_less_is_refused` (`IllegalArgumentException`, message `limit must be positive`), and `a_limit_above_a_thousand_is_capped` (Review Focus 3)
  - `what_a_listener_heard_live_is_what_the_replay_returns`: run one scripted direct-door turn with a recording listener, keep the `Narrated` whose event is a `Narration.Story`, and assert the list equals `stories.of(TYPE, agent).replay(Seq.NONE, 100)`

- [ ] **Step 2: Run and see them fail to compile.**
- [ ] **Step 3: Implement** `streamWrittenFrom` in both backends (JDBC selects `payload, written_at` ordered by `seq`), then `EventAgentStories.replay`: validate and cap the limit, stream, `limit`, and map each `Written` to `Narrated.story(type, id, StoryEvents.of(w.event()), w.event().seq(), w.at())`. Close the stream.
- [ ] **Step 4: Run the tests, then add the starter's bean** over the context's `AgentEvents`, `@ConditionalOnMissingBean`, with a test that the bean is present with a backend and absent without one.
- [ ] **Step 5: Docs.** A new section in `docs/guides/narration.md`, "Reading the story afterwards", with a `replay` example; CHANGELOG `### Added`: "`AgentStories` replays an agent's story: the stored events, as the `Narrated` a live listener hears, with each event's position."
- [ ] **Step 6: Gate and commit.** Message `feat: an agent's story can be replayed`.

---

### Task 5: Projections, and usage as one

**Files:**
- Create: `nessy-api/.../StoryProjection.java`
- Modify: `nessy-api/.../AgentStory.java`, `nessy-engine/.../story/EventAgentStories.java`
- Modify: `nessy-engine/.../usage/EventUsageReports.java`

**Interfaces:**
- Produces:

```java
public interface StoryProjection<T> {
  T initial();
  T apply(T soFar, Narrated story);
}
// on AgentStory:
<T> T project(StoryProjection<T> projection);
```

- [ ] **Step 1: Write the failing tests:** a projection counting `TurnStarted` over a three-turn story returns 3; a projection over an empty story returns `initial()`; `a_projection_that_throws_surfaces_its_own_exception` (Review Focus 5: the thrown `IllegalStateException("boom")` reaches the caller, and a second `project` on the same story still works).
- [ ] **Step 2: Implement `project`** as a fold over `streamWrittenFrom(type, id, Seq.NONE)`, closing the stream in `finally`.
- [ ] **Step 3: Reimplement `EventUsageReports` over the story.** It sums the `Usage` of `ActionsRequested`, `Answered`, `TurnRefused`, `TurnFailed` and `InferenceRetried`, by model, and counts each as one inference. **`EventUsageReportsTest` must pass with no edit**: that is the proof the totals are unchanged.
- [ ] **Step 4: Run** `./mvnw -B -q -pl :nessy-engine -am test -Dtest='EventAgentStoriesTest,EventUsageReportsTest' -Dsurefire.failIfNoSpecifiedTests=false` (exit 0; `git diff --stat` shows no change to `EventUsageReportsTest.java`).
- [ ] **Step 5: Docs** (a "Projections" paragraph with the turn-counting example) **, gate and commit.** Message `feat: a story can be projected, and usage reports are a projection`.

---

### Task 6: Reading content

**Files:**
- Create: `nessy-api/.../StoryContent.java`, `TurnContent.java`, `RequestContent.java`, `CallResult.java`
- Modify: `AgentStory.java`, `EventAgentStories.java` (constructor gains `Payloads`)
- Create: `nessy-engine/src/test/.../story/StoryContentTest.java`

**Interfaces:**
- Produces:

```java
public interface StoryContent {
  TurnContent turn(TurnId turn);
  Optional<List<Block.ToolResultContent>> result(IdempotencyKey key);
  List<CallResult> results(Seq after, int limit);
}
public record TurnContent(
    List<Block.InputContent> input, List<RequestContent> requests, Optional<List<Block.AnswerContent>> answer) {}
public record RequestContent(Seq seq, List<Block.ActionRequestContent> blocks) {}
public record CallResult(Seq seq, IdempotencyKey idempotencyKey, List<Block.ToolResultContent> blocks) {}
// on AgentStory:
StoryContent content();
```

  `question(IdempotencyKey)` is plan 4's. A key is resolved inside one agent's story: an `ActionsRequested` event gives each call's key and id, and the `ToolSucceeded` for that id in the same request gives the result's `PayloadRef`.

- [ ] **Step 1: Write the failing tests** over one scripted turn with two tool calls, one succeeding and one failing, then an answer:
  - `a_turns_content_is_its_input_what_the_model_wrote_and_its_answer`
  - `a_turn_still_in_progress_has_no_answer`
  - `a_calls_result_is_read_by_its_key`, and `a_failed_call_has_no_result`
  - `a_key_that_is_not_in_this_agents_story_has_no_result`
  - `results_are_only_the_successful_calls_oldest_first`, `results_after_a_position_skip_what_came_before`, `results_return_no_more_than_their_limit`
  - `a_turn_that_does_not_exist_is_refused` (`IllegalArgumentException`, message `no turn 99 in this agent's story`)
- [ ] **Step 2: Implement** over `AgentEvents` and `Payloads.forAgent(id)`. `results` applies the same limit rule as `replay`.
- [ ] **Step 3: Docs** (a "Reading content" section stating that the story never carries content and that every read is addressed by a turn, a key or a position) **, gate and commit.** Message `feat: an agent's content is read by turn and by call`.

---

### Task 7: Following a story

**Files:**
- Create: `nessy-api/.../Following.java`
- Create: `nessy-engine/.../story/StoryHub.java`, `nessy-engine/src/test/.../story/FollowingTest.java`
- Modify: `AgentStory.java`, `EventAgentStories.java` (constructor gains the hubs), `DefaultDirectHarnessFactory.java`, `DefaultQueuedHarnessFactory.java`, `AgentStoriesAutoConfiguration.java`

**Interfaces:**
- Produces:

```java
public interface Following extends AutoCloseable { @Override void close(); }
// on AgentStory:
Following follow(Seq after, NarrationListener listener);
```

  `StoryHub implements NarrationListener` (engine-internal): each factory registers one as an engine-wide listener and exposes it; `EventAgentStories` is given the hubs of the doors it reads.

  `follow` does, in order: subscribe to the hubs **held back**, so everything narrated for this agent queues; replay everything stored after `after`, delivering each to the listener and remembering the last `seq`; release the queue, dropping any story event whose `seq` is at or before the last replayed and delivering the rest in the order they arrived; from then on deliver as it arrives. A listener that throws is logged at WARN and the delivery goes on, as `Listeners` does today.

- [ ] **Step 1: Write the failing tests:**
  - `everything_after_a_position_is_heard_and_then_what_happens_next`
  - `an_event_committed_while_the_replay_runs_is_heard_once` (Review Focus 2): the listener, on its first replayed event, makes the test thread run a second turn to completion on the same agent; assert every story event of both turns is heard exactly once, in `seq` order
  - `a_listener_that_throws_during_the_replay_still_hears_the_rest` (Review Focus 1)
  - `live_signals_held_during_the_replay_arrive_after_it`
  - `a_closed_following_hears_nothing_more`
- [ ] **Step 2: Implement** `StoryHub` (subscriptions keyed by agent type and id, a per-subscription queue and a held flag, both guarded by the subscription's own lock) and `follow`.
- [ ] **Step 3: Docs** (a "Following" section: what it is for, and that the live part is this process's narration) **, gate and commit.** Message `feat: a story can be followed from a position, replay first and then live`.

---

## After the last task

The final whole-branch review (Opus) is given the spec, this plan and the branch diff, and is asked to confirm three things by name: that no file listed in the first Global Constraint changed; that the only new public names are those in spec §12; and that `EventUsageReportsTest` and the pre-existing narration tests changed only where a constructor's shape changed.

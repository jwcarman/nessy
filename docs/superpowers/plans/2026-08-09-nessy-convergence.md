# Nessy Convergence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bring the implemented codebase into line with spec v2 — zones, renames, the event hub, grammar completion, `TerminationPolicy`, Micrometer Observation, the `Agent` facade, and JPMS — so every later plan builds on the converged shape.

**Architecture:** The v1 engine (pure reducer + effectful `InProcessEngine`) is preserved byte-for-byte in behavior. This plan restructures packaging into `api`/`spi`/`internal` zones, replaces per-object listeners with a synchronous event hub, completes the sealed grammar before it freezes, generalizes the hard-coded error ceiling into `TerminationPolicy`, instruments engine phases with Micrometer Observation, and puts a thin conversation facade in front of the engine.

**Tech Stack:** Java 25, Maven multi-module, Jackson, victools, `micrometer-observation` (+ `-test`), JUnit Jupiter, AssertJ.

**Source spec:** `docs/superpowers/specs/2026-08-09-nessy-agent-harness-design-v2.md`

**Starting state:** 93 tests green (`./mvnw -q clean verify`, no key, no network), packages `org.jwcarman.nessy.{core,tool,model,approval,session,engine}` per v1.

## Global Constraints

- **Java 25.** `<maven.compiler.release>25</maven.compiler.release>`.
- **groupId `org.jwcarman.nessy`**, version `0.1.0-SNAPSHOT`. Target packages are the spec v2 zones: root, `api`, `api.tool`, `api.approval`, `api.event`, `spi`, `spi.model`, `spi.session`, `internal`.
- **`nessy-core` dependencies:** jackson-databind, jackson-annotations, victools jsonschema-generator + jsonschema-module-jackson, **micrometer-observation** (Task 1 adds it), and micrometer-observation-test at test scope. Nothing else. No reactive types anywhere.
- **No star imports** (including static). **No inline fully-qualified class names.** **No `@SuppressWarnings` of any kind** — fix generics instead; `Class.cast` exists for exactly this.
- **Apache 2.0 header on every `.java` file:** `./mvnw license:format -Plicense` (never hand-written).
- **Spotless (Google Java Format) enforced at `validate`:** run `./mvnw spotless:apply` before every commit. 2-space indentation replacing this plan's 4-space samples is required, not a deviation.
- **Sealed-switch etiquette:** core switches over sealed types are exhaustive with NO `default` arm.
- **Live tests** stay excluded via the `nessy.excludedGroups` property; `./mvnw verify` must stay green with no API key and no model-provider network access.
- **Behavior-pinning assertions must never be weakened.** Mechanical updates to existing tests (imports, renamed methods, new constructor arguments, package moves) are expected and required by this plan; changing what a test *asserts* is not.
- **Tests read as prose.** Method names are `snake_case` sentences (`a_denial_counts_toward_the_error_ceiling`); related scenarios group into `@Nested` inner classes named as capitalized phrases (`Max_turns`, `Of_text`); the underscore→space display-name generator is configured module-wide via `src/test/resources/junit-platform.properties` (created in Task 1), so no per-class annotation is needed. Every test written by this plan uses this style; Task 11 converges the pre-existing suite.
- **Validation convention** (carried from the v1 fix wave): records crossing a seam `Objects.requireNonNull` their non-optional components; `SessionState.failureReason` is the documented nullable exception.
- Commit after every task.

---

## File Structure

The end state is spec v2 §4.2 exactly. This plan reaches it in two mechanical passes (moves, then renames) followed by seven feature/finish passes. New files created by this plan:

| File (under `nessy-core/src/main/java/org/jwcarman/nessy/`) | Responsibility | Task |
|---|---|---|
| `api/event/EventEmitter.java` | emit-only face of the hub | 4 |
| `api/event/EventHub.java` | subscribe + emit; `synchronous()` factory | 4 |
| `api/event/Subscription.java` | AutoCloseable unsubscribe handle | 4 |
| `api/event/SynchronousEventHub.java` (package-private) | default hub | 4 |
| `api/event/SessionEvent.java` | envelope for re-published loop events | 4 |
| `api/event/ToolProgress.java` | standard progress event for tools | 4 |
| `api/ThinkingBlock.java`, `api/RedactedThinkingBlock.java`, `api/ImageBlock.java` | grammar completion | 5 |
| `api/Usage.java` | token accounting | 5 |
| `api/TerminationPolicy.java` | halt rules + factories | 6 |
| `Nessy.java`, `AgentBuilder.java`, `Agent.java`, `Conversation.java`, `Reply.java` (root) | the front door | 8 |
| `module-info.java` | JPMS zoning enforcement | 9 |

In `nessy-testing`: `RecordingSubscriber.java` (Task 4, replacing `RecordingEventListener`).

---

### Task 1: Micrometer dependencies

**Files:**
- Modify: `pom.xml` (parent — property, BOM import)
- Modify: `nessy-core/pom.xml` (dependencies)
- Create: `nessy-core/src/test/resources/junit-platform.properties`, `nessy-testing/src/test/resources/junit-platform.properties`
- Test: `nessy-core/src/test/java/org/jwcarman/nessy/internal/ObservationDependencyTest.java` (temporary location is fine pre-restructure: use package `org.jwcarman.nessy.internal`, directory to match)

**Interfaces:**
- Consumes: nothing.
- Produces: `io.micrometer.observation.ObservationRegistry` and `io.micrometer.observation.tck.TestObservationRegistry` resolvable from `nessy-core` code and tests. Tasks 7 and 8 rely on both.

- [ ] **Step 1: Write the failing test**

```java
package org.jwcarman.nessy.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistry;
import org.junit.jupiter.api.Test;

class ObservationDependencyTest {

  @Test
  void observation_api_is_on_the_classpath_and_noop_by_default() {
    ObservationRegistry noop = ObservationRegistry.NOOP;

    assertThat(noop.isNoop()).isTrue();
    assertThat(TestObservationRegistry.create()).isNotNull();
  }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw -q -pl nessy-core test`
Expected: FAIL — compilation error, `package io.micrometer.observation does not exist`.

- [ ] **Step 3: Add the dependency management and dependencies**

In the parent `pom.xml` properties:

```xml
<micrometer.version>1.16.2</micrometer.version>
```

(If `1.16.2` does not resolve, use the newest available 1.x release and record the chosen version in your report.)

In the parent's `<dependencyManagement>`, import the BOM:

```xml
<dependency>
  <groupId>io.micrometer</groupId>
  <artifactId>micrometer-bom</artifactId>
  <version>${micrometer.version}</version>
  <type>pom</type>
  <scope>import</scope>
</dependency>
```

In `nessy-core/pom.xml`:

```xml
<dependency>
  <groupId>io.micrometer</groupId>
  <artifactId>micrometer-observation</artifactId>
</dependency>
<dependency>
  <groupId>io.micrometer</groupId>
  <artifactId>micrometer-observation-test</artifactId>
  <scope>test</scope>
</dependency>
```

- [ ] **Step 4: Configure prose display names module-wide**

Create `nessy-core/src/test/resources/junit-platform.properties` and
`nessy-testing/src/test/resources/junit-platform.properties`, both containing exactly:

```properties
junit.jupiter.displayname.generator.default=org.junit.jupiter.api.DisplayNameGenerator$ReplaceUnderscores
```

This makes `snake_case` method names render as sentences in every report without
per-class annotations. (The license plugin already excludes `src/test/resources`.)

- [ ] **Step 5: Run the test to verify it passes**

Run: `./mvnw -q -pl nessy-core test`
Expected: PASS, all prior tests still green, and the new test's surefire display name reads
`observation api is on the classpath and noop by default`.

- [ ] **Step 6: Format, license, commit**

```bash
./mvnw license:format -Plicense && ./mvnw spotless:apply
git add pom.xml nessy-core/pom.xml nessy-core/src/test nessy-testing/src/test
git commit -m "build: add micrometer-observation and prose test display names"
```

---

### Task 2: Zone restructure (moves only — zero renames, zero behavior change)

**Files:** every `.java` file in `nessy-core` and `nessy-testing` moves per the mapping below; tests mirror. No class, method, or field is renamed in this task.

**Interfaces:**
- Consumes: the v1 layout.
- Produces: the spec v2 package layout with v1 names intact. Every later task assumes these locations.

- [ ] **Step 1: Apply the move mapping with `git mv`, then fix `package` and `import` statements**

| From `org.jwcarman.nessy…` | To `org.jwcarman.nessy…` |
|---|---|
| `.core.` — `SessionId`, `Role`, `ContentBlock`, `TextBlock`, `ToolUseBlock`, `ToolResultBlock`, `Message`, `ToolCall`, `ToolResult`, `StopReason`, `Decision`, `SessionStatus`, `SessionState`, `Event`, `Awaited`, `ParkToken` | `.api.` |
| `.core.` — `Reducer`, `Effect`, `Step` | `.spi.` |
| `.engine.` — `ExecutionEngine`, `InProcessEngine` | `.spi.` |
| `.engine.RunOutcome` | `.api.` |
| `.engine.AgentConfig` | `.spi.model.` (renamed in Task 3) |
| `.engine.AgentEventListener` | `.api.event.` (deleted in Task 4) |
| `.engine.Nessy` | root `org.jwcarman.nessy` |
| `.tool.` — `Tool`, `ToolSpec`, `ToolContext`, `ToolRegistry`, `MapToolRegistry` | `.api.tool.` |
| `.tool.` — `ToolInvoker`, `Schemas` | `.internal.` |
| `.approval.` — all | `.api.approval.` |
| `.model.` — all | `.spi.model.` |
| `.session.` — all | `.spi.session.` |

Test sources move to the mirrored packages (`ReducerTextTest` and siblings → `spi`; `MessageTest`, `SessionStateTest`, `EventTest`, `AwaitedTest`, `ValidationTest` → `api`; `ToolRegistryTest`, `SchemasTest` → `api.tool` and `internal` respectively per their subject's new home; `InProcessEngineTest` → `spi`; `ApproverTest` → `api.approval`; `InMemorySessionStoreTest` → `spi.session`; `ModelRequestTest` → `spi.model`; `BuildSmokeTest` and `ObservationDependencyTest` stay where their packages already are). `nessy-testing` packages are unchanged; only its imports update.

- [ ] **Step 2: Verify nothing changed but location**

Run: `./mvnw -q clean verify`
Expected: PASS with the same test count as before this task.

Run: `grep -rn "org\.jwcarman\.nessy\.core\|org\.jwcarman\.nessy\.engine\b" nessy-core/src nessy-testing/src`
Expected: no matches.

- [ ] **Step 3: Format, license, commit**

```bash
./mvnw license:format -Plicense && ./mvnw spotless:apply && ./mvnw -q clean verify
git add -A
git commit -m "refactor: restructure into api/spi/internal zones (moves only)"
```

---

### Task 3: The rename ledger

**Files:**
- Modify: `org/jwcarman/nessy/Nessy.java` (builder method renames)
- Rename: `spi/model/AgentConfig.java` → `spi/model/ModelSettings.java`
- Modify: `api/tool/ToolRegistry.java` (+`of` factory), `api/tool/MapToolRegistry.java` → package-private `api/tool/DefaultToolRegistry.java`
- Modify: `api/approval/Approver.java` (+factories); `ApproveEverything`/`DenyEverything` → package-private `AllowAllApprover`/`DenyAllApprover`
- Modify: `spi/session/SessionStore.java` (+`inMemory` factory); `InMemorySessionStore` → package-private
- Modify: every caller and test of the above (mechanical)

**Interfaces:**
- Consumes: Task 2's layout.
- Produces (relied on by Tasks 4–8): `Nessy` builder methods `provider(ModelProvider)` and `model(String)` (replacing `model(ModelProvider)`/`modelName(String)`); `ModelSettings(String model, String systemPrompt, int maxTokens, Set<Capability> capabilities)`; `static ToolRegistry of(Tool<?>... tools)` on `ToolRegistry`; `static Approver allowAll()` and `static Approver denyAll(String reason)` on `Approver`; `static SessionStore inMemory()` on `SessionStore`.

- [ ] **Step 1: Write the failing test**

Add to `nessy-core/src/test/java/org/jwcarman/nessy/api/tool/ToolRegistryTest.java` (adjusting its existing helper tools):

```java
@Test
void the_interface_is_the_front_door_to_its_default() {
  ToolRegistry registry = ToolRegistry.of(new GreetTool());

  assertThat(registry.find("greet")).isPresent();
}
```

Add a new `nessy-core/src/test/java/org/jwcarman/nessy/api/approval/ApproverFactoriesTest.java`:

```java
package org.jwcarman.nessy.api.approval;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.Decision;
import org.jwcarman.nessy.api.SessionId;
import org.jwcarman.nessy.api.ToolCall;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ApproverFactoriesTest {

  private final ApprovalRequest request =
      new ApprovalRequest(
          new SessionId("s1"),
          new ToolCall("c1", "anything", JsonNodeFactory.instance.objectNode()),
          "anything()");

  @Nested
  class Allow_all {

    @Test
    void allows() {
      assertThat(Approver.allowAll().approve(request)).isEqualTo(Awaited.ready(Decision.allow()));
    }
  }

  @Nested
  class Deny_all {

    @Test
    void denies_with_its_reason() {
      assertThat(Approver.denyAll("read-only").approve(request))
          .isEqualTo(Awaited.ready(new Decision.Deny("read-only")));
    }
  }
}
```

Add to `nessy-core/src/test/java/org/jwcarman/nessy/spi/session/InMemorySessionStoreTest.java`:

```java
@Test
void in_memory_factory_returns_a_working_store() {
  SessionStore store = SessionStore.inMemory();
  store.save(SessionState.newSession(id));

  assertThat(store.load(id)).isPresent();
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `./mvnw -q -pl nessy-core test`
Expected: FAIL — `cannot find symbol: method of`, `method allowAll`, `method inMemory`.

- [ ] **Step 3: Implement the renames**

1. `ToolRegistry` gains `static ToolRegistry of(Tool<?>... tools) { return DefaultToolRegistry.of(tools); }`. Rename `MapToolRegistry` → `DefaultToolRegistry`, make the class package-private, keep its duplicate-name rejection and precomputed, registration-ordered `specs()` exactly as they are (both are pinned by tests).
2. `Approver` gains
   ```java
   static Approver allowAll() {
     return AllowAllApprover.INSTANCE;
   }

   static Approver denyAll(String reason) {
     return new DenyAllApprover(reason);
   }
   ```
   Rename `ApproveEverything` → package-private `AllowAllApprover` (an enum-singleton or a final class with a static `INSTANCE`), `DenyEverything` → package-private `DenyAllApprover`. Delete the old test `ApproverTest` only if `ApproverFactoriesTest` fully covers its two assertions (it does — same behavior through the factories); otherwise fold them together.
3. `SessionStore` gains `static SessionStore inMemory() { return new InMemorySessionStore(); }`; `InMemorySessionStore` becomes package-private. Its javadoc (last-write-wins, non-evicting tokens) moves onto the factory where readers will actually find it.
4. Rename `AgentConfig` → `ModelSettings` (same components: `model, systemPrompt, maxTokens, capabilities`).
5. In `Nessy.Builder`: `model(ModelProvider)` → `provider(ModelProvider)`; `modelName(String)` → `model(String)`. Error messages update to name the new setters.
6. Update every caller: `InProcessEngine`, `EndToEndTest`, `InProcessEngineTest`, and any other references. Assertions stay identical.

- [ ] **Step 4: Verify, audit, commit**

Run: `./mvnw -q clean verify` — PASS, prior count + 4 new tests.
Run: `grep -rn "MapToolRegistry\|ApproveEverything\|DenyEverything\|AgentConfig\|modelName(" nessy-core/src nessy-testing/src` — no matches.

```bash
./mvnw license:format -Plicense && ./mvnw spotless:apply && ./mvnw -q clean verify
git add -A
git commit -m "refactor: apply the spec v2 rename ledger"
```

---

### Task 4: The event hub

**Files:**
- Create: `api/event/EventEmitter.java`, `api/event/EventHub.java`, `api/event/Subscription.java`, `api/event/SynchronousEventHub.java` (package-private), `api/event/SessionEvent.java`, `api/event/ToolProgress.java`
- Delete: `api/event/AgentEventListener.java`
- Modify: `spi/InProcessEngine.java` (hub instead of listener list; `ToolContext` construction), `api/tool/ToolContext.java` (+`events`), `internal/ToolInvoker.java` (unchanged signatures, context passthrough), `org/jwcarman/nessy/Nessy.java` (builder `.events(EventHub)`)
- Create: `nessy-testing/src/main/java/org/jwcarman/nessy/testing/RecordingSubscriber.java`; delete `RecordingEventListener.java`
- Test: `nessy-core/src/test/java/org/jwcarman/nessy/api/event/EventHubTest.java`; modify `InProcessEngineTest`, `EndToEndTest`

**Interfaces:**
- Consumes: Task 3's builder.
- Produces (relied on by Tasks 7–8):
  - `EventEmitter.emit(Object event)`
  - `EventHub extends EventEmitter` with `<E> Subscription subscribe(Class<E> type, Consumer<E> subscriber)` and `static EventHub synchronous()`
  - `Subscription extends AutoCloseable` with `void close()` (idempotent, no checked exception)
  - `SessionEvent(SessionId sessionId, Event event, SessionState state)`
  - `ToolProgress(SessionId sessionId, String toolCallId, String message)`
  - `ToolContext(SessionId sessionId, EventEmitter events)`
  - `RecordingSubscriber` with `attachTo(EventHub)`, `all()`, `<E> ofType(Class<E>)`

- [ ] **Step 1: Write the failing hub test**

`nessy-core/src/test/java/org/jwcarman/nessy/api/event/EventHubTest.java`:

```java
package org.jwcarman.nessy.api.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class EventHubTest {

  record Ping(String name) {}

  record Pong(String name) {}

  private final EventHub hub = EventHub.synchronous();

  @Nested
  class Delivery {

    @Test
    void reaches_only_matching_types() {
      List<Ping> pings = new ArrayList<>();
      hub.subscribe(Ping.class, pings::add);

      hub.emit(new Ping("a"));
      hub.emit(new Pong("ignored"));

      assertThat(pings).containsExactly(new Ping("a"));
    }

    @Test
    void reaches_supertype_subscribers_for_every_subtype() {
      List<Object> everything = new ArrayList<>();
      hub.subscribe(Object.class, everything::add);

      hub.emit(new Ping("a"));
      hub.emit(new Pong("b"));

      assertThat(everything).containsExactly(new Ping("a"), new Pong("b"));
    }

    @Test
    void is_synchronous_and_in_subscription_order() {
      List<String> order = new ArrayList<>();
      hub.subscribe(Ping.class, p -> order.add("first"));
      hub.subscribe(Ping.class, p -> order.add("second"));

      hub.emit(new Ping("a"));

      assertThat(order).containsExactly("first", "second");
    }

    @Test
    void survives_a_throwing_subscriber() {
      List<Ping> pings = new ArrayList<>();
      hub.subscribe(
          Ping.class,
          p -> {
            throw new IllegalStateException("observer bug");
          });
      hub.subscribe(Ping.class, pings::add);

      hub.emit(new Ping("a"));

      assertThat(pings).containsExactly(new Ping("a"));
    }
  }

  @Nested
  class Subscriptions {

    @Test
    void closing_stops_delivery_and_is_idempotent() {
      List<Ping> pings = new ArrayList<>();
      Subscription subscription = hub.subscribe(Ping.class, pings::add);

      subscription.close();
      subscription.close();
      hub.emit(new Ping("a"));

      assertThat(pings).isEmpty();
    }
  }
}
```

- [ ] **Step 2: Run to verify it fails** — `./mvnw -q -pl nessy-core test`, compilation error `cannot find symbol: class EventHub`.

- [ ] **Step 3: Implement the hub**

`EventEmitter.java`:

```java
package org.jwcarman.nessy.api.event;

/** The emit-only face of the hub. Anything holding one may announce; nothing more. */
public interface EventEmitter {

  void emit(Object event);
}
```

`Subscription.java`:

```java
package org.jwcarman.nessy.api.event;

/** An open subscription. Closing it stops delivery; closing twice is harmless. */
public interface Subscription extends AutoCloseable {

  @Override
  void close();
}
```

`EventHub.java`:

```java
package org.jwcarman.nessy.api.event;

import java.util.function.Consumer;

/**
 * Where runtime narrative flows.
 *
 * <p>Three commitments, all load-bearing. Delivery is synchronous, in subscription order, on the
 * emitting thread — live streaming and deterministic tests depend on it; asynchronous delivery is
 * a decorator's job. The hub is exhaust, never intake: no return values, no vetoes, and input
 * reaches the reducer only through the engine. Subscriber exceptions are contained here, so no
 * observer can alter or abort execution.
 *
 * <p>The vocabulary is open on purpose: any module may publish its own event records, and
 * subscribers select by type. The reducer's sealed {@code Event} grammar stays closed; the hub
 * re-publishes loop activity wrapped in {@link SessionEvent} and never feeds the loop.
 */
public interface EventHub extends EventEmitter {

  <E> Subscription subscribe(Class<E> type, Consumer<E> subscriber);

  /** The default: dispatches on the emitting thread, in subscription order. */
  static EventHub synchronous() {
    return new SynchronousEventHub();
  }
}
```

`SynchronousEventHub.java` (package-private; note `Class.cast` keeps this free of unchecked casts and therefore free of suppressions):

```java
package org.jwcarman.nessy.api.event;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

final class SynchronousEventHub implements EventHub {

  private final List<Registration<?>> registrations = new CopyOnWriteArrayList<>();

  @Override
  public void emit(Object event) {
    for (Registration<?> registration : registrations) {
      registration.deliver(event);
    }
  }

  @Override
  public <E> Subscription subscribe(Class<E> type, Consumer<E> subscriber) {
    Registration<E> registration = new Registration<>(type, subscriber);
    registrations.add(registration);
    return () -> registrations.remove(registration);
  }

  private record Registration<E>(Class<E> type, Consumer<E> subscriber) {

    void deliver(Object event) {
      if (!type.isInstance(event)) {
        return;
      }
      try {
        subscriber.accept(type.cast(event));
      } catch (RuntimeException e) {
        // Observers must never alter execution. A failure during failure
        // reporting would recurse, so a broken subscriber is simply skipped.
      }
    }
  }
}
```

`SessionEvent.java`:

```java
package org.jwcarman.nessy.api.event;

import java.util.Objects;
import org.jwcarman.nessy.api.Event;
import org.jwcarman.nessy.api.SessionId;
import org.jwcarman.nessy.api.SessionState;

/** One reduced loop event, re-published for observers. Exhaust, never intake. */
public record SessionEvent(SessionId sessionId, Event event, SessionState state) {

  public SessionEvent {
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(event, "event");
    Objects.requireNonNull(state, "state");
  }
}
```

`ToolProgress.java`:

```java
package org.jwcarman.nessy.api.event;

import java.util.Objects;
import org.jwcarman.nessy.api.SessionId;

/** A long-running tool reporting from inside its own execution. */
public record ToolProgress(SessionId sessionId, String toolCallId, String message) {

  public ToolProgress {
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(toolCallId, "toolCallId");
    Objects.requireNonNull(message, "message");
  }
}
```

- [ ] **Step 4: Thread the hub through the engine**

1. `ToolContext` becomes `record ToolContext(SessionId sessionId, EventEmitter events)` (both `requireNonNull`).
2. `InProcessEngine`: the `List<AgentEventListener> listeners` field and constructor parameter become `EventHub hub` (`requireNonNull`). `reduceAndNotify` replaces the listener loop with `hub.emit(new SessionEvent(step.state().id(), event, step.state()))`. `executeTool` constructs `new ToolContext(state.id(), hub)`.
3. Delete `AgentEventListener.java`.
4. `Nessy.Builder`: replace `listener(…)` and the listeners list with `private EventHub events = EventHub.synchronous();` and `public Builder events(EventHub events)`. Pass the hub to the engine.
5. `nessy-testing`: delete `RecordingEventListener`, create `RecordingSubscriber`:

```java
package org.jwcarman.nessy.testing;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.jwcarman.nessy.api.event.EventHub;
import org.jwcarman.nessy.api.event.Subscription;

/** Captures everything a hub emits, so tests can assert on it. */
public final class RecordingSubscriber implements Consumer<Object> {

  private final List<Object> received = new CopyOnWriteArrayList<>();

  /** Subscribes to every event on the hub. */
  public Subscription attachTo(EventHub hub) {
    return hub.subscribe(Object.class, this);
  }

  @Override
  public void accept(Object event) {
    received.add(event);
  }

  public List<Object> all() {
    return Collections.unmodifiableList(received);
  }

  public <E> List<E> ofType(Class<E> type) {
    return received.stream().filter(type::isInstance).map(type::cast).toList();
  }
}
```

6. Update `InProcessEngineTest`'s `RecordingListener` and `EndToEndTest`'s listener usage: build with a hub (`EventHub hub = EventHub.synchronous()`, pass via constructor/builder), attach a recording consumer, and assert on `ofType(SessionEvent.class)` mapped to `SessionEvent::event` — the *event sequences asserted must remain identical* to the old listener assertions.

- [ ] **Step 5: Add the tool-progress engine test**

In `InProcessEngineTest`, add a tool that emits progress and assert a subscriber sees it:

```java
@Test
void tools_can_report_progress_through_the_hub() {
  FakeProvider provider =
      new FakeProvider(
          List.of(
              List.of(
                  new ModelEvent.ToolUseEmitted(new ToolCall("c1", "noisy", echoArgs("hi"))),
                  new ModelEvent.TurnEnded(StopReason.TOOL_USE)),
              List.of(
                  new ModelEvent.TextChunk("Done."),
                  new ModelEvent.TurnEnded(StopReason.END_TURN))));
  EventHub hub = EventHub.synchronous();
  List<ToolProgress> progress = new ArrayList<>();
  hub.subscribe(ToolProgress.class, progress::add);

  Tool<Echo> noisy =
      new Tool<>() {
        @Override
        public String name() {
          return "noisy";
        }

        @Override
        public String description() {
          return "Reports progress";
        }

        @Override
        public Class<Echo> inputType() {
          return Echo.class;
        }

        @Override
        public boolean requiresApproval() {
          return false;
        }

        @Override
        public Awaited<ToolResult> execute(Echo input, ToolContext context) {
          context.events().emit(new ToolProgress(context.sessionId(), "c1", "halfway"));
          return Awaited.ready(ToolResult.ok("done"));
        }
      };

  engineWith(provider, ToolRegistry.of(noisy), Approver.allowAll(), SessionStore.inMemory(), hub)
      .run(ID, Event.UserSaid.of("go"));

  assertThat(progress).containsExactly(new ToolProgress(ID, "c1", "halfway"));
}
```

(`engineWith` is the test's existing engine helper extended with the hub parameter. `Event.UserSaid.of` arrives in Task 5 — until then use `new Event.UserSaid("go")` and let Task 5's mechanical sweep update it, or land this test after Step 6 verification with the current constructor. Note this ordering in your report.)

- [ ] **Step 6: Verify, commit**

Run: `./mvnw -q clean verify` — PASS.
Run: `grep -rn "AgentEventListener\|RecordingEventListener" nessy-core/src nessy-testing/src` — no matches.

```bash
./mvnw license:format -Plicense && ./mvnw spotless:apply && ./mvnw -q clean verify
git add -A
git commit -m "feat: replace listeners with the event hub"
```

---

### Task 5: Grammar completion

**Files:**
- Create: `api/ThinkingBlock.java`, `api/RedactedThinkingBlock.java`, `api/ImageBlock.java`, `api/Usage.java`
- Modify: `api/ContentBlock.java` (permits), `api/Event.java` (`ThinkingDelta`; `UserSaid` canonicalized), `api/SessionState.java` (`turns`, `usage`, `failureReason` + withers), `spi/model/ModelEvent.java` (`ThinkingChunk`; `TurnEnded` gains `Usage`), `spi/Reducer.java` (thinking merge, turn/usage accounting, failure reasons), `spi/InProcessEngine.java` (`translate` arm), `nessy-testing/…/ScriptedModelProvider.java` (usage overload)
- Test: `nessy-core/src/test/java/org/jwcarman/nessy/spi/ReducerGrammarTest.java` (new); mechanical updates wherever `TurnEnded`/`UserSaid` are constructed

**Interfaces:**
- Consumes: Tasks 2–4.
- Produces (relied on by Tasks 6–8):
  - `ThinkingBlock(String text, String signature)`, `RedactedThinkingBlock(String data)`, `ImageBlock(String mediaType, String base64Data)` — all `implements ContentBlock`, all components `requireNonNull` (empty strings permitted for `signature`)
  - `Usage(long inputTokens, long outputTokens)` with `Usage.zero()`, `usage.plus(Usage)`, negative components rejected
  - `ModelEvent.ThinkingChunk(String text)`; `ModelEvent.TurnEnded(StopReason reason, Usage usage)`
  - `Event.ThinkingDelta(String text)`; `Event.UserSaid(List<ContentBlock> content)` with `static UserSaid of(String text)`
  - `SessionState` components in order: `id, messages, pendingBlocks, pendingCalls, pendingResults, consecutiveErrors, turns, usage, failureReason, status`; withers `withTurns(int)`, `withUsage(Usage)`, `withFailureReason(String)`; `failureReason` is the documented nullable exception to the validation convention
  - `ScriptedModelProvider.Builder.endTurn(Usage usage)` overload; the no-arg `endTurn()`/`endWithToolUse()` use `Usage.zero()`

- [ ] **Step 1: Write the failing tests**

`ReducerGrammarTest.java`:

```java
package org.jwcarman.nessy.spi;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.jwcarman.nessy.api.Event;
import org.jwcarman.nessy.api.Message;
import org.jwcarman.nessy.api.SessionId;
import org.jwcarman.nessy.api.SessionState;
import org.jwcarman.nessy.api.SessionStatus;
import org.jwcarman.nessy.api.StopReason;
import org.jwcarman.nessy.api.TextBlock;
import org.jwcarman.nessy.api.ThinkingBlock;
import org.jwcarman.nessy.api.Usage;
import org.junit.jupiter.api.Test;

class ReducerGrammarTest {

  private final Reducer reducer = Reducer.withDefaults();
  private final SessionState initial = SessionState.newSession(new SessionId("s1"));

  @Test
  void thinking_deltas_accumulate_into_a_single_thinking_block() {
    SessionState state = reducer.reduce(initial, Event.UserSaid.of("hi")).state();
    state = reducer.reduce(state, new Event.ThinkingDelta("Let me ")).state();
    state = reducer.reduce(state, new Event.ThinkingDelta("think.")).state();
    state = reducer.reduce(state, new Event.TextDelta("Answer.")).state();

    assertThat(state.pendingBlocks())
        .containsExactly(new ThinkingBlock("Let me think.", ""), new TextBlock("Answer."));
  }

  @Test
  void turns_and_usage_accumulate_across_turn_ends() {
    SessionState state = reducer.reduce(initial, Event.UserSaid.of("hi")).state();
    state =
        reducer
            .reduce(state, new Event.ModelTurnEnded(StopReason.END_TURN, new Usage(100, 50)))
            .state();

    assertThat(state.turns()).isEqualTo(1);
    assertThat(state.usage()).isEqualTo(new Usage(100, 50));
  }

  @Test
  void token_ceiling_failure_records_its_reason() {
    SessionState state = reducer.reduce(initial, Event.UserSaid.of("hi")).state();
    state =
        reducer
            .reduce(state, new Event.ModelTurnEnded(StopReason.MAX_TOKENS, Usage.zero()))
            .state();

    assertThat(state.status()).isEqualTo(SessionStatus.FAILED);
    assertThat(state.failureReason()).contains("MAX_TOKENS");
  }

  @Test
  void user_said_carries_arbitrary_content_blocks() {
    Step step =
        reducer.reduce(
            initial, new Event.UserSaid(List.of(new TextBlock("describe this"))));

    assertThat(step.state().messages())
        .containsExactly(new Message(org.jwcarman.nessy.api.Role.USER, List.of(new TextBlock("describe this"))));
  }

  @Test
  void usage_addition_is_componentwise() {
    assertThat(Usage.zero().plus(new Usage(3, 4)).plus(new Usage(10, 20)))
        .isEqualTo(new Usage(13, 24));
  }
}
```

(Fix the inline-qualified `Role` with a proper import when transcribing — house rule.)

- [ ] **Step 2: Run to verify failure** — compilation errors on the new types.

- [ ] **Step 3: Implement**

New records (each with its Apache header via the plugin, `requireNonNull` components):

```java
public record ThinkingBlock(String text, String signature) implements ContentBlock { … }
public record RedactedThinkingBlock(String data) implements ContentBlock { … }
public record ImageBlock(String mediaType, String base64Data) implements ContentBlock { … }

public record Usage(long inputTokens, long outputTokens) {
  public Usage {
    if (inputTokens < 0 || outputTokens < 0) {
      throw new IllegalArgumentException("token counts must be non-negative");
    }
  }

  public static Usage zero() {
    return new Usage(0, 0);
  }

  public Usage plus(Usage other) {
    return new Usage(inputTokens + other.inputTokens, outputTokens + other.outputTokens);
  }
}
```

`ContentBlock` permits all six. `Event` gains `ThinkingDelta(String text)`; `UserSaid` becomes:

```java
record UserSaid(List<ContentBlock> content) implements Event {
  public UserSaid {
    content = List.copyOf(content);
  }

  public static UserSaid of(String text) {
    return new UserSaid(List.of(new TextBlock(text)));
  }
}
```

`ModelEvent` gains `ThinkingChunk(String text)`; `TurnEnded` becomes `TurnEnded(StopReason reason, Usage usage)` (`requireNonNull` both).

`SessionState` gains the three components in the order given above; `newSession` starts `0, Usage.zero(), null`; the compact constructor `requireNonNull`s everything except `failureReason`, whose javadoc states the nullable exception. New withers follow the existing pattern.

`Reducer`:
- `userSaid` builds `new Message(Role.USER, event.content())`.
- new `thinkingDelta` handler mirrors `textDelta`, merging into a trailing `ThinkingBlock` (`new ThinkingBlock(last.text() + delta, last.signature())`), else appending `new ThinkingBlock(event.text(), "")`.
- `modelTurnEnded` first accounts: `state = state.withTurns(state.turns() + 1).withUsage(state.usage().plus(event.usage()))`, then branches as today; the `MAX_TOKENS` branch adds `.withFailureReason("model hit the token ceiling (MAX_TOKENS)")`, and the consecutive-error ceiling branch in `toolFinished` adds `.withFailureReason(state.consecutiveErrors() + " consecutive tool errors")` (exact current ceiling logic otherwise unchanged — Task 6 replaces it).
- The `Event` switch gains the `ThinkingDelta` arm; no `default` anywhere.

`InProcessEngine.translate` gains `case ModelEvent.ThinkingChunk chunk -> new Event.ThinkingDelta(chunk.text());`.

`ScriptedModelProvider.Builder`: `endTurn()`/`endWithToolUse()` delegate to a new `end(StopReason, Usage)`; add `public Builder endTurn(Usage usage)`.

Mechanical sweep: every `new Event.UserSaid("…")` → `Event.UserSaid.of("…")`; every `new ModelEvent.TurnEnded(reason)` → `new ModelEvent.TurnEnded(reason, Usage.zero())`. Assertions unchanged.

- [ ] **Step 4: Verify, commit**

Run: `./mvnw -q clean verify` — PASS.

```bash
./mvnw license:format -Plicense && ./mvnw spotless:apply && ./mvnw -q clean verify
git add -A
git commit -m "feat: complete the pre-1.0 grammar (thinking, images, usage)"
```

---

### Task 6: TerminationPolicy

**Files:**
- Create: `api/TerminationPolicy.java`
- Modify: `spi/Reducer.java` (record component `TerminationPolicy termination`; policy consultation replacing the hard ceiling), `org/jwcarman/nessy/Nessy.java` (`.termination(…)` replacing `.maxConsecutiveErrors(…)`)
- Test: `nessy-core/src/test/java/org/jwcarman/nessy/api/TerminationPolicyTest.java` (new); `ReducerToolResultTest`, `ReducerTextTest` (mechanical constructor updates + new halt tests)

**Interfaces:**
- Consumes: Task 5's `SessionState.turns()`/`failureReason`.
- Produces (relied on by Task 8): `TerminationPolicy` with `Optional<String> shouldHalt(SessionState state)` and statics `maxTurns(int)`, `maxConsecutiveErrors(int)`, `anyOf(TerminationPolicy...)`, `never()`, `defaults()` (= `anyOf(maxConsecutiveErrors(3), maxTurns(100))`); `Reducer(TerminationPolicy termination)` with `Reducer.withDefaults()` using `TerminationPolicy.defaults()`. `Reducer.DEFAULT_MAX_CONSECUTIVE_ERRORS` is deleted.

- [ ] **Step 1: Write the failing tests**

`TerminationPolicyTest.java`:

```java
package org.jwcarman.nessy.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TerminationPolicyTest {

  private static SessionState stateWith(int turns, int consecutiveErrors) {
    return SessionState.newSession(new SessionId("s1"))
        .withTurns(turns)
        .withConsecutiveErrors(consecutiveErrors);
  }

  @Test
  void max_turns_halts_at_the_ceiling_and_not_below() {
    TerminationPolicy policy = TerminationPolicy.maxTurns(5);

    assertThat(policy.shouldHalt(stateWith(4, 0))).isEmpty();
    assertThat(policy.shouldHalt(stateWith(5, 0))).isPresent();
  }

  @Test
  void max_consecutive_errors_halts_at_the_ceiling_and_not_below() {
    TerminationPolicy policy = TerminationPolicy.maxConsecutiveErrors(2);

    assertThat(policy.shouldHalt(stateWith(0, 1))).isEmpty();
    assertThat(policy.shouldHalt(stateWith(0, 2))).isPresent();
  }

  @Test
  void any_of_reports_the_first_halting_policy() {
    TerminationPolicy policy =
        TerminationPolicy.anyOf(
            TerminationPolicy.maxConsecutiveErrors(2), TerminationPolicy.maxTurns(5));

    assertThat(policy.shouldHalt(stateWith(9, 0)).orElseThrow()).contains("turn");
    assertThat(policy.shouldHalt(stateWith(0, 9)).orElseThrow()).contains("consecutive");
  }

  @Test
  void never_never_halts() {
    assertThat(TerminationPolicy.never().shouldHalt(stateWith(1_000_000, 1_000_000))).isEmpty();
  }

  @Test
  void ceilings_below_one_are_rejected() {
    assertThatThrownBy(() -> TerminationPolicy.maxTurns(0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> TerminationPolicy.maxConsecutiveErrors(0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
```

Reducer-level halt tests (add to `ReducerToolResultTest` / `ReducerTextTest`):

```java
@Test
void a_fresh_user_message_on_a_turn_exhausted_session_halts_instead_of_calling_the_model() {
  Reducer limited = new Reducer(TerminationPolicy.maxTurns(1));
  SessionState exhausted = SessionState.newSession(new SessionId("s1")).withTurns(1);

  Step step = limited.reduce(exhausted, Event.UserSaid.of("more?"));

  assertThat(step.state().status()).isEqualTo(SessionStatus.FAILED);
  assertThat(step.state().failureReason()).contains("turn");
  assertThat(step.effects()).isEmpty();
}

@Test
void halting_mid_batch_still_answers_every_pending_tool_use() {
  Reducer limited = new Reducer(TerminationPolicy.maxConsecutiveErrors(1));
  ToolCall first = call("c1");
  ToolCall second = call("c2");
  SessionState state = awaitingApprovalWith(limited, first, second);
  state = limited.reduce(state, new Event.ApprovalDecided(first, Decision.allow())).state();

  Step step = limited.reduce(state, new Event.ToolFinished(first, ToolResult.error("boom")));

  assertThat(step.state().status()).isEqualTo(SessionStatus.FAILED);
  assertThat(step.state().failureReason()).contains("consecutive");
  assertThat(step.state().pendingCalls()).isEmpty();
  assertThat(step.state().messages().getLast().content())
      .extracting("toolUseId")
      .containsExactly("c1", "c2");
  assertThat(step.effects()).isEmpty();
}
```

(`awaitingApprovalWith` is the existing `awaitingApproval` helper parameterized by reducer; adapt it.)

- [ ] **Step 2: Run to verify failure** — `cannot find symbol: class TerminationPolicy`.

- [ ] **Step 3: Implement**

`TerminationPolicy.java`:

```java
package org.jwcarman.nessy.api;

import java.util.List;
import java.util.Optional;

/**
 * Decides when the loop must stop calling the model.
 *
 * <p>Pure and stateless: consulted by the reducer, never by the engine, so termination is
 * semantics — identical on every engine. The reducer consults it after applying any event that
 * could lead to another model call; a halt settles pending work (answering every outstanding
 * tool_use, preserving the transcript invariant), records the reason, and fails the session with
 * no effects.
 */
public interface TerminationPolicy {

  /** A human-readable reason to halt, or empty to continue. */
  Optional<String> shouldHalt(SessionState state);

  static TerminationPolicy maxTurns(int max) {
    requireAtLeastOne(max, "maxTurns");
    return state ->
        state.turns() >= max
            ? Optional.of("reached the turn ceiling (" + max + " turns)")
            : Optional.empty();
  }

  static TerminationPolicy maxConsecutiveErrors(int max) {
    requireAtLeastOne(max, "maxConsecutiveErrors");
    return state ->
        state.consecutiveErrors() >= max
            ? Optional.of(max + " consecutive tool errors")
            : Optional.empty();
  }

  static TerminationPolicy anyOf(TerminationPolicy... policies) {
    List<TerminationPolicy> all = List.of(policies);
    return state ->
        all.stream().map(p -> p.shouldHalt(state)).flatMap(Optional::stream).findFirst();
  }

  static TerminationPolicy never() {
    return state -> Optional.empty();
  }

  /** The wallet-guarding default: three consecutive errors or one hundred turns. */
  static TerminationPolicy defaults() {
    return anyOf(maxConsecutiveErrors(3), maxTurns(100));
  }

  private static void requireAtLeastOne(int max, String name) {
    if (max < 1) {
      throw new IllegalArgumentException(name + " must be at least 1");
    }
  }
}
```

`Reducer` becomes `record Reducer(TerminationPolicy termination)` (`requireNonNull`); `withDefaults()` returns `new Reducer(TerminationPolicy.defaults())`; delete `DEFAULT_MAX_CONSECUTIVE_ERRORS` and the `maxConsecutiveErrors` validation. Consultation points:

- `userSaid`: build the next state as today, then `termination.shouldHalt(next)` — on halt return `Step.of(next.withFailureReason(reason).with(SessionStatus.FAILED))` with no effects.
- `toolFinished`: after computing `next` (result recorded, errors updated, calls trimmed), check the policy **before** the existing branches. On halt: `Step.of(flushResults(abandonPendingCalls(next)).withFailureReason(reason).with(SessionStatus.FAILED))` — this subsumes the old ceiling branch exactly (delete it) and extends the same transcript-balancing to every policy.
- The `MAX_TOKENS` branch from Task 5 stays as-is (it is a model outcome, not a policy decision).

`Nessy.Builder`: `maxConsecutiveErrors(int)` is deleted; `termination(TerminationPolicy)` added, default `TerminationPolicy.defaults()`; `build()` constructs `new Reducer(termination)`. Mechanical sweep: `new Reducer(2)` in tests → `new Reducer(TerminationPolicy.maxConsecutiveErrors(2))`.

- [ ] **Step 4: Verify, commit**

Run: `./mvnw -q clean verify` — PASS, including all pre-existing ceiling/boundary tests (their assertions are unchanged — same ceilings expressed through the policy).
Run: `grep -rn "DEFAULT_MAX_CONSECUTIVE_ERRORS\|maxConsecutiveErrors(" nessy-core/src/main org/ 2>/dev/null; grep -rn "DEFAULT_MAX_CONSECUTIVE_ERRORS" nessy-core/src nessy-testing/src` — the constant is gone (the factory name legitimately remains).

```bash
./mvnw license:format -Plicense && ./mvnw spotless:apply && ./mvnw -q clean verify
git add -A
git commit -m "feat: generalize termination into TerminationPolicy"
```

---

### Task 7: Micrometer Observation instrumentation

**Files:**
- Modify: `spi/InProcessEngine.java` (constructor gains `ObservationRegistry`; phase instrumentation), `org/jwcarman/nessy/Nessy.java` (`.observations(…)`)
- Create: `internal/EngineObservations.java` (span names + key-value assembly in one place)
- Test: `nessy-core/src/test/java/org/jwcarman/nessy/spi/InProcessEngineObservationTest.java`

**Interfaces:**
- Consumes: Tasks 1–6.
- Produces (relied on by Task 8): `InProcessEngine` constructor order `(ModelProvider, ToolRegistry, Approver, SessionStore, EventHub, Reducer, ModelSettings, ObjectMapper, ObservationRegistry)`; builder method `observations(ObservationRegistry)` defaulting to `ObservationRegistry.NOOP`. Observation names (stable metric identity): `nessy.run`, `nessy.turn`, `nessy.model.call`, `nessy.tool.call`, `nessy.approval.wait`; contextual (span) names follow the OTel GenAI agent conventions: `invoke_agent`, `chat {model}`, `execute_tool {tool}`.

- [ ] **Step 1: Write the failing test**

```java
package org.jwcarman.nessy.spi;

import static io.micrometer.observation.tck.TestObservationRegistryAssert.assertThat;

import io.micrometer.observation.tck.TestObservationRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
// … imports for the test fixtures already in InProcessEngineTest (FakeProvider, EchoTool, echoArgs) —
// extract those fixtures into a package-private EngineFixtures class in this package so both tests share them.

class InProcessEngineObservationTest {

  @Test
  void a_tool_calling_run_produces_the_span_taxonomy() {
    TestObservationRegistry observations = TestObservationRegistry.create();
    // two-turn scripted conversation: tool_use turn, then END_TURN — same script as
    // InProcessEngineTest.aToolCallRunsAndFeedsItsResultBack, built from EngineFixtures.
    runToolCallingConversation(observations);

    assertThat(observations)
        .hasObservationWithNameEqualTo("nessy.run")
        .that()
        .hasContextualNameEqualTo("invoke_agent")
        .hasHighCardinalityKeyValueWithKey("gen_ai.conversation.id");
    assertThat(observations)
        .hasObservationWithNameEqualTo("nessy.model.call")
        .that()
        .hasContextualNameEqualTo("chat fake-model")
        .hasLowCardinalityKeyValue("gen_ai.request.model", "fake-model");
    assertThat(observations).hasObservationWithNameEqualTo("nessy.turn");
    assertThat(observations)
        .hasObservationWithNameEqualTo("nessy.tool.call")
        .that()
        .hasContextualNameEqualTo("execute_tool echo")
        .hasLowCardinalityKeyValue("gen_ai.tool.name", "echo");
  }

  @Test
  void an_approval_gated_tool_produces_an_approval_wait_span() {
    TestObservationRegistry observations = TestObservationRegistry.create();
    runToolCallingConversation(observations, /* requiresApproval= */ true);

    assertThat(observations).hasObservationWithNameEqualTo("nessy.approval.wait");
  }
}
```

Write `runToolCallingConversation` concretely against the shared fixtures — it builds the engine with the given registry and runs one `Event.UserSaid.of("echo hi")` conversation.

- [ ] **Step 2: Run to verify failure** — no observations recorded (assertion failure), since nothing is instrumented yet.

- [ ] **Step 3: Implement**

`internal/EngineObservations.java` centralizes names and key values so `InProcessEngine` stays readable:

```java
package org.jwcarman.nessy.internal;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.jwcarman.nessy.api.SessionId;
import org.jwcarman.nessy.api.Usage;

/** Span names and attribute assembly for the engine's phases. GenAI-semconv attribute keys. */
public final class EngineObservations {

  private EngineObservations() {}

  // Observation names are Nessy's stable metric identity; contextual names follow
  // the (pre-1.0) OTel GenAI agent span conventions: invoke_agent / chat {model} /
  // execute_tool {tool}. Metrics stay stable even as span conventions evolve.

  public static Observation run(ObservationRegistry registry, SessionId id) {
    return Observation.start("nessy.run", registry)
        .contextualName("invoke_agent")
        .lowCardinalityKeyValue("gen_ai.operation.name", "invoke_agent")
        .highCardinalityKeyValue("gen_ai.conversation.id", id.value());
  }

  public static Observation turn(ObservationRegistry registry) {
    return Observation.start("nessy.turn", registry);
  }

  public static Observation modelCall(ObservationRegistry registry, String model) {
    return Observation.start("nessy.model.call", registry)
        .contextualName("chat " + model)
        .lowCardinalityKeyValue("gen_ai.operation.name", "chat")
        .lowCardinalityKeyValue("gen_ai.request.model", model);
  }

  public static void recordUsage(Observation observation, Usage usage) {
    observation
        .highCardinalityKeyValue("gen_ai.usage.input_tokens", Long.toString(usage.inputTokens()))
        .highCardinalityKeyValue("gen_ai.usage.output_tokens", Long.toString(usage.outputTokens()));
  }

  public static Observation toolCall(ObservationRegistry registry, String toolName, String callId) {
    return Observation.start("nessy.tool.call", registry)
        .contextualName("execute_tool " + toolName)
        .lowCardinalityKeyValue("gen_ai.operation.name", "execute_tool")
        .lowCardinalityKeyValue("gen_ai.tool.name", toolName)
        .highCardinalityKeyValue("gen_ai.tool.call.id", callId);
  }

  // No semconv concept exists for a human approval gate; this one is ours.
  public static Observation approvalWait(ObservationRegistry registry, String toolName) {
    return Observation.start("nessy.approval.wait", registry)
        .lowCardinalityKeyValue("gen_ai.tool.name", toolName);
  }
}
```

`InProcessEngine` gains the `ObservationRegistry observations` field (constructor position 9, `requireNonNull`). Instrumentation pattern — identical at each site, shown once here for `run` and applied likewise to the others:

```java
Observation observation = EngineObservations.run(observations, id);
try (Observation.Scope scope = observation.openScope()) {
  // …existing body…
} catch (RuntimeException e) {
  observation.error(e);
  throw e;
} finally {
  observation.stop();
}
```

- `run(...)`: wraps the whole body (around the existing progress-holder try/finally).
- `callModel(...)`: `turn` wraps the method body; `modelCall` wraps the stream-consumption `try (ModelStream …)` block only, capturing the `TurnEnded` usage as it passes through the loop and calling `recordUsage` before its `stop()`. Turns caused by tool round-trips nest under the turn that caused them — accepted and documented in the method javadoc (the recursion is the causality).
- `executeTool(...)`: `toolCall` wraps invoke-through-resolve, passing `call.name()` and `call.id()`.
- `decide(...)`: `approvalWait` wraps only the `approver.approve(request)` call (not the fast requiresApproval-false path — no span for a decision that involves no waiting).

`Nessy.Builder`: `private ObservationRegistry observations = ObservationRegistry.NOOP;` + `observations(ObservationRegistry)` setter; passed to the engine.

Update `InProcessEngineTest`/`EndToEndTest` engine construction for the new constructor parameter (`ObservationRegistry.NOOP`).

- [ ] **Step 4: Verify, commit**

Run: `./mvnw -q clean verify` — PASS.

```bash
./mvnw license:format -Plicense && ./mvnw spotless:apply && ./mvnw -q clean verify
git add -A
git commit -m "feat: instrument engine phases with Micrometer Observation"
```

---

### Task 8: The front door — Agent, Conversation, Reply

**Files:**
- Create: `org/jwcarman/nessy/Agent.java`, `Conversation.java`, `Reply.java`, `AgentBuilder.java`
- Modify: `org/jwcarman/nessy/Nessy.java` (becomes the entry point: `agent()` only; the old nested `Builder` dissolves into `AgentBuilder`)
- Test: `nessy-testing/src/test/java/org/jwcarman/nessy/testing/AgentFacadeTest.java` (new); `EndToEndTest` (rewritten to exercise both levels)

**Interfaces:**
- Consumes: everything.
- Produces:
  - `Nessy.agent()` → `AgentBuilder` (there is no other public entry point)
  - `AgentBuilder`: `provider(ModelProvider)`, `model(String)`, `systemPrompt(String)`, `maxTokens(int)`, `capabilities(Set<Capability>)`, `tools(Tool<?>...)`, `tools(ToolRegistry)`, `approver(Approver)`, `store(SessionStore)`, `events(EventHub)`, `termination(TerminationPolicy)`, `observations(ObservationRegistry)`, `objectMapper(ObjectMapper)`, `build()` → `Agent`. Required: provider + model; defaults per spec (allow-all approver, in-memory store, synchronous hub, `TerminationPolicy.defaults()`, NOOP observations, 4096 max tokens, empty registry/capabilities).
  - `Agent` (final): `converse()` → `Conversation` (fresh `SessionId.random()`), `resume(SessionId)` → `Conversation`, `engine()` → `ExecutionEngine`, `events()` → `EventHub`
  - `Conversation` (final): `send(String)` → `Reply`, `sessionId()` → `SessionId`
  - `Reply` (record over `RunOutcome`): `outcome()`, `state()` (from either variant), `text()` (concatenated `TextBlock`s of the last assistant message, `""` if none), `failed()`, `failureReason()` → `Optional<String>`

- [ ] **Step 1: Write the failing test**

`AgentFacadeTest.java`:

```java
package org.jwcarman.nessy.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jwcarman.nessy.Agent;
import org.jwcarman.nessy.Conversation;
import org.jwcarman.nessy.Nessy;
import org.jwcarman.nessy.Reply;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.ToolResult;
import org.jwcarman.nessy.api.event.SessionEvent;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

class AgentFacadeTest {

  record Add(int left, int right) {}

  static final class AddTool implements Tool<Add> {
    @Override
    public String name() {
      return "add";
    }

    @Override
    public String description() {
      return "Adds two integers";
    }

    @Override
    public Class<Add> inputType() {
      return Add.class;
    }

    @Override
    public boolean requiresApproval() {
      return false;
    }

    @Override
    public Awaited<ToolResult> execute(Add input, ToolContext context) {
      return Awaited.ready(ToolResult.ok(String.valueOf(input.left() + input.right())));
    }
  }

  private static ObjectNode addArgs(int left, int right) {
    ObjectNode args = JsonNodeFactory.instance.objectNode();
    args.put("left", left);
    args.put("right", right);
    return args;
  }

  @Test
  void the_five_minute_path_is_five_lines() {
    ScriptedModelProvider provider =
        ScriptedModelProvider.builder()
            .toolUse("c1", "add", addArgs(2, 2))
            .endWithToolUse()
            .text("The answer is 4.")
            .endTurn()
            .build();

    Agent agent =
        Nessy.agent().provider(provider).model("fake-model").tools(new AddTool()).build();
    Reply reply = agent.converse().send("what is 2+2?");

    assertThat(reply.text()).isEqualTo("The answer is 4.");
    assertThat(reply.failed()).isFalse();
  }

  @Test
  void conversations_carry_their_session_across_sends() {
    ScriptedModelProvider provider =
        ScriptedModelProvider.builder()
            .text("Hello!")
            .endTurn()
            .text("Still here.")
            .endTurn()
            .build();
    Agent agent = Nessy.agent().provider(provider).model("fake-model").build();

    Conversation chat = agent.converse();
    chat.send("hi");
    Reply second = chat.send("you there?");

    assertThat(second.text()).isEqualTo("Still here.");
    assertThat(second.state().messages()).hasSize(4);
  }

  @Test
  void the_hub_is_reachable_from_the_agent() {
    ScriptedModelProvider provider =
        ScriptedModelProvider.builder().text("Hi").endTurn().build();
    Agent agent = Nessy.agent().provider(provider).model("fake-model").build();
    RecordingSubscriber recorder = new RecordingSubscriber();
    recorder.attachTo(agent.events());

    agent.converse().send("hello");

    assertThat(recorder.ofType(SessionEvent.class)).isNotEmpty();
  }

  @Test
  void a_missing_provider_is_rejected_at_build_time() {
    assertThatThrownBy(() -> Nessy.agent().model("fake-model").build())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("provider");
  }
}
```

- [ ] **Step 2: Run to verify failure** — `cannot find symbol: class Agent`.

- [ ] **Step 3: Implement**

`Nessy.java` shrinks to the entry point:

```java
package org.jwcarman.nessy;

/** The front door. {@code Nessy.agent()} is the only way in; everything else is reachable from what it builds. */
public final class Nessy {

  private Nessy() {}

  public static AgentBuilder agent() {
    return new AgentBuilder();
  }
}
```

`AgentBuilder` carries the old builder's fields plus Task 4/6/7 additions, `build()` assembling the engine exactly as before and returning `new Agent(engine, hub, store)`.

`Agent.java`:

```java
package org.jwcarman.nessy;

import java.util.Objects;
import org.jwcarman.nessy.api.SessionId;
import org.jwcarman.nessy.api.event.EventHub;
import org.jwcarman.nessy.spi.ExecutionEngine;

/** A configured agent: a reusable factory of conversations, with the full machinery one call away. */
public final class Agent {

  private final ExecutionEngine engine;
  private final EventHub events;

  Agent(ExecutionEngine engine, EventHub events) {
    this.engine = Objects.requireNonNull(engine, "engine");
    this.events = Objects.requireNonNull(events, "events");
  }

  /** Opens a fresh conversation. */
  public Conversation converse() {
    return new Conversation(engine, SessionId.random());
  }

  /** Reopens a stored session. The engine loads its state on the next send. */
  public Conversation resume(SessionId sessionId) {
    return new Conversation(engine, sessionId);
  }

  /** The event-level API, for anything the facade does not say. */
  public ExecutionEngine engine() {
    return engine;
  }

  public EventHub events() {
    return events;
  }
}
```

(Adjust the constructor arity to what `build()` actually needs — if `store` proves unnecessary here, do not add it; YAGNI.)

`Conversation.java`:

```java
package org.jwcarman.nessy;

import java.util.Objects;
import org.jwcarman.nessy.api.Event;
import org.jwcarman.nessy.api.SessionId;
import org.jwcarman.nessy.spi.ExecutionEngine;

/** One session. Sugar over {@code engine.run} — no semantics of its own. */
public final class Conversation {

  private final ExecutionEngine engine;
  private final SessionId sessionId;

  Conversation(ExecutionEngine engine, SessionId sessionId) {
    this.engine = Objects.requireNonNull(engine, "engine");
    this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
  }

  public Reply send(String text) {
    return new Reply(engine.run(sessionId, Event.UserSaid.of(text)));
  }

  public SessionId sessionId() {
    return sessionId;
  }
}
```

`Reply.java`:

```java
package org.jwcarman.nessy;

import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.jwcarman.nessy.api.Message;
import org.jwcarman.nessy.api.Role;
import org.jwcarman.nessy.api.RunOutcome;
import org.jwcarman.nessy.api.SessionState;
import org.jwcarman.nessy.api.SessionStatus;
import org.jwcarman.nessy.api.TextBlock;

/** What came back. Sugar over the final {@link SessionState}. */
public record Reply(RunOutcome outcome) {

  public Reply {
    Objects.requireNonNull(outcome, "outcome");
  }

  public SessionState state() {
    return switch (outcome) {
      case RunOutcome.Completed completed -> completed.state();
      case RunOutcome.Parked parked -> parked.state();
    };
  }

  /** The prose of the last assistant message; empty if there is none. */
  public String text() {
    return state().messages().stream()
        .filter(message -> message.role() == Role.ASSISTANT)
        .reduce((first, second) -> second)
        .map(Reply::proseOf)
        .orElse("");
  }

  public boolean failed() {
    return state().status() == SessionStatus.FAILED;
  }

  public Optional<String> failureReason() {
    return Optional.ofNullable(state().failureReason());
  }

  private static String proseOf(Message message) {
    return message.content().stream()
        .filter(TextBlock.class::isInstance)
        .map(TextBlock.class::cast)
        .map(TextBlock::text)
        .collect(Collectors.joining());
  }
}
```

Rewrite `EndToEndTest` so the full-conversation test uses the facade, while the schema-reaches-the-model and capabilities-reach-the-provider tests use `agent.engine()` + `provider.requests()` — proof the escape hatch works. Build-time-rejection tests move to `AgentFacadeTest` (already written above).

- [ ] **Step 4: Verify, commit**

Run: `./mvnw -q clean verify` — PASS.
Run: `grep -rn "Nessy.builder()" nessy-core/src nessy-testing/src` — no matches.

```bash
./mvnw license:format -Plicense && ./mvnw spotless:apply && ./mvnw -q clean verify
git add -A
git commit -m "feat: add the Agent facade as the single front door"
```

---

### Task 9: JPMS

**Files:**
- Create: `nessy-core/src/main/java/module-info.java`
- Modify (fallback only): `nessy-core/pom.xml`, `nessy-testing/pom.xml` (Automatic-Module-Name manifest entries)

**Interfaces:**
- Consumes: the converged layout.
- Produces: `internal` structurally unreachable from outside the module — or the documented fallback.

- [ ] **Step 1: Discover the real module names of the dependencies**

For each of jackson-databind, jackson-annotations, jackson-core, victools jsonschema-generator, jsonschema-module-jackson, and micrometer-observation (plus micrometer-commons), inspect the resolved jar: `unzip -p ~/.m2/repository/…/<artifact>.jar META-INF/MANIFEST.MF | grep -i automatic` and check for a `module-info.class` (`jar -d --file <jar>` prints the descriptor). Record what you find in your report — these names go into `requires` verbatim.

- [ ] **Step 2: Write the module descriptor**

```java
module org.jwcarman.nessy.core {
  requires transitive com.fasterxml.jackson.databind; // verify names per Step 1
  requires com.fasterxml.jackson.annotation;
  requires transitive micrometer.observation;
  requires com.github.victools.jsonschema.generator;
  requires com.github.victools.jsonschema.module.jackson;

  exports org.jwcarman.nessy;
  exports org.jwcarman.nessy.api;
  exports org.jwcarman.nessy.api.tool;
  exports org.jwcarman.nessy.api.approval;
  exports org.jwcarman.nessy.api.event;
  exports org.jwcarman.nessy.spi;
  exports org.jwcarman.nessy.spi.model;
  exports org.jwcarman.nessy.spi.session;
  // org.jwcarman.nessy.internal is deliberately NOT exported.
}
```

`requires transitive` for jackson-databind (JsonNode appears in `ToolCall`) and micrometer-observation (`ObservationRegistry` appears in `AgentBuilder`).

- [ ] **Step 3: Build, and take one of two documented exits**

Run: `./mvnw -q clean verify`.

**If green:** give `nessy-testing` an `Automatic-Module-Name` of `org.jwcarman.nessy.testing` via maven-jar-plugin `manifestEntries` (a test-support library does not need a full descriptor), re-verify, done.

**If the module graph fights back** (automatic-module resolution failures, surefire/javadoc breakage you cannot resolve within this task): delete `module-info.java`, set `Automatic-Module-Name: org.jwcarman.nessy.core` via maven-jar-plugin on `nessy-core` (and `.testing` on `nessy-testing`), and record in `CHANGELOG.md` under Unreleased: "JPMS descriptor deferred; internal-package non-export enforced by convention until revisited pre-1.0, blocked on <the specific error>." Either exit is a completed task; silent limbo is not.

- [ ] **Step 4: Verify, commit**

```bash
./mvnw license:format -Plicense && ./mvnw spotless:apply && ./mvnw -q clean verify
git add -A
git commit -m "build: enforce zone boundaries with JPMS"   # or "build: set Automatic-Module-Name (JPMS deferred)"
```

---

### Task 10: Documentation convergence

**Files:**
- Modify: `README.md` (rewritten), `CHANGELOG.md` (Unreleased section)

**Interfaces:**
- Consumes: everything.
- Produces: a README whose every code sample compiles against what ships.

- [ ] **Step 1: Rewrite the README**

Structure (write real prose, not headings-with-stubs; every claim checked against the code):

1. **Name + one-liner** — agent harness framework for Java; the loop, tools, approval gate, sessions, streaming, observability.
2. **The five-minute example** — the `AgentFacadeTest` shape, verbatim-compilable: `Nessy.agent().provider(…).model(…).tools(…).build()`, `agent.converse().send(…)`, `reply.text()`. Use `ScriptedModelProvider` so the example runs keyless, with one sentence noting real providers arrive as `nessy-model-*` modules.
3. **How it works** — the effectful reducer, four sentences, linking the spec.
4. **The zones** — the api/spi/internal table with the one-sentence zone rule ("if writing an agent requires it, it's API; if hosting agents requires it, it's SPI").
5. **The seams and their defaults** — spec §13's ladder table.
6. **Observability** — hub for narrative, Micrometer Observation for spans/metrics; the span taxonomy; the Spring Boot zero-config sentence.
7. **Testing** — the promise, verbatim: "You will never need a mocking library to test a Nessy agent." Note the framework's own suite holds itself to it.
8. **Building** — `./mvnw verify` (keyless, offline from any model provider); live tests via `./mvnw test -Dnessy.excludedGroups=`.
9. **Status** — honest: core + testing converged to spec v2; providers, durable engine, policy, compaction, Spring starter, TUI not yet built.
10. **License / Contributing** — pointers to the existing files.

- [ ] **Step 2: CHANGELOG**

Under `## [Unreleased]`, summarize the convergence in user-facing terms: zones and renames (with the ledger table), event hub replacing listeners, grammar additions, `TerminationPolicy`, Micrometer Observation, the `Agent` facade, JPMS outcome.

- [ ] **Step 3: Verify every README code block**

Paste each Java block into a scratch test file, compile it (`./mvnw -q -pl nessy-testing test-compile` with the block in `src/test/java`), then delete the scratch. A README example that does not compile is a task failure — this was a real finding last time; do not repeat it.

- [ ] **Step 4: Final full verification and commit**

Run: `./mvnw -q clean verify` — the whole reactor, keyless.
Run: `grep -rn "SuppressWarnings" nessy-core/src nessy-testing/src` — comments only.
Run: `grep -rn "^import .*\*;" nessy-core/src nessy-testing/src` — no matches.

```bash
./mvnw license:format -Plicense && ./mvnw spotless:apply && ./mvnw -q clean verify
git add -A
git commit -m "docs: converge README and CHANGELOG to spec v2"
```

---

### Task 11: Test style convergence

**Files:** every pre-existing test class in `nessy-core/src/test` and `nessy-testing/src/test` (the suite written by Plan 1; tests created by Tasks 1–10 of this plan are already in style).

**Interfaces:**
- Consumes: the module-wide display-name generator from Task 1.
- Produces: a suite that reads as prose end to end. No signature any production code depends on changes.

- [ ] **Step 1: Rename every test method to a `snake_case` sentence**

The transformation is mechanical — lower each camelCase hump: `userInputIsRecordedAndAsksForTheModel` → `user_input_is_recorded_and_asks_for_the_model`, `aTokenCanBeConsumedExactlyOnce` → `a_token_can_be_consumed_exactly_once`, and so on for every `@Test` method. While renaming, apply the sentence test: a name that cannot be read as a sentence is usually testing more than one thing — flag any such case in your report rather than silently splitting it.

- [ ] **Step 2: Introduce `@Nested` groups where a class covers distinct behaviors**

Group when a class exercises two or more separable behaviors; name groups as capitalized phrases describing the behavior; leave single-purpose classes flat. Concretely:

| Class | Groups |
|---|---|
| `ReducerTextTest` | `User_input`, `Text_deltas`, `Turn_end` |
| `ReducerToolCallTest` | flat (single behavior: calls become approval requests) |
| `ReducerToolResultTest` | `Approval_decisions`, `Batching`, `The_error_ceiling` |
| `InProcessEngineTest` | `Plain_answers`, `Tool_calls`, `Approval`, `Failure_handling`, `Streams_and_sessions` |
| `ToolRegistryTest` | `Lookup`, `Specs`, `Invocation` |
| `SessionStateTest`, `EventTest`, `AwaitedTest`, `MessageTest`, `ValidationTest`, `SchemasTest`, `ModelRequestTest`, `InMemorySessionStoreTest`, `ScriptedModelProviderTest`, `EndToEndTest` | flat unless a grouping is self-evident — judgment call, note choices in the report |

Fields shared across groups (fixtures, helper methods) stay on the outer class; `@Nested` inner classes reach them directly.

- [ ] **Step 3: Verify nothing but names changed**

Run: `./mvnw -q clean verify`
Expected: PASS with a test count **identical** to the count before this task — record both numbers in your report. Assertions are untouched; only method names, group nesting, and any imports (`org.junit.jupiter.api.Nested`) change.

Spot-check the prose: `./mvnw -q -pl nessy-core test 2>&1 | grep -i "error ceiling" || true` against a deliberately broken assertion locally reads as a sentence — then restore it. (Or simply inspect a surefire `.txt` report for the underscore-free display names.)

- [ ] **Step 4: Format, license, commit**

```bash
./mvnw license:format -Plicense && ./mvnw spotless:apply && ./mvnw -q clean verify
git add -A
git commit -m "test: converge the suite to prose naming and nested grouping"
```

---

### Task 12: Time-ordered UUIDs (v7)

**Files:**
- Modify: `pom.xml` (parent — `jug.version` property + dependencyManagement), `nessy-core/pom.xml` (dependency)
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/internal/Uuids.java`
- Modify: `api/SessionId.java`, `api/ParkToken.java` (`random()` implementations only — signatures unchanged), `module-info.java` if Task 9 produced one (`requires com.fasterxml.uuid`)
- Modify: `CHANGELOG.md` (one Unreleased line)
- Test: additions to `ValidationTest` (or the ids' own test homes)

**Interfaces:**
- Consumes: nothing new.
- Produces: `SessionId.random()` and `ParkToken.random()` now yield UUIDv7 (time-ordered) values via `com.fasterxml.uuid:java-uuid-generator`; `Uuids.timeOrdered()` internal helper. Public signatures unchanged.

- [ ] **Step 1: Write the failing tests**

```java
@Test
void random_session_ids_are_time_ordered_uuidv7() {
  assertThat(UUID.fromString(SessionId.random().value()).version()).isEqualTo(7);
}

@Test
void random_park_tokens_are_time_ordered_uuidv7() {
  assertThat(UUID.fromString(ParkToken.random().value()).version()).isEqualTo(7);
}
```

- [ ] **Step 2: Run to verify they fail** — versions currently report 4.

- [ ] **Step 3: Implement**

Parent pom: `<jug.version>5.1.0</jug.version>` (newest 5.x if unresolvable; record it) and manage `com.fasterxml.uuid:java-uuid-generator`; `nessy-core` declares it (compile). JUG's own transitive slf4j-api is already satisfied/aligned in the tree — verify with `dependency:tree` and note the outcome.

`internal/Uuids.java`:

```java
package org.jwcarman.nessy.internal;

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;
import java.util.UUID;

/** Time-ordered (UUIDv7) identifiers: sortable by creation time, index-friendly in stores. */
public final class Uuids {

  private static final TimeBasedEpochGenerator GENERATOR = Generators.timeBasedEpochGenerator();

  private Uuids() {}

  public static UUID timeOrdered() {
    return GENERATOR.generate();
  }
}
```

`SessionId.random()` / `ParkToken.random()` delegate to `Uuids.timeOrdered().toString()`. Existing distinctness tests must still pass unchanged. If Task 9 shipped `module-info.java`, add `requires com.fasterxml.uuid;` (verify the module name from the jar manifest as Task 9 did).

- [ ] **Step 4: Verify, changelog, commit**

Full green `./mvnw -q clean verify`; CHANGELOG Unreleased gains "Session and park identifiers are now time-ordered UUIDv7". Then license:format, spotless:apply, re-verify, commit `feat: time-ordered UUIDv7 identifiers`.

---

## Self-Review

**Spec coverage** (spec v2 § → task): zones/package map §4.1–4.2 → Tasks 2–3; naming ledger §5 → Task 3; grammar §7 → Task 5; facade §8.1 → Task 8; `ToolContext.events()` §8.2 → Task 4; hub §9 → Task 4; `TerminationPolicy` §10.4 → Task 6; Observation §11 → Tasks 1, 7; JPMS §4.4 → Task 9; README promise §3/§12 → Task 10; prose test style §12 → Task 1 (generator) + Task 11 (suite convergence), with every test authored by Tasks 1–10 already in style. Deliberately not in this plan, per spec §14: `Policy` (Plan 2.5), `ContextBuilder` (with the compactor), retry decorator and its `Sleeper` test seam (Plan 2), traceparent-in-state (with `DurableEngine`), the run-refusal rule for non-idle sessions (§6 — lands with `DurableEngine`'s resume work, where it can be tested against a real parked state rather than a fabricated one).

**Placeholder scan:** the two references to existing test fixtures (`EngineFixtures` extraction in Task 7, `awaitingApprovalWith` adaptation in Task 6) name the exact source material rather than full listings, because the material already exists in the repository — the implementer adapts named, existing code, not imagined code. Everything new is fully specified.

**Type consistency:** `EventHub.synchronous()`, `SessionEvent`, `ToolProgress`, `ToolContext(SessionId, EventEmitter)` (Task 4) match their uses in Tasks 5–8; `Usage`/`TurnEnded(reason, usage)`/`UserSaid.of` (Task 5) match Tasks 6–8; `TerminationPolicy.defaults()` and `Reducer(TerminationPolicy)` (Task 6) match Task 8's builder; the nine-argument engine constructor order in Task 7 matches Task 8's `build()`; `provider(…)`/`model(…)` naming is uniform from Task 3 onward.

**Known sequencing note:** Task 4's tool-progress test references `Event.UserSaid.of`, which Task 5 introduces — the task text gives the transitional form and flags it; either order of landing the two steps is green.

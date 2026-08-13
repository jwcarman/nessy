# Chat-Web Example Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement `docs/superpowers/specs/2026-08-13-chat-web-example-design.md`: `JdbcMemory` in `nessy-store-jdbc`, the `nessy-examples` aggregator restructure, and the `chat-web` Spring Boot chat app (compose-launched Postgres, SSE streaming, approval cards, kill-and-restart survival).

**Architecture:** Three layers, built in order. First the framework prerequisite (`JdbcMemory` beside `JdbcConversationStore`, reusing `StateCodec`). Then the module restructure (aggregator + `chat-cli` move, reactor stays green). Then the app: backend (beans, tool, SSE bridge, controllers) with offline unit tests, the static UI, one container-tagged Boot smoke proving the whole wiring, and docs.

**Tech Stack:** Java 25, Maven reactor, Spring Boot **4.1.0** (BOM-imported in `chat-web` only — locally cached, offline-friendly), spring-boot-starter-web + starter-jdbc + spring-boot-docker-compose, Postgres 17 (compose) / Testcontainers (tests), the LGTM observability suite (`grafana/otel-lgtm` in compose; actuator + `spring-boot-starter-opentelemetry` + `micrometer-registry-otlp` + OTel Logback appender — spec §5a, recipe mirrored from `/Users/jcarman/IdeaProjects/mocapi-enterprise-demo`), vanilla HTML/JS/CSS (no build step), JUnit 5 + AssertJ, no mocking libraries.

## Global Constraints

- **No warning suppressions; no star imports; prose snake_case test names; S5778; S5841; no `default` arms in core sealed switches** (the examples' extender switches over `TurnEvent` MAY carry `default` per sealed etiquette).
- `./mvnw -q clean verify` at the reactor root must stay green **offline — no Docker, no API key** — after every task (container tag excluded by default).
- Before every commit: `./mvnw license:format -Plicense && ./mvnw spotless:apply`.
- Spring enters the reactor ONLY inside `nessy-examples/chat-web` (BOM import there; `nessy-parent` untouched).
- Example artifacts are never published: both new example poms carry `<maven.deploy.skip>true</maven.deploy.skip>` (copy whatever skip property today's `nessy-examples/pom.xml` uses — check it; mirror exactly).
- **Model policy (dispatch):** implementer = Sonnet throughout; task review = Sonnet; Haiku scoped re-reviews. No Opus gates — nothing here is fold/loop/persistence-core risk (JdbcMemory's review rides the store TCK precedent).
- Commit messages in the repo voice + trailer `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.

---

### Task 1: JdbcMemory — the durable transcript floor

**Files:**
- Modify: `nessy-store-jdbc/src/main/java/org/jwcarman/nessy/store/jdbc/StateCodec.java` (add two methods)
- Create: `nessy-store-jdbc/src/main/java/org/jwcarman/nessy/store/jdbc/JdbcMemory.java`
- Create: `nessy-store-jdbc/src/main/resources/org/jwcarman/nessy/store/jdbc/memory-schema.sql`
- Test: extend `nessy-store-jdbc/src/test/java/org/jwcarman/nessy/store/jdbc/StateCodecTest.java`; create `JdbcMemoryTest.java` (container-tagged)

**Interfaces:**
- Consumes: `Memory` (`spi.memory`: `void remember(ConversationId, Message)`, `Context recall(ConversationId)`), `StateCodec`'s existing mixin-configured mapper, `Context.of(List<Message>)`.
- Produces: `JdbcMemory implements Memory`, constructor `(DataSource, ObjectMapper)`, factory `create(DataSource, ObjectMapper)` (bootstraps `memory-schema.sql` idempotently, mirroring `JdbcConversationStore.create`'s discipline exactly — read that method first and copy its shape); `StateCodec.writeMessage(Message) → String` / `readMessage(String) → Message` (package-private, like its four siblings).

- [ ] **Step 1:** Add to `StateCodecTest` (offline): `a_message_round_trips_through_the_codec` — a `Message.assistant(List.of(new ThinkingBlock("hmm","sig"), new TextBlock("hi"), new ToolUseBlock(call)))` written and re-read equals itself; and `an_unknown_message_payload_fails_loudly` (malformed type id → exception, S5778: one throwing call). Run — fails (methods missing).
- [ ] **Step 2:** Implement `writeMessage`/`readMessage` on `StateCodec` (delegate to the same configured mapper; identical shape to `writeAgendaItem`/`readAgendaItem`). Green.
- [ ] **Step 3:** `memory-schema.sql` exactly per spec §2:

```sql
CREATE TABLE IF NOT EXISTS nessy_memory (
  conversation_id text   NOT NULL,
  seq             bigint NOT NULL,
  message         jsonb  NOT NULL,
  PRIMARY KEY (conversation_id, seq)
);
```

- [ ] **Step 4:** `JdbcMemory` — javadoc opens: "The durable floor: verbatim retention in Postgres, the {@code ListMemory} contract with a lifespan." Implementation contract:
  - `remember`: one transaction — `SELECT seq, message FROM nessy_memory WHERE conversation_id=? ORDER BY seq DESC LIMIT 1 FOR UPDATE`; if the row exists and its decoded message equals the new one, return (the consecutive-duplicate rule — at-least-once tolerance); else `INSERT` with `seq = last+1` (or `0`). The `FOR UPDATE` serializes concurrent remembers per conversation.
  - `recall`: `SELECT message FROM nessy_memory WHERE conversation_id=? ORDER BY seq` → decode each → `Context.of(list)`; empty list for unknown ids.
  - Same connection/try-with-resources/rollback discipline as `JdbcConversationStore` (`addSuppressed` on rollback failure — copy the `rollbackQuietly` helper's pattern or reuse it if visibility allows; if it is private, replicate the four lines rather than widening visibility).
- [ ] **Step 5:** `JdbcMemoryTest extends` nothing (Memory has no TCK — mirror `ListMemoryTest`'s scenarios against the real container): `@Tag("container")`, static `PostgreSQLContainer<?>` (copy `JdbcConversationStoreTest`'s container setup verbatim), tests: `recalls_exactly_what_it_was_told_in_order`, `recalls_nothing_for_a_conversation_never_told_anything`, `keeps_conversations_apart`, `tolerates_the_same_message_told_twice_in_a_row` (two structurally-equal-distinct instances — the `ListMemoryTest` fix-round lesson), `the_schema_bootstrap_is_idempotent` (create twice), and `survives_a_new_instance_over_the_same_database` (remember with one `JdbcMemory`, recall with a fresh one — the restart pin).
- [ ] **Step 6:** Run with Docker (`docker info` first; start Docker Desktop if down — `open -a Docker` and poll): `./mvnw -pl nessy-store-jdbc test -Dnessy.excludedGroups=live`. Then offline reactor verify. Format.
- [ ] **Step 7:** Commit: `feat: JdbcMemory — the transcript learns to survive the JVM`

---

### Task 2: The examples aggregator — chat-cli moves house

**Files:**
- Modify: `nessy-examples/pom.xml` → `<packaging>pom</packaging>`, `<modules>chat-cli</modules>` (chat-web joins in Task 3); strip the dependencies/exec-plugin (they move).
- Create: `nessy-examples/chat-cli/pom.xml` — artifactId `nessy-example-chat-cli`, parent `nessy-examples` aggregator, the exact dependencies + exec-plugin + deploy-skip from today's `nessy-examples/pom.xml`.
- Move (git mv): `nessy-examples/src/**` → `nessy-examples/chat-cli/src/**` (all of `AnthropicChat`, `OpenAiChat`, `DemoAgent`, `logback.xml`, `junit-platform.properties`).

**Interfaces:** none new; the reactor `<module>nessy-examples</module>` entry is unchanged (aggregation nests).

- [ ] **Step 1:** Read today's `nessy-examples/pom.xml` fully; note the deploy-skip mechanism and the parent's relativePath convention used by other modules.
- [ ] **Step 2:** Perform the restructure (git mv preserves history). Aggregator pom keeps parent `nessy-parent`, gains `<packaging>pom</packaging>` and the module list; `chat-cli` pom's parent is the aggregator (`<relativePath>../pom.xml</relativePath>`).
- [ ] **Step 3:** `./mvnw -q clean verify` offline — green; confirm `git log --follow nessy-examples/chat-cli/src/main/java/org/jwcarman/nessy/examples/AnthropicChat.java` shows history.
- [ ] **Step 4:** Format; commit: `build: examples become a family — chat-cli takes the old rooms`

---

### Task 3: chat-web backend — beans, tool, bridge, controllers

**Files:**
- Modify: `nessy-examples/pom.xml` (add `<module>chat-web</module>`)
- Create: `nessy-examples/chat-web/pom.xml`
- Create under `nessy-examples/chat-web/src/main/java/org/jwcarman/nessy/examples/chatweb/`: `ChatWebApplication.java`, `NessyConfig.java`, `IssueCouponTool.java`, `SseEvents.java`, `ChatController.java`, `ApprovalController.java`, `TranscriptView.java`, `OpenTelemetryAppenderInitializer.java`
- Create: `nessy-examples/chat-web/src/main/resources/application.yaml`, `src/main/resources/logback-spring.xml`, `nessy-examples/chat-web/docker-compose.yml`
- Test: `nessy-examples/chat-web/src/test/java/org/jwcarman/nessy/examples/chatweb/SseEventsTest.java`, `TranscriptViewTest.java` (both offline)

**Interfaces:**
- Consumes: everything Task 1 produced plus the shipped kernel (`Agent`, `Harness.resume/progress`, `RunOutcome`, `TurnObserver`, `JdbcConversationStore`, `ConversationStore.load` for parked cards).
- Produces (Tasks 4–5 rely on): the endpoint contract of spec §4's table verbatim; SSE event names `delta`, `thinking`, `tool-requested`, `tool-progress`, `tool-decided`, `tool-completed`, `approval-needed`, `done`; JSON shapes below.

- [ ] **Step 1: `chat-web/pom.xml`.** Parent = the aggregator. `dependencyManagement`: import `org.springframework.boot:spring-boot-dependencies:4.1.0` (pom, import scope). Dependencies: `spring-boot-starter-web`, `spring-boot-starter-jdbc`, `org.postgresql:postgresql` (runtime), `spring-boot-docker-compose` (runtime, `<optional>true</optional>`), `nessy-core`, `nessy-model-anthropic`, `nessy-store-jdbc` (project versions), test: `spring-boot-starter-test` (exclude `mockito-core` — the house bans mocking libraries; use `<exclusions>`), `org.testcontainers:postgresql` + `junit-jupiter`. Observability set (spec §5a): `spring-boot-starter-actuator`, `spring-boot-starter-opentelemetry`, `io.micrometer:micrometer-registry-otlp` (all BOM-managed), and `io.opentelemetry.instrumentation:opentelemetry-logback-appender-1.0` at the version aligned with Boot 4.1.0's managed `opentelemetry` core — read `/Users/jcarman/IdeaProjects/mocapi-enterprise-demo/pom.xml` lines 40–47 for the alignment rule and copy its pin-and-comment discipline (verify the pinned `2.x.0-alpha` instrumentation version routes through the otel-api version Boot 4.1.0 manages; if Boot 4.1.0 manages a newer core than 4.0.6 did, bump the instrumentation pin per that comment's rule and say so in the report). Build: `spring-boot-maven-plugin` (version from the BOM) with NO repackage execution needed for `spring-boot:run`; deploy-skip like chat-cli. Do NOT add a compiler config — inherit the parent's Java 25 setup.
- [ ] **Step 2: the offline-testable core, TDD.** `SseEventsTest` first:

```java
class SseEventsTest {
  @Test
  void every_turn_event_maps_to_a_named_payload() {
    ToolCall call = new ToolCall("c1", "issue_coupon", JsonNodeFactory.instance.objectNode());
    assertThat(SseEvents.of(new TurnEvent.TextDelta("hi")))
        .isEqualTo(new SseEvents.Event("delta", Map.of("text", "hi")));
    assertThat(SseEvents.of(new TurnEvent.ThinkingDelta("hmm")))
        .isEqualTo(new SseEvents.Event("thinking", Map.of("text", "hmm")));
    assertThat(SseEvents.of(new TurnEvent.ToolCallRequested(call)))
        .isEqualTo(new SseEvents.Event("tool-requested", Map.of("id", "c1", "name", "issue_coupon")));
    assertThat(SseEvents.of(new TurnEvent.ToolCallProgressed(call, "issuing…")))
        .isEqualTo(new SseEvents.Event("tool-progress", Map.of("id", "c1", "message", "issuing…")));
    assertThat(SseEvents.of(new TurnEvent.ToolCallCompleted(call, ToolResult.ok("done"))))
        .isEqualTo(new SseEvents.Event("tool-completed", Map.of("id", "c1", "error", false)));
  }

  @Test
  void a_decision_maps_with_its_verdict() {
    ToolCall call = new ToolCall("c1", "issue_coupon", JsonNodeFactory.instance.objectNode());
    assertThat(SseEvents.of(new TurnEvent.ToolCallDecided(call, Decision.allow())))
        .isEqualTo(new SseEvents.Event("tool-decided", Map.of("id", "c1", "allowed", true)));
  }

  @Test
  void redacted_thinking_is_a_marker_only() {
    assertThat(SseEvents.of(new TurnEvent.RedactedThinking("opaque")))
        .isEqualTo(new SseEvents.Event("thinking", Map.of("text", "[redacted]")));
  }
}
```

  `SseEvents`: a final class with `record Event(String name, Map<String, Object> payload)` and a static `Event of(TurnEvent event)` implemented as an exhaustive switch (extender code — but write it exhaustive WITHOUT a `default` so new variants surface here at compile time; this module compiles in-reactor so the etiquette's core rule is the better trade, note it in a comment). Plus `static TurnObserver observer(Consumer<Event> sink)` returning `event -> sink.accept(of(event))`.
- [ ] **Step 3: `TranscriptView`** + test. `record Line(String role, String text)`; `static List<Line> of(Context context)` — for each message, join its `TextBlock`s' text (skip thinking/tool blocks); skip messages with no text; role lowercased (`user`/`assistant`). Test: a context of `[user("hi"), assistant([thinking, text("hello")]), toolResults([...])]` yields `[("user","hi"), ("assistant","hello")]` — the results message has no text blocks and disappears.
- [ ] **Step 4: the tool.**

```java
public final class IssueCouponTool implements Tool<IssueCouponTool.Input> {
  public record Input(String customerEmail, int amountUsd, String reason) {}

  @Override public String name() { return "issue_coupon"; }
  @Override public String description() {
    return "Issues a store-credit coupon to a customer. Use when compensation is warranted.";
  }
  @Override public Class<Input> inputType() { return Input.class; }

  @Override public Awaited<ToolResult> execute(Input input, ToolContext context) {
    context.events().emit(new ToolProgress(context.conversationId(), "n/a", "issuing…"));
    String code = "DEMO-" + Math.abs(input.customerEmail().hashCode() % 10_000);
    return Awaited.ready(ToolResult.ok(
        "Coupon " + code + " for $" + input.amountUsd() + " issued to "
            + input.customerEmail() + " (" + input.reason() + ")"));
  }
}
```

  (The `ToolProgress` `toolCallId` is unknowable inside the tool — pass a placeholder; the tee attaches the authoritative call for narration, which is exactly the design's distrust rule. Say so in a comment.)
- [ ] **Step 5: `NessyConfig`** — the spec §4 beans verbatim (store, memory via `JdbcMemory.create`, harness with `.observations(observations)` taking Boot's auto-configured `ObservationRegistry` — the dogfood: nessy's model-call/tool observations join Boot's HTTP and JDBC spans in one trace, agent with `.memory(memory)`, the one-line parking approver, `.tools(ToolGrant.grant(new IssueCouponTool(), UsagePolicy.requireApproval()))`, system prompt from spec). Also a `@Bean ObjectMapper` only if Boot's isn't suitable — it is; inject Boot's. `ChatWebApplication` = standard `@SpringBootApplication` main. `OpenTelemetryAppenderInitializer` — copy `/Users/jcarman/IdeaProjects/mocapi-enterprise-demo/src/main/java/com/callibrity/mocapi/demo/infra/OpenTelemetryAppenderInitializer.java` verbatim (adjust package + license header): an `InitializingBean` calling `OpenTelemetryAppender.install(openTelemetry)`.
- [ ] **Step 6: controllers.** `ChatController`:
  - `GET /api/conversations/{id}` → `{ status, transcript: [{role,text}...], approvals: [{token, tool, args}...] }` — transcript via `TranscriptView.of(agent.contextFor(new ConversationId(id)))`; approvals + status via `store.load(id)` (empty state → `IDLE`, empty lists); args pretty-printed via the injected mapper.
  - `POST /api/conversations/{id}/messages` body `{"text": "..."}` → `SseEmitter` (timeout 0): run on a virtual thread (`Thread.ofVirtual().start`), `RunOutcome outcome = agent.converse-for-id...` — check how a `Conversation` is obtained for a GIVEN id (read `Agent`/`Conversation`: `converse()` mints a fresh id; find the with-id path — `Agent.conversation(ConversationId)` or equivalent; if none exists, THIS IS A TASK-3 DISCOVERY: add `Agent.converse(ConversationId)` overload to nessy-core as a tiny sanctioned addition, test alongside, and record it in the report — the web app is exactly why it must exist). Observer = `SseEvents.observer(e -> send(emitter, e))`. After the outcome: if `Parked`, send one `approval-needed` per `outcome.state().parkedCalls()` (`{token, tool, args}`); always send `done` `{status, failureReason?}`; `emitter.complete()`. Exceptions → `emitter.completeWithError` after a `done` with `status:"ERROR"`.
  - `ApprovalController`: `POST /api/approvals/{token}` body `{"decision":"allow"|"deny","reason"?}` → `SseEmitter` same bridge over `harness.resume(new ParkToken(token), decision…, observer)`; `IllegalArgumentException` (unknown token) → 409 JSON `{error}` (use `@ExceptionHandler`).
  - `send(...)` helper wraps `emitter.send(SseEmitter.event().name(e.name()).data(e.payload()))` with the checked-IO try/catch (log-and-complete on broken pipe — a closed tab must not fail the turn; comment: narration never alters the record).
- [ ] **Step 7: config files.** `application.yaml`: datasource url `jdbc:postgresql://localhost:5432/nessy`, username/password `nessy`/`nessy`, `spring.docker.compose.file: nessy-examples/chat-web/docker-compose.yml`? — NO: compose file sits beside the module; default discovery finds `docker-compose.yml` in the working directory, and `spring-boot:run` runs with module basedir — verify and set `spring.docker.compose.file` explicitly relative if needed; `spring.threads.virtual.enabled: true`; `spring.application.name: nessy-chat-web`. Observability block (Boot 4 property names — mirror `/Users/jcarman/IdeaProjects/mocapi-enterprise-demo/src/main/resources/application.properties`'s tracing section, YAML-ified):

```yaml
management:
  tracing:
    export:
      enabled: true
    sampling:
      probability: 1.0
  opentelemetry:
    tracing:
      export:
        otlp:
          endpoint: http://localhost:4318/v1/traces
          transport: http
  otlp:
    metrics:
      export:
        url: http://localhost:4318/v1/metrics
```

  (Verify each property key resolves under Boot 4.1.0 — no `Unknown property` warnings at startup; if 4.1 renamed the metrics-export key, follow Boot's relocation warning.) `logback-spring.xml`: copy `/Users/jcarman/IdeaProjects/mocapi-enterprise-demo/src/main/resources/logback-spring.xml` verbatim — Boot console defaults + the `OpenTelemetryAppender` with `captureMdcAttributes` on, both on the root logger. `docker-compose.yml`:

```yaml
services:
  postgres:
    image: postgres:17-alpine
    environment:
      POSTGRES_DB: nessy
      POSTGRES_USER: nessy
      POSTGRES_PASSWORD: nessy
    ports:
      - "5432:5432"
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U nessy"]
      interval: 2s
      timeout: 2s
      retries: 15

  # OTel Collector + Tempo + Prometheus + Loki + Grafana in one demo image
  # (Grafana ships it for local dev, explicitly not production). All three
  # OTLP signals land on 4318; Grafana UI on 3000.
  otel-lgtm:
    image: grafana/otel-lgtm:0.29.2
    ports:
      - "3000:3000"
      - "4318:4318"
      - "4317:4317"
```

- [ ] **Step 8:** Offline: `./mvnw -q clean verify` (the two unit tests run; the app itself isn't started). Format. Commit: `feat: chat-web backend — three beans, one tool, and the observer becomes a stream`

---

### Task 4: The page

**Files:**
- Create: `nessy-examples/chat-web/src/main/resources/static/index.html`, `static/app.js`, `static/style.css`

**Interfaces:** consumes Task 3's endpoints and SSE event names exactly.

- [ ] **Step 1: `index.html`** — semantic skeleton: header (title + "new conversation" button), `<main id="log">`, `<section id="approvals">`, footer form (`<input id="text">`, send button). No framework, no CDN.
- [ ] **Step 2: `app.js`** (~120 lines, vanilla): conversation id from `location.hash` or `crypto.randomUUID()` (persist to hash + `localStorage`); on load `GET /api/conversations/{id}` → render transcript lines + approval cards + status; `send()` → POST the message with `fetch`, read the SSE body via `ReadableStream` + a ~15-line SSE parser (split on `\n\n`, parse `event:`/`data:` lines — write it in the plan's code block for the implementer):

```js
async function stream(response, handlers) {
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    let sep;
    while ((sep = buffer.indexOf("\n\n")) >= 0) {
      const chunk = buffer.slice(0, sep); buffer = buffer.slice(sep + 2);
      let event = "message", data = "";
      for (const line of chunk.split("\n")) {
        if (line.startsWith("event:")) event = line.slice(6).trim();
        else if (line.startsWith("data:")) data += line.slice(5).trim();
      }
      handlers[event]?.(data ? JSON.parse(data) : {});
    }
  }
}
```

  Handlers: `delta` appends to the open assistant bubble; `thinking` renders a collapsed muted line; `tool-*` render the 🔧 line states; `approval-needed` renders a card with Approve/Deny buttons (POST `/api/approvals/{token}`, stream the response through the same handlers); `done` re-enables input and, if `FAILED`, renders the reason as a system line. Input disabled while any stream is open.
- [ ] **Step 3: `style.css`** — minimal chat layout (bubbles right/left, muted tool lines, a bordered approval card), ~60 lines, system fonts, both color-schemes via `prefers-color-scheme`.
- [ ] **Step 4:** No JS unit tests (vanilla page; Task 5's smoke covers the contract; manual demo covers rendering). Verify the reactor still builds (resources only). Format (spotless may not cover js/css — fine). Commit: `feat: chat-web page — a chat log, a coupon card, and no framework`

---

### Task 5: The Boot smoke — the whole wiring, one test

**Files:**
- Create: `nessy-examples/chat-web/src/test/java/org/jwcarman/nessy/examples/chatweb/ChatWebSmokeTest.java`

**Interfaces:** consumes everything; proves spec §6.

- [ ] **Step 1:** `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `@Tag("container")` + Testcontainers `PostgreSQLContainer` via `@DynamicPropertySource` (datasource props from the container; `spring.docker.compose.enabled=false`; o11y quiet per spec §5a: `management.tracing.export.enabled=false` and `management.otlp.metrics.export.enabled=false` so no exporter retry-spams a collector that isn't there). A `@TestConfiguration` overrides the provider bean chain: the cleanest seam is overriding the `Harness` bean wholesale with one built on a scripted `ModelProvider` (copy the two-turn scripted-provider pattern from `ListenerDeclarationsTest` in nessy-core: first call emits `ToolUseEmitted(issue_coupon)` + `TurnEnded(TOOL_USE)`, second emits `TextChunk("coupon issued, anything else?")` + `TurnEnded(END_TURN)`), same store bean, real `JdbcMemory`. `ANTHROPIC_API_KEY` must NOT be required: ensure `NessyConfig`'s provider construction lives in its own `@Bean` that the test profile replaces (`@Profile("!test")` on the real provider bean + `@ActiveProfiles("test")` — implement that split back in Task 3's `NessyConfig` now if you didn't; it's the standard Boot idiom and part of this step).
- [ ] **Step 2:** The one scenario, with `TestRestTemplate`/`RestClient` + manual SSE reading: POST a message → collect events → assert order contains `tool-requested` then `approval-needed` (capture token) and terminal `done` with `PARKED`; GET the conversation → transcript has the user line, approvals has the card; POST the approval `allow` → events contain `tool-decided`, `tool-completed`, `delta`("coupon issued…"), `done` with `COMPLETE`; GET again → approvals empty, transcript carries the assistant answer. One test method, prose name `the_whole_durable_story_on_one_page`.
- [ ] **Step 3:** Run with Docker: `./mvnw -pl nessy-examples/chat-web test -Dnessy.excludedGroups=live`. Offline reactor verify (smoke excluded) green. Format. Commit: `test: the smoke proves the page — park, survive, approve, complete`

---

### Task 6: Docs

**Files:**
- Create: `nessy-examples/chat-web/README.md` (run instructions + the spec §7 demo script verbatim including step 6 — open Grafana at `http://localhost:3000`, find the turn's trace in Tempo, logs in Loki — adapted to command reality)
- Modify: root `README.md` (an "Examples" section: chat-cli one-liner, chat-web paragraph + run command), `CHANGELOG.md` (JdbcMemory under the store module's entry; the examples restructure; the chat-web example), spec status flip to `IMPLEMENTED (see plan 2026-08-13-chat-web-example)`.

- [ ] **Step 1:** Write the three docs in the repo voice; verify the run command actually matches (`./mvnw -pl nessy-examples/chat-web spring-boot:run` — test it resolves the module path; adjust to the real invocation if the aggregator path differs).
- [ ] **Step 2:** Offline verify; format; commit: `docs: the demo has a doorbell — run it, break it, approve it`

---

## Self-review notes (performed at plan time)

- **Spec coverage:** §2 → Task 1; §3 → Task 2 (+3 for the module add); §4 → Task 3 (+ the `Agent.converse(ConversationId)` discovery clause — the spec assumed id-addressable conversations; the plan makes the gap explicit and sanctions the tiny core addition if confirmed); §5 → Task 3 step 7; §5a (observability) → Task 3 steps 1/5/7 (deps + `ObservationRegistry` into the harness + appender/initializer + yaml + otel-lgtm compose service), Task 5 (o11y disabled in tests), Task 6 (Grafana demo step); §6 → Tasks 1/5; §7 → Task 6's README; §8 honored by absence; §9.1 resolved (extract = add two methods to `StateCodec`), §9.2 resolved (Boot 4.1.0, locally cached).
- **Placeholder scan:** clean; every code step carries real code or an exact copy-from pointer to a named existing file.
- **Type consistency:** SSE event names identical across Tasks 3/4/5; `SseEvents.Event(name, payload)` used consistently; endpoint paths identical in §4 table, controllers, JS, and smoke.
- **Known risk, named:** the `Conversation`-by-id gap (Task 3 step 6). If `Agent` already exposes it, the clause is a no-op; if not, the addition is one overload + one test in nessy-core, reviewed with the task.

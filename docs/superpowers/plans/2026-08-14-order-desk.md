# The Order Desk Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A message-driven Spring Boot example (`nessy-examples/order-desk`) where the broker initiates turns: order events on a queue drive per-order conversations; the fulfillment tool parks over an AMQP reply queue (token = correlation id); at-least-once redelivery is absorbed by the fold.

**Architecture:** See the spec — it is prescriptive. `@RabbitListener(orders)` tells the order's typed conversation (`Agent<OrderEvent>`, first typed-vocabulary dogfood); `request_fulfillment` publishes to `fulfillment-requests` and parks; an in-app warehouse listener replies on `fulfillment-replies` with `{kind: progress|completed}`; the reply listener routes to `harness.progress`/`harness.resume`. Durable substrate via the starter; compose supplies rabbitmq:4-management + postgres by service connection, start-only; AUTO acks (a ruling — the container's requeue-on-death IS the lesson).

**Tech Stack:** Java 25, Boot 4.1.0 (`spring-boot-starter-amqp`, Spring AMQP 4's `JacksonJsonMessageConverter` — Jackson 3, honors the vocabulary's `com.fasterxml` annotations; NOT the deprecated `Jackson2JsonMessageConverter`), the nessy starter, Testcontainers rabbitmq + postgresql with `@ServiceConnection` (add `spring-boot-testcontainers` test dep), Awaitility (BOM-managed) for async assertions.

**Spec:** `docs/superpowers/specs/2026-08-14-order-desk-design.md` — binding; read before any task. Queue names are constants stated once: `orders`, `fulfillment-requests`, `fulfillment-replies`.

## Global Constraints

- TDD where a test is prescribed; RED/GREEN evidence in reports. Container command: `./mvnw -q verify -pl nessy-examples/order-desk -am -Dnessy.excludedGroups=live` (Docker required). Offline `./mvnw -q clean verify` green after EVERY task — no Docker, no key.
- Before every commit: `./mvnw license:format -Plicense && ./mvnw spotless:apply`, re-stage. Never hand-write license headers. Never stage IDE metadata.
- No suppressions, no star imports, no mocking libraries (hand-rolled doubles fine), prose snake_case test names, S5778/S5841, no `Thread.sleep` in tests (Awaitility), sealed-grammar etiquette.
- **The agent is `Agent<OrderEvent>` — spec §9.6: an `Agent<String>` here is a defect, not a shortcut.**
- Package `org.jwcarman.nessy.examples.orderdesk`; module dir `nessy-examples/order-desk`; artifactId `nessy-example-order-desk`. Boot BOM confined in-module. `<maven.deploy.skip>true</maven.deploy.skip>`.
- Commit messages in house style + trailer `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.

---

### Task 1: Scaffold — pom, app, yaml, compose

**Files:** modify `nessy-examples/pom.xml` (add `<module>order-desk</module>`); create `nessy-examples/order-desk/pom.xml`, `.../orderdesk/OrderDeskApplication.java`, `src/main/resources/application.yaml`, `docker-compose.yml`.

- Pom: copy night-watchman's discipline (BOM import, deploy-skip, plain starter, logback-classic compile, mockito-excluded test starter) PLUS: `spring-boot-starter-amqp`; `nessy-store-jdbc` + `org.postgresql:postgresql` (runtime); `spring-boot-docker-compose` (runtime, optional); test deps `spring-boot-testcontainers`, `org.testcontainers:rabbitmq`, `org.testcontainers:postgresql`, `org.testcontainers:junit-jupiter`, `org.awaitility:awaitility`. `spring-boot-maven-plugin` without repackage (demo runs `spring-boot:run`).
- App class: `@SpringBootApplication`, plain `SpringApplication.run` main (long-lived; the AMQP listener container keeps it alive). Javadoc: "the broker decides when the agent thinks."
- application.yaml: `spring.application.name: nessy-order-desk`, `web-application-type: none`, `banner-mode: off`, `spring.docker.compose.lifecycle-management: start-only`. NO datasource/rabbitmq coordinates — service connections supply them (spec §4); state that in a comment.
- docker-compose.yml: `rabbitmq:4-management` (ports 5672, 15672) and `postgres:17-alpine` (5432, nessy/nessy/nessy env — chat-web's exact service). No Boot labels needed — service connections are image-detected.
- Verify offline build; format; commit: `feat: the order desk is scaffolded — a broker will do the knocking`

### Task 2: The vocabulary — sealed OrderEvent + offline round-trip test

**Files:** create `OrderEvent.java`; test `OrderEventTest.java`.

Spec §3's shape verbatim, with the `@JsonSubTypes` entries named exactly as the demo publishes them (`OrderPlaced`, `PaymentCleared`, `AddressChanged`, `CustomerInquiry` — `@JsonSubTypes.Type(value = OrderEvent.OrderPlaced.class, name = "OrderPlaced")` etc.). Records get compact-constructor null checks (house record hygiene); `List<String> items` copied via `List.copyOf`. Test (offline, TDD): every variant serializes with its `type` tag and round-trips through a classic `ObjectMapper` AND through a `tools.jackson.databind.json.JsonMapper` (the AMQP converter's engine — this cross-engine case is the load-bearing one; if tools-jackson is awkward to reference from this module's test scope, prove the converter path instead in Task 5's container test and say so); `orderId()` reachable through the sealed interface for each variant. Commit: `feat: the order vocabulary — four events, one sealed story`

### Task 3: Queue topology + the fulfillment tool

**Files:** create `Queues.java` (constants + `Declarable` beans), `RequestFulfillmentTool.java`, `OrderDeskConfig.java`; test `RequestFulfillmentToolTest.java`.

- `Queues`: `public static final String ORDERS = "orders"; FULFILLMENT_REQUESTS = "fulfillment-requests"; FULFILLMENT_REPLIES = "fulfillment-replies";` plus a `@Configuration` declaring the three durable `Queue` beans (default exchange; no bindings needed).
- `RequestFulfillmentTool implements Tool<RequestFulfillmentTool.Input>`; `record Input(String orderId, List<String> items)`; name `request_fulfillment`; description written for the model ("Sends the order to the warehouse. Slow: confirmation arrives later."). `execute`: `ParkToken token = ParkToken.generate();` publish via injected `RabbitTemplate` to `FULFILLMENT_REQUESTS` with `message -> { message.getMessageProperties().setCorrelationId(token.value()); return message; }` post-processor (`convertAndSend(queue, payload, postProcessor)`), payload = a small `record FulfillmentRequest(String orderId, List<String> items)`; `context.progress("fulfillment requested; awaiting the warehouse")`; return `Awaited.parked(token)`. **The token rides ONLY as the AMQP correlation id — never in the payload** (spec §1: the correlation contract made wire-visible).
- `OrderDeskConfig`: the ONE nessy bean — `Agent<OrderEvent> agent(Harness harness, Memory memory, RabbitTemplate rabbit)` → `harness.agent(OrderEvent.class).model("claude-sonnet-4-5").systemPrompt(ORDER_DESK_ORDERS).memory(memory).tools(ToolGrant.grant(new RequestFulfillmentTool(rabbit), UsagePolicy.allow())).onToolProgressAsync(log).build()`. Standing orders: you are an order desk; each event arrives as tagged JSON for one order; when an OrderPlaced arrives, request fulfillment with your tool; answer inquiries from this order's own history; be terse. ALSO: the `JacksonJsonMessageConverter` `@Bean` (Boot wires it into template + listener factory automatically when a `MessageConverter` bean exists).
- Tool unit test (offline): identity (name/inputType), and `execute` against a recording `RabbitTemplate`... `RabbitTemplate` is a class — hand-rolled subclass overriding `convertAndSend(String routingKey, Object payload, MessagePostProcessor)` to capture (allowed: hand-rolled double, no library). Assert: parked (not ready), captured payload carries orderId/items, captured post-processor sets the returned token as correlation id (apply it to a fresh `MessageProperties`-backed Message and read it back), token in the `Awaited.Parked` equals the correlation id set. This is the correlation-contract test — the plan's most load-bearing offline assertion. Commit: `feat: the fulfillment tool parks on the wire — the token is the correlation id`

### Task 4: The listeners — orders in, warehouse, replies back

**Files:** create `OrderDesk.java` (the orders listener), `Warehouse.java`, `FulfillmentReplies.java` (+ `record FulfillmentReply(String kind, String text)` where it best lives — suggest inside `FulfillmentReplies`).

- `OrderDesk`: `@Component`; injects `Agent<OrderEvent>`; `@RabbitListener(queues = Queues.ORDERS) void on(OrderEvent event)` → `agent.conversation(new ConversationId("order-" + event.orderId())).tell(event, observer)` with a logging observer (night-watchman's render pattern: accumulate TextDelta, log tool calls/parks; log `round`-style begin/end lines with the order id). AUTO ack — no ack code anywhere (spec §4 ruling; say so in the javadoc).
- `Warehouse`: `@Component`; `@RabbitListener(queues = Queues.FULFILLMENT_REQUESTS) void on(FulfillmentRequest request, @Header(AmqpHeaders.CORRELATION_ID) String correlationId)` → publishes to `FULFILLMENT_REPLIES` a progress reply (`kind=progress`, "picking N items for order X…") then a completed reply (`kind=completed`, "shipped: tracking NESSY-" + deterministic hash of orderId), both with the correlation id preserved via post-processor. Obviously fake, javadoc says so (coupon-tool ethos).
- `FulfillmentReplies`: `@Component`; injects `Harness` AND `Agent<OrderEvent>` (the agent injection is what guarantees the harness has a built loop — night-watchman's Verbs precedent, cite it); `@RabbitListener(queues = Queues.FULFILLMENT_REPLIES) void on(FulfillmentReply reply, @Header(AmqpHeaders.CORRELATION_ID) String correlationId)` → token = `new ParkToken(correlationId)`; `kind=progress` → `harness.progress(token, reply.text())`, false logged as stale narration; `kind=completed` → `try { harness.resume(token, new ToolResolution.Completed(ToolResult.ok(reply.text())), observer) } catch (UnknownParkTokenException e) { log stale }`. Turn completes on this listener thread — the broker's delivery IS the drive (spec §4); a `RuntimeException` escaping lets AUTO nack/requeue do its at-least-once job (do NOT catch broadly — the requeue is the design; javadoc states it).
- No new tests in this task beyond compilation (the smoke test is Task 5's; the listeners are integration creatures) — but the module must still compile in the offline build. Commit: `feat: three listeners — orders arrive, the warehouse answers, the desk resumes`

### Task 5: The smoke test — the whole story against real broker + database

**Files:** test `OrderDeskSmokeTest.java`.

Container-tagged `@SpringBootTest`, `@Testcontainers`, `RabbitMQContainer` + `PostgreSQLContainer` both `@ServiceConnection`, `spring.docker.compose.enabled=false` property; `@TestConfiguration` Harness over a scripted provider (chat-web pattern, `ObjectProvider<ObservationRegistry>`), sync `onToolProgress`/`onToolFinished` listeners into static lists. Script by call index: call 1 → `ToolUseEmitted(new ToolCall("c1", "request_fulfillment", argsFor("4711", ["lantern","rope"])))` + TOOL_USE; call 2 → TextChunk("Order 4711 fulfilled — " + the tracking text) + END_TURN; later calls → short all-quiet texts + END_TURN (inquiry answers).

One story method, Awaitility for every cross-thread assertion (`untilAsserted`), prose name `the_broker_drives_and_the_order_remembers`:
1. Publish `OrderPlaced("4711", [lantern, rope])` via `RabbitTemplate` to `orders`. Await: conversation `order-4711` reaches... the park happens then the warehouse replies asynchronously and the turn completes — await final `COMPLETE` via `agent.snapshot(new ConversationId("order-4711"))`; along the way assert (from the recorded listener lists) that a `ToolProgress` with "picking" text was heard (the from-afar lane) and that `request_fulfillment` finished with the tracking text (`ToolFinished`).
2. Assert the transcript's final assistant text contains the tracking number (scripted).
3. Duplicate-reply idempotency: re-publish a `completed` reply for the SAME correlation id (recover it — assert it from the registry via `harness.peek`-accessible path: capture the token by a sync `onToolProgress`… simpler: query `parks.forConversation` is not injectable here; instead capture the correlation id in the test by consuming? Cleanest: subscribe a test `@RabbitListener`? — NO: re-publish using the token obtained from `agent.snapshot` BEFORE completion (poll until PARKED first, record `snapshot.parkedCalls().getFirst().token()`, THEN let the warehouse complete it — ordering note: the warehouse is fast, so instead publish the duplicate AFTER completion using that recorded token). Await a beat (Awaitility on the listener list length staying stable is flaky — instead assert via the scripted provider's call count: it must remain exactly 2 for this order after the duplicate, `untilAsserted` + a fixed `during`? Awaitility `.during(Duration)` holds a condition true for a window — use `await().during(ofSeconds(2)).atMost(ofSeconds(5)).until(() -> provider.calls() == expected)`). The duplicate must not re-drive a resume of the tool nor add model calls.
4. Second order isolation: publish `OrderPlaced("9000", …)` (script serves later calls); await its COMPLETE; assert `order-9000`'s transcript does NOT contain 4711's items and vice versa.

If capturing the park token before the warehouse completes proves racy, the sanctioned alternative (state it in the report): disable the warehouse bean in the test context (`@SpringBootTest(properties = "order-desk.warehouse.enabled=false")` — which requires adding `@ConditionalOnProperty(name = "order-desk.warehouse.enabled", havingValue = "true", matchIfMissing = true)` to `Warehouse` in Task 4; ADD IT PROACTIVELY in Task 4) and have the test play warehouse itself: consume the request message, read its correlation id, reply once, reply the duplicate. This is the more deterministic design — PREFER IT from the start; the property also lets a future demo run the desk against a real external warehouse.

Commit: `test: the whole order story against a real broker — parked, narrated, resumed, replayed`

### Task 6: Paperwork — README, matrix, CHANGELOG

- Module README: spec §6's demo script verbatim-in-spirit (run command, management-console publishes with exact JSON bodies, the kill scene), what it demonstrates (§1's five lessons), the correlation-id contract called out, AUTO-ack ruling explained, both containers' consoles (15672 guest/guest).
- Root README Examples: four examples now; matrix sentence per spec §9.7; entry format matches siblings.
- CHANGELOG: one `### Added` bullet at section end (the trigger, the typed-vocabulary first, the machine-half-over-AMQP, the correlation-id contract, AUTO-ack ruling).
- Full offline verify + full container sweep (`-Dnessy.excludedGroups=live`). Commit: `docs: the order desk signs the paperwork — four examples, one broker`

---

## Self-Review Notes (already applied)

- Task 5's warehouse-toggle property is pulled INTO Task 4 proactively so the deterministic test design needs no Task-4 revisit.
- The correlation-contract has two independent proofs: offline (Task 3's captured post-processor) and integration (Task 5's replies resolving the right park).
- The `JacksonJsonMessageConverter`/classic-annotations interaction is verified early (Task 2's cross-engine round-trip) with a stated fallback proof site (Task 5) if tools-jackson isn't referenceable from the module's test scope.
- Redelivery-on-death (the §6 kill scene) is deliberately demo-only — no automated kill test; the duplicate-reply test covers the replay-protection half that CAN be automated honestly. State this in the module README rather than implying the kill is tested.

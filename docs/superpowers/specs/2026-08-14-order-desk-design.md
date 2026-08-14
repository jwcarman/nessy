# The Order Desk — design

**Date:** 2026-08-14
**Status:** APPROVED — 2026-08-14 (design reviewed in session)
**Builds on:** the three-front-doors rework (2026-08-14, shipped) — the
inbox grammar and the fold-owned replay protection are this example's
load-bearing walls — and the Spring Boot starter (2026-08-13, shipped).

---

## 1. Purpose — the world assigns

Exhibit the queue-driven trigger model: a message on a broker is what
initiates a turn. Five lessons, three of them firsts for the family:

- **the queue as driver** — `@RabbitListener` → deposit mail → drive; no
  human present, no clock, the broker decides when the agent thinks;
- **external-identity routing** — the order id mints the
  `ConversationId`, so each order is one conversation that remembers its
  own history, and every event about it lands in the same story;
- **at-least-once, made visible** — kill the app mid-turn and restart:
  the broker redelivers the unacked message and the fold's own
  idempotency absorbs the replay. The store rework's "replay protection
  is the fold's question" ruling, demonstrated by a real broker
  misbehaving on cue;
- **the machine half, over AMQP** — the tool-side park
  (`Awaited.parked` from `execute`), `ToolResolution.Completed`
  delivered by `harness.resume` from a listener that never saw the ask,
  and `harness.progress` narration — with the park token riding as the
  AMQP **correlation id**: the kernel's "the token is the correlation
  contract" claim made wire-visible;
- **the first typed-vocabulary agent** — every existing example is
  `Agent<String>`; this one is `harness.agent(OrderEvent.class)` over a
  sealed event grammar, dogfooding the typed `tell` and the JSON
  renderer for the first time.

Success criterion: the demo script (§6) runs end to end from RabbitMQ's
own management console — publish order events by hand, watch each
order's conversation tell its story in the log, watch a fulfillment
request park and its reply resume, and kill/restart anywhere without
losing anything.

## 2. The story

An order desk. The `orders` queue carries order lifecycle events as
JSON. Each event is `tell`-ed to its order's own conversation; the agent
keeps the order's running story, answers inquiries from that history,
and when an order is placed it calls its one tool.

`request_fulfillment` publishes a job to the `fulfillment-requests`
queue — correlation id = the park token — and parks. A small in-app
"warehouse" listener plays the world (the coupon-tool ethos: obviously
fake, structurally honest): it consumes the request, publishes a
progress message ("picking 2 items…") and then a completion ("shipped:
tracking NESSY-…", derived deterministically from the order id) onto the
`fulfillment-replies` queue, correlation id preserved. The reply
listener routes by kind: progress → `harness.progress(token, …)`
(narration, drop-legal), completed → `harness.resume(token,
Completed(ok(…)))` — and the turn finishes in whatever process the reply
reached.

## 3. The vocabulary

```java
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({ ... one entry per record ... })
public sealed interface OrderEvent {
  String orderId();

  record OrderPlaced(String orderId, List<String> items) implements OrderEvent {}
  record PaymentCleared(String orderId) implements OrderEvent {}
  record AddressChanged(String orderId, String newAddress) implements OrderEvent {}
  record CustomerInquiry(String orderId, String question) implements OrderEvent {}
}
```

The agent is `harness.agent(OrderEvent.class)` — the typed `tell` with
the default JSON renderer over the harness mapper. The conversation id
is minted from external identity: `new ConversationId("order-" +
event.orderId())`. The same Jackson tagging serves both jurisdictions:
the queue's message converter deserializes it, the renderer shows the
model tagged JSON.

## 4. Module

`nessy-examples/order-desk` (artifactId `nessy-example-order-desk`,
deploy-skipped; package `org.jwcarman.nessy.examples.orderdesk`). A
long-lived Spring Boot app, no web of its own (`web-application-type:
none`; RabbitMQ's management UI is the demo's only surface). The log is
the UI, banner off.

- Dependencies: `spring-boot-starter`, `spring-boot-starter-amqp`,
  `logback-classic` (compile), `nessy-spring-boot-starter`,
  `nessy-model-anthropic`, `nessy-store-jdbc`,
  `org.postgresql:postgresql` (runtime), `spring-boot-docker-compose`
  (runtime, optional), mockito-excluded test starter, Testcontainers
  (`rabbitmq` + `postgresql`) test-scoped. Boot BOM confined in-module.
- Compose: `rabbitmq:4-management` (5672 + 15672) and
  `postgres:17-alpine`, both auto-wired by Boot **service connections**,
  `lifecycle-management: start-only` — the containers outlive app exits,
  so kill-and-restart keeps both the broker's unacked messages and the
  database's conversations.
- Substrate: durable via the starter (all three doors + transcript
  memory arrive by classpath and datasource). The example's own nessy
  config is ONE bean: the typed agent — model `claude-sonnet-4-5`, the
  order-desk standing orders, `request_fulfillment` granted
  `UsagePolicy.allow()`, an `onToolProgressAsync` logging listener.
- Queues (`orders`, `fulfillment-requests`, `fulfillment-replies`)
  declared as beans, published to via the default exchange; names are
  constants in one class, stated once.
- **Acknowledgement mode is Boot's default AUTO — a ruling, not an
  omission**: the container acks on successful listener return and
  requeues on failure or death, which IS the at-least-once lesson with
  zero channel plumbing. Manual acks would teach RabbitMQ API trivia,
  not the trigger model.
- Listener flow: `orders` listener converts to `OrderEvent`, tells the
  order's conversation with a logging observer, lets AUTO ack it.
  Turns run on the listener container's thread — the broker's delivery
  IS the drive; there is no detached-turn machinery here on purpose.

## 5. The warehouse

In-app, one `@RabbitListener` on `fulfillment-requests` — the demo's
fake world. Reply messages on `fulfillment-replies` carry
`{kind: "progress"|"completed", text: …}` plus the preserved correlation
id. Progress precedes completion on the same queue and channel, so
ordering holds without ceremony. The reply listener's two arms map
exactly onto the two callback verbs; a reply whose token the registry
no longer knows (or whose call already settled) drains as stale mail —
`Harness.progress` returns false and is logged, a duplicate resume
re-drives idempotently. Nothing in the warehouse is clever; it exists so
the demo needs no second process to be honest about cross-process
delivery (the kill/restart scene supplies the process boundary).

## 6. The demo script (the acceptance test by hand)

1. `ANTHROPIC_API_KEY=… ./mvnw -pl nessy-examples/order-desk
   spring-boot:run` — compose starts both containers; the app connects
   by service connection.
2. Open `http://localhost:15672` (guest/guest), publish to `orders`:
   `{"type":"OrderPlaced","orderId":"4711","items":["lantern","rope"]}`
   — watch the log: the order's conversation opens, the agent calls
   `request_fulfillment`, the tool parks, the warehouse narrates
   progress (`harness.progress`, heard by the declared listener), then
   the reply resumes and the turn completes.
3. Publish `{"type":"CustomerInquiry","orderId":"4711","question":"where
   is my order?"}` — the agent answers FROM THE ORDER'S OWN HISTORY
   (tracking number included): external-identity routing at work.
4. Publish an event for `orderId: 9000` — a different conversation,
   ignorant of 4711: one conversation per order, proven.
5. The kill scene: publish another order, and kill the app the moment
   the log shows the turn begin. Restart. The broker redelivers the
   unacked event, the durable store remembers whatever the fold had
   already committed, and the turn completes — nothing lost, nothing
   doubled.

## 7. Testing

Container-tagged `@SpringBootTest` with Testcontainers RabbitMQ and
Postgres (both via `@ServiceConnection`), scripted provider bean behind
the chat-web pattern (`@ConditionalOnMissingBean(Harness.class)`; no
key, no network), publishing real messages via `RabbitTemplate` and
awaiting outcomes with Awaitility (the queue is genuinely asynchronous;
no sleeps):

- an `OrderPlaced` lands → the order's conversation exists, status
  `PARKED`, one park registered whose token equals the correlation id
  the warehouse request carried;
- the warehouse round-trip completes: progress heard by a declared sync
  listener, then resume → `COMPLETE`, transcript quoting the fake
  tracking number;
- a re-published duplicate completion reply drains as stale mail — the
  resume count stays at one (the fold's replay protection against the
  wire);
- two orders never share a conversation;
- offline: the vocabulary's Jackson round-trip (every variant tagged,
  deserialized, rendered) with no broker and no database.

Offline reactor `verify` stays green with no Docker and no key.

## 8. Deliberately not built

Dead-letter queues and retry topology (the requeue default is the
lesson; poison-message policy is production's problem), competing
consumers and scaling notes, broker security beyond the dev-image
defaults, a web surface of our own, outbound event publishing by the
agent (tools could publish; one tool is the lesson), and schema
versioning for the event vocabulary.

## 9. Resolved at review (2026-08-14)

1. Sequenced ahead of the webhook/A2A-client example (user ruling —
   "AMQP first"); that example slims to A2A-client + HTTP signals when
   its turn comes ([[nessy-trigger-matrix-plan]] in session memory).
2. RabbitMQ over NATS/Kafka: first-party Boot starter + compose service
   connection; the lesson stays the trigger model, not client wiring.
3. Machine-half verbs included via the reply queue (their third home
   after two retirements — this one sticks because the broker is
   honest).
4. Durable substrate (broker outlives the app; conversations must too).
5. AUTO acks over manual (§4's ruling).
6. Typed vocabulary claimed as a stated first (§1) — not optional
   decoration; reviewers should treat `Agent<String>` here as a defect.
7. The examples matrix now reads: chat-cli (plain + interactive),
   chat-web (Boot web + HITL), night-watchman (Boot + scheduled
   autonomy), order-desk (Boot + message-driven autonomy).

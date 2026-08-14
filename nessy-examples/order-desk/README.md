# Nessy Example: Order Desk

The fourth example, and the first where a queue is the trigger: a message
landing on RabbitMQ's `orders` queue is what initiates a turn — no human
present, no clock, the broker decides when the agent thinks. Five lessons,
three of them firsts for the family:

- **the queue as driver** — `@RabbitListener` → deposit mail → drive;
- **external-identity routing** — the order id mints the `ConversationId`,
  so each order is one conversation that remembers its own history, and
  every event about it lands in the same story;
- **at-least-once, made visible** — kill the app mid-turn and restart: the
  broker redelivers the unacked message and the fold's own idempotency
  absorbs the replay;
- **the machine half, over AMQP** — the tool-side park rides the AMQP
  correlation id, and a listener that never saw the ask delivers the
  resolution that resumes it;
- **the first typed-vocabulary agent** — every other example is
  `Agent<String>`; this one is `harness.agent(OrderEvent.class)` over a
  sealed event grammar.

## The story

An order desk. The `orders` queue carries order lifecycle events as JSON,
each tagged with a `type` field (`OrderPlaced`, `PaymentCleared`,
`AddressChanged`, `CustomerInquiry`). Each event is `tell`-ed to its order's
own conversation (`new ConversationId("order-" + orderId)`); the agent keeps
the order's running story and answers inquiries from that history alone.
When an order is placed, the agent calls its one tool, `request_fulfillment`,
which publishes a job to the `fulfillment-requests` queue — correlation id =
the park token — and parks.

An in-app "warehouse" (`Warehouse`, one `@RabbitListener`) plays the world:
obviously fake, structurally honest, the same coupon-tool ethos as
`chat-web`'s demo tool. It consumes the request, publishes a progress
message ("picking N items…") and then a completion ("shipped: tracking
NESSY-…", derived deterministically from the order id) onto the
`fulfillment-replies` queue, correlation id preserved. The reply listener
(`FulfillmentReplies`) routes by kind: `progress` → `harness.progress(token,
…)` (narration, drop-legal), `completed` → `harness.resume(token,
Completed(ok(…)))` — and the turn finishes in whatever process the reply
reached.

## The correlation-id contract

The park token never appears in either message's JSON body.
`RequestFulfillmentTool` sets it as the AMQP correlation id on the outbound
`fulfillment-requests` message; `Warehouse` reads that same header and
echoes it, unchanged, onto both `fulfillment-replies` messages;
`FulfillmentReplies` reads it back off the reply and hands it straight to
`harness.progress`/`harness.resume`. The wire payloads never carry a token
field at all — the kernel's "the token is the correlation contract" claim,
made wire-visible.

## The AUTO-ack ruling

Acknowledgement mode is Spring Boot's default, AUTO — a ruling, not an
omission. The listener container acks a message on successful return from
`OrderDesk#on`/`FulfillmentReplies#on` and requeues it on failure or death.
That IS the at-least-once lesson, with zero manual channel plumbing. Neither
listener class touches an ack API anywhere; a `RuntimeException` escaping
`FulfillmentReplies#on` is left to propagate on purpose, so a listener death
mid-delivery nacks and requeues rather than silently swallowing the reply.
Manual acks would teach RabbitMQ API trivia, not the trigger model.

## Run it

Requires a real Anthropic key and Docker, to run the compose stack (RabbitMQ
plus Postgres) that `spring-boot-docker-compose` starts automatically and
leaves running between app runs (`start-only` lifecycle — the containers
outlive app exits, so kill-and-restart keeps both the broker's unacked
messages and the database's conversations). No coordinates for either
container live in `application.yaml`: Boot's service connections detect
both from `docker-compose.yml` and wire the app to whatever port each
container actually mapped, so this module has no hardcoded coordinates
anywhere.

```bash
ANTHROPIC_API_KEY=… ./mvnw -pl nessy-examples/order-desk spring-boot:run
```

Postgres is published on host port **5433** (not the usual 5432) so this
module's compose stack can coexist with `chat-web`'s, which owns 5432 by its
explicit datasource URL — service connections don't care which host port a
container landed on, so the app itself needs no change, but reach for
`psql -h localhost -p 5433 -U nessy nessy` if you want to look at the
conversations table by hand while both stacks are up.

## The demo script (the acceptance test by hand)

This is the acceptance test, run by hand from RabbitMQ's own management
console at <http://localhost:15672> (guest/guest):

1. `ANTHROPIC_API_KEY=… ./mvnw -pl nessy-examples/order-desk spring-boot:run`
   — compose starts both containers; the app connects by service connection.
2. In the management console, publish to the `orders` queue (exchange:
   default, routing key: `orders`), with this body:

   ```json
   {"type":"OrderPlaced","orderId":"4711","items":["lantern","rope"]}
   ```

   Watch the log: the order's conversation opens, the agent calls
   `request_fulfillment`, the tool parks, the warehouse narrates progress
   (`harness.progress`, heard by the declared logging listener), then the
   reply resumes and the turn completes.
3. Publish to `orders` again:

   ```json
   {"type":"CustomerInquiry","orderId":"4711","question":"where is my order?"}
   ```

   The agent answers FROM THE ORDER'S OWN HISTORY (tracking number
   included) — external-identity routing at work.
4. Publish an event for a different order:

   ```json
   {"type":"OrderPlaced","orderId":"9000","items":["compass"]}
   ```

   A different conversation, ignorant of `4711`: one conversation per order,
   proven.
5. **The kill scene.** Publish another order, and kill the app (`Ctrl-C`)
   the moment the log shows the turn begin — before the warehouse's reply
   arrives. Restart it (`./mvnw -pl nessy-examples/order-desk
   spring-boot:run` again). The broker redelivers the unacked event (or the
   warehouse's own reply, whichever was in flight), the durable store
   remembers whatever the fold had already committed, and the turn
   completes — nothing lost, nothing doubled.

**This kill/restart scene is deliberately by-hand — there is no automated
kill test.** Killing a JVM on cue mid-turn and asserting on what a real
broker does next isn't something the automated suite stages honestly; the
automated suite instead covers the half of the story that CAN be tested
without theater — a duplicate delivery of the *same* completion reply
(`RequestFulfillmentToolTest`'s round trip plus the smoke test's stale-mail
case) proves the fold's own replay protection drains a redelivered resolve
quietly rather than re-running the tool. Treat the automated duplicate-reply
test as the honest automated half of this lesson, and the steps above as
the only way to see the other half — the actual kill — for yourself.

## The warehouse toggle

`order-desk.warehouse.enabled` (default `true`) turns the in-app `Warehouse`
listener on or off. The container test suite sets it to `false` so the test
itself can play warehouse deterministically — publishing scripted replies on
its own schedule — instead of racing the real one; the demo leaves it at its
default so a freshly started app needs nothing extra.

## What this example deliberately does not build

Dead-letter queues and retry topology (the requeue default is the lesson;
poison-message policy is production's problem), competing consumers and
scaling notes, broker security beyond the dev-image defaults, a web surface
of its own, outbound event publishing by the agent, and schema versioning
for the event vocabulary. See
`docs/superpowers/specs/2026-08-14-order-desk-design.md` §8 for the full
list and the reasoning.

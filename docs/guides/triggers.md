# Triggers

A conversation doesn't care who starts a turn. `agent.converse().tell(...)`
looks the same whether the caller is a person at a keyboard, a browser
request, a cron firing, or a message landing on a queue — the durable inbox
absorbs the telling either way. This guide surveys the five trigger shapes
the example family demonstrates, one app per shape.

| Trigger | Example | What initiates a turn |
|---|---|---|
| Terminal | `chat-cli` | A person typing at a REPL prompt. |
| Web | `chat-web` | A browser `POST`, streamed back over SSE. |
| Clock | `night-watchman` | `@Scheduled` firing on a cron cadence. |
| Webhook | `dispatcher` | An inbound `curl`/HTTP call from another system. |
| Queue | `order-desk` | A message landing on a RabbitMQ queue. |

## Terminal

`chat-cli`'s `Chat` main drives `ConsoleRepl`, which reads a line, tells the
agent, and renders deltas as they stream. See [Console Apps](console-apps.md)
for the full builder chain — this is the trigger every other example's
README compares itself against, since it's the shape most guides start from.

## Web

`chat-web` is a Spring Boot app: the browser's `POST` starts a turn on a
virtual thread, streamed back to the page over Server-Sent Events. The
approver is `Approver.parkAll()` — every approval parks, and the browser
itself is the thing that answers it, later, in whatever process happens to
be running when the click arrives. Kill the app mid-approval and restart it:
the transcript is intact (it lived in Postgres, not the JVM's heap) and the
approval card is still there, because the park is a durable row, not process
state.

## Clock

`night-watchman` has no web, no database, no Docker — its only front door is
`@Scheduled` firing on a cron expression (`watchman.cadence`, default the
top of every minute). Each firing tells the *same* conversation, so trend
judgment across rounds is conversation state at work, not something the app
tracks separately. Bounding what an endless, never-parking conversation lets
the model see is one `Memory.pipeline(...).keepRecent(window)` stage over an
in-memory `Transcript`.

## Webhook

`dispatcher` answers plain `curl` rather than a browser or the clock. The
inbox has exactly two kinds of mail, and this app is nothing more than the
two doors that receive them:

- `POST /signals` — the world volunteers news. Fire and forget: the app
  deposits the signal, answers `202 Accepted` immediately, and drives the
  turn on a virtual thread.
- `POST /callbacks/{token}` and `POST /callbacks/{token}/progress` — the
  world answers a question. The app's one tool, `request_field_crew`, parks
  the moment it's called; these two endpoints are how the crew reports back
  — progress narrates (`agent.progress`), completion resumes the turn to
  done (`agent.resume`).

The headline scene is `JdbcParks` earning its keep: signal an incident, kill
the app, restart it, then answer the callback in the fresh process that
never saw the original signal. The park token survives the restart because
it lives in Postgres, not process memory.

## Queue

`order-desk` is the first example where a queue is the trigger: a message
landing on RabbitMQ's `orders` queue is what initiates a turn — no human
present, no clock, the broker decides when the agent thinks. The order id
mints the `ConversationId`, so every event about one order lands in the same
conversation's history. When an order is placed, the agent calls its one
tool, `request_fulfillment`, which publishes a job to a reply queue —
correlation id equal to the park token — and parks. A reply listener routes
by kind: progress narrates, completion resumes the turn, in whatever process
the reply reaches.

!!! warning "At-least-once delivery means a consumer can see an event twice"
    Kill the app mid-turn and restart it: the broker redelivers the
    unacked message and nothing is lost, but the fold's replay protection is
    precise about which half it absorbs. A redelivered *resolution* (the
    warehouse's completion reply) drains as stale mail — the resume never
    runs twice. A redelivered *order event* is re-told into the
    conversation, because there is no such guard on ordinary tellings — the
    desk genuinely cannot distinguish a duplicate delivery from a second,
    identical order update, and the example says so rather than pretending
    otherwise.

## Tell-while-parked

A parked conversation is not deaf. `AgentTold` can still land on a
conversation whose only outstanding call is parked — the inbox absorbs every
`tell` and every resolution concurrently, whether or not a turn is currently
running. `order-desk`'s `CustomerInquiry` events prove this in practice: an
inquiry about an order still mid-fulfillment is answered from that order's
own history, without waiting for the parked call to resolve first.

## Where next

- [Parks and Callbacks](../concepts/parks-and-callbacks.md) — the park
  token, the agent stamp, and the doors (`resume`, `progress`, `peek`)
  every callback-shaped trigger answers through.
- [The Durable Loop](../concepts/durable-loop.md) — `tell`, at-least-once
  execution, and why every trigger shape reduces to the same fold.
- [Spring Boot](spring-boot.md) — the starter wiring `dispatcher`,
  `order-desk`, `night-watchman`, and `chat-web` all build on.

# Nessy Example: Dispatcher

The fourth example, and the first to answer HTTP rather than a browser or the
clock: a Spring Boot app exhibiting the inbox's two trigger models over plain
`curl` (spec §1). The inbox has exactly two kinds of mail — the world
volunteering news, and the world answering a question — and this app is
nothing more than the two doors that receive them:

- **`POST /signals`** — the world volunteers news (a `Told` entry). Fire and
  forget: the app deposits the signal, answers `202 Accepted` immediately,
  and drives the turn on a virtual thread. The incident id mints the
  conversation id directly, so every signal about the same incident joins
  that incident's story.
- **`POST /callbacks/{token}`** and **`POST /callbacks/{token}/progress`** —
  the world answers a question (a `Resolved` entry). The dispatcher's one
  tool, `request_field_crew`, parks the turn the moment it's called — the
  crew is out in the world, not answerable synchronously — and these two
  endpoints are how the crew reports back: progress narrates
  (`harness.progress`), completion resumes the turn to done
  (`harness.resume`).

## The story

A dispatcher for field incidents. A signal opens (or continues) an incident's
conversation; the agent triages tersely and, for anything actionable, calls
`request_field_crew` exactly once. That call parks — there is no outbound
transport here, no queue publish, no webhook fired to notify a dispatch
system. The park token reaches the world two ways, deliberately: the app log
(the turn observer prints `ToolCallParked`) and `GET /incidents/{id}`'s
snapshot, which lists every open park's `(token, tool)` pair. The demo below
uses the `GET`, on purpose — after a restart, the log line from before the
restart is gone, but the registry (real Postgres, via `JdbcParks`) still
remembers.

## The headline scene: restart, then callback

The reason this example exists: `JdbcParks` earning its keep. Everything else
in `nessy-examples` either doesn't park (`chat-cli`, `night-watchman`) or
resolves its park in the same process it opened in (`chat-web`'s demo script
approves before ever restarting). This one signals, parks, and is **killed**
— then a fresh process, which never saw the signal, answers the callback and
finishes the turn.

```bash
# 1. Start the app (Postgres via docker-compose, start-only lifecycle).
ANTHROPIC_API_KEY=… ./mvnw -pl nessy-examples/dispatcher spring-boot:run
```

```bash
# 2. Signal an incident — 202 immediately; the log streams the triage and the park.
curl -s -X POST localhost:8080/signals -H 'Content-Type: application/json' \
  -d '{"incidentId":"INC-7","kind":"water-main","detail":"corner of 5th"}'
```

```bash
# 3. Read the park token back off the incident snapshot.
curl -s localhost:8080/incidents/INC-7
# → {"status":"PARKED","parks":[{"token":"…","tool":"request_field_crew"}], …}
```

```
4. Kill the app (Ctrl-C). Restart it. Postgres stays up — docker-compose's
   start-only lifecycle means only the very first run pays the cold start,
   and the kill-and-restart demo keeps its database.
```

```bash
# 5. The crew narrates progress, in the fresh process.
curl -s -X POST localhost:8080/callbacks/<token>/progress \
  -H 'Content-Type: application/json' -d '{"message":"crew en route"}'
# → {"heard":true}
```

```bash
# 6. The crew confirms — the turn completes in a JVM that never saw the signal.
curl -s -X POST localhost:8080/callbacks/<token> \
  -H 'Content-Type: application/json' -d '{"outcome":"valve replaced, water restored"}'
# → {"status":"COMPLETE"}
```

```bash
# 7. The story, told.
curl -s localhost:8080/incidents/INC-7
# → {"status":"COMPLETE","parks":[],"transcript":[…, {"role":"assistant","text":"…valve replaced, water restored…"}]}
```

```bash
# 8. Repeat step 6 — same 200, same truth, no replay.
curl -s -X POST localhost:8080/callbacks/<token> \
  -H 'Content-Type: application/json' -d '{"outcome":"valve replaced, water restored"}'
# → {"status":"COMPLETE"}
```

The restart scene (steps 1–4) stays a by-hand demo — nothing in this repo's
test suite kills a JVM mid-run. `DispatcherSmokeTest`'s duplicate-completion
assertion (step 8, automated) is the honest, replayable half of the same
claim: the registry entry survives resolution rather than being consumed, so
a redelivered callback re-drives the same resolution and the fold's own
still-outstanding check drains it quietly instead of replaying the tool call
— which is exactly the property a resumed-after-restart callback also leans
on. The test proves the replay-safety; the restart scene proves the process
boundary is real. Together they're the whole claim; neither alone is.

## What this example deliberately isn't

A2A — its own later generation, not a lesson this HTTP face teaches.
Webhook signature verification or any other inbound auth — a real deployment
of endpoints this shape wants it; this demo's whole point is the durable-park
mechanics, and adding auth would only dilute that. Retry or delivery
guarantees for anything outbound — nothing here is ever outbound; the crew's
own reports are the only traffic, and they arrive as plain inbound `curl`.
A UI — `curl` and the log are the only clients. A typed signal vocabulary —
`Agent<String>` is correct here (unlike the order-desk example, whose own
lesson is exactly that typing); the signal renders as one prose line, keeping
the lesson on the two doors, not on request/response shapes. SSE streaming of
the driven turn — `chat-web`'s lesson, not this one's: `/signals` answers
`202` and drives on a plain virtual thread, not the starter's `TurnRunner`
(which is SSE-shaped and, without `io.micrometer:context-propagation` on this
module's classpath, isn't even built as a bean).

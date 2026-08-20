# The Dispatcher — design

**Date:** 2026-08-14
**Status:** APPROVED — 2026-08-14 (design reviewed in session; built in
parallel with the order desk on its own branch — user ruling)
**Builds on:** the three-front-doors rework (shipped) and the starter.
A2A is deliberately NOT here — it is its own later generation; this
example is the plain HTTP face of the inbox grammar.

---

## 1. Purpose — the two doors, over HTTP

Exhibit the webhook trigger models — both of them, because the inbox has
exactly two kinds of mail:

- **the world volunteers news** (`Told`): `POST /signals` is
  fire-and-forget — deposit the signal, answer `202 Accepted`
  immediately, drive the turn on a virtual thread. Routing is by
  external identity: the incident id mints the `ConversationId`, so
  every signal about an incident joins that incident's story.
- **the world answers a question** (`Resolved`): the escalation tool
  parks; `POST /callbacks/{token}` resolves it via `harness.resume`,
  `POST /callbacks/{token}/progress` narrates via `harness.progress`.
  The demo's "external system" is the operator with `curl` — honest,
  because the process boundary is real:

**the headline scene is restart-then-callback**: signal → park → kill
the app → restart → `curl` the callback → the turn completes. Durable
parks (`JdbcParks`) earning their keep — no example shows this today.

Success criterion: the demo script (§5) end to end, including the
restart scene, with `curl` as the only client.

## 2. The story

A dispatcher for field incidents. `POST /signals` with
`{"incidentId":"INC-7","kind":"water-main","detail":"corner of 5th"}`
opens (or continues) incident INC-7's conversation. The agent triages;
for anything actionable it calls its one tool,
`request_field_crew(incidentId, action)`, which parks — the crew is out
in the world. The app logs the park token (the operator's copy of the
correlation contract). The crew reports progress
(`POST /callbacks/{token}/progress {"message":"crew en route"}`) and
finally confirms (`POST /callbacks/{token} {"outcome":"valve replaced,
water restored"}`), which resumes the turn to completion.
`GET /incidents/{id}` returns the snapshot — status, open parks (token +
tool), transcript prose — the page-rebuild read as JSON.

## 3. Module

`nessy-examples/dispatcher` (artifactId `nessy-example-dispatcher`,
deploy-skipped; package `org.jwcarman.nessy.examples.dispatcher`). A
Spring Boot WEB app (this example's lesson is HTTP), `Agent<String>` —
the typed-vocabulary first belongs to the order desk; here the signal
renders as one prose line ("Signal for INC-7: water-main — corner of
5th."), keeping the lesson on the doors, not the typing.

- Dependencies: `spring-boot-starter-web`, `spring-boot-jackson2`
  (chat-web's precedent for classic-Jackson beans), `logback-classic`
  (compile), `nessy-spring-boot-starter`, `nessy-model-anthropic`,
  `nessy-store-jdbc`, `postgresql` (runtime), `spring-boot-docker-compose`
  (runtime, optional), mockito-excluded test starter,
  `spring-boot-resttestclient` + `spring-boot-restclient` (test,
  chat-web's TestRestTemplate wiring), Testcontainers postgresql +
  junit-jupiter, Awaitility. Boot BOM confined in-module. The
  `-parameters` compiler override (chat-web's pom comment explains why —
  `@PathVariable` name matching).
- Compose: postgres:17-alpine only (chat-web's service verbatim),
  `start-only`. Durable substrate via the starter — **JdbcParks is the
  load-bearing bean**: the restart scene depends on it.
- The example's own nessy config: ONE bean, the agent
  (`claude-sonnet-4-5`, dispatcher standing orders, the tool granted
  `UsagePolicy.allow()`, `onToolProgressAsync` logging listener,
  starter-supplied `AgentMemory`).
- Endpoints (one controller each or one small controller — implementer's
  call, prose over ceremony):
  - `POST /signals` → validate body (incidentId, kind, detail all
    required → 400 otherwise), deposit + drive on a virtual thread, log
    via a turn observer, return 202 with `{"incident":"INC-7"}`.
  - `POST /callbacks/{token}` → body `{"outcome": …}`;
    `harness.resume(token, Completed(ok(outcome)))` driven
    synchronously (the caller deserves the settled answer); 200 with the
    resulting status; `UnknownParkTokenException` → 404. A duplicate
    callback re-drives idempotently and 200s with current truth — state
    it in the javadoc.
  - `POST /callbacks/{token}/progress` → body `{"message": …}`;
    `harness.progress`; 200 `{"heard": true|false}` (false = settled,
    dropped — legal).
  - `GET /incidents/{id}` → snapshot JSON: status, parked cards
    `(token, tool)`, transcript lines (chat-web's TranscriptView
    pattern, local copy).
- The park token surfaces in TWO places, deliberately: the app log (the
  turn observer prints `ToolCallParked`) and `GET /incidents/{id}` —
  the demo script uses the GET, because after the restart the log line
  is gone but the registry remembers.

## 4. Testing

One container-tagged `@SpringBootTest(RANDOM_PORT)` smoke (Testcontainers
postgres, scripted provider behind `@ConditionalOnMissingBean(Harness)`,
`TestRestTemplate`, Awaitility for the async signal path; no key, no
Docker in the offline build):

- signal → 202 immediately; await `GET /incidents/{id}` = `PARKED` with
  one card; the scripted first call requests `request_field_crew`.
- progress callback → 200 heard:true, recorded by a declared sync
  listener; a progress after settlement → heard:false.
- completion callback → 200 COMPLETE; transcript's final assistant text
  quotes the outcome (scripted second call echoes it — the script embeds
  a fixed marker the outcome body carries).
- duplicate completion callback → 200, still COMPLETE, scripted
  provider's call count unchanged (no replay).
- unknown token → 404. Malformed signal → 400.

The restart scene stays a by-hand demo (README says so) — the automated
duplicate-callback test covers the replayable half honestly.

## 5. The demo script

1. `ANTHROPIC_API_KEY=… ./mvnw -pl nessy-examples/dispatcher
   spring-boot:run`
2. `curl -s -X POST localhost:8080/signals -H 'Content-Type:
   application/json' -d '{"incidentId":"INC-7","kind":"water-main",
   "detail":"corner of 5th"}'` → 202; the log streams the triage and the
   park.
3. `curl -s localhost:8080/incidents/INC-7` → PARKED + the token.
4. **Kill the app. Restart it.** (Postgres stays up — start-only.)
5. `curl -s -X POST localhost:8080/callbacks/<token>/progress -d
   '{"message":"crew en route"}' -H 'Content-Type: application/json'` →
   heard in the fresh process.
6. `curl -s -X POST localhost:8080/callbacks/<token> -d
   '{"outcome":"valve replaced, water restored"}' -H 'Content-Type:
   application/json'` → the turn completes in a JVM that never saw the
   signal.
7. `curl -s localhost:8080/incidents/INC-7` → COMPLETE, the story told.
8. Repeat step 6 → same 200, same truth, no replay.

## 6. Deliberately not built

A2A (its own generation), webhook signature verification/auth (a
sentence in the README says production wants it), retry/delivery
guarantees for outbound anything (nothing outbound exists), a UI (curl
and the log are the clients), typed signal vocabulary (order-desk's
lesson), SSE streaming of the driven turn (chat-web's lesson).

## 7. Resolved at review (2026-08-14)

1. Built in parallel with order-desk, own branch/worktree, single
   executing agent, reviewed at the end (user ruling: "shouldn't be a
   lot of coordination").
2. A2A excluded — scope ruling announced and unobjected.
3. Machine-half verbs here are the HTTP face; the AMQP face ships in
   order-desk. Both are honest; the restart scene is this one's own.
4. `Agent<String>` here is CORRECT (unlike order-desk) — the doors are
   the lesson.
5. Merge order: order-desk lands first; this branch rebases the trivial
   seams (examples `<modules>`, README matrix, CHANGELOG).

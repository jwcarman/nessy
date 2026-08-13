# The Chat-Web Example (and JdbcMemory) — design

**Date:** 2026-08-13
**Status:** DRAFT — pending review
**Builds on:** the essence (2026-08-11) and the durable kernel (2026-08-12), both
shipped. This is the first non-toy dogfooding: a Spring Boot chat app against
Postgres that exercises the whole durable story on one HTML page — and the
first dogfood discovery is a framework gap this spec closes on the way.

---

## 1. Purpose

Feel how simple nessy is to use in a real deployment shape: a web app, a
database, restarts, humans clicking approve. Success criterion: the entire
nessy wiring is a handful of Spring beans a stranger can read in one sitting,
and the demo script (§7) runs end to end — including surviving a kill.

## 2. The framework prerequisite: `JdbcMemory`

Restart survival requires durable *Memory*, not just durable state: the
Postgres store preserves the control block (status, agenda, parks, debt), but
the transcript lives behind the `Memory` seam, and `ListMemory` dies with the
JVM. Every real durable deployment needs this, so it lands in the framework:

- **`JdbcMemory`** in `nessy-store-jdbc` (`org.jwcarman.nessy.store.jdbc`),
  constructor `(DataSource, ObjectMapper)`, factory `create(...)` running its
  schema idempotently (same discipline as `JdbcConversationStore`):

```sql
CREATE TABLE IF NOT EXISTS nessy_memory (
  conversation_id text   NOT NULL,
  seq             bigint NOT NULL,
  message         jsonb  NOT NULL,
  PRIMARY KEY (conversation_id, seq)
);
```

- `remember(id, message)`: append with the next `seq`, applying the
  at-least-once idempotency rule the `Memory` contract requires — skip when
  `message` equals the row with the highest `seq` for that conversation
  (`ListMemory`'s consecutive-duplicate rule, in SQL). Insert and dup-check in
  one transaction.
- `recall(id)`: select ordered by `seq` → `Context.of(messages)`. Verbatim
  retention — this is the durable floor, not a summarizing memory.
- Message (de)serialization via the existing `StateCodec` mixins (extract the
  message codec surface for reuse rather than duplicating mixin registration).
- Tests: codec round-trips offline; behavior against real Postgres in the
  container-tagged suite (remember/recall order, isolation between
  conversations, consecutive-duplicate tolerance, empty recall — mirroring
  `ListMemoryTest`'s scenarios).

## 3. Module restructure

`nessy-examples` becomes a `pom`-packaging aggregator (parent stays
`nessy-parent`; the reactor's `<module>` entry is unchanged):

```
nessy-examples/
  pom.xml            (packaging pom; modules: chat-cli, chat-web)
  chat-cli/          (existing AnthropicChat, OpenAiChat, DemoAgent + exec plugin, moved verbatim)
  chat-web/          (the Spring Boot app)
```

Artifact ids: `nessy-example-chat-cli`, `nessy-example-chat-web`. Neither is
published to Maven Central (skip-deploy like today's examples). Spring Boot
enters the reactor **only** inside `chat-web`: the Boot BOM
(`spring-boot-dependencies`, current stable) imported in that pom's
`dependencyManagement` — `nessy-parent` never learns Spring exists.

`chat-web` dependencies: `spring-boot-starter-web`,
`spring-boot-starter-jdbc` (DataSource auto-config + Hikari),
`spring-boot-docker-compose` (runtime, optional), `nessy-core`,
`nessy-model-anthropic`, `nessy-store-jdbc`, `org.postgresql:postgresql`.

## 4. The app

**The nessy wiring — the simplicity test itself** (one `@Configuration`):

```java
@Bean ConversationStore store(DataSource ds, ObjectMapper mapper) {
  return JdbcConversationStore.create(ds, mapper);
}

@Bean Memory memory(DataSource ds, ObjectMapper mapper) {
  return JdbcMemory.create(ds, mapper);
}

@Bean Harness harness(ConversationStore store) {
  return Nessy.harness(AnthropicModelProvider.builder().fromEnv().build())
      .store(store)
      .build();
}

@Bean Agent<String> agent(Harness harness, Memory memory) {
  return harness.agent()
      .model("claude-sonnet-4-5")
      .systemPrompt("You are the demo shop's helpful assistant. Use your tool when a coupon is warranted.")
      .memory(memory)
      .tools(ToolGrant.grant(new IssueCouponTool(), UsagePolicy.requireApproval()))
      .approver(request -> Awaited.parked(ParkToken.generate()))
      .build();
}
```

The approver is the durable-HITL posture in one line: every approval parks;
the UI is the approver. (The harness mapper: reuse Spring's `ObjectMapper`
bean for both stores so JSON conventions agree; the harness builds its own
internal mapper as today — no coupling required.)

**The demo tool.** `IssueCouponTool` — `issue_coupon(customerEmail, amountUsd,
reason)` → returns a fake confirmation string ("coupon DEMO-1234 for $15
issued to …"). Obviously consequence-bearing (approval feels natural),
obviously harmless (nothing real happens). It reports one `ToolProgress`
("issuing…") so the tee shows up in the UI.

**Endpoints** (all JSON/SSE, no server-side templating):

| Endpoint | Does |
|---|---|
| `GET /` | serves the static `index.html` |
| `GET /api/conversations/{id}` | page-rebuild reading: transcript (text blocks of `agent.contextFor(id)`, rendered per role) + pending approval cards (loaded state's `parkedCalls` → token, tool name, args) + status |
| `POST /api/conversations/{id}/messages` `{text}` | returns an SSE stream of THIS entry's segment: runs `tell(text, observer)` on a virtual thread; the observer bridges `TurnEvent` → SSE events (`delta`, `thinking`, `tool-requested`, `tool-progress`, `tool-decided`, `tool-completed`); on `RunOutcome.Parked`, emits `approval-needed` events (one per parked call, from `state.parkedCalls()`); terminal `done` event carries final status |
| `POST /api/approvals/{token}` `{decision: "allow"\|"deny", reason?}` | returns an SSE stream of the RESUMED segment: `harness.resume(token, new Decided(...), observer)`, same event bridge, same `done`. Unknown/consumed token → 409 with a body naming it |

Conversation identity: the browser mints a UUID, keeps it in `localStorage`,
and puts it in the URL hash so a conversation is shareable/bookmarkable. "New
conversation" = new UUID.

**The UI.** One `index.html`, vanilla JS + `fetch` with `ReadableStream` SSE
parsing, one small CSS file. Chat log (user right / assistant left, streaming
text appended live), a muted "🔧 tool" line for tool lifecycle events, an
approval card (tool name, pretty-printed args, Approve/Deny buttons) pinned
below the log while parked, input box disabled while a stream is open. No
framework, no build step, no npm.

**Error handling.** A failed turn (`FAILED` status in `done`) renders the
`failureReason` as a system line. SSE stream errors surface as a retry hint.
The 409 on a stale approval card refreshes the page state (the card
disappears — someone else decided, or it was already applied).

## 5. Compose and config

`chat-web/docker-compose.yml`: `postgres:17-alpine`, database `nessy`,
port 5432, healthcheck. `spring-boot-docker-compose` on the classpath makes
`mvn spring-boot:run` start (and stop) it automatically. `application.yaml`
carries the datasource URL/credentials matching compose; `ANTHROPIC_API_KEY`
comes from the environment (fail fast at startup with a clear message when
absent — the provider's `fromEnv` already throws; let it, at bean creation).
Both schemas bootstrap idempotently via the two `create(...)` factories.

Run instructions (README of `chat-web`): `ANTHROPIC_API_KEY=… ./mvnw -pl
nessy-examples/chat-web spring-boot:run`, open `http://localhost:8080`.

## 6. Testing

- `JdbcMemory`: as §2 — codec tests offline, behavior container-tagged.
- `chat-web`: one `@SpringBootTest` smoke, `@Tag("container")`, Testcontainers
  Postgres (compose support disabled in tests), the Anthropic provider bean
  overridden by a scripted `ModelProvider` (no network, no key): POST a
  message → SSE events arrive in order → tool parks → card data present →
  resume via the endpoint → conversation completes. One test proving the whole
  wiring; the framework already proves the parts.
- Offline reactor `verify` stays green with no Docker and no key (container
  tag excluded by default; CI runs it with `-Dnessy.excludedGroups=live`).

## 7. The demo script (the acceptance test by hand)

1. `mvn spring-boot:run` — compose starts Postgres, schemas bootstrap.
2. Ask: "my order arrived broken, can you make it right?" — watch the answer
   stream; the model calls `issue_coupon`; an approval card appears.
3. **Kill the app.** Restart it. Refresh the page: transcript intact
   (JdbcMemory), card intact (parked lane), status `PARKED`.
4. Click Approve — the resumed segment streams the confirmation; the turn
   completes with nothing on the agenda.
5. Type another message in the same conversation — it just continues.

## 8. Deliberately not built

Authentication, multi-user identity, conversation listing/search, WebSockets,
any JS framework, a Spring Boot starter for nessy (a real future artifact —
this example hand-wires beans precisely to show what a starter would
automate), summarizing/token-aware Memory (the `contextWindow` reservation
stands), multi-agent routing (single agent; the kernel's single-agent resume
guard is fine here).

## 9. Open questions

1. Whether `StateCodec`'s message-codec surface is extracted as a small
   package-private helper shared by both stores, or `JdbcMemory` instantiates
   its own codec — lean: extract, one mixin registration to rule them all.
2. Boot version pin (current stable at implementation time; the plan pins it).

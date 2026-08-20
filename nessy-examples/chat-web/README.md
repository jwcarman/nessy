# Nessy Example: Chat Web

A Spring Boot chat app that dogfoods the durable kernel end to end: a real
browser UI, a real Postgres-backed `ConversationStore` and `AgentMemory`, a tool
gated behind human approval, and full observability — with `nessy-spring-boot-starter`
autoconfiguring every substrate bean, `ChatWebConfig.java` declares exactly one:
the agent.

The whole nessy wiring an application writes itself — one bean in `ChatWebConfig`:

```java
@Bean Agent<String> agent(Harness harness, Memory memory) {
  return harness.agent(
      a ->
          a.name("chat-web")
              .model("claude-sonnet-4-5")
              .systemPrompt("You are the demo shop's helpful assistant. Use your tool when a coupon is warranted.")
              .memory(memory)
              .tools(ToolGrant.grant(new IssueCouponTool(), UsagePolicy.requireApproval()))
              .approver(Approver.parkAll()));
}
```

`Harness` and `AgentMemory` arrive as method parameters, autoconfigured by the
starter from the classpath: `nessy-model-anthropic` (plus `.fromEnv()`
credential resolution) yields the `ModelProvider`, `nessy-jdbc` next
to the app's `DataSource` bean yields the Postgres-backed `ConversationStore`
and `AgentMemory`, and both feed the autoconfigured `Harness` — Boot's own
auto-configured `ObservationRegistry` included, so nessy's spans join Boot's
in the same trace with no application wiring at all. The approver is the
durable-HITL posture in one line: every approval parks — the browser is the
approver, and the park survives a restart because `AgentMemory` and the
`ConversationStore` both live in Postgres, not the JVM's heap. (`ChatWebConfig`
carries no `@Profile` split anymore — the container smoke test's own
`@TestConfiguration` `Harness` bean wins over the starter's by
`@ConditionalOnMissingBean`, so the real `Harness` above simply backs off in
the test context.)

The demo tool is `IssueCouponTool`: `issue_coupon(customerEmail, amountUsd,
reason)` returns a fake confirmation string. Obviously consequence-bearing
(approval feels natural), obviously harmless (nothing real happens).

## Running it

Requires a real Anthropic key — the app fails fast at startup without one —
and Docker, to run the compose stack (Postgres plus the observability
stack, below) that `spring-boot-docker-compose` starts automatically and
leaves running between app runs (`start-only` lifecycle: the database should
not die because the app exited). Only the first-ever run pays the container
cold start; the app itself waits only for Postgres, not for the Grafana
stack, which warms up in the background.

```bash
ANTHROPIC_API_KEY=… ./mvnw -pl nessy-examples/chat-web spring-boot:run
```

Then open <http://localhost:8080>.

## Observability

The compose file also brings up `grafana/otel-lgtm` — one image bundling
the OTel Collector, Tempo, Prometheus, Loki, and Grafana — so every chat
turn's traces, metrics, and logs land somewhere you can look at them: Grafana
at <http://localhost:3000>, OTLP ingest on `4318`. The dogfood point: the
harness bean takes Spring Boot's own auto-configured `ObservationRegistry`,
so nessy's model-call and tool observations show up in Tempo *in the same
trace* as Boot's own HTTP and JDBC spans — one chat turn, one trace, from the
`POST` that started it down through the model call, the tool call, and the
JDBC saves either side of it.

## The demo script

This is the acceptance test, run by hand:

1. `ANTHROPIC_API_KEY=… ./mvnw -pl nessy-examples/chat-web spring-boot:run` —
   compose starts Postgres and the LGTM stack; the app bootstraps both
   schemas idempotently on startup.
2. Ask: "my order arrived broken, can you make it right?" — watch the answer
   stream in; the model calls `issue_coupon`; an approval card appears below
   the chat log.
3. **Kill the app** (`Ctrl-C`). Restart it (`./mvnw -pl nessy-examples/chat-web
   spring-boot:run` again). Refresh the page: the transcript is intact
   (the transcript survived the JVM), the approval card is still there (the
   park is a durable row in Postgres, not process state), and the
   conversation's status reads `PARKED`.
4. Click **Approve** — the resumed segment streams the confirmation; the
   turn completes with nothing left in the inbox.
5. Type another message in the same conversation — it just continues; same
   conversation id, same transcript.
6. Open Grafana at <http://localhost:3000> and find the turn's trace in
   Tempo: one trace running HTTP `POST` → nessy model call → tool execution
   → JDBC saves, and the same turn's logs in Loki, correlated by trace id.

## What this example deliberately does not build

Authentication, multi-user identity, conversation listing/search, WebSockets,
or any JS framework. It no longer hand-wires nessy's substrate beans either
— `nessy-spring-boot-starter` (see the root
[README](../../README.md#spring-boot)) autoconfigures those now; this
example's own code is the agent, the controllers, and the UI. See
`docs/superpowers/specs/2026-08-13-chat-web-example-design.md` §8 and
`docs/superpowers/specs/2026-08-13-spring-boot-starter-design.md` for the
complete list and the reasoning.

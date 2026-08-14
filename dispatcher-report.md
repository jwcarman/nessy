# Dispatcher build report

Branch: `dispatcher` (created from `main` tip `26e934b`, since this worktree's
own HEAD `4032047` was stale — `main` had advanced with the order-desk and
dispatcher spec/plan commits after this worktree was cut).

## Task 1: Scaffold

**What:** `nessy-examples/pom.xml` gains `<module>dispatcher</module>`.
New module `nessy-examples/dispatcher`:
- `pom.xml` — artifactId `nessy-example-dispatcher`, chat-web as template:
  Boot BOM confined in-module, `spring-boot-starter-web` + `spring-boot-jackson2`
  + compile-scope `logback-classic` + `postgresql` (runtime) +
  `spring-boot-docker-compose` (runtime, optional), `nessy-spring-boot-starter`
  / `nessy-model-anthropic` / `nessy-store-jdbc`, mockito-excluded test
  starter, `spring-boot-resttestclient` + `spring-boot-restclient` (test),
  Testcontainers postgresql + junit-jupiter, awaitility (test). `-parameters`
  compiler override (chat-web's comment, adapted for this module's three
  controllers). `spring-boot-maven-plugin` with no repackage execution.
- `DispatcherApplication` — plain `@SpringBootApplication` + `main`, no
  `@EnableScheduling` (this is a web app; Tomcat keeps it alive), modeled on
  chat-web's/night-watchman's application classes.
- `application.yaml` — `spring.application.name: nessy-dispatcher`,
  banner off, virtual threads enabled, docker compose `start-only`, datasource
  nessy/nessy/nessy@localhost:5432 (chat-web verbatim pattern).
- `docker-compose.yml` — chat-web's postgres service only (no otel-lgtm — spec
  §3 says "postgres:17-alpine only").

**RED/GREEN:** No tests in this scaffold task (plan doesn't ask for one) —
the acceptance bar is "offline verify green", which is the GREEN evidence
below. No RED state applicable (nothing to fail first).

**Commands + output tails:**
```
./mvnw -q clean verify        # exit 0, full reactor including new empty
                               # dispatcher module compiles and package-verifies
```
Confirmed via `echo "MVN_EXIT:$?"` after redirecting to a log file: `MVN_EXIT:0`.

**Self-review:** Diffed against chat-web's pom line by line; kept only what
spec §3 asks for (no actuator/OTel deps — those are chat-web's own
observability lesson, not this module's). Compared `-parameters` justification
comment to reference actual controller names I intend to write in Task 3
(SignalController, CallbackController, IncidentController) rather than
copy-pasting chat-web's comment verbatim with its class names.

**Deviation noted:** `./mvnw license:format -Plicense` runs across the whole
reactor and added license headers to ~15 pre-existing files elsewhere in the
repo (chat-web's application.yaml, static assets, several junit-platform.properties,
store-jdbc SQL schema files) that apparently predate the license plugin's
current file-type coverage. These are unrelated to the dispatcher module, so
I reverted them with `git checkout --` before staging/committing, keeping this
branch's diff scoped to dispatcher work only. Flagging in case the controller
wants that pre-existing gap addressed separately. (Confirmed on Task 2 that
this was a one-time artifact of the first `-Plicense` run in this worktree —
subsequent `license:format -Plicense` runs only touched dispatcher's own new
files, so no repeat cleanup was needed.)

## Task 2: The agent bean + the tool

**What:** `RequestFieldCrewTool implements Tool<Input>`, `record
Input(String incidentId, String action)`, name `request_field_crew`; `execute`
always returns `Awaited.parked(ParkToken.generate())` after calling
`context.progress("crew requested; awaiting confirmation callback")` — never
`Ready`, since the crew is out in the world (spec §2). Javadoc states the "no
outbound transport" fact from spec §3's last bullet: the token reaches the
world only through narration (turn observer log, Task 3) and the
`GET /incidents/{id}` snapshot (Task 3), never a queue publish or webhook.

`DispatcherConfig`: one bean, `Agent<String> agent(Harness, Memory)` —
model `claude-sonnet-4-5`, the dispatcher's standing-orders system prompt
(triage tersely, call the tool once per incident, close out with a one-line
summary when the outcome arrives), `.tools(ToolGrant.grant(new
RequestFieldCrewTool(), UsagePolicy.allow()))`, `.memory(memory)`,
`.onToolProgressAsync(...)` wired to an SLF4J logger so `ToolProgress` events
(conversationId + message) print to the app log.

**RED:** `RequestFieldCrewToolTest` written first (three cases: name
identity, execute-parks-with-fresh-token, two-executes-mint-two-distinct-tokens),
built with `new ToolContext(ConversationId.generate(), call,
EventEmitter.noop())` — night-watchman's `WatchmanToolsTest` pattern. Compile
failed as expected (`cannot find symbol: class RequestFieldCrewTool`) —
`./mvnw -q -pl nessy-examples/dispatcher test` exit 1.

**GREEN:** `RequestFieldCrewTool` + `DispatcherConfig` written.
`./mvnw -q -pl nessy-examples/dispatcher test` → exit 0, surefire report:
`Tests run: 3, Failures: 0, Errors: 0, Skipped: 0`.

**Commands + output tails:**
```
./mvnw -q -pl nessy-examples/dispatcher test     # RED: exit 1, compile error
                                                   # (symbol not found)
# ... implementation written ...
./mvnw -q -pl nessy-examples/dispatcher test     # GREEN: exit 0, 3/3 tests
./mvnw -q clean verify                            # full offline reactor: exit 0
```

**Self-review:** Confirmed `ToolProgress`'s actual field names
(`conversationId`, `toolCallId`, `message`) against
`nessy-core/src/main/java/org/jwcarman/nessy/api/event/ToolProgress.java`
before wiring the logger, rather than trusting the earlier research summary's
paraphrase. `onToolProgressAsync` confirmed to live on `ListenerDeclarations`
(implemented by `AgentBuilder`), not declared directly on `AgentBuilder`
itself — matches the research agent's flagged correction. Checked
`AgentBuilder.defaultApprover()`: the design-§13.1 "no approver configured"
WARN fires unconditionally at `build()` whenever `.approver(...)` is never
called, regardless of whether any grant would actually trigger approval — so
added `.approver(Approver.allowAll())` explicitly, night-watchman's exact
precedent and comment, since this tool's `UsagePolicy.allow()` grant means no
human is ever in this loop.

## Task 3: The controllers

**What:** `TranscriptView` — local copy of chat-web's class verbatim, cited
in javadoc. `IncidentLog` — a shared package-private observer factory,
modeled on night-watchman's `Watchman` (raw lambda pattern-matching over
`TurnEvent`, `default -> {}` for sealed-grammar tolerance), reused by both
signal- and callback-driven turns; its `label` parameter doubles as incident
id (signal path) or park token (callback path, which never has the incident
id in hand). `SignalController` (`POST /signals`): validates all three
fields (400 via `ResponseStatusException` otherwise), mints
`ConversationId("incident-" + incidentId)`, renders the one prose line, drives
on `Thread.ofVirtual().start(...)` with a try/catch that logs-and-continues
(the caller already has its 202), returns 202 `{"incident": id}`.
`CallbackController`: `POST /callbacks/{token}` builds
`new ToolResolution.Completed(ToolResult.ok(outcome))` and calls
`harness.resume(token, resolution, observer)` synchronously, 200
`{"status": ...}`; `POST /callbacks/{token}/progress` calls
`harness.progress(token, message)`, 200 `{"heard": bool}`;
`@ExceptionHandler(UnknownParkTokenException.class)` → 404 (chat-web's
`ApprovalController` pattern, mapped to 404 instead of chat-web's 409 per
spec §3). `IncidentController` (`GET /incidents/{id}`): `agent.snapshot(...)`
→ `{status, parks:[{token,tool}], transcript:[...]}` via `TranscriptView`.

**Micrometer/TurnRunner decision:** researched `NessyWebAutoConfiguration` —
its `TurnRunner` bean is gated
`@ConditionalOnClass({SseEmitter.class, ContextSnapshotFactory.class})`
specifically so a webmvc app *without* `io.micrometer:context-propagation` on
its classpath never gets a half-built `TurnRunner` (the class's own javadoc
explains the `NoClassDefFoundError` this gating avoids). Dispatcher's pom
doesn't declare `context-propagation` (spec §3's dependency list doesn't list
it, unlike chat-web's observability stack), so no `TurnRunner` bean exists
here regardless. `TurnRunner` is also SSE-shaped (`.run()` returns an
`SseEmitter`) and `/signals` isn't a streaming endpoint — 202 or bust — so it
is not a fit even if it were available. Plain `Thread.ofVirtual().start(...)`
is used instead, and `SignalController`'s javadoc says explicitly why
`TurnRunner` doesn't apply, per the plan's escape hatch. Tracing-context
propagation onto the virtual thread is therefore not attempted; the log is
the observability story here, exactly as in night-watchman.

**RED/GREEN:** No new tests in this task (plan defers behavior testing to
Task 4's smoke test) — the acceptance bar is "controllers compile;
behavior is Task 4's" per the plan. `./mvnw -q -pl nessy-examples/dispatcher
test-compile` — first pass failed on spotless-check formatting only (long
lines), `spotless:apply` fixed it, second pass exit 0. Existing
`RequestFieldCrewToolTest` (Task 2) still green: `./mvnw -q -pl
nessy-examples/dispatcher test` exit 0.

**Commands + output tails:**
```
./mvnw -q -pl nessy-examples/dispatcher test-compile   # RED (spotless-check):
                                                         # exit 1, format violations
./mvnw -q spotless:apply                                # exit 0
./mvnw -q -pl nessy-examples/dispatcher test-compile   # GREEN: exit 0
./mvnw -q -pl nessy-examples/dispatcher test            # exit 0 (3 pre-existing
                                                         # tests still pass)
./mvnw -q clean verify                                  # full offline reactor: exit 0
```

**Self-review:** Read the diff class by class before commit. Verified
`ToolResolution.Completed(ToolResult)` and `Harness.resume(token, resolution,
observer)` signatures against `nessy-core` source directly (not just the
earlier research summary) before wiring `CallbackController`. Confirmed
`ConversationStatus` enum values (`PARKED`, `COMPLETE`, etc.) and
`RunOutcome.state().status()` return type. Checked that `IncidentController`
and `SignalController` compute the conversation id the same way
(`"incident-" + id`) so a signal and a later `GET` agree on which
conversation they mean — this is the one cross-controller invariant that
would silently break the demo if it drifted. Deliberately did NOT add an
`@ExceptionHandler` for malformed-JSON/blank-token cases beyond what the plan
asks (unknown token → 404, malformed signal → 400) — those two are the only
error paths spec §4 tests for.

**Deviation noted:** the plan's Task 3 text says the transcript view should
be "a local `Transcripts` copy — night-watchman's, cite it" — I searched the
whole repo (including night-watchman) and no class named `Transcripts` exists
anywhere; the only transcript-rendering class in the codebase is chat-web's
`TranscriptView`, which spec §3 itself names explicitly ("chat-web's
TranscriptView pattern, local copy"). I followed the spec (binding over the
plan where they conflict) and copied chat-web's `TranscriptView` verbatim,
noting this in the new file's javadoc. Flagging the plan's apparent
typo/wrong-attribution for the controller.

**Also noted:** the stray license-header diffs on ~15 unrelated pre-existing
files (see Task 1's deviation note) reappeared once more after this task's
`license:format -Plicense` run and were reverted again before staging;
non-deterministic across runs in this worktree, cause not fully diagnosed,
but confirmed harmless each time (never staged, never committed).

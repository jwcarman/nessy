# The Dispatcher Implementation Plan

> **For agentic workers:** this plan is executed end-to-end by ONE agent in an isolated worktree (session ruling — parallel build). Work task by task in order, TDD within each, commit per task, self-review before reporting. You do not dispatch subagents; review arrives from the controller after you report.

**Goal:** `nessy-examples/dispatcher` — the two inbox doors over HTTP: fire-and-forget signals (202 + virtual-thread drive) and token callbacks (resume/progress), with durable parks proving the restart-then-callback scene.

**Spec:** `docs/superpowers/specs/2026-08-14-dispatcher-design.md` — binding; read it in full first. Where this plan is terse, the spec's section governs.

**Reference implementations to study before starting:** `nessy-examples/chat-web` (pom discipline incl. `-parameters` and jackson2, TestRestTemplate smoke pattern, TranscriptView, compose service), `nessy-examples/night-watchman` (logging observer pattern, config-bean shape, README voice), `Harness` javadocs (resume/progress semantics you must not re-document wrongly).

## Global Constraints

- TDD; RED/GREEN evidence per task in your running report file. Offline `./mvnw -q clean verify` green after EVERY task (no Docker, no key); container suite `./mvnw -q verify -pl nessy-examples/dispatcher -am -Dnessy.excludedGroups=live` green from Task 4 on (Docker available in the worktree host).
- Before every commit: `./mvnw license:format -Plicense && ./mvnw spotless:apply`, re-stage. No IDE metadata. No suppressions, no star imports, no mocking libraries (hand-rolled doubles fine), prose snake_case test names, S5778/S5841, Awaitility not Thread.sleep, sealed-grammar etiquette.
- Package `org.jwcarman.nessy.examples.dispatcher`; module `nessy-examples/dispatcher`; artifactId `nessy-example-dispatcher`. Boot BOM confined in-module; deploy-skip. Commit trailer `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.
- Branch: create `dispatcher` from your worktree's HEAD and stay on it.

## Tasks

### Task 1: Scaffold
`nessy-examples/pom.xml` gains `<module>dispatcher</module>`; module pom per spec §3's dependency list (chat-web is the template — copy its BOM confinement, `-parameters` override with its comment, jackson2 rationale comment, mockito exclusion, resttestclient/restclient test wiring; add Testcontainers postgresql+junit-jupiter and awaitility test deps; spring-boot-maven-plugin without repackage); `DispatcherApplication` (`@SpringBootApplication`, plain main — web app, Tomcat keeps it alive); `application.yaml` (name `nessy-dispatcher`, banner off, compose start-only, datasource nessy/nessy/nessy@localhost:5432 like chat-web); `docker-compose.yml` = chat-web's postgres service only. Offline verify green. Commit: `feat: the dispatcher is scaffolded — two doors, soon`

### Task 2: The agent bean + the tool
`DispatcherConfig`: ONE nessy bean — `Agent<String> agent(Harness, Memory)` with model `claude-sonnet-4-5`, standing orders (you are an incident dispatcher; signals arrive one line each; triage tersely; for actionable incidents call your tool exactly once per incident unless told otherwise; when the crew's outcome arrives, close out with a one-line summary), `.tools(ToolGrant.grant(new RequestFieldCrewTool(), UsagePolicy.allow()))`, `.memory(memory)`, `.onToolProgressAsync(log)`. `RequestFieldCrewTool implements Tool<Input>`; `record Input(String incidentId, String action)`; name `request_field_crew`; `execute` → `ParkToken.generate()`, `context.progress("crew requested; awaiting confirmation callback")`, `Awaited.parked(token)` — note: unlike order-desk there is no outbound transport; the token reaches the world via narration + snapshot (spec §3 last bullet, put that sentence in the tool's javadoc). Offline unit test: identity + execute parks with a fresh token (two executes → two distinct tokens) — construct `ToolContext` with `EventEmitter.noop()`. Commit: `feat: the crew is requested and the desk waits — a park with no wire`

### Task 3: The controllers
Per spec §3's endpoint table, exactly. Suggested shape: `SignalController` (POST /signals — validate all three fields with 400 via `ResponseStatusException`; mint `ConversationId("incident-" + incidentId)`; render the one prose line; `Thread.ofVirtual().start(...)` driving `conversation.tell(line, observer)` with a logging observer that prints text/tool/park events incl. the token; return 202 `{"incident": id}`), `CallbackController` (POST /callbacks/{token} — resume synchronously, 200 `{"status": outcome.state().status()}`; catch `UnknownParkTokenException` → 404 via ResponseStatusException; javadoc the duplicate-callback idempotency; POST /callbacks/{token}/progress → 200 `{"heard": bool}`), `IncidentController` (GET /incidents/{id} → `{status, parks:[{token,tool}], transcript:[{role,text}]}` via a local `Transcripts` copy — night-watchman's, cite it). Micrometer context propagation onto the virtual thread: copy chat-web's controller pattern if it uses ContextSnapshot; if the starter's `TurnRunner` fits the fire-and-forget path without SSE, prefer plain virtual thread + note why TurnRunner (SSE-shaped) does not apply. Offline verify green (controllers compile; behavior is Task 4's). Commit: `feat: three doors open — signals accepted, callbacks answered, incidents readable`

### Task 4: The smoke test
Spec §4's list, chat-web's smoke pattern exactly (container-tagged, Testcontainers postgres via `@DynamicPropertySource` or `@ServiceConnection` — match chat-web unless `@ServiceConnection` is cleaner, say which; `@TestConfiguration` Harness on a scripted provider with `ObjectProvider<ObservationRegistry>`; TestRestTemplate; static sync listener lists; Awaitility for the async signal→PARKED transition). Scripted provider: call 1 → tool-use `request_field_crew` for INC-7; call 2 → text echoing a fixed outcome marker; later → terse text. Cover every spec §4 bullet including 404, 400, duplicate-callback call-count stability (`await().during(...)` pattern), progress heard:false after settlement. Container suite green + offline green. Commit: `test: the doors proved — parked, narrated, resumed, replayed, refused`

### Task 5: Paperwork
Module README (spec §5's script verbatim with real curl lines incl. the restart scene and the auth sentence from §6; state the restart scene is by-hand and why the duplicate test is its automated half). Root README examples section + matrix gain the dispatcher (coordinate wording: the order-desk branch ALSO edits these — keep your edit minimal and mechanical so the later rebase is one hunk). CHANGELOG `### Added` bullet at section end (two doors, durable-parks restart scene, curl-as-the-world). Full offline + container sweeps green. Commit: `docs: the dispatcher signs the paperwork — curl is a fine crew`

## Report

Keep a running report at the worktree root: `dispatcher-report.md` (per task: what/RED-GREEN/commands+output tails/self-review; final: branch name, commit list, both verify outputs, deviations). Your final message: status, branch, commits, one-line test summary, report path, concerns. The controller reviews the whole branch afterward — do not merge, push, or leave the worktree.

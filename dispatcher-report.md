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
wants that pre-existing gap addressed separately.

# The watchman — a Spring Boot long-running agent, and the starter it needs

*2026-08-26. Status: shape agreed in conversation (James: "If you think the
watchman thing will work, I can get that running on my linux box home
server pretty easily"). Two new modules: `nessy-spring-boot-starter` and
`nessy-examples/watchman`.*

## 0. Thesis

Everything Nessy claims about production — parks that survive restarts,
deferred tools, the drop rule, re-fires, real dwell time, telemetry — has
been proven by tests and never by time. The watchman is the soak: an
agent that lives on James's home Linux server for weeks, does rounds on a
timer, proposes remediations that wait days for a human, and exports
every span and counter to a Grafana LGTM box he can watch from anywhere.

It is also the first Spring Boot code in Nessy. A Boot application is how
most people would run a long-lived agent, so the wiring a Boot app needs
— `DataSource` → durable stores, Boot's `ObservationRegistry` → the
harness seam, properties → the recipe, the desks as beans — is a feature
jar, not example code. James: "I'm ok if a feature is self contained as a
jar."

## 1. `nessy-spring-boot-starter`

One module, `org.jwcarman.nessy.spring.boot`, `spring-boot-starter`
conventions (`AutoConfiguration.imports`, `@ConditionalOnMissingBean`
everywhere, `nessy.*` properties bound to a `@ConfigurationProperties`
record). It composes; it invents nothing.

### 1.1 What it wires

| Bean | From | Condition |
|---|---|---|
| `Substrate` | `new JdbcSubstrate(dataSource)` | a `DataSource` bean and `nessy-substrate-jdbc` on the classpath; else the in-memory substrate |
| `Continuum` | `new DefaultContinuum(new JdbcContinuumRepository(dataSource), InstantSource.system())` | same |
| `Model` | `ModelDiscovery` (nessy-model-discovery) | always; `nessy.model.id` picks the id |
| `Harness<String>` | `Nessy.harness(c -> c.type(...).model(...).systemPrompt(...).grants(...).substrate(...).continuum(...).observationRegistry(...).harnessObserver(...))` | always; grants come from every `ToolGrant` bean and every `Tool` bean (a bare `Tool` bean is granted `Approvers.allow()`) |
| `ApprovalDesk`, `CompletionDesk` | `harness.approvals()` / `harness.completions()` | always |
| `ObservationRegistry` → seam | Boot's own `ObservationRegistry` bean (from `spring-boot-starter-actuator` + micrometer) | when present; else NOOP |
| shutdown | `harness.shutdown()` on context close | always |

Properties, all under `nessy.`: `type`, `model.id`, `system-prompt` (or
`system-prompt-file`), `staleness` (duration; the re-fire policy),
`backlog-capacity`. Nothing else — the recipe's tools and approvers are
beans, because they are code.

### 1.2 What it does not do

No web layer, no scheduling, no security, no approvers beyond what the
application declares. Those are the application's — the starter wires a
harness, the app decides what it is for.

### 1.3 The pending-approvals projection

Nothing in Nessy can *enumerate* parked approvals: the desk answers by id
or coordinates, the Continuum client has no read door, the phase is per
agent. A page that lists what is waiting needs a projection, and the
harness fact stream is exactly the thing to project from. The starter
ships it, because every Boot application that parks approvals will want
the same table:

- `PendingApprovals` — a `HarnessObserver` subscribed at harness build
  (the starter's `harnessObserver` composes it in front of the default
  narrator). On `ApprovalDeferred` applied: insert
  `(agent_type, agent_id, call_id, computation_id, request_json, action,
  parked_at)`. On `ApprovalAnswered` applied: update with
  `(answer, reference, principal_note, answered_at)`. Row keyed by
  `computation_id`.
- A `PendingApprovalsRepository` bean with `List<PendingApproval>
  pending()` and `List<PendingApproval> recent(int)`; the row type is a
  record in the starter.
- DDL as a classpath resource the application applies (Flyway/Liquibase
  or by hand), like `nessy-substrate-jdbc`'s.
- It is a projection: at-least-once, rebuilt from the stream, never the
  source of truth. Approve/deny go through `ApprovalDesk`; the row
  updates when the fold's fact arrives. A restart between the fold and
  the insert loses a row — the page shows one approval fewer than the
  phase holds until the staleness re-fire re-asks. Documented, accepted:
  the ledger is the phase.

This is the spec's §7 audit division landing where it said it would: the
approver subsystem owns the evidence; the core gave it the question, the
reference and the clock.

## 2. `nessy-examples/watchman`

A Boot app. `@Scheduled(cron = "${watchman.cron:0 */30 * * * *}")` tells
the one agent (`AgentId.of("watchman")`) to do its rounds:
`harness.bind(id).tell("It is <now>. Do your rounds.")`. The system
prompt says what a round is: check the box, fix what you are allowed to,
propose what you are not, write the note.

### 2.1 Tools — generic Linux, feature-detected

Read-only, `Approvers.allow()`, always run:

- `disk_usage` — `df -h` parsed; percent per mount.
- `failed_units` — `systemctl --failed` when systemd is present.
- `journal_errors` — `journalctl -p err --since -30m` when present.
- `containers` — `docker ps --format json` when docker is present;
  unhealthy/exited flagged.
- `updates_pending` — `apt list --upgradable` or `dnf check-update`,
  whichever exists.
- `uptime_load` — `/proc/loadavg`, `/proc/uptime`.
- `previous_notes` — the last N daily notes from the notes directory.

Remediation, `Approvers.defer()` (rule ladder in a later cut), each a
real shell action with a rendered `ActionContributor` so the approval
page shows exactly what will run:

- `restart_unit(name)`, `restart_container(name)`, `prune_images`,
  `apply_updates`, `clean_journal(days)`.

Write, `Approvers.allow()`: `write_note(text)` — appends to
`${watchman.notes-dir}/YYYY-MM-DD.md`. Every round ends with a note.

Every tool is a `Tool` bean; the starter grants them. Feature detection
is a `which` at startup; an absent tool is not registered, so the model
never sees it.

A remediation tool that defers is the deferred-tool path: it does not
defer at the *tool* level (the action is quick once approved) — the
approval is the wait. One tool, `long_job`, exists purely to exercise
`ToolContext.defer()` for real: it starts a background `fstrim` /
`smartctl` scan via `ProcessBuilder`, hands the computation id to a
watcher thread that completes it through `CompletionDesk` when the
process exits. That is the soak's proof that a tool can hold its own id
across a restart of the *agent* (not the process).

### 2.2 The page

Spring MVC + Thymeleaf, one template, HTMX for the two buttons. `GET /`
lists `pending()` with action, agent, parked-at, dwell so far, the
request JSON expandable; `POST /approve/{id}` and `/deny/{id}` call the
desk with the principal from HTTP basic auth and the note from the form,
then redirect. `GET /recent` shows the last 50 answered. Basic auth from
properties; it is on a LAN. No JavaScript beyond HTMX.

### 2.3 Telemetry

Boot's actuator + `micrometer-tracing-bridge-otel` + OTLP exporters,
configured by `management.otlp.*` properties pointed at the LGTM box.
The starter passes Boot's `ObservationRegistry` to the harness seam; the
application-side `gen_ai.client.token.usage` handler from
`nessy-examples/observed` is copied in (it is ten lines and belongs to
applications by the o11y spec's ruling). Logs via the OTel logback
appender so a trace id clicks through.

### 2.4 Running it

`docker compose` in the example: Postgres 17 and `grafana/otel-lgtm`.
The app runs on the host (it needs `df`, `systemctl`, `docker`),
`java -jar`, env for the model key and the DB URL. The README is the
runbook: how to start, how to crash it on purpose, what to look at in
Grafana after a day.

## 3. What the soak measures

From the o11y roster, on the LGTM dashboards: `nessy.approval.wait`
dwell (days, real), `nessy.tool.wait` for `long_job`, `invoke_agent`
segments per round, `chat` tokens per round, `nessy.delivery.dropped`
(should be zero), `nessy.state.stale_retries` (should be near zero),
`nessy.effects.refired` (one per reboot per parked scope). Plus the notes
directory as the transcript a human reads.

What failure looks like: a round that never ends, an approval that
approves and nothing happens, a dropped counter ticking, a re-fire storm
after a reboot, a note that contradicts the tools' output.

## 4. Tests

- Starter: an `@SpringBootTest` slice with an embedded Postgres
  (Testcontainers, the same image `DurableResumeTest` uses) proving the
  beans assemble, a `tell` parks an approval, the projection row appears,
  the desk answers it, the row updates, `shutdown` runs on close. A
  second context over the same DB sees the same pending row (two
  harnesses, both stores shared — the rule from harness.md).
- Watchman: tool tests against fake process output (each tool takes a
  `CommandRunner` seam; the real one is `ProcessBuilder`); a scripted
  model round that proposes a remediation and writes a note; the page
  renders pending rows and posts an approval; `long_job` completes
  through the desk. No test shells out to the host.
- The example's `--scripted` mode runs the whole round without a model
  key, as `hello` does.

## 5. Docs

`docs/guides/spring-boot.md` (new), `docs/guides/harness.md` (pointer),
`README.md` (the starter line), `CHANGELOG.md` (added: starter, the
projection, the example).

## 6. Open for James

1. `nessy-spring-boot-starter` as a module — taken as yes from "If you
   think the watchman thing will work…"; recorded here so it is a
   decision, not an assumption.
2. The projection table living in the starter rather than the example —
   it is the first thing every Boot app with approvals needs, and it is
   the stream's first real consumer.
3. Spring Boot version: whatever the root pom manages today, bumped to
   current stable if it is behind — the implementer reports which.

## 7. Rejected

- Wiring the harness by hand inside the example — the code every Boot
  user would copy.
- Reading pending approvals from the phases in the substrate — a scan of
  every scope's state document to answer "what's waiting"; the projection
  is a table because the question is a query.
- A notifier (Slack, email) for new parks — the page is the notifier;
  James looks every couple of days on purpose.
- Tools specific to James's box — feature detection instead, so the
  example runs anywhere.

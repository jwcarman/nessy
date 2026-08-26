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
| `Model` | `ModelDiscovery.select()` (nessy-model-discovery; `NESSY_MODEL` picks the id, `NESSY_PROVIDER` breaks a tie) — the closeable `Selection` is itself a bean with `destroyMethod = "close"`, so the container closes the gateway's SDK client on shutdown | always; a user `Model` bean wins, and discovery then never runs |
| `Harness<String>` | `Nessy.harness(c -> c.type(...).model(...).systemPrompt(...).grants(...).substrate(...).continuum(...).observationRegistry(...).harnessObserver(...))` | always; grants come from every `ToolGrant` bean and every `Tool` bean (a bare `Tool` bean is granted `Approvers.allow()`) |
| `ApprovalDesk`, `CompletionDesk` | `harness.approvals()` / `harness.completions()` | always |
| `ObservationRegistry` → seam | Boot's own `ObservationRegistry` bean (from `spring-boot-starter-actuator` + micrometer) | when present; else NOOP |
| shutdown | `harness.shutdown()` on context close | always |

Properties, all under `nessy.`: `type`, `system-prompt` (or
`system-prompt-file`), `staleness` (duration; the re-fire policy),
`backlog-capacity`. Nothing else — the recipe's tools and approvers are
beans, because they are code.

*Amended 2026-08-26 during execution:* there is no `nessy.model.id`. The
`Model` bean comes from `ModelDiscovery.select()` (the table above said
`fromEnv()` through several drafts; `select()` is what shipped, because it
hands back a closeable `Selection` the container can own), and that module
already owns
the id override (`NESSY_MODEL`); a property-driven choice is a
user-supplied `Model` bean, which wins. Widening `nessy-model-discovery`
for a property it did not need was rejected. And: supplying a `HarnessObserver` used to replace the default
narrator. James: "I don't want only one observer." So
`HarnessConfig.harnessObserver(...)` is additive, the default narrator
is always subscribed (`Harness.subscribe` stays package-private — no runtime door until something needs one);
the starter subscribes `PendingApprovals` and every user-supplied
`HarnessObserver` bean. No factory, no starter-side composite.

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
  through the additive `harnessObserver(...)` door, beside the default
  narrator and any user-supplied observers. On `ApprovalDeferred` applied: insert
  `(agent_type, agent_id, call_id, computation_id, request_json, action,
  parked_at)`. On `ApprovalAnswered` applied: update with
  `(answer, reference, principal_note, answered_at)`. Row keyed by
  `computation_id`.
- A `PendingApprovalsRepository` bean with `List<PendingApproval>
  pending()` and `List<PendingApproval> recent(int)`; the row type is a
  record in the starter.
- DDL as a classpath resource the application applies (Flyway/Liquibase
  or by hand), like `nessy-substrate-jdbc`'s.
- It is a projection: at-least-once, never the source of truth, and
  **not self-healing**. Approve/deny go through `ApprovalDesk`; the row
  updates when the fold's fact arrives. *Amended 2026-08-26 (final
  review):* this bullet used to say a lost row returns "until the
  staleness re-fire re-asks". **That was never true.**
  `Phase.AwaitingTools#outstandingEffects` contributes no effect for a
  call in `AwaitingApproval` — the Continuum holds it — the projection
  ignores `reFired`, and `Harness.subscribe` is package-private by
  ruling, so there is no replay door. A fact lost to a restart or a
  `DataSource` blip is lost permanently, in both directions: a lost
  **park** leaves no row (the approval is still parked and still
  answerable by coordinates, but the page cannot show it), and a lost
  **answer** leaves the row in `pending()` forever, showing a human a
  decision already made — re-answering is a benign no-op at the desk, so
  the click appears to do nothing. The ledger is the phase; the
  operator's recourse is the agent's own transcript. A future
  self-healing rebuild would need a public replay door on the fact
  stream; one was deliberately not invented for this.

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

- `disk_usage` — `df -hP` parsed; percent per mount. (*Amended during
  execution:* `-P` added. POSIX output keeps one filesystem on one line;
  without it a long device name wraps and every column shifts, silently
  corrupting the parse.)
- `failed_units` — `systemctl --failed` when systemd is present.
- `journal_errors` — `journalctl -p err --since -30m` when present.
- `containers` — `docker ps -a --format json` when docker is present;
  unhealthy/exited flagged. (*Amended during execution:* `-a` added. A
  container that EXITED is exactly what this tool exists to notice, and
  bare `docker ps` hides precisely those — which the "unhealthy/exited
  flagged" clause requires.)
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
(should be zero), `nessy.state.stale_retries` (see the amendment below),
`nessy.effects.refired` (usually zero — a parked scope re-fires nothing,
because the phase names the computation it awaits; only a call caught
`Pending` or `Running` by a crash produces one). Plus the notes
directory as the transcript a human reads.

**Amendment (2026-08-26, the SOAK — finding F3).** This section said
`nessy.state.stale_retries` "should be near zero", and the README added
that a rising count meant rounds were being re-driven while genuinely
waiting on a human. Both were wrong, and the first real round said so: a
healthy round that ran three tools in parallel plus six `create_memory`
writes produced FIVE retries. Concurrent folds on one scope contend on
the CAS and the losers re-read and re-handle — the retries are the cost
of parallelism, not a fault. Read per round, against that round's own
parallelism: **a round with N calls in flight normally produces retries
on the order of N.** What is pathological is a count that climbs while no
`invoke_agent` span is open (a scope re-driven while parked — the thing
the old text described), or a per-round count that grows without the
rounds getting more parallel, which means contention from outside the
round. The absolute number means nothing on its own. The README carries
the full rule of thumb.

Also amended by the same soak: the three counters are span EVENTS on the
round's `invoke_agent` span rather than observations of their own — see
the o11y spec §1.2 Amendment 3 — so on the dashboards they are read by
opening a round, not by listing traces.

What failure looks like: a round that never ends, an approval that
approves and nothing happens, a dropped counter ticking,
`nessy.effects.refired` climbing while nothing has crashed, a note that
contradicts the tools' output.

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

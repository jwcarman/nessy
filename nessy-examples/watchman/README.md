# watchman — the soak

A Spring Boot agent that lives on a Linux box, does rounds on a timer, proposes
things it is not allowed to do, and waits days for you to answer.

Everything Nessy claims about production — parks that survive restarts,
deferred tools, real dwell time, telemetry — has been proven by tests and never
by time. This is the thing that runs for a month and finds out.

The harness comes entirely from `nessy-spring-boot-starter`. Nothing in this
module wires a Nessy type by hand: the tools are beans, the grants are beans,
the `DataSource` is a bean, and the starter assembles a durable, observed
harness out of them.

---

## What it does

Every half hour (`watchman.cron`, default `0 */30 * * * *`) it tells one agent —
`AgentId.of("watchman")` — the time and asks it to do its rounds. A round is
four steps, and the system prompt (`src/main/resources/system-prompt.md`) says
so: read back the last few notes, look at the box, act or propose, write a note.

**Read-only tools** run without asking anybody:

| tool | what it runs | present when |
|---|---|---|
| `disk_usage` | `df -hP` | `which df` |
| `failed_units` | `systemctl --failed --no-legend --plain --no-pager` | `which systemctl` |
| `journal_errors` | `journalctl -p err --since -Nm --no-pager --no-hostname` | `which journalctl` |
| `containers` | `docker ps -a --format json` | `which docker` |
| `updates_pending` | `apt list --upgradable` or `dnf check-update` | `which apt` or `which dnf` |
| `uptime_load` | reads `/proc/loadavg`, `/proc/uptime` | both files readable |
| `previous_notes` | reads the notes directory | always |
| `write_note` | appends to `${watchman.notes-dir}/YYYY-MM-DD.md` | always |

**Remediation tools park.** Each is a `ToolGrant` with `Approvers.defer()` and
an `ActionContributor` that renders **the exact command line that will run** —
that string is what the approval page shows, and it is the whole question you
answer:

| tool | the line the page shows | present when |
|---|---|---|
| `restart_unit(name)` | `systemctl restart -- <name>` | `which systemctl` |
| `restart_container(name)` | `docker restart -- <name>` | `which docker` |
| `prune_images` | `docker image prune -af` | `which docker` |
| `apply_updates` | `apt-get -y upgrade` / `dnf -y upgrade` | `which apt` or `which dnf` |
| `clean_journal(days)` | `journalctl --vacuum-time=<days>d` | `which journalctl` |

The `--` is not decoration and must not be tidied away: the name comes from
the model, and without an end-of-options marker a name beginning with a dash
is read as a flag — `restart_unit("--version")` would look like a restart on
the page and do something else on the box. Arguments that are not plain words
are quoted for the same reason, so `restart_unit("web api")` renders
`systemctl restart -- 'web api'` rather than something that reads as two
units.

**One tool defers at the tool level.** `long_job` starts `fstrim -av` in the
background, hands the id from `ToolContext.defer()` to a watcher thread, and
completes it through `CompletionDesk` when the process exits — from a thread
that is not the harness's. It exists to exercise that path for real, over days,
and is registered only where `which fstrim` succeeds.

**Feature detection is a `which` at startup.** It runs once, before the Spring
context exists, and publishes `watchman.detected.*` properties that gate the
tool beans. A tool for a command this host does not have is never created, so
the model never sees it — on a box without docker the watchman genuinely cannot
restart containers, and is not told that it can.

---

## Running it

### 1. Start the two things it needs

```
cd nessy-examples/watchman
docker compose up -d
```

That is Postgres 17 on **5432** and `grafana/otel-lgtm` on **3000** (Grafana),
**4317** (OTLP/gRPC) and **4318** (OTLP/HTTP — what `application.yml` points at).

The application itself is deliberately not in the compose file: it needs `df`,
`systemctl`, `docker` and the journal of the **real** host, and a container
would only ever see its own.

### 2. Apply the three schemas

Nessy ships DDL and never runs it. Three files, all on the classpath, all
idempotent (`CREATE TABLE IF NOT EXISTS`):

| what | resource |
|---|---|
| the substrate | `org/jwcarman/nessy/substrate/jdbc/nessy-postgresql.sql` (in `nessy-substrate-jdbc`) |
| the Continuum | `org/jwcarman/continuum/jdbc/continuum-postgresql.sql` (in `continuum-jdbc`) |
| the pending-approvals projection | `org/jwcarman/nessy/spring/boot/pending-approvals-postgresql.sql` (in `nessy-spring-boot-starter`) |

Extract and apply them by hand, or point Flyway at them:

```
unzip -p ~/.m2/repository/org/jwcarman/nessy/nessy-substrate-jdbc/*/nessy-substrate-jdbc-*.jar \
  org/jwcarman/nessy/substrate/jdbc/nessy-postgresql.sql | psql "$WATCHMAN_DB_URL"
unzip -p ~/.m2/repository/org/jwcarman/continuum/continuum-jdbc/*/continuum-jdbc-*.jar \
  org/jwcarman/continuum/jdbc/continuum-postgresql.sql | psql "$WATCHMAN_DB_URL"
unzip -p ~/.m2/repository/org/jwcarman/nessy/nessy-spring-boot-starter/*/nessy-spring-boot-starter-*.jar \
  org/jwcarman/nessy/spring/boot/pending-approvals-postgresql.sql | psql "$WATCHMAN_DB_URL"
```

### 3. Run it

```
./mvnw -q -pl nessy-examples/watchman -am package -DskipTests

export ANTHROPIC_API_KEY=...             # this module ships nessy-model-anthropic only
export NESSY_MODEL=...                   # optional: the model id override
export WATCHMAN_DB_URL=jdbc:postgresql://localhost:5432/watchman
export WATCHMAN_USER=ops
export WATCHMAN_PASSWORD='something you did not read in this file'
export WATCHMAN_NOTES_DIR=/var/lib/watchman/notes

java -jar nessy-examples/watchman/target/nessy-example-watchman-*.jar
```

`watchman.user` and `watchman.password` are **required and have no defaults**.
The application refuses to start without them, on purpose — see Privileges,
immediately below, for what those two buttons are actually attached to.

---

## Privileges — read this before the first run

**`java -jar` as your own user is not enough.** Five of the tools need
privilege, and the README used to say nothing about it:

| tool | needs |
|---|---|
| `restart_unit` | root, or `systemctl` policy allowing that unit |
| `clean_journal` | root (`journalctl --vacuum-time` writes to `/var/log/journal`) |
| `apply_updates` | root |
| `long_job` | root (`fstrim` opens block devices) |
| `restart_container` | root, or membership of the `docker` group |

The read-only tools mostly do not: `df`, `uptime_load`, `previous_notes` and
`write_note` need nothing special, `journalctl -p err` shows only your own
units without privilege, and `docker ps` needs the `docker` group.

**The recommended shape** is a dedicated unprivileged user with narrowly-scoped
sudoers entries for exactly those commands and nothing else — so that a
compromise of the agent buys those five actions, not the machine:

```
# /etc/sudoers.d/watchman  (visudo -f, never a plain editor)
Cmnd_Alias WATCHMAN = /usr/bin/systemctl restart *, \
                      /usr/bin/journalctl --vacuum-time=*, \
                      /usr/bin/apt-get -y upgrade, \
                      /usr/sbin/fstrim -av, \
                      /usr/bin/docker restart *
watchman ALL=(root) NOPASSWD: WATCHMAN
```

That requires prefixing the argv with `sudo` — a change to each tool's `argv`
method, deliberately **not** made here, because a sudoers file that does not
match the argv exactly is worse than none. Decide the shape for your box first.

**Or accept running as root**, which is what the compose-and-`java -jar` path
above actually implies. If you do:

> **The basic-auth page becomes a root surface on your LAN.** Anyone who reaches
> port 8080 and knows one password can restart any unit, prune every image and
> upgrade every package on the box. That is not "a LAN is not a trust boundary"
> as a slogan — it is the literal consequence, and it is why
> `watchman.password` has no default and the application will not start without
> one. Bind it to an interface you trust, put it behind a reverse proxy with TLS
> if it leaves the machine, and pick a password you did not read in this file.

**Feature detection is presence-only.** `which docker` succeeding means the
binary exists, not that this process may use it. A host with docker installed
and the agent outside the `docker` group registers `restart_container` and then
fails every call with a permission error — visible in the tool result and in
the notes, but not before. There is deliberately no permission probe: the only
honest one is running the command.

---

## What leaves the box

Worth knowing before pointing this at a machine with real users on it.

**To the model provider.** Every tool result the agent reads is sent to
Anthropic as part of the conversation. `journal_errors` is the sharp one: raw
`journalctl` lines routinely carry hostnames, usernames, source IP addresses,
failed-authentication detail, mail addresses, and whatever your services log at
error level. `containers` sends container names, `disk_usage` sends mount
points, `previous_notes` sends everything a previous round wrote. Prompt
caching is enabled, so the system prompt and tool schemas are held by the
provider for its cache window — but tool results are conversation content and
go every round regardless.

**To the collector.** The OTLP log appender ships this application's own log
lines to Loki, and spans carry tool names and agent ids. Tool results are not
logged wholesale, but anything the application logs at INFO travels.

**To disk.** The notes directory is plain markdown, world-readable unless you
say otherwise, and contains whatever the model chose to write down about what
it saw.

Nothing here is anonymised or redacted. If that is not acceptable for your box,
the honest fix is to drop `journal_errors` from `ToolBeans` — the tool set is
just beans, and a tool that is not registered cannot send anything.

### Without an API key

```
java -jar nessy-examples/watchman/target/nessy-example-watchman-*.jar --scripted
```

`--scripted` activates a profile whose `Model` bean is a fixed script: look at
the disk, write the note, propose `systemctl restart nginx.service`. No key, no
network, no tokens. It is how you find out whether Postgres, the three schemas,
the page and the projection are wired correctly before spending anything. It
still needs the database — the projection is a table.

---

## Approving something

Open <http://localhost:8080/> and log in with `watchman.user` /
`watchman.password`.

- **`GET /`** — everything waiting, longest wait first: the exact command line,
  which agent asked, when it parked, how long it has waited, and the frozen
  approval request expandable underneath as the evidence it was judged on.
- **`POST /approve/{id}`** with an optional note, **`POST /deny/{id}`** with a
  reason — the two buttons. The principal is whoever authenticated, never a
  form field, so the audit trail has a name in it.
- **`GET /recent`** — the last fifty answered.

The row does not change the instant you click. Approving goes through
`ApprovalDesk`, the fold publishes a fact, and the projection catches up — the
page is a queryable shadow of the phase, never the source of truth. Refresh a
second later and it has moved to `/recent`.

A denial's reason is not just for you: it goes back to the model as the tool
result, so "not during the change freeze" is something the next round can read.

HTMX comes from a **webjar** (`org.webjars.npm:htmx.org`), not a CDN — this box
may have no outbound internet at all, and a page that cannot render during an
outage is exactly the page you need then. It is used only as `hx-boost` over
ordinary form posts, so with scripting off everything still works.

---

## Crashing it on purpose

This is the point of the exercise. The claim is that a parked approval survives
the death of the process that asked, because the phase is in Postgres and the
Continuum holds the computation.

1. Let a round park something. Confirm it on `GET /`.
2. `kill -9` the JVM. Not a graceful shutdown — that would prove much less.
3. Start it again.
4. `GET /` still shows the same approval, with the same computation id and its
   dwell still counting from the original park.
5. Approve it. The tool runs, in a turn belonging to a process that did not
   exist when the question was asked.

Two more worth doing over a month:

- **Reboot the box.** A scope parked on an approval re-fires *nothing* — the
  phase names the computation it is waiting on, so there is no effect to
  re-issue, and `nessy.effects.refired` will not tick for it. What you are
  proving is that the park survived: the row is still on the page, the
  `nessy.approval.wait` span is still open, and answering it after the restart
  still resumes the round. A re-fire counts only for a scope caught
  mid-flight — `Pending` or `Running` — which a reboot between rounds will
  usually not produce at all.
- **Stop the LGTM container for an hour.** Nothing should break. Exporter
  failures are dropped, never thrown; the application keeps logging to the
  console and doing rounds.

---

## What to look at in Grafana after a day

<http://localhost:3000>. From the o11y roster (spec §3):

| what | reading it |
|---|---|
| `nessy.approval.wait` | the headline. Real dwell, in days, on approvals a real person really did leave sitting. No test can produce this number. |
| `nessy.tool.wait` | `long_job`'s own wait — the deferred-tool path, measured rather than asserted. |
| `invoke_agent` spans | one per round. The shape of a healthy round is minutes; a round that never ends is the first failure mode. |
| `jdbc.query` / `jdbc.connection` | **the SQL underneath the memory spans.** `search_memory` and `create_memory` are otherwise leaves, and a slow one is ambiguous: is recall slow, or is the transcript huge? Open a memory span and read its children — connection acquisition and query time are separate, and the statement rides the span. Spring Boot does *not* instrument JDBC on its own (the built app has 132 jars and not one of them is a JDBC instrumentation); this comes from `net.ttddyy.observation:datasource-micrometer-spring-boot`, declared in this module's POM and nowhere else. It is an application's choice to have its `DataSource` wrapped — never the starter's, and never `nessy-agent`'s. |
| `chat` tokens | `gen_ai.client.token.usage` per round, split input/output. What the soak costs per day. |
| `nessy.delivery.dropped` | **should be zero.** Anything else means an answer arrived for a phase that was not waiting for it. |
| `nessy.state.stale_retries` | **normal, and proportional to parallelism** — see below. Read it per round against how many calls that round ran at once, never as an absolute. |
| `nessy.effects.refired` | **usually zero.** A parked scope re-fires nothing; only a call caught `Pending` or `Running` by a crash does. A steady climb means rounds are being re-driven mid-flight. |

All three are **span events on the round's own `invoke_agent` span**, not
standalone traces — open the round in Tempo and read its events. (A count
recorded while no round was running is the exception: that one is its own
tiny trace, because there was no round to hang it on.)

### Reading the cache numbers

Two observed rounds a minute apart recorded `gen_ai.usage.cache_read.input_tokens`
and `cache_write.input_tokens` **both zero**, with `nessy.capabilities:
prompt-caching` set and reaching the request. The cause was placement, not
plumbing: the Anthropic request marked the system prompt and the last tool
definition and nothing else, so the only cacheable prefix was the part that never
grows, while the transcript — 13 records to 45 in that one minute — could never be
cached at all. The request now also marks the conversation, which is what makes a
long-running agent's cache worth having.

What to look for on the next run, in order:

1. **`cache_write.input_tokens` > 0 on the first chat of a round.** If this is
   still zero, the prefix is under the model's minimum cacheable size (model
   dependent — 1024 tokens on Sonnet 5 and Opus 4.8, 512 on Opus 5, 4096 on Opus
   4.6 and Haiku 4.5). Nothing is broken; there is simply not enough to cache
   yet, and it will start once the transcript is long enough.
2. **`cache_read.input_tokens` > 0 on the *second* chat of the same round.** This
   is the number that proves the fix. Within a round, the model is called several
   times — once per tool result batch — seconds apart, and each call should read
   back what the previous one wrote.
3. **`cache_read` ACROSS rounds only if rounds are close together.** The cache
   entries are the 5-minute ephemeral kind. On the default `0 */30 * * * *` cron,
   every entry has expired long before the next round starts, so cross-round reads
   are *expected to be zero* and their absence is not a bug. Run the cron every
   minute or two if you want to watch cross-round reads; otherwise the win is
   entirely within-round, which for a round that runs several tool batches is
   still most of the tokens.
4. **Watch `input_tokens` *against* the transcript length, not on its own.** The
   Anthropic adapter passes the provider's own `input_tokens` straight through,
   and Anthropic reports that number *excluding* what it served from cache. So a
   round whose `input_tokens` stops growing while the transcript keeps growing is
   the fix working, not a stalled agent — and the prompt's true size is
   `input_tokens + cache_read + cache_write`. (Note: `TokenUsageHandler`'s javadoc
   quotes semconv's "SHOULD be included in `gen_ai.usage.input_tokens`" and warns
   against summing all four series. That warning is correct for a provider that
   follows the SHOULD; Anthropic does not, so for this soak the four series do not
   overlap. Read the numbers, not the guidance, until that is reconciled.)

### Reading `nessy.state.stale_retries`

This runbook used to say the count "should be near zero", and that a rising
count meant rounds were being re-driven while waiting on a human. **That is
wrong**, and the first real round proved it: a perfectly healthy round that ran
three tools in parallel and wrote six `create_memory` entries produced **five**
stale retries.

They are not a symptom. Every fold reads the scope, does its work, then
CAS-writes; concurrent folds on the same scope contend, the losers re-read and
re-handle, and the phase converges. That is the design working — the retries
are the *cost* of parallelism, not evidence of a fault.

The rule of thumb:

- **A round with N calls in flight normally produces retries, roughly on the
  order of N.** Zero retries on a round that ran one tool is unremarkable; five
  on a round that ran three tools plus a handful of memory writes is exactly
  right. Compare a round's retries against *that round's* parallelism.
- **What is pathological is retries with nothing to explain them.** Two shapes
  to watch for: a count that climbs while no `invoke_agent` span is open (a
  scope being re-driven while it is parked, which is what the old text
  described and what would be genuinely wrong), and a per-round count that
  grows week over week *without* the rounds getting more parallel — that is
  contention from somewhere other than this round's own tool calls, e.g. a
  second process writing the same scope.
- **The absolute number is meaningless on its own.** A busy soak day is
  supposed to have more of them than a quiet one.
| the scheduler's own logs | a full backlog is the failure that shows up here and nowhere else. `tell` throws when the per-scope backlog (`nessy.backlog-capacity`, 256) is full, and it throws out of `Rounds.doRounds()` into Spring's scheduler — which logs it and schedules the next tick as if nothing happened. So the symptom is not a crash: it is rounds that keep firing and keep doing nothing, with an exception in the log every half hour. If `invoke_agent` spans stop appearing while the cron keeps ticking, read the log before anything else. |

And the notes directory, which is the transcript a human actually reads.

**What failure looks like:** a round that never ends; an approval that approves
and nothing happens; `nessy.delivery.dropped` ticking; `nessy.effects.refired`
climbing while nothing has crashed; a note that contradicts what the tools
returned. The last one is the
only failure the dashboards cannot show you, which is why the notes are on disk
in plain markdown.

---

## Configuration

| property | default | what it is |
|---|---|---|
| `nessy.type` | `watchman` | the recipe name — the first coordinate of every durable address |
| `nessy.system-prompt-file` | `classpath:system-prompt.md` | what a round is |
| `nessy.staleness` | `30m` | how long a quiet phase may sit before the recovery arm re-fires it |
| `nessy.backlog-capacity` | `256` | per-scope backlog depth |
| `nessy.capabilities` | `prompt-caching` | what the harness ASKS the provider to use. A provider that cannot do it says so and nothing fails; `gen_ai.usage.cache_read.input_tokens` and `cache_write.input_tokens` on the chat span are how you tell whether it did. See "Reading the cache numbers" below — the interesting prefix is the transcript, not the system prompt. |
| `watchman.cron` | `0 */30 * * * *` | when rounds happen |
| `watchman.scheduling.enabled` | `true` | set `false` and rounds only happen when something calls them — how the tests keep cron out of their assertions |
| `watchman.notes-dir` | `./notes` | where the daily notes live |
| `watchman.note-history` | `3` | how many notes `previous_notes` hands back by default |
| `watchman.command-timeout` | `30s` | how long any one host command may take before it is destroyed |
| `watchman.upgrade-timeout` | `15m` | how long `apply_updates` may take. Separate and much longer on purpose: the timeout is enforced by destroying the process, so thirty seconds on `apt-get -y upgrade` is a SIGKILL to dpkg mid-transaction |
| `watchman.user` / `watchman.password` | **none** | the single account the page accepts; required |

The model is **not** a property. The starter calls `ModelDiscovery.select()`,
which picks a provider from whichever credentials are in the environment and
hands back a closeable `Selection` — a bean with a destroy method, so the
container closes the SDK client and its connection pool on shutdown rather than
leaking them. `NESSY_MODEL` overrides the model id and `NESSY_PROVIDER` breaks a
tie; an application that wants to choose any other way declares its own `Model`
bean, and discovery then never runs at all — which is exactly what `--scripted`
does.

**Only `nessy-model-anthropic` is on this module's classpath.** Discovery can
only select a provider whose bootstrap it can see, so `OPENAI_API_KEY` alone
will not start this application. Add the provider's module to the pom to use it.

---

## Tests

`./mvnw -q -pl nessy-examples/watchman -am test`

No test shells out to the host: every tool takes a `CommandRunner`, and the
tests hand it canned `df`, `systemctl`, `docker ps` and `apt` output. The two
Boot tests use Testcontainers Postgres — the projection is a table, so testing
the page against in-memory stores would be testing something else. Docker is
needed for those two.

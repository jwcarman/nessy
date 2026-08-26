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
| `restart_unit(name)` | `systemctl restart <name>` | `which systemctl` |
| `restart_container(name)` | `docker restart <name>` | `which docker` |
| `prune_images` | `docker image prune -af` | `which docker` |
| `apply_updates` | `apt-get -y upgrade` / `dnf -y upgrade` | `which apt` or `which dnf` |
| `clean_journal(days)` | `journalctl --vacuum-time=<days>d` | `which journalctl` |

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
| the pending-approvals projection | `org/jwcarman/nessy/spring/boot/pending_approvals.sql` (in `nessy-spring-boot-starter`) |

Extract and apply them by hand, or point Flyway at them:

```
unzip -p ~/.m2/repository/org/jwcarman/nessy/nessy-substrate-jdbc/*/nessy-substrate-jdbc-*.jar \
  org/jwcarman/nessy/substrate/jdbc/nessy-postgresql.sql | psql "$WATCHMAN_DB_URL"
unzip -p ~/.m2/repository/org/jwcarman/continuum/continuum-jdbc/*/continuum-jdbc-*.jar \
  org/jwcarman/continuum/jdbc/continuum-postgresql.sql | psql "$WATCHMAN_DB_URL"
unzip -p ~/.m2/repository/org/jwcarman/nessy/nessy-spring-boot-starter/*/nessy-spring-boot-starter-*.jar \
  org/jwcarman/nessy/spring/boot/pending_approvals.sql | psql "$WATCHMAN_DB_URL"
```

### 3. Run it

```
./mvnw -q -pl nessy-examples/watchman -am package -DskipTests

export ANTHROPIC_API_KEY=...             # or any provider ModelDiscovery finds
export NESSY_MODEL=...                   # optional: the model id override
export WATCHMAN_DB_URL=jdbc:postgresql://localhost:5432/watchman
export WATCHMAN_USER=ops
export WATCHMAN_PASSWORD='something you did not read in this file'
export WATCHMAN_NOTES_DIR=/var/lib/watchman/notes

java -jar nessy-examples/watchman/target/nessy-example-watchman-*.jar
```

`watchman.user` and `watchman.password` are **required and have no defaults**.
The application refuses to start without them, on purpose: the two buttons on
its page restart production services, and a LAN is not a trust boundary.

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

- **Reboot the box.** On restart the recovery arm re-fires each parked scope
  exactly once — watch `nessy.effects.refired` show one per parked scope, not a
  storm.
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
| `chat` tokens | `gen_ai.client.token.usage` per round, split input/output. What the soak costs per day. |
| `nessy.delivery.dropped` | **should be zero.** Anything else means an answer arrived for a phase that was not waiting for it. |
| `nessy.state.stale_retries` | **should be near zero.** A rising count means rounds are being re-driven while genuinely waiting on a human. |
| `nessy.effects.refired` | one per reboot per parked scope. More than that is a re-fire storm. |

And the notes directory, which is the transcript a human actually reads.

**What failure looks like:** a round that never ends; an approval that approves
and nothing happens; `nessy.delivery.dropped` ticking; a re-fire storm after a
reboot; a note that contradicts what the tools returned. The last one is the
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
| `watchman.cron` | `0 */30 * * * *` | when rounds happen |
| `watchman.scheduling.enabled` | `true` | set `false` and rounds only happen when something calls them — how the tests keep cron out of their assertions |
| `watchman.notes-dir` | `./notes` | where the daily notes live |
| `watchman.note-history` | `3` | how many notes `previous_notes` hands back by default |
| `watchman.command-timeout` | `30s` | how long any one host command may take before it is destroyed |
| `watchman.user` / `watchman.password` | **none** | the single account the page accepts; required |

The model is **not** a property. `ModelDiscovery.fromEnv()` picks the provider
from whichever credentials are in the environment, `NESSY_MODEL` overrides the
model id, and an application that wants to choose any other way declares its own
`Model` bean — which is exactly what `--scripted` does.

---

## Tests

`./mvnw -q -pl nessy-examples/watchman -am test`

No test shells out to the host: every tool takes a `CommandRunner`, and the
tests hand it canned `df`, `systemctl`, `docker ps` and `apt` output. The two
Boot tests use Testcontainers Postgres — the projection is a table, so testing
the page against in-memory stores would be testing something else. Docker is
needed for those two.

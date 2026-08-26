# Spring Boot

`nessy-spring-boot-starter` is a harness assembled from Spring beans. It
composes; it invents nothing. Every bean it registers is one an
application could write by hand from the public API described in
[The Harness](harness.md), and every one steps aside — via
`@ConditionalOnMissingBean` — for an application's own. What the starter
contributes is the wiring nobody should have to write twice: a
`DataSource` becoming the durable store pair, `Tool` beans becoming
grants, Boot's `ObservationRegistry` reaching the harness's observability
seam, and the harness's own `shutdown()` running on context close.

Add the jar, declare a system prompt, and a `Harness<String>` bean comes
out:

```xml
<dependency>
  <groupId>org.jwcarman.nessy</groupId>
  <artifactId>nessy-spring-boot-starter</artifactId>
</dependency>
```

```yaml
nessy:
  type: ops
  system-prompt: You are the ops assistant.
```

That alone is a working, in-memory harness with no tools. `nessy-examples/watchman`
is the full runnable shape — a soak agent, a `DataSource`, remediation
tools, and a page over the pending-approvals projection this page
describes. Read its [README](https://github.com/jwcarman/nessy/blob/main/nessy-examples/watchman/README.md)
alongside this page for the consumer-code half of the story.

## What it wires

| Bean | From | Condition |
|---|---|---|
| `Model` | `ModelDiscovery.Selection#model()` | `@ConditionalOnMissingBean` |
| `ModelDiscovery.Selection` | `ModelDiscovery.select()`, `@Bean(destroyMethod = "close")` | `@ConditionalOnMissingBean(Model.class)` — never runs when the application supplies its own `Model` |
| `Substrate` | `new JdbcSubstrate(dataSource)` | a `DataSource` bean present and `nessy-substrate-jdbc` on the classpath; else `InMemorySubstrate` |
| `Continuum` | `new DefaultContinuum(new JdbcContinuumRepository(dataSource), InstantSource.system())` | same condition, same fallback |
| `Harness<String>` | `Nessy.harness(...)`, `@Bean(destroyMethod = "shutdown")` | `@ConditionalOnMissingBean` |
| `ApprovalDesk` | `harness.approvals()` | `@ConditionalOnMissingBean` |
| `CompletionDesk` | `harness.completions()` | `@ConditionalOnMissingBean` |
| `PendingApprovals` | a `HarnessObserver` writing the projection | `JdbcTemplate` on the classpath and a `DataSource` bean present |
| `PendingApprovalsRepository` | the projection's read door | same condition |

Grants come from every `ToolGrant` bean, taken exactly as declared, plus
every bare `Tool<?>` bean, granted `Approvers.allow()` — the two are
alternatives, not layers: a tool whose authority matters is declared as a
grant, a tool that only reads something is declared as a tool. Declaring
the same tool both ways registers it twice, and the tool registry rejects
the duplicate name at startup.

Every `HarnessObserver` bean is subscribed to the harness's fact stream
through the additive `harnessObserver(...)` door (see
[The Harness](harness.md#building-a-harness)) — one call per bean, in the
order Spring hands them over, alongside the harness's own default
narrator, which nothing here replaces. The pending-approvals projection is
one `HarnessObserver` bean among however many an application supplies; it
is not special-cased anywhere in the wiring.

Boot's own `ObservationRegistry` bean — the one `spring-boot-starter-actuator`
plus Micrometer assemble — reaches the harness's observability seam the
same way: `ObjectProvider<ObservationRegistry>`, falling back to
`ObservationRegistry.NOOP` when actuator is not on the classpath. Once
wired, every span and counter the [Observability](observability.md#the-roster-otel-genai-spans-and-counters)
guide describes flows through whatever exporters the application's own
`management.otlp.*`/`management.opentelemetry.*` properties point at — the
starter does not add an exporter of its own, only the seam.

## What it does not do

No web layer, no scheduling, no security, no approvers beyond what the
application declares. Those are the application's job: the starter wires
a harness, the app decides what it is for. `nessy-examples/watchman`
supplies all four — Spring MVC + Thymeleaf for the approvals page,
`@Scheduled` for the rounds, `spring-boot-starter-security` for the one
account that can click the buttons, and the remediation tools' own
`Approvers.defer()` grants.

## Properties

Everything under `nessy.` is genuinely configuration, not code — tools,
grants and approvers stay beans on purpose, because a properties file is
the wrong place to express authority.

| Property | Default | What it is |
|---|---|---|
| `nessy.type` | `agent` | the recipe's name — the first coordinate of every durable address |
| `nessy.system-prompt` | — | the system prompt, inline; exactly one of this and `system-prompt-file` must be set |
| `nessy.system-prompt-file` | — | a `Resource` (`classpath:`, `file:`) whose whole contents are the system prompt, for prompts too long for a properties value |
| `nessy.staleness` | `5m` | how long a quiet phase may sit before the recovery arm re-fires it |
| `nessy.backlog-capacity` | `1024` | the per-scope backlog depth |
| `nessy.capabilities` | *(empty)* | what the harness **asks** its provider to use — `nessy.capabilities=prompt-caching` |

Leaving both prompt properties unset, or setting both, fails the context
at startup with a message naming the property — a harness with no system
prompt is not a harness, and two sources for one prompt is a configuration
mistake worth failing loudly over rather than silently preferring one.

**There is no `nessy.model.id`.** See the next section.

**`nessy.capabilities`** binds to the `Capability` enum with relaxed
naming — `prompt-caching` becomes `PROMPT_CACHING` — and is laid over
`ModelSettings.defaults()`. It is a request, not an assertion: a provider
that cannot do what was asked says so, and nothing fails. `PROMPT_CACHING`
is the one a long-running agent wants — a system prompt and a tool schema
resent every round, forever, are exactly what a provider cache is for —
and whether it worked shows up as `gen_ai.usage.cache_read.input_tokens`
(against `cache_write` on the round that populated it) on the `chat` span;
see [Observability](observability.md#the-roster-otel-genai-spans-and-counters).

## Choosing a model — no `nessy.model.id`

The `Model` bean is `ModelDiscovery.select().model()`, from
`nessy-model-discovery`: it reads the process environment, picks whichever
provider module on the classpath can bootstrap from the credentials it
finds, and binds a model id — `NESSY_MODEL` when set, otherwise the
winning provider's own default. `NESSY_PROVIDER` breaks a tie when more
than one provider's credentials are present. None of this is a `nessy.*`
property; it was deliberately not widened to be one. An application that
wants the model chosen by a property, a database row, or a feature flag
declares its own `Model` bean, which wins outright over the starter's —
`@ConditionalOnMissingBean` steps aside, and discovery never runs at all
(so it never reaches for credentials the application deliberately did not
supply).

The gateway discovery builds — the SDK client, its connection pool, its
threads — is registered as its own bean, `ModelDiscovery.Selection`, with
`@Bean(destroyMethod = "close")`: a `ModelProvider` is `AutoCloseable`
(every vendor SDK this repository wraps has a `close()`), and a container
that builds a gateway and never releases it leaks all three for the life
of the process. This bean is conditional on a **missing `Model`**, not a
missing `Selection` — the same reasoning as above, from the other
direction: an application supplying its own `Model` bean owns whatever
built it, so discovery, and the credentials it would need, must never run.

## Durable stores, and the both-or-neither guard

A `DataSource` bean is the whole durability switch. Present, and with
`nessy-substrate-jdbc`/`continuum-jdbc` on the classpath, the starter
wires a `JdbcSubstrate` and a `JdbcContinuumRepository`-backed `Continuum`
— both, from one `DataSource`, in the same configuration class, so the
starter itself can never wire one durable and the other volatile. Absent
either the bean or the classpath adapters, both fall back to their
in-memory counterparts.

What the starter's own wiring cannot make impossible is an application
supplying **exactly one** of `Substrate`/`Continuum` itself while a
`DataSource` is present — its own store suppresses the starter's via
`@ConditionalOnMissingBean`, while the starter still builds the other one
JDBC-backed from the `DataSource`, and nobody says a word. A durable
substrate over a volatile computation store silently drops every
delivery; the reverse hangs every parked call — see
[Durable Computation](../concepts/durable-computation.md) for why the two
must match.

So it is said at startup instead: the harness bean throws
`IllegalStateException` — naming which bean the application supplied and
which one the starter still built — the moment a `DataSource` is present
and exactly one of the two stores is user-supplied. Supplying **both**
yourself is unaffected; that pairing is the application's own business,
matched or not, and the starter has no opinion on it. Supplying **neither**
with a `DataSource` present is the ordinary case this whole page describes.

## The pending-approvals projection

Nothing in Nessy can *enumerate* parked approvals — the desk answers by
id or by coordinates, the Continuum client has no read door, and a phase
is per agent. A page that lists what is waiting needs a projection, and
the harness's own fact stream is exactly the thing to project from. The
starter ships one, because every Boot application with approvals will
want the same table.

`PendingApprovals` is a `HarnessObserver` bean, wired through the same
additive `harnessObserver(...)` door every other observer uses — nothing
about it is special-cased. On `ApprovalDeferred` applied, it inserts a row
keyed by computation id: agent type, agent id, call id, the rendered
action, the frozen `ApprovalRequest` as JSON, and when it parked. On
`ApprovalAnswered` applied, it fills in the answer, the reference, a
denial's note, and when it was answered. `PendingApprovalsRepository`
answers the two questions a page asks: `pending()` (longest wait first)
and `recent(int limit)` (most recently answered first).

**It is a projection, not the source of truth.** Approve and deny always
go through `ApprovalDesk` — never write to this table directly — and a
row changes only when the fold's own fact arrives. Two consequences
follow, both accepted rather than fixed:

- **At-least-once.** A fact may be re-delivered, so both the park write
  and the answer write are `INSERT … ON CONFLICT … DO UPDATE`, and each is
  a no-op on redelivery.
- **Not necessarily in commit order.** `HarnessObserver`'s own contract
  says publishes for one scope can reach a subscriber in either order, so
  an answer can arrive before the park it answers. Both directions
  therefore upsert, and neither ever overwrites the other's columns — a
  park fills only the park columns while they are empty, an answer fills
  only the answer columns while they are empty. `pending()` filters out a
  row that holds an answer but no request yet; the park's own fact
  completes it moments later.
- **A restart between the fold and the insert loses a row.** The page then
  shows one approval fewer than the phase actually holds, until the
  staleness re-fire re-asks and the projection catches up. The ledger is
  the phase; this table is a queryable shadow of it.

!!! warning "PostgreSQL only"
    Every statement is written in PostgreSQL's dialect — `ON CONFLICT …
    DO UPDATE` and a `jsonb` column — and the DDL is named for it:
    `pending-approvals-postgresql.sql`, matching `nessy-substrate-jdbc`'s
    own `nessy-postgresql.sql`. The bean condition stays dialect-blind on
    purpose: there is no honest way to ask a `DataSource` its dialect
    without opening a connection at condition-evaluation time. An
    application on another database declares its own
    `PendingApprovals`-shaped bean, or none at all, rather than getting a
    silent syntax error at the first park.

The starter ships the DDL as a classpath resource and **never runs it** —
schema is a deployment decision, and a library that silently runs DDL is
one that surprises somebody at 3am. Apply it the way you apply
`nessy-substrate-jdbc`'s and `continuum-jdbc`'s own DDL: Flyway, Liquibase,
or by hand.

```sql
CREATE TABLE IF NOT EXISTS nessy_pending_approvals (
    computation_id TEXT PRIMARY KEY,
    agent_type     TEXT,
    agent_id       TEXT,
    call_id        TEXT,
    action         TEXT,
    request_json   JSONB,
    parked_at      TIMESTAMPTZ,
    answer         TEXT,
    reference      TEXT,
    note           TEXT,
    answered_at    TIMESTAMPTZ
);
```

Every column but `computation_id` is nullable on purpose — that is the
out-of-order case above, not sloppiness. The full file, with its two
partial indexes (one for `pending()`, one for `recent()`), lives at
`org/jwcarman/nessy/spring/boot/pending-approvals-postgresql.sql` inside
the starter jar:

```
unzip -p ~/.m2/repository/org/jwcarman/nessy/nessy-spring-boot-starter/*/nessy-spring-boot-starter-*.jar \
  org/jwcarman/nessy/spring/boot/pending-approvals-postgresql.sql | psql "$DB_URL"
```

Applying it beside `nessy-substrate-jdbc`'s `nessy-postgresql.sql` and
`continuum-jdbc`'s `continuum-postgresql.sql` is exactly the three-schema
step `nessy-examples/watchman`'s README walks through end to end.

## Shutdown

The harness bean is `@Bean(destroyMethod = "shutdown")`: Spring calls
`Harness#shutdown()` on context close, quiescing the delivery worker's
pumps while the harness is still reachable. The harness is deliberately
not `AutoCloseable` (see [The Harness](harness.md#kept-not-closed)), so
this is named explicitly rather than inferred.

The model gateway closes the same way, on the same trigger:
`ModelDiscovery.Selection`'s bean is `@Bean(destroyMethod = "close")`, so
the SDK client, its connection pool, and its threads are released when the
context closes — not left running until the JVM exits, which is what
`ModelDiscovery.fromEnv()` alone would do for the life of the process.
Neither destroy method fires when the application supplied its own `Model`
or `Harness` bean: only what the starter built is the starter's to close.

## Where next

- [The Harness](harness.md) — the builder surface the starter drives:
  `.model`, `.substrate`, `.continuum`, `.grants`, `.harnessObserver`,
  `.observationRegistry`, and what each one defaults to.
- [Observability](observability.md) — the spans and counters that reach
  Boot's `ObservationRegistry` once it is wired, and how to read them on a
  dashboard.
- [Durable Computation](../concepts/durable-computation.md) — why the
  substrate and the Continuum must both be durable or both be volatile,
  and what happens when they aren't.

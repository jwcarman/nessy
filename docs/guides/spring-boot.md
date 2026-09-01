# Spring Boot

`nessy-spring-boot-starter` wires a working agent from properties and beans.

```xml
<dependency>
  <groupId>org.jwcarman.nessy</groupId>
  <artifactId>nessy-spring-boot-starter</artifactId>
</dependency>
```

```yaml
nessy:
  type: assistant
  model: claude-opus-4
  system-prompt: You are a terse assistant.
```

Add a `ModelProvider` bean (or `nessy-model-discovery` and the matching
environment), and you have a `Harness<String>`.

## Properties

| Property | Default |
|---|---|
| `nessy.type` | `agent` — the agent type, and the persistence id prefix |
| `nessy.model` | *required* — resolved against your `ModelProvider` |
| `nessy.system-prompt` | empty |
| `nessy.system-prompt-file` | a `Resource`; **setting both is an error**, because silently preferring one makes a misconfigured prompt very hard to notice |
| `nessy.provider` | `unknown` — the vendor name observability reports |
| `nessy.max-tokens` | 4096 |
| `nessy.capabilities` | none |
| `nessy.reply-keys` | ephemeral — see below |

## Every bean backs off

Each bean is `@ConditionalOnMissingBean`. The starter is a convenience over
the engine, never a replacement for it: declare your own `@Bean` and the
starter steps aside, and then the choice is written down where a reader can
find it.

| Bean | Default |
|---|---|
| `DataSource` | an in-memory H2, **loudly announced** |
| `Clock` | `Clock.systemUTC()` |
| `ReplyTokens` | ephemeral keys |
| `ActorSystem` | a cluster-of-one, terminated on shutdown |
| `Traces` | wired to your `ObservationRegistry` if there is one |
| `PekkoHarnessFactory` | built from the above |
| `Replies` | the door outside answers park calls through |
| `Harness<String>` | built from `nessy.*` and every `Tool` bean |
| `PendingApprovalsRepository` | when a `JdbcTemplate` is present |

Tools come from the application context: every `Tool` bean is granted.
Gating one means declaring the harness yourself, because an approver is a
decision about *your* policy and the starter cannot know it — that is what
`nessy-examples/chat-web` does.

## The database

With no `DataSource` bean, the starter builds an in-memory H2, initializes
it, and warns that transcripts, notes, plans and parked approvals will not
survive a restart. Configure `spring.datasource.*` and Boot's own
auto-configuration supplies the real one; the starter backs off to it.

**A `DataSource` you supplied is never touched uninvited.** Apply Nessy's
DDL yourself, once:

```java
@Bean
InitializingBean nessySchema(DataSource dataSource) {
    return () -> Schemas.initialize(dataSource);
}
```

Boot looks for `schema.sql`; Nessy's file is `nessy-schema.sql`, so the name
*is* the opt-in. See [Storage](../concepts/storage.md).

## Reply tokens outlive the process, if you let them

By default reply keys are ephemeral: tokens die with the JVM. That is right
for a test and wrong for anything that parks work for days.

```yaml
nessy:
  reply-keys:
    - ${NESSY_REPLY_KEY_CURRENT}
    - ${NESSY_REPLY_KEY_PREVIOUS}
```

Tokens are minted with the first key and read by trying every one, so a
rotation does not invalidate a token already sitting in somebody's inbox.

## The approvals projection

When a `JdbcTemplate` is present, the starter ships a pending-approvals
projection: `PendingApprovalsListener` records what parked, and
`PendingApprovalsRepository` reads it back.

```java
@Bean
Approver desk(PendingApprovalsListener listener, Clock clock) {
    return (request, context) -> {
        listener.record(request, context.replyToken());
        return Awaited.deferred(clock.instant().plus(Duration.ofDays(3)));
    };
}
```

Answering is `Replies`:

```java
replies.approve(token, ApprovalResult.approved());
```

Its table is `nessy_pending_approvals`, shipped in the starter's own
`nessy-schema.sql` and created by the same `Schemas.initialize` call as
everything else.

**Subscribe before the first observation.** A projection only hears what it
is present for.

## A worked example

`nessy-examples/watchman` is the full shape: its own PostgreSQL, a schema it
applies itself, an agent doing rounds on a timer, tools it is not allowed to
run without a person, and a page to answer on. `soak.sh` runs it and then
asserts what happened — including that something actually parked, so a run
that did nothing fails rather than reading as success.

## See also

- [The Harness](harness.md) — the configuration the starter is wrapping
- [Authorization](../concepts/authorization.md) — approvers and reply tokens
- [Storage](../concepts/storage.md) — applying the schema
- [Observability](observability.md) — traces and metrics

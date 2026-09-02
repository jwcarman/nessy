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

A `ReplyToken` is the address a parked call is answered at, and it is
**encrypted**: the coordinates inside it are sealed with AES-GCM so the
holder cannot read them, and cannot forge one either.

`nessy.reply-keys` are those encryption keys, base64-encoded — secrets,
handled like any other. AES accepts 16, 24 or 32 bytes; **use 32**.

### Minting one

Any of these produces a key in the form the property expects:

```bash
openssl rand -base64 32
head -c 32 /dev/urandom | base64        # no openssl
```

```java
KeyGenerator generator = KeyGenerator.getInstance("AES");
generator.init(256);
String key = Base64.getEncoder().encodeToString(generator.generateKey().getEncoded());
```

The output is 44 characters ending in `=` — that is what 32 bytes of base64
looks like, and a quick way to eyeball a key that got truncated somewhere.

A key of some other length is refused **at startup**, naming which one — a
cipher only sees its key when something asks it to encrypt, so without that
check a mistyped key would surface the first time a call parked on a person,
which is the worst moment to find out and the furthest from the line that
caused it.

```yaml
nessy:
  reply-keys:
    - ${NESSY_REPLY_KEY_CURRENT}        # base64 of 32 random bytes
    - ${NESSY_REPLY_KEY_PREVIOUS}       # the one before it, kept to read old tokens
```

**Configure none and they are ephemeral** — a fresh key at startup, so every
token minted before a restart becomes unreadable and every approval parked on
a person silently becomes unanswerable. Right for a test, wrong for anything
else, and the starter says so loudly at startup.

**Rotating.** Tokens are minted with the **first** key and read by trying
**every** one, so putting a new key at the front and keeping the old one
below it means a token already sitting in somebody's inbox still works. Drop
a key only once every token minted with it has expired.

A token that reads cleanly says only that this engine issued it — never that
the call is still waiting.

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

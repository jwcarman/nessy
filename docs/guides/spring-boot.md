# Spring Boot

`nessy-spring-boot-starter` wires a working agent from properties and beans.

```xml
<dependency>
  <groupId>org.jwcarman.nessy</groupId>
  <artifactId>nessy-spring-boot-starter</artifactId>
</dependency>
<dependency>
  <groupId>org.jwcarman.nessy</groupId>
  <artifactId>nessy-inference-anthropic</artifactId>
</dependency>
```

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/nessy
    username: nessy
    password: secret
nessy:
  type: assistant
  model: claude-sonnet-5
  system-prompt: You are a terse assistant.
```

Set `ANTHROPIC_API_KEY` and you have a `Harness<String>` bean, every `Tool`
bean granted to it.

## Two artifacts, and which one you want

Almost always the starter. It carries **no code**, the shape every Spring
Boot starter has, and brings the auto-configuration plus what a running
application needs.

| Artifact | What it is |
|---|---|
| `nessy-spring-boot-starter` | the one dependency to add; no classes of its own |
| `nessy-spring-boot-autoconfigure` | the beans and the `nessy.*` properties; every auto-configuration Nessy ships lives here |

Every optional module's auto-configuration is in the autoconfigure jar too,
gated on that module's classes being present: add `nessy-lease` and a
`Leases` bean appears, add an engine and the prompt becomes a template.

## Properties

| Property | Default |
|---|---|
| `nessy.type` | `agent`: the agent type, and the key every row is stored under |
| `nessy.model` | *required*: sent to your `InferenceProvider` with every call |
| `nessy.max-tokens` | 4096 |
| `nessy.system-prompt` | the standing instruction, inline |
| `nessy.system-prompt-file` | a `Resource`; **setting both is an error**, because silently preferring one makes a misconfigured prompt very hard to notice |
| `nessy.provider` | `unknown`: the vendor name observability reports |
| `nessy.initialize-schema` | `true`: run every module's `nessy-schema.sql` at startup |
| `nessy.reply-token-encryption-keys` | ephemeral; see below |
| `nessy.prompt.engine` | `spring`, or `mustache` |
| `nessy.narration.odyssey.inactivity-ttl`, `entry-ttl`, `retention-ttl` | a day, a day, an hour |
| `anthropic.api-key`, `openai.api-key`, `openai.base-url`, `xai.api-key`, `gemini.api-key`, `google.api-key` | pick a provider; see [Providers](providers.md#boot-auto-configuration) |

## Every bean backs off

Each bean is `@ConditionalOnMissingBean`. The starter is a convenience over
the engine, never a replacement for it: declare your own `@Bean` and the
starter steps aside, and the choice is written down where a reader can find
it.

| Bean | What it is |
|---|---|
| `NessySchema` | the tables, created when `nessy.initialize-schema` says so; everything that needs tables depends on it |
| `ReplyTokens` | from the configured keys, or ephemeral, loudly |
| `DefaultHarnessFactory` | the engine, from the `DataSource`, the provider, the keys, your `ObservationRegistry` and `StorageCodec` if present; closed on shutdown |
| `TurnHistories`, `InferenceContexts` | the story and the recorded model calls, read-only |
| `Replies` | the door outside answers park calls through |
| `Harness<String>` | built from `nessy.*` and every `Tool` bean, each wrapped for observation |
| `InferenceProvider` | from an adapter on the classpath and its key |
| `PromptTemplateFactory`, `SystemPromptSource` | when a prompt engine is present; see [Prompts](prompts.md) |
| `Leases` | when `nessy-lease` is present |
| `AgentStreams`, `OdysseyNarrator` | when `nessy-narration-odyssey` and an `Odyssey` bean are present |

Every `AgentEventListener` bean is attached to the engine once the context
has fully started, so a listener may depend on the factory without a cycle.

The auto-configuration classes, for an application that excludes one:
`NessyAutoConfiguration` (the engine and the free harness),
`OpenAiAutoConfiguration`, `AnthropicAutoConfiguration` and
`GeminiAutoConfiguration` under `inference`, `PromptEngineAutoConfiguration`
and `PromptAutoConfiguration` under `prompt`, `LeaseAutoConfiguration`,
`OdysseyNarrationAutoConfiguration` and `SubstrateCodecAutoConfiguration`
under `narration`. The console excludes the engine's and the lease's to build
its own from the same beans.

Tools come from the application context: every `Tool` bean is granted,
ungated. Gating one, or adding summaries, ambient sources or a listener to
the harness, means declaring the `Harness<String>` yourself with the
factory, because an approver is a decision about *your* policy and the
starter cannot know it. That is what `nessy-examples/chat-web` does.

## The database

Boot's own auto-configuration supplies the `DataSource` from
`spring.datasource.*`; there is no in-memory fallback. With
`nessy.initialize-schema` on, the starter runs every module's
`nessy-schema.sql` at startup, safe to repeat. Turn it off when migrations
are yours, and apply the same files through whatever runs them. Boot looks
for `schema.sql`; Nessy's file is `nessy-schema.sql`, so the name *is* the
opt-in. See [Storage](../concepts/storage.md).

## Reply tokens outlive the process, if you let them

A `ReplyToken` is the address a parked call is answered at, and it is
**encrypted**: the coordinates inside it are sealed with AES-GCM so the
holder cannot read them, and cannot forge one either.

`nessy.reply-token-encryption-keys` are those keys, base64-encoded; secrets,
handled like any other. AES accepts 16, 24 or 32 bytes; **use 32**.

```bash
openssl rand -base64 32
```

The output is 44 characters ending in `=`. A key of some other length is
refused **at startup**, naming which one, rather than the first time a call
parks on a person.

```yaml
nessy:
  reply-token-encryption-keys:
    - ${NESSY_REPLY_KEY_CURRENT}        # base64 of 32 random bytes
    - ${NESSY_REPLY_KEY_PREVIOUS}       # the one before it, kept to read old tokens
```

**Configure none and they are ephemeral**: a fresh key at startup, so every
token minted before a restart becomes unreadable and every approval parked
on a person silently becomes unanswerable. Right for a test, wrong for
anything else, and the starter says so loudly at startup.

**Rotating.** Tokens are minted with the **first** key and read by trying
**every** one, so putting a new key at the front and keeping the old one
below it means a token already sitting in somebody's inbox still works.

## Leases

`nessy-lease` is a small module with one interface, for background work
that must run once even when several processes hear the same event:

```java
public interface Leases {
  boolean tryRun(String kind, String key, Duration ttl, Runnable work);
}
```

`JdbcLeases` takes the lease with one `INSERT ... ON CONFLICT` against
`nessy_lease`, runs the work if it won, and releases it. A holder that dies
mid-work loses the lease when the TTL passes. The head and episode
summarisers run under one; so will anything else that reacts to events from
more than one process. The starter contributes a `Leases` bean when the
module is on the classpath. Why a lease rather than an effect, and what it
does and does not promise, is on the [Leases](../concepts/leases.md) page.

## Encryption at rest

Declare a `StorageCodec` bean and every row the engine writes passes
through it after Jackson, and so does every entry the Odyssey narration
writes to its journal:

```java
@Bean
StorageCodec nessyStorage(KeySource keys) {
    return StorageCodec.of(new AesCodec(keys));
}
```

No codec ships with the starter: the seam is there, and the codec, its keys
and their rotation are the application's.

## A worked example

`nessy-examples/chat-web` is the full shape: the starter, a harness declared
by hand with a notebook, a plan and the head summariser, an approval desk
that pushes cards to the browser over Odyssey streams, and `Last-Event-ID`
resume when a tab reconnects. `nessy-examples/watchman` is the soak: an
agent doing rounds on a timer against a real host, proposing remediations
it is not allowed to run itself.

## See also

- [The Harness](harness.md), the configuration the starter is wrapping
- [Prompts](prompts.md), the prompt as a template
- [Events](events.md), listeners and streams
- [Observability](observability.md), traces and metrics

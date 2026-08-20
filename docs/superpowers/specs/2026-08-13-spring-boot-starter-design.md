# The Spring Boot Starter — design

**Date:** 2026-08-13
**Status:** IMPLEMENTED (see plan 2026-08-13-spring-boot-starter)
**Builds on:** the DX generation (2026-08-13, spec'd — `JdbcPersistence`,
`snapshot`, the narration contract all get wired here). Consumed by the
chat-web rewrite and the patient-researcher (2026-08-13, spec'd). Sequence:
DX generation → **this** → patient-researcher.

---

## 1. Purpose

Make the durable-agent stack arrive by classpath: a Boot app that adds nessy
dependencies and a datasource gets provider, store, memory, harness, and
observability wired with zero configuration — and declares only what was
always its own business, the agent. Success criterion: chat-web's
`NessyConfig` shrinks to the agent bean (plus its test-profile split), its
`SseEvents`/bridge statics are deleted in favor of the starter's, and the
README's "planned starter" paragraph becomes present tense.

The design bet was placed two generations ago: the two-builder razor gives
`Harness` everything once-per-application and `AgentBuilder` everything
identity-shaped. Autoconfiguration is the razor's Spring costume — substrate
arrives, identity stays yours.

## 2. Module

Two published artifacts, the mocapi shape (which is Spring Boot's own:
`spring-boot-autoconfigure` + dependency-only starter poms, the pattern
Boot's documentation prescribes for third parties):

- **`nessy-autoconfigure`** — all `@AutoConfiguration` classes and
  `@ConfigurationProperties` records, one `.imports` file; every feature
  dependency (`nessy-model-anthropic`, `nessy-model-openai`,
  `nessy-store-jdbc`, `spring-webmvc`) **optional** — each configuration
  gates itself with `@ConditionalOnClass` and activates only when the app
  chose that jar.
- **`nessy-spring-boot-starter`** — src-less dependency aggregator:
  `nessy-autoconfigure` + `nessy-core`. Provider and store jars stay the
  application's explicit choices (classpath is intent; a starter that picked
  Anthropic for you would be configuration in a trench coat). Flavor
  starters (à la mocapi's per-transport pair) wait until nessy has flavors.

- Boot BOM imported in-module only; `nessy-parent` still never learns Spring.
- Compatibility baseline: Spring Boot 4.x (documented in the README and the
  pom comment; the Boot 4.1 property/module lessons from chat-web — jackson2,
  resttestclient — live here now as managed knowledge, not example folklore).
- Dependencies: `nessy-core` (compile); everything else **optional/provided**
  — `nessy-model-anthropic`, `nessy-model-openai`, `nessy-store-jdbc`,
  `spring-webmvc`, micrometer bits — the whole point is conditional presence.
- Registered via `META-INF/spring/…AutoConfiguration.imports`.

## 3. The autoconfiguration graph

Every bean is `@ConditionalOnMissingBean` — a hand-declared bean always wins,
silently. Classpath expresses intent; properties are the escape hatch.

**Providers** (`@ConditionalOnClass` per module):

- `AnthropicModelProvider` present → `ModelProvider` bean from
  `nessy.anthropic.*` properties (`api-key`, `base-url`), falling back to the
  SDK's own env support (`fromEnv`) when unset — the chat-cli behavior,
  autoconfigured.
- `OpenAiModelProvider` present → same shape under `nessy.openai.*`.
- Both present → `nessy.provider=anthropic|openai` chooses; neither chosen →
  fail fast at startup with a message naming the property. No silent
  preference order.

**Persistence** (`@ConditionalOnClass(JdbcPersistence)` +
`@ConditionalOnBean(DataSource)`):

- `JdbcPersistence.create(dataSource, mapper)` → `ConversationStore` and
  `AgentMemory` beans. Adding `nessy-store-jdbc` next to a datasource flips the
  app from JVM-lifetime memory to durable — the right default the moment the
  classpath says "I have a database."
- Escape hatches: your own `ConversationStore`/`AgentMemory` beans, or
  `nessy.jdbc.enabled=false` for "the jar is here for other reasons."
- Schema bootstrap is the factories' own idempotent `create` — no migration
  choreography; `nessy.jdbc.bootstrap-schema=false` for DBAs who run the DDL
  themselves.

**Harness:**

- `ModelProvider` + `ConversationStore` (`ObjectProvider` — absent means
  in-memory default) + Boot's `ObservationRegistry` (`ObjectProvider` — absent
  means `NOOP`) + Boot's `ObjectMapper` → `Harness`. The chat-web o11y
  dogfood point — nessy spans joining Boot's HTTP/JDBC spans — becomes zero
  user lines.

**Never autoconfigured:** agents. The application declares
`@Bean Agent<…>` exactly as chat-web does — model, prompt, tools, grants,
approver are identity, and identity is not configuration. The starter's
documentation says this in its first paragraph, as a feature.

## 4. The web bridge (`@ConditionalOnClass(SseEmitter)`)

Same artifact, activates only when webmvc is present — console apps never see
it. Three pieces, all extracted from what chat-web hand-rolled and the final
review flagged:

- **`TurnEventSse`** — the `TurnEvent` → named-SSE-event mapping (chat-web's
  `SseEvents`, generalized): stable event names (`delta`, `thinking`,
  `tool-requested`, `tool-progress`, `tool-decided`, `tool-completed`,
  `tool-parked`, `done`), payload shapes documented as the wire contract the
  DX generation settled. Exhaustive switch, no `default`, per etiquette —
  the starter is in-reactor extender code.
- **Broken-pipe-tolerant send** — a closed tab completes the stream, never
  fails the turn (narration never alters the record).
- **`TurnRunner`** — the async turn executor: captures a Micrometer
  `ContextSnapshot` on the request thread, runs the turn on a virtual thread
  with the scope restored, hands the terminal `RunOutcome` to a completion
  callback. The trace-propagation bug chat-web shipped first-try becomes
  unmakeable; the `approval-needed`/`done` tail that remains app-shaped stays
  in app code.

## 5. Properties

One namespace, flat and small — the whole surface:

| Property | Default | Meaning |
|---|---|---|
| `nessy.provider` | (none) | required only when both provider jars are present |
| `nessy.anthropic.api-key` / `base-url` | SDK env | provider credentials |
| `nessy.openai.api-key` / `base-url` | SDK env | provider credentials |
| `nessy.default-model` | (none) | harness-level default model, optional |
| `nessy.jdbc.enabled` | `true` | JDBC wiring master switch |
| `nessy.jdbc.bootstrap-schema` | `true` | run the idempotent DDL at startup |

`@ConfigurationProperties` records, metadata generated
(`spring-boot-configuration-processor`) so IDEs complete them.

## 6. Testing

`ApplicationContextRunner` throughout — offline, no containers, fast:

- classpath permutations (provider present/absent/both, store present/absent,
  webmvc present/absent) assert exactly which beans exist;
- user-bean-wins for every `@ConditionalOnMissingBean`;
- both escape-hatch properties;
- fail-fast cases (two providers, no choice) assert the message names the
  property.

The real-database and real-HTTP proof lives where it already is: the chat-web
smoke and the patient-researcher test, both rewritten to consume the starter
in their own plans. Offline reactor `verify` stays green — the starter's
tests need neither Docker nor a key.

## 7. Ripples

- **chat-web rewrite, round two** (this module's acceptance test): drops its
  provider/store/memory/harness beans and its `SseEvents`/`sendEvent`/
  snapshot-runner statics; keeps the agent bean, the controllers' app-shaped
  tails, and the UI. ~~The `@Profile("!test")` split moves to the property
  level (`nessy.provider` unset + a test-profile `Harness` bean keeps
  working — the smoke's bean override already wins by `OnMissingBean`).~~
  **Amendment (2026-08-13, final review):** the split dissolved entirely
  rather than moving — chat-web round two deleted `@Profile("!test")`
  outright; no property-level substitute was needed, since the smoke test's
  `@TestConfiguration` `Harness` bean already wins over the starter's own via
  the same `@ConditionalOnMissingBean` every autoconfigured bean here honors.
- **README**: the observability section's "that starter does not exist yet"
  and Status §'s not-yet-built list both flip; the durable section gains the
  two-line Boot recipe.
- **BOM** gains the artifact; CHANGELOG entry under Unreleased.
- Publication makes Boot an optional dependency of the published family —
  the compatibility baseline (§2) is now a stated support commitment.

## 8. Deliberately not built

Agent autoconfiguration in any form (identity is not configuration), GraalVM
native/AOT metadata, actuator endpoints or health indicators for
conversations (an ops surface is a future design, not a starter freebie),
~~a `-autoconfigure`/`-starter` artifact split (one module until proven
needed),~~ WebFlux variants of the bridge (servlet + virtual threads is the
house position), auto-registration of `TurnObserver`s or listeners (observers
are per-entry by invariant — DX §4).

**Amendment (2026-08-13, final review):** the `-autoconfigure`/`-starter`
split was struck from this list — §2 ships exactly that split
(`nessy-autoconfigure` + `nessy-spring-boot-starter`) as the mocapi shape
this design chose from the start; it was never actually deliberately not
built, and listing it here contradicted §2 outright.

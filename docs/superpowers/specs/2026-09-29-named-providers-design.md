# Named providers: an agent type says who answers

**Status: DESIGN, NOTHING BUILT. The shape was settled in conversation with James on 2026-09-29
and this record writes it down. Every new public concept is listed in §11 and awaits sign-off on
this record; the questions in §14 are asked, not answered.**

Date: 2026-09-29. Sits on the two doors of `2026-09-25-one-core-two-doors-design.md` and the
customizer shape of `2026-09-25-spring-boot-autoconfiguration-design.md`. It **supersedes** that
spec's §4b ("one class, one bean, one decision") and its ruling "the provider is chosen, or the
application is refused": there is no longer a single winner to choose, so there is nothing to
arbitrate. The 2026-08-31 ruling that the starter takes a bean and never discovers one stands.
Every path, name and count below was checked against `src/main/java` and `src/test/java` on
`main` at the time of writing; where the brief for this record and the code disagreed, the
disagreement is in §14 rather than smoothed over.

---

## 1. The problem

A harness factory holds **one** `InferenceProvider`, and the model is chosen **per agent type**.
The two facts are decided in different places by different people and nothing checks that they
agree.

Verified in source:

- `DirectHarnessFactoryConfig` (`nessy-engine`, `engine.harness.direct`) has one
  `provider(InferenceProvider)` setter and one `requiredProvider()`. `DefaultDirectHarnessFactory`
  keeps it in a field and wraps it into every harness's `DefaultInferenceService`.
- `QueuedHarnessFactoryConfig` has `inference(InferenceProvider, InferenceOptions)` -- provider and
  default terms in one call -- and `DefaultQueuedHarnessFactory` folds them into
  `DefaultQueuedHarnessConfig.Defaults`, from which every agent type's `Inference` takes its
  provider. `Inference` has a `provider` field and no way to set it: `InferenceConfig` (`nessy-api`)
  offers `model(String)`, `maxTokens`, `context`, `timeout` and `retryPolicy`, and nothing about who
  is asked.
- So an agent type can say `in.model("claude-sonnet-4-5")` and be sent to OpenAI. The symptom is a
  404 from a vendor nobody meant to call, naming a model that vendor has never heard of.

Under Boot the one provider is chosen by the alphabet. Each vendor bean is
`@ConditionalOnProperty(<vendor>.api-key)` and `@ConditionalOnMissingBean(InferenceProvider.class)`;
none of `OpenAiAutoConfiguration`, `AnthropicAutoConfiguration` and `GeminiAutoConfiguration`
declares an order, so Boot sorts them by class name and the first to run wins. A throwaway
`ApplicationContextRunner` probe over the three classes measured, on 2026-09-29:

| keys set | provider built |
|---|---|
| openai, anthropic, gemini | anthropic |
| openai, gemini | gemini |
| openai, xai | openai (declaration order inside `OpenAiAutoConfiguration`) |
| gemini, xai | gemini |

`InferenceReport` (`352c73c44`) now warns at startup when more than one key is set, and then starts
with the accidental winner anyway. Its `KEYS` list is `openai.api-key`, `anthropic.api-key`,
`gemini.api-key`, `xai.api-key`; it omits `google.api-key`, which `GeminiAutoConfiguration` also
accepts, so two Gemini spellings plus one other key is reported as one key.

xAI is a special case inside `OpenAiAutoConfiguration`: a second bean, `OpenAiInferenceProvider`
at `XAI_BASE_URL = "https://api.x.ai/v1"`, stamped `XAI_PROVIDER_NAME = "x_ai"` through
`OpenAiProviderConfig.provider(String)`.

One sentence on history, because the memory record keeps it alive: the probe also showed that
`NESSY_PROVIDER` in a system-environment property source **does** satisfy
`@ConditionalOnProperty(name = "nessy.provider")`, so the earlier uncommitted, reverted attempt at
that property failed for some other reason -- a stale example jar is the suspect -- and its failure
is not evidence against a property.

There is no `ServiceLoader` discovery anywhere in the tree (verified by search). A non-Spring
application hands the factory a provider instance in code, and that stays true.

## 2. The shape in one paragraph

A factory holds providers **by name**. An agent type names the provider it wants and the model it
wants, and both are required -- the factory carries defaults for either, and an agent type that
says nothing gets them. The name is a value type, `ProviderId`, resolved once when the harness is
built; an unknown or missing one fails there, naming the agent type and listing what is
registered. Under Boot a catalogue of presets turns a vendor's key into a registered provider, an
application's own `InferenceProvider` beans join the registry under their bean names, and the
startup report lists every provider that lit and which agent types use which. Nothing in the engine
loop changes: by the time an effect is dispatched, the provider was decided long ago.

## 3. Vocabulary

Four words, each meaning one thing. They were chosen against the code as it is, and the rename in
§4 exists to make the code agree with them.

| word | meaning | where it lives |
|---|---|---|
| **provider** | an `InferenceProvider` instance, registered in an application under a `ProviderId` -- `xai`, `openai-batch`, `local` | `InferenceProvider` (`nessy-inference-spi`) is unchanged in meaning |
| **preset** | a catalogue entry shipped with the Boot starter: an id, a wire, a base URL, a vendor, and its *ingredient* (a key, or an explicit `enabled`). It becomes a provider when the ingredient is supplied | `nessy-spring-boot-autoconfigure` only |
| **wire** | the protocol a provider speaks. Prose and a property value; **no SPI type** | a Boot-internal enum (§5c) and a property value |
| **vendor** | the OpenTelemetry `gen_ai.provider.name` value: `openai`, `x_ai`, `gcp.gemini`, `anthropic`, `aws.bedrock` | `InferenceProvider.vendor()` after §4; already the name of the field on `Block.Provider(String vendor, String payload)` |

**Wire values are named for the protocol, not the vendor.** `chat-completions` is what
`OpenAiInferenceProvider` speaks, `messages` what `AnthropicInferenceProvider` speaks,
`generate-content` what `GeminiInferenceProvider` speaks. The relationship is many-to-many in both
directions and a vendor-named value hides it: one wire serves many vendors (openai, xai, groq,
ollama and lmstudio all speak chat-completions, which is why xAI is an `OpenAiInferenceProvider`
today), and one vendor has several wires (OpenAI's Chat Completions and its Responses API; Google's
native endpoint and its OpenAI-compatible one). A value called `openai` would be wrong the first
time either of those arrives.

**"Profile" was rejected** for what §3 calls a preset: it collides with Spring profiles, which are
in the same `application.yaml` and mean something else.

## 4. The rename: `providerName()` becomes `vendor()`

This lands **first, as its own commit**, mechanical and reviewable alone. The reason is the trap
that §5 would otherwise set: once `ProviderId` exists, "provider name" -- which is the vendor,
shared by two `openai`-wire instances that report the same `gen_ai.provider.name` -- and "provider
id" -- which is ours, and distinct per instance -- would be two things one word apart. `Block` in
`nessy-api` already calls this value `vendor`; the SPI catches up.

OpenTelemetry output stays **byte-identical**: `ObservedInferenceProvider` maps `delegate.vendor()`
to the `gen_ai.provider.name` tag exactly as it maps `providerName()` today, and the values are
untouched.

### 4a. What moves, verified by search

`InferenceProvider.providerName()` and every override and caller (main):

| file | what |
|---|---|
| `nessy-inference/spi/src/main/java/org/jwcarman/nessy/inference/InferenceProvider.java` | the default method, line 63; its javadoc |
| `nessy-inference/openai/src/main/java/org/jwcarman/nessy/inference/openai/OpenAiInferenceProvider.java` | override, line 369; `PROVIDER_NAME` constant, line 80; the `provider` field and constructor parameter; the "vendor gateways" prose at line 73 |
| `nessy-inference/openai/src/main/java/org/jwcarman/nessy/inference/openai/OpenAiProviderConfig.java` | `provider(String)` becomes `vendor(String)`, line 142; the `provider` field, line 39 |
| `nessy-inference/anthropic/src/main/java/org/jwcarman/nessy/inference/anthropic/AnthropicInferenceProvider.java` | override, line 129; `PROVIDER_NAME`, line 75 (also used at line 357 and by `AnthropicRequests` line 485) |
| `nessy-inference/gemini/src/main/java/org/jwcarman/nessy/inference/gemini/GeminiInferenceProvider.java` | override, line 138; `PROVIDER_NAME`, line 78 (also line 372 and `GeminiRequests` line 312) |
| `nessy-inference/bedrock/src/main/java/org/jwcarman/nessy/inference/bedrock/BedrockInferenceProvider.java` | override, line 134; `PROVIDER_NAME`, line 93 (also line 420 and `BedrockRequests` line 305) |
| `nessy-engine/src/main/java/org/jwcarman/nessy/engine/observability/ObservedInferenceProvider.java` | override, lines 83-84; the tag at line 101 |
| `nessy-engine/src/main/java/org/jwcarman/nessy/engine/harness/direct/DefaultDirectHarnessFactory.java` | `providerName()` delegating to the provider, lines 186-187 (see §14 (5)) |
| `nessy-api/src/main/java/org/jwcarman/nessy/api/DirectHarnessFactory.java` | `String providerName()` on the interface, line 106 (see §14 (5)) |
| `nessy-console/src/main/java/org/jwcarman/nessy/console/Repl.java` | `factory.providerName()`, line 97 |
| `nessy-spring-boot/autoconfigure/src/main/java/org/jwcarman/nessy/spring/boot/InferenceReport.java` | lines 77 and 94 |
| `nessy-spring-boot/autoconfigure/src/main/java/org/jwcarman/nessy/spring/boot/inference/OpenAiAutoConfiguration.java` | `XAI_PROVIDER_NAME` becomes `XAI_VENDOR`, lines 63 and 103; the `.provider(...)` call at line 103 |

Tests and docs:

| file | what |
|---|---|
| `nessy-inference/spi/src/test/java/org/jwcarman/nessy/inference/InferenceProviderTest.java` | five references (lines 47, 55, 63, 72, 77) |
| `nessy-inference/openai/src/test/java/org/jwcarman/nessy/inference/openai/OpenAiProviderNameTest.java` | renamed `OpenAiVendorTest`; `PROVIDER_NAME` at 41, `.provider("x_ai")` at 53, `providerName()` at 55 and 64 |
| `nessy-inference/anthropic/src/test/java/org/jwcarman/nessy/inference/anthropic/AnthropicProviderNameTest.java` | the brief named only the OpenAI test; this one exists too and is renamed `AnthropicVendorTest` for the same reason (`PROVIDER_NAME` at line 35) |
| `nessy-inference/gemini/src/test/java/org/jwcarman/nessy/inference/gemini/GeminiRequestsTest.java` | `PROVIDER_NAME` at lines 185 and 318 |
| `nessy-inference/bedrock/src/test/java/org/jwcarman/nessy/inference/bedrock/BedrockRequestsTest.java` | `PROVIDER_NAME` at lines 223, 227, 307, 309 |
| `nessy-spring-boot/autoconfigure/src/test/java/org/jwcarman/nessy/spring/boot/inference/OpenAiAutoConfigurationTest.java` | lines 75 and 101 |
| `docs/guides/providers.md` | `.provider("x_ai")` at line 239; `providerName()` at line 305 |
| `docs/guides/observability.md` | `InferenceProvider.providerName()` at line 38 |

Counts: eleven main-source files, six test files (two of them renamed), two guide pages.

### 4b. Prose that teaches the old design

"Gateway" was the previous name for an adapter class, and it survives in javadoc and test names.
Only the adapter sense moves to "provider"; "gateway" meaning an HTTP intermediary (OpenRouter, a
proxy, a 502 "bad gateway") is correct English and stays. The adapter-sense occurrences, by
search:

- `OpenAiInferenceProvider.java` line 73 ("Unlike the other vendor gateways")
- `OpenAiProviderConfig.java` -- none; line 84 is the HTTP sense and stays
- `OpenAiProviderNameTest.java` lines 27, 35, 40, 47, 50 (moves with the file's rename)
- `OpenAiCloseOwnershipTest.java` lines 27, 61, 63
- `AnthropicCloseOwnershipTest.java` line 27
- `AnthropicProviderNameTest.java` line 43 (moves with the file's rename)
- `OpenAiAutoConfiguration.java` line 59 ("The gateway class is shared with OpenAI")

Six files. "ModelProvider" was the name before `InferenceProvider`, and is mentioned in four
places, all prose: `NessyProperties.java` line 38 (javadoc on `model`), `ReplTest.java` line 34,
`AnthropicAutoConfigurationTest.java` line 60 (a `@DisplayName`), and `OpenAiProviderNameTest.java`
line 33 (which also cites a bean method `xaiModelProvider` that is now `xaiInferenceProvider`).
All four say the current name.

### 4c. Class and module names stay

Option A, chosen: only the wire **values** in properties are protocol-named. `OpenAiInferenceProvider`,
`AnthropicInferenceProvider`, `GeminiInferenceProvider`, `nessy-inference-openai` and the rest keep
their names, and each provider class's javadoc says which wire it speaks (`OpenAiInferenceProvider`:
"speaks the chat-completions wire", and so on). Reason: a class rename across four modules, every
example and every guide buys nothing today, because there is no place in the code where the wire
is a type. James's stated trigger, in his words: if a wire ever becomes a named SPI type -- when a
Responses-API adapter arrives, say -- a class and module rename is in order then.

## 5. Value types

### 5a. `ProviderId`, in `nessy-api`

A record following `AgentType`: `public record ProviderId(String value)` in
`org.jwcarman.nessy.api`, validated through `Identifiers.require(value, "provider id", 64)`. It is
our namespace and a map key, and the identifier-types work (`nessy-identifier-types`, 2026-09-02)
found silent String-versus-typed-id bugs through `Object`-typed lookups; a `Map<String, ...>` keyed
by whatever string happened to be in hand is exactly that bug again.

`AgentType` carries no `@JsonValue`/`@JsonCreator` (verified; `TurnId`, `Seq`, `CallId` and
`ToolName` do), so `ProviderId` carries none either until something serialises it -- §14 (1) is
where that would start. The 64 bound matches `AgentType`'s; `Identifiers`' own javadoc says a bound
comes from a real column, and this one has no column yet, so the bound is a convention that becomes
a fact if §14 (1) is answered yes.

### 5b. The model name stays `String`

It is the vendor's namespace, not ours: OpenRouter names a model `anthropic/claude-sonnet-4.5`,
Ollama names one `llama3:8b`, and there is nothing to validate beyond non-blank, which
`InferenceOptions` already does. It is already `String` through `InferenceOptions.modelName`,
`Usage` and the `gen_ai.request.model` tag, and a wrapper would add a type with no rule inside it.

### 5c. `Wire` is a Boot-internal enum

`enum Wire { CHAT_COMPLETIONS, MESSAGES, GENERATE_CONTENT }` in
`org.jwcarman.nessy.spring.boot.inference`, package-private, bound from the property values
`chat-completions`, `messages`, `generate-content` by Boot's relaxed binding. An enum so that a typo
in `application.yaml` is a binding error naming the allowed values rather than a provider that
silently fails to exist. Not public API and not in the SPI -- §3's rule -- so nothing outside the
starter can depend on it. Bedrock's wire (the Converse API) has no value because Bedrock is not a
preset (§7d).

## 6. The engine: a registry, resolved when the harness is built

### 6a. The factory holds named providers

Both factory configs replace their single-provider setter with a repeatable one:

```java
DirectHarnessFactoryConfig provider(ProviderId id, InferenceProvider provider);
QueuedHarnessFactoryConfig  provider(ProviderId id, InferenceProvider provider);
```

On the direct door this replaces `provider(InferenceProvider)`; on the queued door it replaces the
provider half of `inference(InferenceProvider, InferenceOptions)`. A second registration under the
same id fails at once: two things called `openai` is a configuration error, not a preference.

This is a breaking change to both configs and every caller: fifteen `.provider(model)`-style
call sites across five files in `nessy-engine/src/test`, one queued-config
`.inference(provider, options)` call there, chat-cli's `Chat.java`, and the two harness
auto-configurations. The summarisers' own `inference(provider, options)` (§6f) is not touched, so
chat-web's `ChatConfiguration` and the summariser tests are not. Acceptable at
`0.2.0-SNAPSHOT`.

### 6b. Factory-level defaults

Both configs carry a default provider id and default terms, which today only the queued door has
(as the `InferenceOptions` half of `inference(...)`; the direct door has none, and
`DefaultDirectHarnessFactory.create` throws "a model is required: inference(in -> in.model(...))"
when an agent type names none). The one call shape:

```java
config.inference(ProviderId.of("openai"), new InferenceOptions("gpt-4.1-mini", 4096));
```

-- the existing `inference(...)` word on the queued config, retargeted from an instance to an id,
and added to the direct config so the two doors have the same shape. Under Boot the starter fills
it from `nessy.provider` and `nessy.model` (and `nessy.max-tokens`, as today). Whether that method
should keep the name `inference` is §14 (6).

### 6c. An agent type says which

`InferenceConfig` gains:

```java
InferenceConfig provider(ProviderId id);
InferenceConfig provider(String id);   // ProviderId.of(id); a convenience, nothing more
```

beside the existing `model(String)`. **Both provider and model are required for every agent
type**, one rule, no tiered "optional when only one is registered" table -- James's ruling. A table
that made the provider optional when exactly one was registered would be the alphabet problem
again, one preset later: the day a second provider lights, every agent type that relied on "the
only one" changes behaviour without a line of its own changing. The factory defaults of §6b are how
an application with one provider writes it once; the rule is still that every agent type has both,
and the default is where it got them.

### 6d. Resolution happens once, at harness build

`DefaultDirectHarnessFactory.create` and `DefaultQueuedHarnessFactory.create` resolve the agent
type's `ProviderId` -- its own, or the factory default -- against the registry and fail if it
resolves to nothing:

- no provider named and no factory default: `agent type 'chat' names no provider and the factory
  has no default; registered: [openai, xai]`
- an id that is not registered: `agent type 'chat' names provider 'claude', which is not
  registered; registered: [openai, xai]`
- the model, likewise: an agent type with no model and no default fails naming the agent type, as
  the direct door already does.

The resolved `InferenceProvider` goes into that harness's `DefaultInferenceService` exactly where
the one factory-level provider goes today (`DefaultDirectHarnessFactory` line 285,
`DefaultQueuedHarnessFactory` line 315). **The engine loop is untouched**: `InferenceHandler`,
`DefaultInferenceService`, the fold and the effect dispatcher never see a `ProviderId`, and no
lookup happens per call.

### 6e. Observability: always wrap, never re-wrap

The rule of `nessy-observability-wrapping` (2026-09-17) holds. Each resolved provider is wrapped
with `ObservedInferenceProvider.wrap(provider, observations)` as it is handed to the harness, as
today; `wrap` is idempotent (`delegate instanceof ObservedInferenceProvider already ? already :
new ...`, verified), so a provider Boot already wrapped at bean creation is not wrapped twice.
Two agent types sharing one registered provider share one instance and one wrapper.

### 6f. Background consumers

`HeadSummarizer.Config` and `EpisodeSummarizer.Config` (`nessy-memory`) each take
`inference(InferenceProvider provider, InferenceOptions options)` and wrap the provider
themselves. They keep that signature: they are outside the engine and hold whatever instance the
application hands them. What changes is where the application gets the instance from -- under Boot,
every registered provider is also an `InferenceProvider` bean named by its id (§7f), so a
summariser bean asks for the one it means by name instead of for "the" `InferenceProvider`, which
no longer exists as a single bean; in code, the application holds what it registered. Whether the
factory should also expose a lookup by id is §14 (7).

## 7. Boot: presets, and what lights them

### 7a. Properties

```yaml
nessy:
  provider: openai            # the factory default ProviderId (§6b)
  model: gpt-4.1-mini         # the factory default model, as today
  providers:
    xai:
      api-key: ${XAI_API_KEY}         # or just export XAI_API_KEY (§7b)
    ollama:
      enabled: true                   # keyless: must be turned on (§7c)
    my-gateway:                       # not in the catalogue: a custom provider (§7e)
      wire: chat-completions
      base-url: https://gateway.example.com/v1
      api-key: ${GATEWAY_KEY}
      vendor: openai
```

`nessy.provider` and `nessy.model` are the factory defaults, nothing more: they do not choose
between candidates, because every lit provider is registered and nothing is chosen. The earlier
Boot spec recommended `nessy.inference.provider` over `nessy.provider` as "too broad now that
embedding providers exist"; James chose `nessy.provider` on 2026-09-29, and the difference is
recorded in §14 (8) rather than silently resolved.

### 7b. Hosted presets light from a key

A hosted preset registers itself when its ingredient is present, from either of two places:

- the vendor's conventional environment variable, through Boot's relaxed binding, exactly as today:
  `OPENAI_API_KEY` is `openai.api-key`, `XAI_API_KEY` is `xai.api-key`, `ANTHROPIC_API_KEY` is
  `anthropic.api-key`, and Gemini accepts `gemini.api-key` **or** `google.api-key` (`GEMINI_API_KEY`
  / `GOOGLE_API_KEY`, both of which `GeminiAutoConfiguration` reads today);
- or `nessy.providers.<id>.api-key`, for an application that would rather keep everything under one
  prefix.

`OPENAI_BASE_URL` (`openai.base-url`) keeps working as an override of the `openai` preset's base
URL, because chat-web's and watchman's `application.yml` both point it at LM Studio that way and the
getting-started guide teaches it.

### 7c. Keyless presets must be turned on

`ollama` (`http://localhost:11434/v1`) and `lmstudio` (`http://localhost:1234/v1`) have no key to
be present, so their ingredient is `nessy.providers.<id>.enabled=true`. James: keyless ones must
be turned on explicitly. There is **no startup probing** of a local port: a provider that exists
because something answered on `:1234` at boot is a provider that vanishes on the next boot, and a
registry that changes shape by accident is the alphabet problem in a new coat.

### 7d. What a preset carries, and what is not a preset

A preset is `(id, wire, base URL, vendor, ingredient)`. Any field can be overridden under
`nessy.providers.<id>.*` -- a different base URL for `anthropic` behind a proxy, a different vendor
tag for an `openai`-wire endpoint that is really somebody else.

**Bedrock is not a preset.** `GeminiAutoConfiguration`'s javadoc already records why: AWS
credentials are ambient on most machines, and a mechanism that let their presence register a
provider would route an application with a stray profile to Bedrock. It joins as a bean (§7f).

**The candidate catalogue** beyond the four that exist today (`openai`, `xai`, `anthropic`,
`gemini`): `groq`, `deepseek`, `mistral`, `openrouter`, `together`, `fireworks`, `cerebras`,
`ollama`, `lmstudio`. **No preset ships with an unmeasured URL or vendor value.** Each candidate is
measured against our actual request shape -- tool calls, `ToolChoice.Answer`, usage reporting --
before its row is written, because a preset that returns a 400 on the first tool call is worse
than no preset: the person reaching for it was told it works. The first cut ships only what has
been measured; §14 (4) is where the list is settled. `docs/guides/providers.md` already carries base
URLs for OpenRouter, Groq, NVIDIA NIM, Ollama and LM Studio as documentation; documentation is not
measurement.

### 7e. A custom provider

An id under `nessy.providers.*` that is not in the catalogue declares a custom provider and must
state `wire` and `base-url`; `api-key` as the wire requires; `vendor` optional, defaulting to the
wire's own (`openai` for chat-completions, which is what `OpenAiProviderConfig` defaults to today).
Missing `wire` or `base-url` fails binding, naming the id and the field.

### 7f. Application beans join the registry

An application's own `InferenceProvider` bean is registered under its **bean name**, beside the
presets, replacing today's all-or-nothing `@ConditionalOnMissingBean(InferenceProvider.class)`
back-off. That back-off exists because there was one slot; with a registry there is no slot to
protect. Bedrock, a scripted provider in a test (chat-web's `ScriptedProvider`, watchman's
`ScriptedWatchmanProvider`), and anything else the application builds in code all arrive this way.
What happens when a bean's name equals a preset id is §14 (3).

In the other direction, every lit preset is also published as an `InferenceProvider` bean named by
its id, so that a consumer outside the factory -- the summarisers of §6f -- can ask for one by name.

### 7g. The report

`InferenceReport` stops naming a winner, because there is none, and the multi-key warning goes
with it: several keys set is now several providers registered, which is the point. At startup it
lists every registered provider -- id, wire, endpoint, vendor, **never the key** -- and each harness
logs, once, when it is built, which provider and model its agent type resolved to, because that is
the moment the fact exists (`create` is called by the application, not at startup). Two lines, in
the shape of the existing ones:

```
NESSY INFERENCE: providers: openai (chat-completions, https://api.openai.com/v1, vendor openai); xai (chat-completions, https://api.x.ai/v1, vendor x_ai)
NESSY INFERENCE: agent type 'chat' -> openai / gpt-4.1-mini, up to 4096 tokens
```

"No provider is configured" remains a warning rather than a failure, for the reason the class
already gives: whatever needs one will say so, naming the agent type.

## 8. Non-Spring applications

Register instances by id in code and nothing else:

```java
DefaultDirectHarnessFactory.of(f -> f
    .backend(backend)
    .provider(ProviderId.of("openai"), OpenAiInferenceProvider.fromEnv())
    .provider(ProviderId.of("local"), OpenAiInferenceProvider.of(c -> c.apiKey("lm-studio").baseUrl("http://localhost:1234/v1")))
    .inference(ProviderId.of("openai"), InferenceOptions.of("gpt-4.1-mini")));
```

There is no catalogue outside Boot: a preset is a Boot property shape and the starter is where
property shapes live. chat-cli, mcp and policy (`nessy-examples-are-spring-boot`, 2026-09-16: the
simple examples stay non-Spring) are written this way.

## 9. Not being done

Each of these was raised and set aside on 2026-09-29, with the reason:

- **Per-call routing.** The provider is a fact of the agent type, resolved at build; a per-request
  choice would put a lookup and a decision in the engine loop this record keeps untouched.
- **Failover.** A second provider to try when the first fails is a policy on top of retry, which
  `2026-09-27-turn-policy-design.md` is still building; it is not a registry feature.
- **Inferring the provider from the model name.** Ambiguous: `llama3` is served by Ollama, Groq,
  Together and Fireworks, and `gpt-4o` by OpenAI, Azure and OpenRouter. A guess that is right most
  of the time is the alphabet problem with better odds.
- **`nessy.agents.<type>.*` property overrides.** What an agent type infers with is said where the
  harness is created, as the Boot spec ruled for the prompt; a second place to say it is a second
  place to look.
- **A default model per preset.** Model names churn faster than a release cycle, and a preset that
  ships `gpt-4.1-mini` is wrong in six months in a way a preset that ships a URL is not.
- **A combined `provider:model` string.** OpenRouter's names contain `/` and Ollama's contain `:`
  (`anthropic/claude-sonnet-4.5`, `llama3:8b`), so no separator is safe; two fields cost nothing.
- **Embedders.** `Embedder.providerName()` (`nessy-api`) and `EmbeddingProvider.providerName()`
  (`nessy-embedding-spi`) share the name this record renames, and the three embedding
  auto-configurations very likely have the same one-slot bug. Own spec; see §14 (2) for the one
  consequence that cannot wait.

## 10. Sequencing

Four steps, each a reviewable commit, each green under `./mvnw -q clean verify` with no key and no
network before the next starts:

1. **The rename** (§4): `providerName()` to `vendor()` across the inference family, the constants,
   the two test files, the gateway and ModelProvider prose, the two guide pages. No behaviour
   change; OTel tags byte-identical.
2. **`ProviderId` and the engine registry** (§5, §6): the record in `nessy-api`; the repeatable
   `provider(ProviderId, InferenceProvider)` on both factory configs; `inference(ProviderId,
   InferenceOptions)` as the defaults on both; `InferenceConfig.provider(...)`; resolution and the
   two failure messages in both `create`s; every engine test and both auto-configurations moved to
   the new shape (with Boot still building one provider, registered under its vendor's id, so the
   starter keeps working between this step and the next).
3. **Boot presets and the report** (§7): the `Wire` enum, the catalogue, `nessy.providers.*` binding,
   `nessy.provider` as the factory default, application beans joining, the report rewritten, the
   three vendor auto-configurations collapsed into the preset mechanism.
4. **Examples and docs**: chat-web's and watchman's `application.yml`, chat-cli's `Chat.java`,
   `Repl`, `docs/guides/providers.md`, `docs/guides/spring-boot.md`, `docs/guides/observability.md`
   and the README, describing what is (`docs-describe-what-is`), not what was.

## 11. Design authority

Every new public concept in this record was discussed and agreed in conversation with James on
2026-09-29; the sign-off on this written record is pending, and nothing lands before it. Listed by
name, as the rule requires:

| concept | where | § |
|---|---|---|
| `ProviderId` -- a value type in `nessy-api` | public type | 5a |
| `InferenceProvider.vendor()` -- the rename of `providerName()`, with `OpenAiProviderConfig.vendor(String)` and the `VENDOR` / `XAI_VENDOR` constants | public method, renamed | 4 |
| `InferenceConfig.provider(ProviderId)` and `provider(String)` | public methods | 6c |
| named factory registration -- `provider(ProviderId, InferenceProvider)`, repeatable, on both factory configs, replacing the single-provider setters | public methods | 6a |
| `inference(ProviderId, InferenceOptions)` as the factory defaults on both configs | public method, retargeted on one config and new on the other | 6b, 14 (6) |
| `nessy.provider`, `nessy.providers.<id>.*` (`api-key`, `enabled`, `wire`, `base-url`, `vendor`) | Boot properties | 7 |
| the wire property values `chat-completions`, `messages`, `generate-content` | Boot property values | 3, 5c |
| the words **provider**, **preset**, **wire**, **vendor** as §3 defines them | design vocabulary | 3 |

Not new, and needing no yes: the `Wire` enum (package-private, §5c), the catalogue table, the
report's wording, and every renamed test.

## 12. Testing

House rules apply throughout: prose-style test names (`an_xai_key_names_x_ai_as_the_vendor`, as
the existing `OpenAiAutoConfigurationTest` does), `@DisplayName` sentences, **no mocking library**
-- providers in tests are lambdas or small scripted classes, as `ScriptedProvider` and the engine
tests' `model` doubles are today. Sonar S5778: every `assertThatThrownBy` lambda contains exactly
one call that can throw, with the factory, the config and the ids built outside it. Any
`allMatch`/`noneMatch` over a collection is preceded by an assertion that the collection is not
empty. Everything below passes with no API key and no network; the keys in the matrix are
`sk-test`-style strings that are never sent anywhere, as they are in the existing Boot tests.

### 12a. The `ApplicationContextRunner` matrix

The probe's combinations, re-run against the finished starter. Each row is a test; the point is
that every outcome is now decided by something written down.

| keys / properties | expected |
|---|---|
| none | no provider registered; the report warns; a harness build fails naming the agent type |
| `openai.api-key` | one provider, `openai`, wire chat-completions, vendor `openai` |
| `openai.api-key`, `anthropic.api-key`, `gemini.api-key` | three providers, all registered, none chosen |
| `openai.api-key`, `gemini.api-key` | two |
| `openai.api-key`, `xai.api-key` | two, both chat-completions, vendors `openai` and `x_ai` |
| `gemini.api-key`, `xai.api-key` | two |
| `gemini.api-key` and `google.api-key` both set | **one** provider, `gemini`, not two |
| `nessy.providers.xai.api-key` with no `xai.api-key` | `xai` registered from the prefixed form |
| `openai.api-key`, `openai.base-url=http://localhost:1234/v1` | `openai` at the overridden endpoint |
| `nessy.providers.ollama.enabled=true` | `ollama` registered with no key |
| nothing about ollama | `ollama` absent |
| `nessy.providers.mine.wire=chat-completions`, `base-url`, `api-key` | `mine` registered, vendor `openai` by default |
| `nessy.providers.mine.base-url` with no `wire` | context fails to start, naming `mine` and `wire` |
| `nessy.providers.xai.wire=bogus` | context fails to start, naming the allowed values |
| an application `InferenceProvider` bean `scripted` plus `openai.api-key` | two providers, `scripted` and `openai`; the bean is not backed off |
| `nessy.provider=xai` with `xai.api-key` | the factory default resolves to `xai` |
| `nessy.provider=claude` with `openai.api-key` | fails at harness build, listing `[openai]` |

Every registered provider is asserted `isInstanceOf(ObservedInferenceProvider.class)`, as today.

### 12b. The engine

`DefaultDirectHarnessTest` and its queued counterpart gain:

- an agent type naming a registered id gets that instance (two registered lambdas that answer
  differently, and the answer says which one was asked);
- an agent type naming nothing gets the factory default;
- an agent type naming an unknown id fails at `create`, and the message names the agent type and
  lists every registered id (assert the list is non-empty, then its contents);
- no default and no id fails at `create` with the other message;
- registering the same id twice fails at registration;
- two agent types on one id share one `ObservedInferenceProvider` (the same instance reaches both
  services);
- the model rule unchanged: no model and no default fails naming the agent type, on both doors.

### 12c. The rename

`InferenceProviderTest`, `OpenAiVendorTest` and `AnthropicVendorTest` assert the same values as
before under the new name; `ObservedTest` (`nessy-spring-boot`) and the two summariser tests keep
asserting `gen_ai.provider.name` equals the vendor value, which is the byte-identical guarantee
made testable.

## 13. Order of the questions to James

None of §14 blocks step 1. (2), (5) and (6) shape step 2; (3), (4) and (8) shape step 3; (1) and
(7) can wait for step 4 or after.

## 14. Open, for James

1. **Record the `ProviderId` on inference events?** `Usage` already records the model on all four
   inference arms (`nessy-usage-nullable`, 2026-09-26). Without the provider id a trajectory over
   two `openai`-wire providers with the same vendor tag cannot say which one answered. Recording it
   means a field on the events, a column, and `@JsonValue`/`@JsonCreator` on `ProviderId`, which is
   why §5a leaves them off for now.
2. **The embedder half of the rename.** `Embedder.providerName()` (`nessy-api`, line 39),
   `EmbeddingProvider.providerName()` (`nessy-embedding-spi`, line 48), four embedding adapters,
   `ObservedEmbedder`, `DefaultEmbedder` and their tests all carry the name §4 renames on the
   inference side. Renaming only inference leaves the two families disagreeing on what the OTel
   vendor value is called; renaming both widens step 1 into a module family this record otherwise
   leaves alone. Recommendation: rename both in step 1, since it is the same mechanical change and
   the same tag, and leave the embedders' one-slot bug to their own spec.
3. **A bean named the same as a preset id.** An application bean called `openai` while
   `OPENAI_API_KEY` is set: fail at startup naming both, or let the bean win as the more explicit
   statement? Recommendation: fail. A silent winner is what this whole record exists to remove.
4. **Which presets make the first cut**, after measurement (§7d). The four that exist are in; each
   candidate joins with a measured row or not at all.
5. **`DirectHarnessFactory.providerName()`** (`nessy-api`, line 106; implemented by
   `DefaultDirectHarnessFactory`, used by `Repl` for its diagnostics line). The brief did not
   mention it. Step 1 can rename it to `vendor()` mechanically, but after step 2 a factory has
   several providers and the method has no single answer. Options: delete it in step 2 and have
   `Repl` read the provider its own agent type resolved to (which needs a way to ask -- see (7)), or
   keep it as "the vendor of the factory default". Recommendation: delete it; a question with no
   single answer should not have a method.
6. **The name of the factory-defaults method.** §6b keeps the queued config's existing word,
   `inference(ProviderId, InferenceOptions)`, and adds it to the direct config. That name was not
   discussed on 2026-09-29; it is proposed here because it is the word already on one of the two
   configs, not because it was agreed. `defaults(...)` is the alternative.
7. **Should the factory expose a lookup by id?** §6f has background consumers take the instance
   from the application (a bean by name under Boot, a held reference in code). A `provider(ProviderId)`
   getter on the factory would let a consumer share exactly what the agent types share, wrapper
   included, but it is a new public method and a second way to hold a provider.
8. **`nessy.provider` versus `nessy.inference.provider`.** The 2026-09-25 Boot spec (§10 (3))
   recommended the longer name because embedding providers exist and `nessy.provider` reads as
   "the one provider". James chose `nessy.provider` on 2026-09-29 and it is what §7 uses; noting
   the earlier recommendation so the choice is a decision rather than an oversight.
9. **On the queued door, a redeploy that changes an agent type's provider.** An `Infer` effect
   written before the redeploy and dispatched after it goes to the *new* provider, because
   resolution is at harness build and the effect row carries no provider. The model name travels
   with the effect (`InferenceOptions` on the handler) only in the sense that the handler was built
   with it, so the same is true of the model today. Acceptable, or should (1) also be what the
   dispatcher reads back?
10. **Found in the code, at odds with the brief.** Two small ones, recorded so nothing is papered
    over: (a) the brief said the rename replaces "today's single `provider(...)`", and that is
    true of the direct config only -- the queued config's single provider is the first argument of
    `inference(InferenceProvider, InferenceOptions)`, which §6a and §6b split; (b) the brief said
    `ProviderId` should carry `@JsonValue`/`@JsonCreator` "if `AgentType` has them", and
    `AgentType` has neither, so §5a leaves them off and (1) is where they would come back.

---

**Amendment, 2026-09-29:** wire values are vendor-named (`openai`, `anthropic`,
`gemini`) by James's ruling — a wire is the API shape a vendor defined; a
second shape from one vendor gets a qualified name. This supersedes the
protocol-named values in §3, §5c and §11.

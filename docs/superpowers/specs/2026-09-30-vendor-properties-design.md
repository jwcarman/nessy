# Vendor properties: the escape hatch every adapter owns

**Status: DESIGN, NOTHING BUILT. The rulings were made in conversation with James on 2026-09-29
and this record writes them down. The proposals this record had to make to honour them were
ruled on overnight on 2026-09-30 by the controller James authorised for the run; those rulings
are folded in below and marked "accepted overnight" or "changed overnight" in §15, pending his
morning review. The questions in §16 are the ones still open, and the places where the code
disagreed with the brief are recorded there rather than smoothed over.**

Date: 2026-09-30. The second of the three `0.3.0` items named in
`2026-09-29-openai-responses-design.md` §10: (1) the Responses adapter, (2) this record, (3) named
embedding providers. It sits on `2026-09-29-named-providers-design.md` (provider, preset, wire,
vendor; `ProviderId`; the Boot catalogue) and on the Responses record, whose §5g reads
`openai.reasoning.summary` and `openai.reasoning.effort` and whose §5d forward-references
`openai.tools.strict` -- all three names are decided **here**. The Responses adapter is being
implemented on the branch beneath this one, so this record cites that spec's names for the OpenAI
module (`OpenAiChatInferenceProvider`, `OpenAiChatRequests`, `Wire.OPENAI_CHAT`) and says in §16
what the code on this branch still calls them. Every other path, name, signature and SDK method
below was checked against `src/main/java`, `src/test/java` and the SDK jars in `~/.m2`
(`openai-java-core-4.69.2`, `anthropic-java-core-2.65.0`, `google-genai-1.73.0`,
`bedrockruntime-2.55.5`) on branch `vendor-properties` at the time of writing.

---

## 1. The problem

Nessy's inference API is vendor-neutral by design, and every vendor has settings the neutral API
cannot name.

Verified in source, this is the whole of what reaches an adapter today. `DefaultInferenceService`
(`nessy-engine`, `engine.inference`) builds one `InferenceRequest` per call:

```java
new InferenceRequest(
    systemPrompt.forAgent(invocation.agentId()),
    assembler.assemble(invocation),
    invocation.answerOnly() ? answering() : toolset,
    invocation.options(),
    outputSchema)
```

so an adapter sees a `SystemPrompt`, an `InferenceContext`, a `Toolset` (offers and a
`ToolChoice`), an `InferenceOptions` -- the record `(String modelName, int maxTokens)` -- and an
`Optional<JsonSchema>`. Those are the **typed settings**, and there are exactly five of them.
`InferenceConfig` (`nessy-api`) lets an agent type say `provider`, `model`, `maxTokens`,
`context`, `timeout` and `retryPolicy`, and nothing else.

What the vendors want said, and where each one is today:

- **OpenAI reasoning.** GPT-6 with function tools needs `/v1/responses` (the Responses record §1),
  and OpenAI streams reasoning summaries only when the request carries `reasoning: {summary:
  ...}` -- an object that is a 400 on a model that does not reason. The Responses record's ruling
  (§5g) is that the adapter never guesses which kind of model it holds, so today **no `reasoning`
  object is ever sent** and no summary ever arrives. The knob does not exist.
- **Anthropic thinking and caching.** `AnthropicProviderConfig` has `thinking(boolean)`,
  `thinkingBudget(int)` (default `1024`) and `promptCaching(PromptCaching)`, folded into
  `AnthropicRequests.Features(thinking, thinkingBudget, caching)` and applied to **every call the
  provider makes**. `docs/guides/providers.md` line 107 states the consequence as policy:
  "Provider-level features such as thinking and prompt caching are settings on the provider, not
  requests a harness makes. Two agent types that need the provider configured differently get two
  providers, registered under two names, on the same factory." Two providers, two keys' worth of
  connection pools and two OTel identities, to say "this agent thinks and that one does not".
- **Gemini thinking.** `GeminiRequests.toConfig` builds a `GenerateContentConfig` with
  `maxOutputTokens`, the system instruction, tools, tool choice and the response schema. The SDK's
  `ThinkingConfig` (`includeThoughts`, `thinkingBudget`, `thinkingLevel`, verified on
  `ThinkingConfig$Builder`) is never set, and there is no way to set it.
- **Bedrock.** `BedrockRequests.toRequest` sets `modelId`, `inferenceConfig.maxTokens`, messages,
  system and tools. Claude's extended thinking on Bedrock travels in
  `additionalModelRequestFields` (`{"thinking": {"type": "enabled", "budget_tokens": N}}`, Amazon's
  documented route for model-specific fields); the builder method exists
  (`ConverseStreamRequest.Builder.additionalModelRequestFields(Document)`, verified) and nothing
  calls it.
- **Strict tool schemas on the chat wire.** The Responses record (§5d) specifies an adapter-side
  schema rewrite so function tools go out with `strict: true`; the chat adapter never sets
  `strict` on a tool (only on `response_format`), and the record leaves the chat wire's switch to
  this one.

Every one of these is a per-request field a vendor documents. Adding a typed setting for each
would put OpenAI's `effort`, Anthropic's `budget_tokens` and Gemini's `thinkingLevel` on a
neutral interface, each meaning something different, each re-argued whenever a vendor adds a
level. Adding none leaves the features unreachable. The precedent that resolves this is thirty
years old: JPA persistence-unit properties are vendor-prefixed names (`hibernate.*`,
`eclipselink.*`) that a provider reads if it owns them and ignores if it does not, and JPA query
hints are the same idea per call, in code, with the rule that an unrecognised hint **must** be
ignored. Nessy gets the same shape.

## 2. The shape in one paragraph

An agent type carries a map of string-named, string-valued **vendor properties**, set in code by
`InferenceConfig.property(String name, String value)` and, under Boot, defaulted per provider by
`nessy.providers.<id>.properties.<prefixed.name>=<value>`; a preset may ship defaults of its own,
the way it ships a URL. The map rides in `InferenceOptions` beside the model and the ceiling,
fixed when the harness is built, and reaches the adapter with every request. Each adapter owns a
prefix (`openai.`, `anthropic.`, `gemini.`, `bedrock.`), reads only the entries under it and
ignores the rest, so one agent type can carry settings for several vendors and switch providers
without editing. Under its prefix an adapter **parses** the names it knows into the right types
and **passes through** the names it does not, as JSON literals where the value parses as one, into
the request body -- so a typo is the vendor's own 400, and a field Nessy has never heard of is
reachable the day the vendor documents it. A property that names something a typed setting
already decides is refused when the harness is built, naming both, so the neutral API is never
overridden from underneath. Nothing about this touches the event log: properties are
configuration, and replay reads none. Embedders get the same door, `EmbedderConfig.property`, with
the Boot side of it waiting for the third `0.3.0` item.

## 3. Vocabulary

| word | meaning |
|---|---|
| **vendor property** | a `(name, value)` pair of strings. The name is prefixed by the adapter that owns it; the value is whatever the vendor's documentation says goes in that field, spelled as text |
| **prefix** | the first dotted segment of a property name, naming the adapter family that reads it: `openai.`, `anthropic.`, `gemini.`, `bedrock.`. An adapter reads its own prefix and no other (§8a) |
| **known name** | a property an adapter parses into a typed value and places itself, because the wire has a typed field for it or because it is not a request field at all (§8b) |
| **pass-through** | an unknown name under an adapter's prefix, sent into the request body verbatim as a nested path, its value read as a JSON literal where it parses as one (§8c) |
| **clash** | a property that names a thing a typed setting already decides: the model, the ceiling, the tools, the tool choice, the output schema, the messages, or a field the adapter fixes on purpose. Refused at build, naming both (§7b) |

"Property" was chosen over "option", "hint" and "extra" because it is the JPA word for exactly
this mechanism and because `InferenceOptions` already means the typed terms; two things called
options one word apart is the trap the named-providers record (§4) went out of its way to avoid.
"Hint" was rejected because a hint may be ignored and a known name may not be.

## 4. The API

### 4a. `InferenceConfig.property(String name, String value)`

`InferenceConfig` (`nessy-api`) gains one method beside `model(String)`:

```java
/**
 * A setting the vendor understands and the neutral API does not name. The name is prefixed by
 * the adapter that reads it ({@code openai.reasoning.effort}); an adapter ignores every other
 * prefix, so an agent type may carry settings for several vendors at once. Repeatable; the last
 * value given for a name wins. Fixed when the harness is built, sent with every request.
 */
InferenceConfig property(String name, String value);
```

Strings everywhere, by James's ruling: a value is the text the vendor's documentation shows, and
the adapter that owns the name is the one that knows its type. Both arguments are required and
non-blank; a blank name or a blank value fails at once with `IllegalArgumentException` naming the
argument, because there is no reading of "the empty string" that a vendor would want and a blank
name cannot carry a prefix.

An agent type's map is seeded from the factory defaults exactly as its model is today
(`DefaultDirectHarnessConfig.Inference(ProviderId, InferenceOptions)` copies `modelName` and
`maxTokens` from `defaults`; `DefaultQueuedHarnessConfig.Inference(Defaults)` does the same), so a
factory default `InferenceOptions` that carries properties (§5a) hands them to every agent type
that does not overwrite them by name. Boot never fills that level (§6b); it exists because the
record it lives on already exists, and giving it a hole would be a special case.

### 4b. `EmbedderConfig.property(String name, String value)`

`EmbedderConfig` (`nessy-api`, `api.embedding`) gains the same method beside `model(String)` and
`dimension(int)`, with the same rules. `DefaultEmbedderFactory.Settings` (`nessy-engine`,
`engine.embedding`) keeps the map and hands it to `EmbeddingOptions` (§5b). The Boot binding for
embedders -- `nessy.embedders.<id>.properties.*` -- is the third `0.3.0` item's, and is
forward-referenced in §6d rather than specified here.

## 5. Where a property lives at runtime

### 5a. In `InferenceOptions`, decided from the code

Two records travel from the harness config to the adapter, and the choice between them is not a
matter of taste once the code is read:

- `InferenceOptions` (`nessy-inference-spi`) is built **once, at harness build**:
  `DefaultDirectHarnessFactory.create` line 294 (`new InferenceOptions(inference.modelName(),
  inference.maxTokens())`) and `DefaultQueuedHarnessFactory` line 349 (`inference.options()`),
  handed to `InferenceHandler`, which holds it in a field and puts it on every
  `InferenceInvocation`. It is the agent type's fixed terms.
- `InferenceRequest` (`nessy-inference-spi`) is built **per call** in `DefaultInferenceService.infer`
  from things that vary per call: the prompt resolved for this agent, the context assembled now,
  the toolset with this call's `ToolChoice`. It carries the `InferenceOptions` as one component.

Properties are configuration of the agent type, fixed at build and identical on every call --
James's ruling, and the same fact the model already is. So they go where the model is:

```java
public record InferenceOptions(String modelName, int maxTokens, Map<String, String> properties)
```

with `properties` copied through `Map.copyOf` in the canonical constructor, a two-argument
constructor `(modelName, maxTokens)` that passes `Map.of()` so no existing caller changes, and
`of(modelName)` unchanged. Adding a component to `InferenceRequest` instead would say
"this may differ per call", which is a promise the engine does not make and the summarisers would
have to be taught to keep.

The callers that change are the two factory lines above (they pass the config's map) and nothing
else: `InferenceHandler`, `InferenceInvocation`, `DefaultInferenceService`,
`HeadSummarizer.Config` and `EpisodeSummarizer.Config` (`nessy-memory`, which take an
`InferenceOptions` whole) all carry the record as they do today and gain the properties by
carrying it. A summariser built with `new InferenceOptions("gpt-4.1-mini", 512,
Map.of("openai.reasoning.effort", "low"))` sends that effort on every summary; a summariser built
with the two-argument form sends none.

### 5b. In `EmbeddingOptions`, likewise

`EmbeddingOptions` (`nessy-embedding-spi`) is `(String modelName, OptionalInt dimension)` and is
built once per embedder in `DefaultEmbedderFactory.Settings.options()`. It gains the same third
component with the same constructors, for the same reason.

### 5c. Never in the event log

Verified: `InferenceOptions` is referenced in `nessy-engine/src/main` only by the two factory
configs, the two factories, `InferenceHandler`, `InferenceInvocation` and
`InferenceContextAssembler`'s javadoc. The `AgentEffect.Infer` row carries `answerOnly` and nothing
about the model, the ceiling or the terms; the fold never sees an `InferenceOptions`. Properties
are configuration, not events -- James's ruling -- and this is what makes the ruling free: nothing
is written, no schema changes, replay of a transcript produced under one set of properties is
identical to replay under another because replay does not call a model. A redeploy that changes an
agent type's properties changes the next request and nothing recorded, exactly as a redeploy that
changes its model does today (named-providers §14 (9)).

## 6. Provider-level properties, presets, and the Boot binding

### 6a. Each provider config takes properties

A provider carries defaults of its own, and the door is the config it is already built from. Each
`*ProviderConfig` -- `OpenAiChatProviderConfig` and `OpenAiResponsesProviderConfig` (the Responses
record's names for what this branch still calls `OpenAiProviderConfig`), `AnthropicProviderConfig`,
`GeminiProviderConfig`, `BedrockProviderConfig` -- gains:

```java
public XProviderConfig property(String name, String value);          // repeatable, last wins
public XProviderConfig properties(Map<String, String> properties);   // all at once, for Boot
```

**Proposed here and accepted overnight**, because the rulings say presets carry default
properties and Boot binds provider properties, and something has to receive them: the adapter
itself is the right holder, because it is the thing that applies them to every request and because a non-Spring
application then writes exactly what Boot writes
(`OpenAiChatInferenceProvider.of(c -> c.apiKey(key).property("openai.tools.strict", "true"))`).
The alternative -- the factory holding a per-provider map beside the registry and merging it into
each agent type's `InferenceOptions` at build -- would put a merge in the engine that the engine
cannot check, since it does not know any adapter's prefix or clash table.

A provider-level property outside the adapter's own prefix is refused at `build()` naming the
property and the prefix: a provider is one adapter, and `nessy.providers.anthropic.properties.
openai.reasoning.effort` is a mistake, not a setting for a future switch. (An agent type may
carry several prefixes, §8a; a provider may not.)

The four embedder configs -- `OpenAiEmbedderConfig`, `GeminiEmbedderConfig`,
`BedrockEmbedderConfig`, `VoyageEmbedderConfig` (`nessy-embedding/*`) -- gain the same pair, with
the adapter-side reading of the map deferred to the third `0.3.0` item (§9f).

### 6b. The Boot binding

```yaml
nessy:
  providers:
    openai:
      properties:
        openai.reasoning.effort: high
        openai.tools.strict: "false"      # overriding the preset's default (§11)
    anthropic:
      properties:
        anthropic.thinking.type: enabled
        anthropic.thinking.budget_tokens: "8192"
```

`ProviderSettings` (`nessy-spring-boot-autoconfigure`, `spring.boot.inference`) gains a
`@Nullable Map<String, String> properties` component beside `wire`, `baseUrl`, `apiKey`, `enabled`
and `vendor`; `ProviderRegistrar` already binds `nessy.providers` through
`Binder.get(environment).bind("nessy.providers", Bindable.mapOf(String.class,
ProviderSettings.class))`, and the nested map binds with it. `ResolvedProvider` gains a
`Map<String, String> properties` component; `ProviderCatalogue.resolve` fills it with the preset's
defaults overlaid, name by name, by the settings' entries (a custom provider has only its
settings'); `WireProviders.build`'s three nested builders (`OpenAi`, `Anthropic`, `Gemini`, plus the
Responses record's fourth) call `c.properties(resolved.properties())` beside `apiKey`, `baseUrl`
and `timeout`.

**The value type is `String`, and that is what keeps the keys flat.** Measured by James on Spring
Boot 4.1.1's `Binder` on 2026-09-29: a `Map<String, String>` bound from
`nessy.providers.openai.properties.openai.reasoning.effort=high` yields one entry whose key is
`openai.reasoning.effort`, no brackets needed, because the binder stops descending when the value
type is scalar; a `Map<String, Object>` would nest the same keys into `openai -> reasoning ->
effort`. The binding test in §13b pins this so a future Boot cannot quietly change it. A value
that YAML would read as a boolean or a number (`true`, `8192`) still binds as its text, which is
what the adapter reads (§8c); quoting it is harmless and the examples above quote the ones a
reader might otherwise take for typed.

**Environment variables cannot express these keys, and this record says so rather than pretending.**
Boot's relaxed binding maps `NESSY_PROVIDERS_OPENAI_PROPERTIES_OPENAI_REASONING_EFFORT` by turning
every `_` into `.` and lower-casing, which loses two things a property name needs: the underscore
inside a vendor's own field (`budget_tokens`, `service_tier`, `max_output_tokens`) becomes a dot,
and the case Gemini's field names carry (`thinkingBudget`, `generationConfig`) is gone. Vendor
properties are a **configuration-file feature** -- `application.yaml`, `application.properties`,
a config server -- and the guide says so. A deployment that must set one from the environment sets
it in the file as `${SOME_VAR}`, which is the file doing the naming and the environment supplying
only the value.

### 6c. What the report says

`InferenceReport`'s provider line grows a clause when a provider has any:

```
NESSY INFERENCE: providers: openai (openai-chat, https://api.openai.com/v1, vendor openai, properties [openai.tools.strict]); anthropic (anthropic, https://api.anthropic.com, vendor anthropic)
```

**Names only, never values -- changed overnight from this record's first draft, which printed
both.** A pass-through value may be sensitive (a `user` or `safety_identifier`, a `metadata`
entry carrying a tenant), and a report cannot tell which are; the key is redacted today by
`ProviderSettings.toString` and `ResolvedProvider.toString`, and property values join it. The
names say what was configured, which is what a reader of the report is trying to learn; the
values are in the file that set them. Each harness's own line (`agent type 'chat' -> openai /
gpt-4.1-mini, up to 4096 tokens`) gains the agent type's property names the same way when it has
any, because the merged result is the fact that exists at that moment (§7a) and nowhere else.
`ProviderSettings.toString` and `ResolvedProvider.toString` print names only for the same reason.

### 6d. Embedders under Boot: forward reference

The three embedding auto-configurations (`OpenAiEmbeddingAutoConfiguration`,
`GeminiEmbeddingAutoConfiguration`, `VoyageEmbeddingAutoConfiguration`) still build one
`EmbedderFactory` from `@Value("${openai.api-key}")`-style injection and `nessy.embedding.<vendor>.
model` / `.dimension` -- the one-slot shape the named-providers record (§9) said would get its own
spec. `nessy.embedders.<id>.properties.*` arrives with that spec, the third `0.3.0` item, bound
exactly as §6b binds the inference side, and reaches `XEmbedderConfig.properties(...)` (§6a).
Until then an application sets embedder properties in code, on the config or per embedder through
`EmbedderConfig.property` (§4b). Nothing in this record depends on the Boot half landing first.

## 7. Precedence and the clash rule

### 7a. Three layers, and config never overrides code

Read from the top; the first layer that says something about a name wins.

1. **Typed settings are never overridden.** The model, the ceiling, the tools and their choice,
   the output schema, the system prompt and the context are decided by the typed API, and a
   property that would set any of them is a clash (§7b), refused at build. Not "code wins":
   refused, because the two would disagree and one of them would be silently wrong.
2. **Agent-type properties, in code, override provider properties.** `InferenceConfig.property`
   on the agent type beats `nessy.providers.<id>.properties.*` and a preset's defaults, name by
   name. James's ruling, with the reason: a config file that overrode what the code says would be
   a second place to look for why an agent behaves as it does, and the one further from the agent.
3. **Provider properties apply otherwise** -- a preset's defaults, overlaid by the settings under
   `nessy.providers.<id>.properties.*` (§6b), or whatever `XProviderConfig.property` was called
   with in code -- **and an adapter config's own typed setters sit in this same tier.**
   `AnthropicProviderConfig.thinking(boolean)`, `thinkingBudget(int)` and
   `promptCaching(PromptCaching)` are provider-level defaults exactly as a config property is
   (ruled overnight, §9c): a setter and a property for the same name both set on one provider
   fail at `build()` naming both, because two provider-level statements about one field have no
   order between them; an agent-type property in code overrides either, because layer 2 beats
   layer 3 whatever spelling layer 3 used.

The merge of layers 2 and 3 happens **in the adapter, per request**: the provider's own map
overlaid by `request.options().properties()`, name by name, then filtered to the prefix (§8a).
Nothing in the engine merges, because nothing in the engine knows a prefix. The harness's report
line (§6c) is the only place the merged result is shown, and it shows it by asking the adapter
(§7c).

### 7b. The clash rule

Each adapter keeps a small table of the names under its prefix that correspond to typed settings
or to fields it fixes on purpose (§9). The union of the provider's map and the agent type's map is
checked against that table, and a hit fails with `IllegalArgumentException`:

```
agent type 'chat': property 'openai.max_completion_tokens' names what InferenceConfig.maxTokens already decides (4096); remove the property
```

naming both the property and the typed setting, as James ruled. The table also lists each known
name's raw wire spelling (`openai.reasoning_effort` beside `openai.reasoning.effort`, §9a), so two
spellings of one field cannot both be sent. A typed setting layered on later -- a portable
reasoning knob, if one is ever added -- joins the table the day it lands, and the rule already
covers it; that is the whole of what "the clash rule already covers it" in the rulings means.

### 7c. Failing at harness build needs one SPI hook

The ruling is that a clash **fails at harness build**, not on the first call. The engine cannot
check a clash itself (it knows no prefix), and the adapter is not consulted at build today: the
factories resolve the `ProviderId`, wrap the provider and hand it to `DefaultInferenceService`.
So, **proposed here and accepted overnight**: one default method on the SPI,

```java
// InferenceProvider (nessy-inference-spi)
/**
 * Refuses terms this provider cannot honour, before a harness is built on them: a property
 * that clashes with a typed setting, a known name with a value of the wrong type, a known name
 * this wire cannot carry. The default accepts everything, which is right for a provider that
 * reads no properties.
 */
default void validate(InferenceOptions options) {}
```

called by `DefaultDirectHarnessFactory.create` and `DefaultQueuedHarnessFactory.create` on the
resolved provider, after the model check and before any handler is built, with the failure
re-thrown prefixed by the agent type. `ObservedInferenceProvider` delegates it (verified: the
wrapper overrides `vendor()` and both `infer` arms today and forwards each). `EmbeddingProvider`
gets `default void validate(EmbeddingOptions options) {}` and `DefaultEmbedderFactory.create`
calls it. Provider-level properties are checked earlier still, at `XProviderConfig.build()`,
against the same table, so `nessy.providers.openai.properties.openai.model=gpt-4o` fails at
startup naming the property and `InferenceConfig.model`.

The alternative -- checking on the first request and failing the first turn -- was considered and
set aside because it contradicts the ruling and because the first turn of a queued agent may be
hours after the deploy that broke it.

## 8. The adapter contract

Every adapter follows the same five steps, and the steps are written once, as a helper (§8e), so
that four inference adapters and four embedding adapters cannot drift.

### 8a. The prefix, and why it is the adapter's and not the vendor's

| adapter | prefix | `vendor()` |
|---|---|---|
| `OpenAiChatInferenceProvider`, `OpenAiResponsesInferenceProvider` | `openai.` | `openai` by default; `x_ai`, `groq`, `mistral_ai`, `openrouter`, `nvidia`, `lmstudio`, `ollama` when configured (`Preset.CATALOGUE`) |
| `AnthropicInferenceProvider` | `anthropic.` | `anthropic` |
| `GeminiInferenceProvider` | `gemini.` | `gcp.gemini` |
| `BedrockInferenceProvider` | `bedrock.` | `aws.bedrock` |

The prefix names the **adapter family that reads the property**, not the OTel vendor tag, and the
two differ on purpose. `OpenAiChatProviderConfig.vendor(String)` lets one adapter report `x_ai` or
`groq` (verified: `Preset.CATALOGUE` has eight rows on the OpenAI wire with seven vendor values),
and a request to Groq is still shaped by the OpenAI adapter, so `openai.` is the honest prefix for
it. `gcp.gemini` and `aws.bedrock` carry a dot, which would make the prefix ambiguous with the
nesting rule of §8c; `gemini.` and `bedrock.` are the names the modules already have. Under
`openai.` a property reaches whichever OpenAI-wire provider the agent type resolves to, which is
the switching story the rulings ask for: an agent type carrying `openai.reasoning.effort=high`
and `anthropic.thinking.budget_tokens=8192` runs on either and each adapter reads its own.

An entry under **another** prefix is ignored, silently, at the adapter. Logged once at `DEBUG`
with the names ignored, never at `WARN`: it is the design working, not a mistake. An entry with
**no** prefix (`temperature=0.2`) is refused at build by every adapter -- it can belong to nobody,
and the rejected design of §12 is exactly that shape.

### 8b. Known names are parsed

A known name is one the adapter places itself, for one of two reasons: the SDK has a typed field
for it and the adapter would rather use that than write the JSON by hand (`reasoning_effort`,
`thinking`, `thinkingConfig`), or it is not a request field at all and only the adapter can act on
it (`openai.tools.strict` is a schema rewrite; `anthropic.cache_control.ttl` is a set of markers
placed on the prompt and the tool list). The adapter parses the value into the type the field
takes and fails **at build, naming the property and the value** when it cannot:

```
property 'anthropic.thinking.budget_tokens' must be an integer, was 'lots'
```

The adapter checks the **type**, never the **vocabulary**: `openai.reasoning.effort=xhigh` is
passed to the SDK's `ReasoningEffort.of(String)`, which accepts any string and lets the vendor say
whether it is a level; the day OpenAI adds one, nothing here changes. A known name the wire cannot
carry (`openai.reasoning.summary` on the chat wire, §9a) fails at build naming the property and the
wire, for the reason §7c gives about first turns.

### 8c. Unknown names pass through

An unknown name under the prefix is sent into the request body. Two rules, both mechanical:

- **The name after the prefix is a path.** Every remaining dot nests one object:
  `openai.reasoning.effort` is `{"reasoning": {"effort": ...}}`;
  `gemini.generationConfig.thinkingConfig.thinkingBudget` is three levels deep. A name is spelled
  exactly as the vendor's REST reference spells the field -- `snake_case` for OpenAI and Anthropic,
  `lowerCamelCase` for Gemini and Bedrock's Converse fields -- because it is sent verbatim and the
  vendor is the one reading it. Two paths that meet are deep-merged (`a.b=1` and `a.c=2` make one
  `a`); a scalar meeting an object (`a=1` and `a.b=2`) is refused at build naming both.
- **The value is read as a JSON literal when it parses as one, and as a string otherwise.** `12000`
  is a number, `true` is a boolean, `null` is null, `{"type":"enabled","budget_tokens":4096}` is an
  object, `["\n\n"]` is an array, `high` is the string `"high"`. The parse uses the adapter's
  configured `JsonMapper` (every config has one, verified), so the rule is the same on all four. A
  value that must be a string but looks like a number is quoted in the property (`"12345"`), which
  parses as a JSON string; the guide says so beside the example.

This is what makes it a real escape hatch: a field Nessy has never heard of is one property away,
and a typo surfaces as the vendor's own 400 -- `Unrecognized request argument supplied:
reasonig_effort` -- rather than being swallowed. What is given up, said plainly: Nessy cannot warn
about a misspelled pass-through, and the engine cannot reason about what any of them mean.

### 8d. Where the pass-through lands, per SDK -- measured

| adapter | SDK | how arbitrary fields reach the body | verified where |
|---|---|---|---|
| OpenAI chat | openai-java 4.69.2 | `ChatCompletionCreateParams.Builder.putAdditionalBodyProperty(String, JsonValue)`, merged into the body beside the typed fields | `ChatCompletionCreateParams$Builder` |
| OpenAI Responses | openai-java 4.69.2 | `ResponseCreateParams.Builder.putAdditionalBodyProperty(String, JsonValue)` | `ResponseCreateParams$Builder` |
| Anthropic | anthropic-java 2.65.0 | `MessageCreateParams.Builder.putAdditionalBodyProperty(String, JsonValue)` | `MessageCreateParams$Builder` |
| Gemini | google-genai 1.73.0 | **no additional-properties on `GenerateContentConfig`.** The route is `HttpOptions.Builder.extraBody(Map<String, Object>)`, set per request through `GenerateContentConfig.Builder.httpOptions(...)`: `Models` reads `GenerateContentConfig.httpOptions()` on each call and `ApiClient.buildRequest` merges `extraBody` into the JSON body through a private `mergeMaps` (the bytecode carries the messages "Failed to merge extraBody into request body" and "HttpOptions.extraBody is set, but the HTTP method does not support a request body"). The merge is a deep merge by the shape of `mergeMaps(Map, Map)`; whether it deep-merges **into** the SDK's own `generationConfig` or replaces it is the one thing the bytecode does not settle, and §13d measures it live before a Gemini pass-through under `generationConfig` is documented | `HttpOptions$Builder`, `ApiClient`, `Models` |
| Bedrock | bedrockruntime 2.55.5 | `ConverseStreamRequest.Builder.additionalModelRequestFields(Document)` -- Amazon's documented route for **model-specific** fields, sent to the model as a JSON document. **Converse's own top-level fields are typed and cannot be added arbitrarily**: `inferenceConfig`, `guardrailConfig`, `performanceConfig`, `serviceTier`, `requestMetadata`, `promptVariables`, `additionalModelResponseFieldPaths` (all verified on the builder). So on Bedrock, pass-through means the model document, and a Converse-level field is reachable only as a known name (§9e) | `ConverseStreamRequest$Builder`, `InferenceConfiguration$Builder` |
| OpenAI embeddings | openai-java 4.69.2 | `EmbeddingCreateParams.Builder.putAdditionalBodyProperty` | `EmbeddingCreateParams$Builder` |
| Gemini embeddings | google-genai 1.73.0 | `EmbedContentConfig.Builder.httpOptions(...)`, the same `extraBody` route | `EmbedContentConfig$Builder` |
| Bedrock embeddings | bedrockruntime 2.55.5 | the adapter writes the `InvokeModel` body itself as an `ObjectNode` (`BedrockEmbeddingProvider` line 151), so a field is a `put` | source |
| Voyage embeddings | JDK `HttpClient` | the adapter writes the body itself as an `ObjectNode` (`VoyageEmbeddingProvider` line 146) | source |

Every SDK in use can carry arbitrary fields; the rulings' clause "where an SDK cannot carry
arbitrary fields say what the adapter does: known names only + a clear error for unknown ones"
applies to no adapter today, and is written into the helper (§8e) as the mode an adapter selects
if a future SDK cannot. Gemini is the one with a condition attached (§13d, §16 (5)).

### 8e. One helper, so the adapters cannot drift

**Proposed here; its home changed overnight.** The filtering, the merge, the literal reading, the
path nesting and the clash check are one piece of logic, and a copy per adapter is how four
vendors come to disagree about what `true` means. This record's first draft put it in `nessy-api`
because both SPIs depend on that module; the overnight ruling moved it out, because `nessy-api` is
the application-facing insulator and a helper for adapter authors does not belong on the surface
applications read. It lives in **`nessy-inference-spi`** as
`org.jwcarman.nessy.inference.VendorProperties`, a public `final` class of static functions over
`Map<String, String>` -- public because four adapter modules call it, SPI-only in the sense that
nothing in `nessy-api` or the engine's application surface names it, and it appears in no
signature. **The embedding side is decided in the third `0.3.0` item** (named embedders): whether
`nessy-embedding-spi` takes a dependency on the inference SPI for this one class, carries a copy,
or the class moves to a shared support module is that record's question, and the embedding
adapters do not read properties until it is answered (§9f). Its functions:

- `under(Map<String,String> merged, String prefix)` -- the entries under a prefix, the prefix
  stripped, insertion order kept;
- `merge(Map<String,String> provider, Map<String,String> agentType)` -- layer 3 under layer 2;
- `literal(String value, JsonMapper mapper)` -- the JSON-or-string reading of §8c;
- `nest(Map<String,String> flat, JsonMapper mapper)` -- the paths of §8c into one object tree, or
  an `IllegalArgumentException` naming the two entries that cannot coexist;
- `refuseClashes(Map<String,String> underPrefix, Map<String,String> clashTable)` -- the message of
  §7b, given the adapter's table of `name -> what decides it`;
- `requireInteger`, `requireBoolean`, `requireString` -- the typed reads of §8b, each failing with
  the message shape shown there.

The alternative was a package-private copy per module; the overnight ruling took the shared class
in the SPI, and §15 records it as accepted there pending James's review.

## 9. Each adapter's table

For each adapter: the prefix, the known names with their types and where they land, the clash
table, and what pass-through means there. "Refused" means at build, naming the property.

### 9a. OpenAI, chat wire (`OpenAiChatInferenceProvider`, prefix `openai.`)

| known name | type | lands in |
|---|---|---|
| `openai.reasoning.effort` | string | `ChatCompletionCreateParams.Builder.reasoningEffort(ReasoningEffort.of(value))` -- the chat wire's flat `reasoning_effort` field. One name, two wires (§9b), so an agent type switching between them keeps its setting |
| `openai.reasoning.summary` | -- | **refused**: the chat wire has no reasoning-summary field. Message names the property and says `openai-responses` carries it |
| `openai.tools.strict` | boolean | the strict-schema rewrite, §10 |
| `openai.service_tier` | string | `serviceTier(ServiceTier.of(value))` |

Clash table (the typed settings and the fields the adapter fixes): `model`, `messages`,
`max_completion_tokens`, `max_tokens`, `tools`, `tool_choice`, `response_format`, `stream`,
`stream_options`, and the raw spellings `reasoning_effort` and `service_tier` when their known
name is also present.

Pass-through examples that work today with no code beyond the helper: `openai.temperature=0.2`,
`openai.seed=42`, `openai.parallel_tool_calls=false`, `openai.prompt_cache_key=support-bot`,
`openai.store=false`, `openai.metadata.team=billing`. Each has a typed setter on the builder as
well (verified); the adapter does not use them, because the JSON is the same and a typed setter
per field is the table this record is trying not to grow.

### 9b. OpenAI, Responses wire (`OpenAiResponsesInferenceProvider`, prefix `openai.`)

| known name | type | lands in |
|---|---|---|
| `openai.reasoning.effort` | string | `Reasoning.builder().effort(ReasoningEffort.of(value))` on `ResponseCreateParams.Builder.reasoning(...)`; the object is built only when at least one of the two reasoning properties is present, which is the Responses record's §5g made concrete |
| `openai.reasoning.summary` | string | `Reasoning.builder().summary(Reasoning.Summary.of(value))`, same object |
| `openai.tools.strict` | boolean | `true` is what the adapter already does (Responses §5d) and is accepted as a no-op; `false` is **refused**, naming the property and saying the Responses wire is strict regardless -- one rule with §9a's `reasoning.summary`: a known name a wire cannot honour fails at build |
| `openai.service_tier` | string | `serviceTier(ServiceTier.of(value))` |

Clash table: `model`, `input`, `instructions`, `max_output_tokens`, `tools`, `tool_choice`,
`text`, `stream`, and the fields the Responses record fixes on purpose -- `store` (always
`false`), `include` (always the encrypted reasoning), `previous_response_id` and `conversation`
(never sent), `background` -- plus the raw `service_tier`. A property for any of the fixed ones is
refused with the Responses record's reason quoted (§5a: the event log is the only conversation).

### 9c. Anthropic (`AnthropicInferenceProvider`, prefix `anthropic.`)

| known name | type | lands in |
|---|---|---|
| `anthropic.thinking.type` | string: `enabled`, `disabled`, `adaptive` | `MessageCreateParams.Builder.thinking(...)` with `ThinkingConfigEnabled`, `ThinkingConfigDisabled` or `ThinkingConfigAdaptive` (all three verified on 2.65.0). Absent with a budget present means `enabled` |
| `anthropic.thinking.budget_tokens` | integer | `ThinkingConfigEnabled.builder().budgetTokens(value)`. `enabled` without a budget is refused naming both properties, because the vendor requires one; a budget at or above `maxTokens` is refused with the message `AnthropicRequests.toParams` already produces (`maxTokens (%d) must be greater than the thinking budget (%d)`), now at build rather than per call |
| `anthropic.cache_control.ttl` | string: `5m`, `1h` | the `cache_control` markers `AnthropicRequests` places on the system prompt and the tool list -- today's `PromptCaching.FIVE_MINUTES` / `ONE_HOUR`. Absent means off. Known and not pass-through because it is not a request field: it is a marker the adapter places in several positions, with the thinking-block fallback the adapter already handles |
| `anthropic.service_tier` | string | `serviceTier(ServiceTier.of(value))` |

Clash table: `model`, `max_tokens`, `messages`, `system`, `tools`, `tool_choice`, `output_config`
(the adapter's route for the output schema, verified at `AnthropicRequests.askForShape`), `stream`,
and the raw `thinking` object and `cache_control` when a known name is also present.

Pass-through: `anthropic.temperature`, `anthropic.top_k`, `anthropic.top_p`,
`anthropic.stop_sequences=["\n\n"]`, `anthropic.metadata.user_id=...`.

**The typed setters stay -- ruled overnight, against this record's first draft, which deleted
them.** `AnthropicProviderConfig.thinking(boolean)`, `thinkingBudget(int)` and
`promptCaching(PromptCaching)` are configuration of a vendor module, not of the neutral API: they
pollute nothing this record exists to keep clean, and removing them would be a breaking change
nobody asked for. How they relate to the `anthropic.*` properties is then one rule, the tier rule
of §7a:

- **A setter is a provider-level default, at the same tier as a config property.** `thinking(true)
  .thinkingBudget(8192)` on the config and `anthropic.thinking.budget_tokens=8192` under
  `nessy.providers.anthropic.properties` mean the same thing in the same place. Both set on one
  provider -- for the same name, whatever the values -- fail at `build()` naming both (`thinkingBudget(int)`
  and `anthropic.thinking.budget_tokens`), because two provider-level statements about one field
  have no order between them and picking one silently is the alphabet problem again. The table of
  correspondences: `thinking(true)` is `anthropic.thinking.type=enabled` and `thinking(false)` is
  the absence of both thinking names; `thinkingBudget(n)` is `anthropic.thinking.budget_tokens=n`;
  `promptCaching(FIVE_MINUTES)` is `anthropic.cache_control.ttl=5m`, `ONE_HOUR` is `1h`, `OFF` is
  the absence of the name.
- **An agent-type property in code overrides either**, name by name: a provider built with
  `thinking(true).thinkingBudget(1024)` serves a research agent that says
  `property("anthropic.thinking.budget_tokens", "16000")` at sixteen thousand and a triage agent
  that says `property("anthropic.thinking.type", "disabled")` with none. That is the per-agent-type
  need the guide's "two providers" answer was covering for, and it is met without touching the
  setters.
- `AnthropicRequests.Features` becomes the **parsed form of the merged result** -- built per
  request from the setters' values overlaid by the agent type's properties -- rather than a
  per-provider constant. The default budget of `1024` stays as the setters' default, for the
  reason the code gives (headroom under `AgentConfig`'s 4096); the property form has no default,
  because `enabled` without a budget is refused (table above) and the floor is the vendor's to
  state. The `maxTokens`-over-budget check runs at `validate` against the merged result.
- `Block.Provider` round-tripping of thinking blocks (`AnthropicRequests.ours`, the signature rule)
  is untouched: it is about what comes back, not what is asked for.

Whether the setters should later fold into properties -- one spelling, the guide's "Anthropic
features" section retired -- is recorded for James in §16 (4), not decided here.

### 9d. Gemini (`GeminiInferenceProvider`, prefix `gemini.`)

| known name | type | lands in |
|---|---|---|
| `gemini.generationConfig.thinkingConfig.thinkingBudget` | integer | `ThinkingConfig.Builder.thinkingBudget(Integer)` on `GenerateContentConfig.Builder.thinkingConfig(...)` |
| `gemini.generationConfig.thinkingConfig.includeThoughts` | boolean | `ThinkingConfig.Builder.includeThoughts(boolean)` -- what lights the adapter's existing thought-summary narration (`GeminiInferenceProvider` line 253) on a model that only sends thoughts when asked |
| `gemini.generationConfig.thinkingConfig.thinkingLevel` | string | `ThinkingConfig.Builder.thinkingLevel(String)` |

Names are spelled as the Gemini REST reference spells them, `generationConfig` included, because
the pass-through route (`extraBody`) sends the path verbatim into the body and a known name should
read the same as the pass-through beside it. The three are known rather than passed through so
that they go through the SDK's typed config and cannot depend on how `extraBody` merges (§8d).

Clash table: `contents`, `systemInstruction`, `tools`, `toolConfig`,
`generationConfig.maxOutputTokens`, `generationConfig.responseMimeType`,
`generationConfig.responseJsonSchema`, `generationConfig.responseSchema`.

Pass-through: `gemini.generationConfig.temperature`, `gemini.generationConfig.seed`,
`gemini.safetySettings=[...]`, `gemini.cachedContent=...`, `gemini.labels.team=billing` -- subject
to §13d's measurement of the merge.

### 9e. Bedrock (`BedrockInferenceProvider`, prefix `bedrock.`)

Two kinds of field, because the SDK has two kinds (§8d):

| known name | type | lands in |
|---|---|---|
| `bedrock.inferenceConfig.temperature` | number | `InferenceConfiguration.Builder.temperature(Float)` |
| `bedrock.inferenceConfig.topP` | number | `InferenceConfiguration.Builder.topP(Float)` |
| `bedrock.inferenceConfig.stopSequences` | JSON array of strings | `InferenceConfiguration.Builder.stopSequences(Collection<String>)` |

Everything else under `bedrock.` is pass-through into **`additionalModelRequestFields`**, the
model's own document, nested by path. Claude's extended thinking on Bedrock is therefore two
properties and no code: `bedrock.thinking.type=enabled` and `bedrock.thinking.budget_tokens=4096`
become `{"thinking": {"type": "enabled", "budget_tokens": 4096}}`, which is what Amazon's
documentation says to send. The adapter's existing reasoning round-trip (`BedrockRequests.ours`,
the signature rule) then does the rest.

Clash table: `modelId`, `messages`, `system`, `toolConfig`, `inferenceConfig.maxTokens`.

What is **not** reachable, said plainly: a Converse-level field other than the three known ones --
`guardrailConfig`, `performanceConfig`, `serviceTier`, `requestMetadata`, `promptVariables` -- is
a typed field on a typed AWS request, and pass-through goes to the model document, where such a
name would be sent to the model and rejected. Each joins the known table when somebody needs it;
§16 (6) names the first candidate.

### 9f. Embedders

Same contract, smaller tables -- **specified here, built with the third `0.3.0` item.** What lands
now is the door (`EmbedderConfig.property`, §4b), the carrier (`EmbeddingOptions.properties`,
§5b), the hook (`EmbeddingProvider.validate`, §7c) and the config setters (§6a); the adapters read
none of it until the named-embedders record settles where the helper of §8e lives for them, so a
property set on an embedder today is carried and ignored, and the guide says so. Known names:
none, to start; every embedder passes through, and the one typed knob beyond the model
(`dimension`) is in the clash table under its wire spelling.

| adapter | prefix | clash table |
|---|---|---|
| `OpenAiEmbeddingProvider` | `openai.` | `model`, `input`, `dimensions` |
| `GeminiEmbeddingProvider` | `gemini.` | `model`, `contents`, `outputDimensionality`, and `taskType`, which the adapter sets from whether it is embedding documents or a query |
| `BedrockEmbeddingProvider` | `bedrock.` | `inputText`, `texts`, `dimensions`, `normalize`, `input_type`, `truncate` -- the fields the adapter writes for Titan and Cohere (`BedrockEmbeddingProvider` lines 151-165) |
| `VoyageEmbeddingProvider` | `voyage.` | `model`, `input`, `output_dimension`, `input_type` (line 146-150) |

The `voyage.` prefix is new with this table; Voyage is an embedding-only vendor with no inference
adapter, and its module already owns the name.

## 10. `openai.tools.strict` on the chat wire

**Ruling (James, 2026-09-29):** the strict tool-schema rewrite the Responses record specifies in
§5d -- every property listed in `required`, an optional property widened to admit `null`,
`additionalProperties: false` on every object, `$defs` walked, and a per-tool fallback with a
`WARN` naming the tool and the disqualifying keyword -- becomes available on the chat wire behind
`openai.tools.strict=true`. The chat adapter then sends each `FunctionDefinition` with
`strict(true)` over the rewritten schema, exactly as the Responses adapter sends its
`FunctionTool`. Off, the chat adapter sends what it sends today.

- **Default `true` on the `openai` preset**, as that preset's default property (§11). OpenAI proper
  is measured to accept strict mode; the price is paid by the rewrite and the benefit is arguments
  that fit the schema by construction.
- **Off for every other chat-wire preset until measured.** `xai`, `openrouter`, `nvidia`, `groq`,
  `mistral`, `lmstudio`, `ollama` carry no default for it; a compatible server that rejects
  `strict` or the widened types would fail every tool call, and the named-providers rule holds:
  no preset ships an unmeasured value. §13d's live check is what turns a row on.
- **The Responses adapter is strict regardless** (§9b).

The rewrite itself moves out of `OpenAiResponsesRequests` into a package-private helper shared by
both request builders, named under the Responses record's §4b rule for shared helpers --
neutral, no adapter qualifier -- `OpenAiStrictSchemas`. Not a design-authority item: package-private,
and the Responses record already names the rewrite as a private projection.

## 11. Presets' default properties

`Preset` (`spring.boot.inference`) gains a `Map<String, String> defaultProperties` component after
`keylessApiKey`. The catalogue:

| preset | default properties |
|---|---|
| `openai` | `openai.tools.strict=true` |
| every other row | none |

`ProviderCatalogue.resolve` overlays `settings.properties()` on the preset's defaults name by name,
so `nessy.providers.openai.properties.openai.tools.strict=false` turns the default off for a
deployment that points the `openai` preset at a server that dislikes it (the Responses record's
§7c case: `openai.base-url` at an LM Studio), and an agent type's own `property("openai.tools.strict",
"false")` turns it off for one agent type. A preset's defaults are shown in the report (§6c) like
any other provider property, so nobody discovers `strict: true` from a 400.

## 12. Not being done

Each of these was raised on 2026-09-29 and set aside, with the reason:

- **A typed reasoning-effort concept.** None, anywhere -- the Responses record's §8 ruling,
  repeated here because this record is where it bites. What is given up is stated: there is no one
  portable knob that says "think harder" on every vendor, and the engine cannot reason about
  effort -- a turn policy cannot lower it under budget pressure, an observation cannot tag it. A
  typed setting can be layered on later; the day it lands, its wire names join each adapter's clash
  table (§7b) and nothing else moves. Until then `openai.reasoning.effort`,
  `anthropic.thinking.budget_tokens` and `gemini.generationConfig.thinkingConfig.thinkingBudget`
  each mean exactly what their vendor's documentation says, which is more than a shared word could.
- **Config-file "extra body" maps that override code.** James: backwards. Code is where an agent
  type is described; a file that silently changed what the code says would be the alphabet problem
  in a new coat. Config supplies defaults; code decides (§7a).
- **Per-agent untyped options without prefixes** -- `option("temperature", "0.2")`. They leak
  vendor detail into the neutral API without saying whose detail it is, and an agent type carrying
  them cannot switch providers, because nobody can say which vendor `temperature` was meant for.
  The prefix is the whole design.
- **Validating vendor vocabularies** (`effort` levels, `service_tier` values). The vendor's, and
  they change; the adapter checks types only (§8b).
- **Per-call properties.** The provider and the model are facts of the agent type, resolved at
  build (named-providers §9); properties are the same kind of fact, and a per-request map would
  put a merge and a clash check on the path of every inference.
- **Reading properties back from the transcript.** Nothing is written (§5c), so nothing is read.

## 13. Testing

House rules throughout: prose-style test names in each module's existing voice
(`a_known_name_is_parsed_into_its_typed_field`, `an_unknown_name_passes_through_as_a_number`),
`@DisplayName` sentences, **no mocking library** -- the adapter tests keep their JDK proxies and
scripted clients (`OpenAiInferenceProviderTest`'s `Proxy.newProxyInstance`, `GeminiClient.over`,
`BedrockClient.over`), and a request builder is tested by building the request and reading it
back. Every `assertThatThrownBy` lambda holds exactly one call that can throw, with the config, the
map and the options built outside it (S5778); an emptiness assertion precedes any `allMatch` /
`noneMatch`. Everything in §13a-§13c passes with no API key and no network.

### 13a. The helper and each adapter

`VendorPropertiesTest` (`nessy-inference-spi`), one group per function of §8e: entries under a prefix are
returned with the prefix stripped and other prefixes left out; a name with no prefix is refused;
agent-type entries override provider entries by name; `12000` reads as a number, `true` as a
boolean, `{...}` as an object, `[...]` as an array, `high` as a string, `"12345"` as a string;
dotted names nest and two paths deep-merge; a scalar meeting an object is refused naming both; a
clash is refused naming the property and what decides it; a bad integer, boolean or string is
refused naming the property and the value.

Per adapter, in its `*RequestsTest` (the Responses record's `OpenAiChatRequestsTest` and
`OpenAiResponsesRequestsTest`; `AnthropicRequestsTest`, `GeminiRequestsTest`,
`BedrockRequestsTest`), a nested group `Vendor properties`:

- each known name lands in its typed field (`reasoning_effort` set; the `reasoning` object built
  only when asked; `thinking` enabled with the budget; `thinkingConfig` on the config;
  `inferenceConfig.temperature` on the request);
- an unknown name lands in the pass-through slot (`_additionalBodyProperties()` on the two OpenAI
  params and the Anthropic params; `httpOptions().extraBody()` on the Gemini config;
  `additionalModelRequestFields()` on the Bedrock request), nested by path, its value typed by
  the literal rule;
- another prefix's entry is not sent;
- a clash fails at `validate` naming both, for the first entry of each adapter's table and for a
  raw spelling beside its known name;
- a bad value fails at `validate` naming the property;
- an agent-type entry overrides the same name given to the config;
- the wire-cannot-carry cases: `openai.reasoning.summary` on the chat builder;
  `openai.tools.strict=false` on the Responses builder; `anthropic.thinking.type=enabled` without a
  budget; a budget at or over `maxTokens`.

`OpenAiChatRequestsTest` additionally carries the strict cases the Responses record wrote for its
own builder (§9a there), run with `openai.tools.strict=true` and asserted absent without it: every
function tool strict over the rewritten schema, the optional component widened, `$defs` walked,
the per-tool fallback with its `WARN`, the offer's `JsonSchema` unchanged. `OpenAiStrictSchemasTest`
owns the rewrite itself, once, on the hand-written fixtures the Responses record describes.

Each `*ProviderConfigTest` gains: `property` on the config reaches the built provider's requests;
a property under another prefix fails at `build()` naming the prefix; a clash fails at `build()`.
`AnthropicProviderConfigTest` keeps its setter cases and gains the tier cases of §9c: a setter and
a property for the same name fail at `build()` naming both; `thinking(true).thinkingBudget(1024)`
on the config and `anthropic.thinking.budget_tokens=16000` on the agent type send sixteen
thousand; `anthropic.thinking.type=disabled` on the agent type sends no `thinking` object over a
config that turned it on. `AnthropicRequestsTest`'s existing thinking cases
(`enabled_asks_for_a_budget_and_disabled_asks_for_nothing`,
`a_budget_with_no_headroom_under_the_ceiling_is_refused_before_the_call`) stay as they are and
are joined by their property-form twins. The four embedding adapters' tests wait for the third
item (§9f); `DefaultEmbedderFactoryTest` covers the door and the carrier now.

### 13b. Boot

`InferenceProvidersAutoConfigurationTest` (`ApplicationContextRunner`, keys `sk-test`, nothing
sent):

| properties | expected |
|---|---|
| `openai.api-key` | the `openai` provider carries `openai.tools.strict=true` from the preset |
| `openai.api-key`, `nessy.providers.openai.properties.openai.tools.strict=false` | it carries `false` -- the settings overlay the preset |
| `openai.api-key`, `nessy.providers.openai.properties.openai.reasoning.effort=high` | **one** entry keyed `openai.reasoning.effort` -- the dotted-key case, pinning the `Map<String, String>` measurement of §6b |
| the same three keys given as YAML under `properties:` | the same map -- through `withPropertyValues` and a `.yaml` resource both |
| `nessy.providers.openai.properties.openai.model=gpt-4o` | context fails to start; the message names the property and `InferenceConfig.model` |
| `nessy.providers.anthropic.properties.openai.reasoning.effort=high` | context fails to start naming the prefix |
| `xai.api-key` | the `xai` provider carries no properties |
| `nessy.providers.mine.wire=openai-chat`, `base-url`, `api-key`, `properties.openai.temperature=0.2` | the custom provider carries the one entry |

`ProviderCatalogueTest` gains the overlay rows; `ProviderSettingsTest` and `ResolvedProviderTest`
assert the new component prints its names, not its values, and the key still prints as `***`;
`InferenceReportTest`'s expected line gains the `properties [openai.tools.strict]` clause for a
provider that has one, none for one that has none, and a case with `openai.user=tenant-42` set
asserts the report contains `openai.user` and not `tenant-42`.

### 13c. The engine

`DefaultDirectHarnessTest` and the queued door's `TerminationAndConfigurationTest` gain: an agent
type's properties reach
the provider in `InferenceOptions.properties()` (a scripted provider records what it was handed);
factory-default properties seed an agent type and `property` overrides one name; `validate`'s
failure at `create` is re-thrown naming the agent type; `ObservedInferenceProvider` delegates
`validate`. A transcript test asserts, after a turn run with properties, that no event carries any
of them -- the §5c promise made checkable. `DefaultEmbedderFactoryTest` gains the two embedder
cases.

### 13d. Live

- `OpenAiChatLiveTest` gains the strict case the Responses record gives its own live test: a tool
  whose input has an `Optional` component, offered under `openai.tools.strict=true`, is called with
  `null` written for it and binds to `Optional.empty()`; and the sealed-vocabulary tool (the
  generator's `oneOf`) either goes out strict or falls back, and the row records which. The same
  test runs `openai.reasoning.effort=low` against a reasoning model (`NESSY_LIVE_REASONING_MODEL`)
  and asserts the answer arrives, and `openai.temperatur=0.2` (the typo, on purpose) and asserts
  the vendor's 400 is the fault's message -- the pass-through promise made checkable, once, on a
  real wire.
- `OpenAiResponsesLiveTest` runs the reasoning-summary case the Responses record's §9c could not:
  `openai.reasoning.summary=auto` on a reasoning model, and the summary deltas are narrated as
  thinking.
- `AnthropicLiveTest`'s thinking case (`config.thinking(true)`, line 197) stays; beside it, a case
  with a provider that does not think and an options map carrying
  `anthropic.thinking.budget_tokens=1024` asserts the reply carries this vendor's `Provider`
  block, and a case with `anthropic.cache_control.ttl=5m` asserts `cacheWriteTokens` or
  `cacheReadTokens` is reported.
- `GeminiLiveTest` gains `thinkingBudget` through the known name **and** a pass-through under
  `generationConfig` (`temperature`), and asserts the response reflects both -- the measurement §8d
  leaves open. If `extraBody` replaces `generationConfig` rather than merging into it, the Gemini
  adapter nests its own pass-through into the config it builds before calling the SDK, and the
  record is amended to say so.
- `BedrockLiveTest` gains Claude thinking through the two `bedrock.thinking.*` properties and
  asserts a signed reasoning block comes back.
- **The strict check per vendor.** `PresetCandidatesLiveTest`'s `Candidate` gains a set of
  properties, and every chat-wire row is run twice: as today, and with `openai.tools.strict=true`.
  `target/preset-measurements.md` gains a `strict` column -- `OK`, or the vendor's message -- and a
  preset's default property is written from that column and from nothing else (§10).

## 14. Sequencing

Six steps, each a reviewable commit, each green under `./mvnw -q clean verify` with no key and no
network before the next starts. Step 1 waits on the Responses branch beneath this one for the
OpenAI module's names; nothing else does.

1. **The helper and the SPI** (§5, §7c, §8e): `VendorProperties` in `nessy-inference-spi` with
   its test; `InferenceOptions` and `EmbeddingOptions` gain `properties`;
   `InferenceProvider.validate` and `EmbeddingProvider.validate` as no-op defaults;
   `ObservedInferenceProvider` delegates. No behaviour change.
2. **The API and the engine** (§4, §13c): `InferenceConfig.property`, `EmbedderConfig.property`;
   the two `Inference` classes and `DefaultEmbedderFactory.Settings` keep the map; the two
   factories pass it and call `validate`; the harness report line; the engine tests.
3. **The adapters** (§6a, §8, §9, §13a): `property`/`properties` on the five inference configs
   and four embedder configs (the embedder configs carry and ignore, §9f); each inference
   adapter's known names, clash table and pass-through; the Anthropic setters kept and `Features`
   built per request from the merged result, with the setter-versus-property check at `build()`;
   the `OpenAiStrictSchemas` move and `openai.tools.strict` on the chat wire (§10); the adapter
   tests.
4. **Boot** (§6b, §6c, §11, §13b): `ProviderSettings.properties`, `Preset.defaultProperties` with
   the `openai` row, `ResolvedProvider.properties`, the overlay in `ProviderCatalogue`, the four
   `WireProviders` builders, the report; the Boot tests including the dotted-key case.
5. **Live measurement** (§13d): the five live tests' new cases; the strict column in
   `PresetCandidatesLiveTest`; results into `target/preset-measurements.md`; the Gemini merge
   settled and §8d amended if it must be; any preset whose strict row is `OK` gets its default
   property in a commit of its own.
6. **Docs**: `docs/guides/providers.md` -- a "Vendor properties" section, the "Anthropic features"
   section extended with the property form and the tier rule (the setters stay documented), the
   "two providers" paragraph at line 107 retired, each vendor's known names and its clash table
   under its own heading, the "Writing a provider" section carrying the contract of §8 for a new
   adapter; `docs/guides/spring-boot.md` for `nessy.providers.<id>.properties.*` and the
   environment-variable limitation; `CHANGELOG.md` `[Unreleased]` with the breaking lines (the
   `InferenceOptions` and `EmbeddingOptions` components; nothing else breaks); describing what is
   (`docs-describe-what-is`).

## 15. Design authority

Listed by name, as the rule requires. "Agreed" means ruled by James on 2026-09-29. "Accepted
overnight" and "changed overnight" mean this record proposed it to make an agreed thing work and
the controller James authorised for the overnight run ruled on it on 2026-09-30, as proposed or
with the change named; every such row awaits his morning review, and nothing lands before it.
"Rejected overnight" rows are kept so the deletion this record first proposed is visible.

| concept | where | status | § |
|---|---|---|---|
| `InferenceConfig.property(String, String)` | public method | agreed | 4a |
| `EmbedderConfig.property(String, String)` | public method | agreed | 4b |
| `nessy.providers.<id>.properties.<name>=<value>`, bound as `Map<String, String>` | Boot property | agreed | 6b |
| presets carrying default properties; `openai.tools.strict=true` on `openai` | Boot catalogue | agreed | 11 |
| the precedence order and the clash rule | contract | agreed | 7 |
| adapter-owned prefixes `openai.`, `anthropic.`, `gemini.`, `bedrock.`; known names parsed, unknown passed through as JSON literals; other prefixes ignored | adapter contract | agreed | 8, 9 |
| `openai.reasoning.effort`, `openai.reasoning.summary`, `openai.tools.strict`, `openai.service_tier`, `anthropic.thinking.budget_tokens` as known names | property names | agreed | 9a-9c |
| `openai.tools.strict` lighting the strict rewrite on the chat wire | adapter behaviour | agreed | 10 |
| no typed reasoning-effort concept; the rejected shapes of §12 | design vocabulary | agreed | 12 |
| the words **vendor property**, **prefix**, **known name**, **pass-through**, **clash** as §3 defines them | design vocabulary | proposed; not ruled on overnight, for James | 3 |
| `InferenceOptions.properties` and `EmbeddingOptions.properties` as the third record component | public record component | accepted overnight, as proposed | 5 |
| `InferenceProvider.validate(InferenceOptions)` and `EmbeddingProvider.validate(EmbeddingOptions)`, default no-op | public SPI method | accepted overnight, as proposed | 7c |
| `XProviderConfig.property(String, String)` / `properties(Map)` on five inference and four embedder configs | public methods | accepted overnight, as proposed | 6a |
| `VendorProperties`, the shared parsing helper | public type (static helper) | **changed overnight**: not in `nessy-api` (the application-facing insulator); in `nessy-inference-spi` as `org.jwcarman.nessy.inference.VendorProperties`, SPI-only; the embedding side is the third item's | 8e |
| deleting `AnthropicProviderConfig.thinking`, `thinkingBudget`, `promptCaching` and `PromptCaching` | public methods and a public type, removed | **rejected overnight**: vendor-module configuration, not neutral API; an unrequested breaking change. They stay, as a provider-level default at the config-property tier | 9c, 7a |
| a config setter and a config property for one name on one provider fail at `build()` naming both; an agent-type property overrides either | contract | ruled overnight, in place of the deletion | 7a, 9c |
| `anthropic.thinking.type`, `anthropic.cache_control.ttl`, `anthropic.service_tier`; the three `gemini.generationConfig.thinkingConfig.*` names; the three `bedrock.inferenceConfig.*` names; the `voyage.` prefix | known names beyond the ruled ones | accepted overnight, as proposed | 9c-9f |
| a known name a wire cannot carry is refused at build (`openai.reasoning.summary` on chat, `openai.tools.strict=false` on Responses) | contract | accepted overnight, as proposed | 8b, 9a, 9b |
| the report printing property names | Boot report | **changed overnight**: names only, never values -- a value may be sensitive | 6c |

Not new, and needing no yes: `OpenAiStrictSchemas` (package-private), the clash tables' contents
(each is a reading of the adapter's own request builder), the `DEBUG` line for ignored prefixes,
the test names, the live rows, the measurements column.

## 16. Open, for James

1. **The OpenAI module's names on this branch.** The code here still has `OpenAiInferenceProvider`,
   `OpenAiProviderConfig`, the public `OpenAiRequests`, `Wire.OPENAI` and eight presets on it; the
   Responses branch beneath renames them (`OpenAiChatInferenceProvider`, `OpenAiChatProviderConfig`,
   the package-private `OpenAiChatRequests`, `Wire.OPENAI_CHAT`). This record uses the Responses
   names throughout, and step 1 of §14 is written to start after that branch lands. If it lands
   in a different shape, the names here follow it, not the other way round.
2. **The `validate` hook, for the morning review.** Accepted overnight; it is the one new SPI
   method and the record's most consequential proposal, and without it the clash ruling cannot be
   honoured at build. `check` and `accept` were considered and set aside (`accept` sounds like it
   stores something). If James would rather not widen the SPI, the fallback is failing on the
   first request, which the record argues against in §7c.
3. **`VendorProperties` for the embedding adapters.** The overnight ruling put the helper in
   `nessy-inference-spi` and left the embedding side to the third `0.3.0` item. That item chooses
   between `nessy-embedding-spi` depending on the inference SPI for one class, a copy, or a shared
   support module; until it does, embedder properties are carried and ignored (§9f).
4. **Fold the Anthropic setters into properties?** Rejected overnight for this item, and the
   setters stay (§9c) with the tier rule relating them to `anthropic.*`. The question for James is
   whether a later item retires them -- one spelling of "think with 8192 tokens", the guide's
   "Anthropic features" section folded into the vendor-properties section -- or whether a
   vendor module keeping typed sugar beside the property door is the shape every adapter should
   have. The first draft of this record argued for retiring them; the overnight ruling's reason
   against (vendor-module configuration is not neutral API, and the deletion was not asked for)
   stands until he says otherwise.
5. **Gemini's `extraBody` merge is measured in bytecode, not on the wire.** `ApiClient.mergeMaps`
   exists and is called on `extraBody`; whether a pass-through `generationConfig.temperature`
   merges into the SDK's own `generationConfig` or replaces it is §13d's first Gemini case. If it
   replaces, the adapter nests pass-through into its own config first, and the contract of §8c is
   unchanged from the outside.
6. **Bedrock's Converse-level fields.** `performanceConfig` (Amazon's `latency` option),
   `serviceTier`, `guardrailConfig` and `requestMetadata` are unreachable as pass-through (§9e).
   The record lists only what it verified on the builder; `performanceConfig.latency` is the first
   candidate for the known table when a deployment asks for it.
7. **`anthropic.thinking.type=adaptive`.** The SDK has `ThinkingConfigAdaptive`; whether the API
   accepts it on the models the live test uses is unmeasured. The name is in the table because the
   type is in the union; the live test's thinking case uses `enabled`.
8. **Found in the code, at odds with the brief.** (a) The brief called the existing Anthropic
   setting "the thinking setting"; there are three (`thinking`, `thinkingBudget`, `promptCaching`),
   and the caching one is the reason `anthropic.cache_control.ttl` is a known name rather than
   pass-through. (b) The brief said embedders' Boot side would be `nessy.embedders.<id>.*`; today's
   embedding properties are `nessy.embedding.<vendor>.model` / `.dimension` (singular, per vendor,
   `@Value`-injected). Ruled overnight out of scope here: the third item migrates them, and this
   record only notes the difference. (c) The brief's list of typed settings ("model, max tokens,
   tool choice") is three; the request carries five (§1), and the clash tables cover all five plus
   the fields each adapter fixes on purpose.
9. **The report printing values -- closed overnight: names only** (§6c). Recorded because the
   first draft argued the other way (a property is never a credential); the ruling's reason -- a
   pass-through value may be sensitive and the report cannot tell which -- is the stronger one,
   and the names still say what was configured.

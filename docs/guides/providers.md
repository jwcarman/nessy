# Providers

A model provider provides models. `ModelProvider` is the vendor gateway —
an application singleton holding the SDK client, credentials, and
transport for one vendor. It does not run requests itself; it hands out
`Model` handles that do:

```java
public interface ModelProvider extends AutoCloseable {
  Model model(String id);
  default String name() { ... }
  default void close() { }   // a gateway with nothing to release says nothing
}

public interface Model {
  ModelStream stream(ModelRequest request);
  Set<Capability> capabilities();
  String id();
}
```

`.model(id)` binds a cheap, immutable handle to one model id, sharing the
gateway's client. `Model` is what `Nessy.harness(h -> h.model(...))`
consumes — the harness never sees the gateway itself. `capabilities()`
lives on the handle, not the gateway, because it is a per-model fact, not a
vendor-wide guess: a lineup's thinking support, context size, and schema
support vary model to model, even at the same vendor.

Four native gateway modules ship today — `nessy-model-anthropic`,
`nessy-model-openai`, `nessy-model-gemini`, and `nessy-model-bedrock` — and
a fifth, `nessy-model-discovery`, resolves a bound `Model` from whichever of
them is on the classpath, configured by that provider's key. Add the provider
jar you want and set its key; switch providers by swapping the jar.
`OpenAiModelProvider` also reaches every service that speaks OpenAI's wire
protocol, covered below.

All four native gateways are live-validated against their real APIs —
Gemini on 2026-08-15 including the tool-call round trip with real thought
signatures, and Bedrock on 2026-08-16 including the tool round trip through
the ConverseStream bridge.

## One gateway, many handles

One gateway per application; as many `Model` handles as you need. Two
agents on two models is two handles drawn from the same gateway, feeding
two harnesses:

```java
var anthropic = AnthropicModelProvider.fromEnv();

var fast = anthropic.model("claude-haiku-4-5");
var strong = anthropic.model("claude-opus-5");

var triageHarness = Nessy.harness(h -> h.model(fast).systemPrompt(triagePrompt));
var reviewHarness = Nessy.harness(h -> h.model(strong).systemPrompt(reviewPrompt));
```

No model string threads through a `ModelRequest` — the request describes
the turn (context, system prompt, max tokens, tools, requested
capabilities, response schema), and the handle it is sent to already knows
which model runs it.

## Building a gateway directly

Each provider module builds a `ModelProvider` the same way — a static
`create(ProviderCustomizer)` factory over a config, not a builder:

```java
ModelProvider anthropic = AnthropicModelProvider.create(c -> c.apiKey(key));
```

```java
ModelProvider openai = OpenAiModelProvider.create(c -> c.apiKey(key));
```

```java
ModelProvider gemini = GeminiModelProvider.create(c -> c.apiKey(key));
```

```java
ModelProvider bedrock = BedrockModelProvider.create(c -> c.region(Region.US_EAST_1));
```

Each also ships a `fromEnv()` static — the blessed one-call shape,
equivalent to `create(config -> config.fromEnv())` — that delegates to that
provider's own seam-integrity read of the environment:

```java
ModelProvider anthropic = AnthropicModelProvider.fromEnv();
Model claude = anthropic.model("claude-sonnet-5");
```

For Anthropic and OpenAI this resolves to the underlying SDK's own
environment resolution (`ANTHROPIC_BASE_URL`, `ANTHROPIC_AUTH_TOKEN`, and
the rest of what each SDK understands); for Gemini, `fromEnv()` reads
`GEMINI_API_KEY` then `GOOGLE_API_KEY` itself, rather than delegating to the
SDK's own resolution; for Bedrock, `fromEnv()` uses the AWS SDK's own
default credentials chain (env vars, shared profile files, container/instance
metadata) and resolves the region by reading `AWS_REGION` then, if unset,
`AWS_DEFAULT_REGION` itself — see [Bedrock](#bedrock) below. Reach for
`create(...)` directly whenever one of those matters; `nessy-model-discovery`,
below, only ever reads the API key (and, for OpenAI, `OPENAI_BASE_URL`).

## Discovery: the provider follows the classpath

`nessy-model-discovery` depends on no provider module. Each provider module
registers a `ModelProviderBootstrap` through `java.util.ServiceLoader`;
discovery loads every registration on the classpath, asks each to bootstrap
from the environment, and hands back the one that applies:

```java
Model model = ModelDiscovery.fromEnv();
```

Two steps, then, and the first is the one that used to be hidden:

1. **Add the provider jar.** `nessy-model-anthropic`, `nessy-model-openai`,
   or `nessy-model-gemini` — one, or more than one if you mean to switch
   between them.
2. **Set its key.** `ANTHROPIC_API_KEY`; `OPENAI_API_KEY` (plus
   `OPENAI_BASE_URL` for a compatible endpoint — see
   [The OpenAI-compatible universe](#the-openai-compatible-universe));
   `XAI_API_KEY` (Grok, via the OpenAI module); `GEMINI_API_KEY` or
   `GOOGLE_API_KEY`.

Three outcomes and nothing in between:

- **None** of the registered providers finds its key → `IllegalStateException`
  listing, per provider on the classpath, its name and the variables it
  reads — `anthropic [ANTHROPIC_API_KEY]; openai [OPENAI_API_KEY, OPENAI_BASE_URL]; xai [XAI_API_KEY]`.
  Only providers actually present are named. No provider module at all is a
  different message, naming the three modules that register one.
- **One** finds its key → chosen, silently. `NESSY_PROVIDER` is ignored here
  whatever it says: it exists to break ties, and one candidate has none.
- **Two or more** find their keys → `NESSY_PROVIDER` (`anthropic`/`openai`/
  `xai`/`gemini`, case-insensitive) naming one of them chooses it silently.
  Anything else — unset, or naming a provider that did not bootstrap — fails
  with `IllegalStateException` naming every candidate: two providers that
  both bootstrap means you shipped two jars and set two keys, and that
  ambiguity is a configuration error, not something to resolve with a log
  line nobody reads.

Each gateway is built the same way its own module builds one from an
explicit key — `Provider.create(c -> c.apiKey(key))`, not that provider's
own `fromEnv()`. The key discovery saw is the key that gets built, and no
other SDK-level environment variable is read underneath it. Construct the
gateway directly when one of those matters.

**Bedrock is not discovered.** It registers no bootstrap, so it never enters
the candidate list — see [Bedrock](#bedrock) below for why, and for the one
line that constructs it.

### Picking a model too — `select()`

`fromEnv()` returns only the bound `Model`. `select()` returns a
`Selection` — the gateway, the model handle, and the winning provider's
registered name (`"anthropic"`/`"openai"`/`"xai"`/`"gemini"`, the same
vocabulary `NESSY_PROVIDER` accepts) — so an application that wants to show
or log what was picked doesn't re-derive it via `instanceof`:

```java
try (ModelDiscovery.Selection selection = ModelDiscovery.select()) {
    Model model = selection.model();
    String vendor = selection.providerName();
    // ... run the harness ...
}
```

**A `Selection` is `AutoCloseable`, and a long-running process should use
it that way.** A `ModelProvider` is `AutoCloseable` too: it owns an SDK
client, a connection pool, and the threads that service it, and every vendor
SDK here has a `close()`. Discovery *builds* that gateway, so the selection
carries it and closing the selection closes it. `close()` defaults to a
no-op, so a gateway holding nothing — or a test double — needs none of its
own, and a gateway handed a client through its config's `client(...)` door
never closes it: it did not open it.

`fromEnv()` hands back a bare `Model` with nothing to close, and therefore
keeps its gateway for the life of the process. That is right for a CLI and
for a process that builds exactly one; anything longer-lived wants
`select()`. In Spring Boot, `nessy-spring-boot-starter` registers the
selection as a bean with `destroyMethod = "close"`, so the container does it.

The model comes from `NESSY_MODEL` when that variable is set and non-blank —
it wins outright, whichever provider was chosen. That is the one way to name
a model whose gateway can't reveal it on its own: a Grok, OpenRouter, or LM
Studio model reached through `OpenAiModelProvider`'s base-url override looks,
by type, exactly like an OpenAI model. Without `NESSY_MODEL`, the winner's
own default applies: Anthropic's `claude-haiku-4-5-20251001`, OpenAI's
`gpt-4o-mini`, xAI's `grok-4.6`, Gemini's `gemini-3.6-flash`.

`ApprovalPlayground` (`nessy-agent`'s test sources, an IDE-run tinker door)
is this in practice: one `main`, no `if` branch for which provider to
import, because discovery already decided both the vendor and the model:

```java
ModelDiscovery.Selection selection;
try {
    selection = ModelDiscovery.select();
} catch (IllegalStateException e) {
    System.out.println(e.getMessage());
    System.exit(1);
    return;
}

var harness = Nessy.harness(h -> h.model(selection.model()).systemPrompt("You are a terse assistant."));
```

### Writing your own provider

A provider module joins discovery by implementing `ModelProviderBootstrap`
(in `nessy-spi`) and registering it. Do this **only** when the presence of
your credentials in the environment signals intent to use you — a vendor API
key, not an ambient cloud identity. Bedrock is the worked example of a
provider that must not register: AWS credentials are on far too many
machines to mean "talk to Bedrock".

```java
public final class AcmeModelProviderBootstrap implements ModelProviderBootstrap {

  @Override
  public String name() {
    return "acme";
  }

  @Override
  public Set<String> environmentVariables() {
    return Set.of("ACME_API_KEY");
  }

  @Override
  public String defaultModelId() {
    return "acme-small";
  }

  @Override
  public Optional<ModelProvider> bootstrap(Map<String, String> env) {
    Objects.requireNonNull(env, "env must not be null");
    var key = env.get("ACME_API_KEY");
    return key == null ? Optional.empty() : Optional.of(AcmeModelProvider.create(c -> c.apiKey(key)));
  }
}
```

Then one line in `src/main/resources/META-INF/services/org.jwcarman.nessy.spi.model.ModelProviderBootstrap`:

```
com.acme.nessy.AcmeModelProviderBootstrap
```

The class is `public final` with a public no-arg constructor — `ServiceLoader`
needs both. Read only the `env` map you are handed, never `System.getenv()`;
return empty for an absent key and throw for a present key you cannot honour.
Write one test that `ServiceLoader.load(ModelProviderBootstrap.class)` finds
your class: the services file is a resource, and a typo in it fails at runtime
with no compiler to notice. `name()` is lowercase, non-blank, and unique
across the classpath — discovery fails fast at startup, naming your class, if
it is not.

## Retrying: `RetryingModel`

Wrappers rebase one level down, on the thing that actually runs requests —
the model handle, not the gateway. `RetryingModel` retries the *opening* of
a model stream, with exponential backoff:

```java
Model resilient = RetryingModel.wrap(claude, RetryPolicy.defaults(), AnthropicModelProvider.RETRYABLE);

var harness = Nessy.harness(h -> h.model(resilient).systemPrompt(prompt));
```

Only the initial `stream()` call is retried — once events flow, tokens have
already been fed downstream, and a mid-stream failure propagates rather
than transparently re-calling and replaying the turn from the top. Which
failures are retryable is vendor-specific (a 429 is not an auth error), so
each vendor module publishes its own predicate —
`AnthropicModelProvider.RETRYABLE` above, `OpenAiModelProvider.RETRYABLE`
for the OpenAI-compatible universe.

## Gemini

`nessy-model-gemini` is a native `ModelProvider` on Google's own
[java-genai](https://github.com/googleapis/java-genai) SDK, talking to the
Gemini Developer API via a plain API key (Vertex AI auth is out of scope for
v1):

```java
ModelProvider provider = GeminiModelProvider.create(c -> c.apiKey(key));
```

```java
ModelProvider provider = GeminiModelProvider.fromEnv();
```

`fromEnv()` reads `GEMINI_API_KEY`, then — if unset — `GOOGLE_API_KEY`,
Google's own documented pair, in that order. `.baseUrl(String)` overrides
the endpoint for proxies, gateways, or Gemini-compatible services. Model
names are the Gemini Developer API's own, e.g. `gemini-3.6-flash` or
`gemini-2.5-pro`.

Capabilities in v1: text and tool calls, including parallel tool calls in
one turn, plus usage reporting. Thinking output is not yet mapped — Gemini's
`thought`-flagged parts are dropped rather than translated.

Tool calls carry real continuity: the stream captures each function call's
`thoughtSignature` and the request builder replays it verbatim on the next
turn. A history with no stored signature — one predating this capture, or
authored by another vendor in a mixed setup — replays with Google's own
documented skip-validation sentinel instead of failing the call, at the cost
of degraded reasoning continuity for that one call only.

!!! note "Live-validated"
    The Gemini mapping, including the signature capture/replay above, passed
    the live suite — a real conversation and tool round trip against the
    Gemini Developer API on `gemini-3.6-flash` — on 2026-08-15. Rerun it
    yourself anytime:
    `GEMINI_API_KEY=... ./mvnw test -Dnessy.excludedGroups= -pl
    nessy-model-gemini`.

## Bedrock

`nessy-model-bedrock` is a native `ModelProvider` on the AWS SDK for Java
v2's `bedrockruntime` client, talking to Amazon Bedrock's unified
Converse/ConverseStream API — one nessy provider covers Claude, Nova, Llama,
Mistral, and the rest of the Bedrock catalog, since Converse is
model-agnostic on the wire (`InvokeModel*`'s per-model JSON bodies are out
of scope, deliberately: they re-fragment exactly what Converse unified):

```java
ModelProvider provider = BedrockModelProvider.create(c -> c.region(Region.US_EAST_1));
```

```java
ModelProvider provider = BedrockModelProvider.fromEnv();
```

`fromEnv()` uses the AWS SDK's own default credentials provider chain —
env vars, shared profile/credentials files, container/instance metadata —
the AWS idiom of ambient credentials, the same reason there is no
`.apiKey(...)` on this config at all. Only the **region** is resolved
directly rather than delegated to the SDK's own region chain: `AWS_REGION`
first, then `AWS_DEFAULT_REGION` if that is unset — Amazon's own documented
pair. An explicit `.region(...)` set alongside `.fromEnv()` still wins;
neither variable set fails fast the instant the customizer returns, with an
`IllegalStateException` naming both. `.credentialsProvider(AwsCredentialsProvider)`
overrides the credentials chain outright, and `.client(BedrockRuntimeAsyncClient)`
is the escape hatch for a fully preconfigured async SDK client.

**Close ownership is not symmetric.** `BedrockModelProvider` is
`AutoCloseable` — the real client holds Netty resources (an event-loop
group, a connection pool) that outlive one model handle's `stream()` call.
Closing the gateway closes that client only when the gateway built it
itself (the `region`/`credentialsProvider`/`fromEnv()` path); a client
handed in via `.client(...)` is the caller's own to close, on whatever
lifecycle the caller built it against — the gateway never closes it, since
it never opened it either.

**Not discovered.** `nessy-model-bedrock` registers no
`ModelProviderBootstrap`, so `ModelDiscovery` never sees it — not by key,
not by classpath presence, not as a tiebreak participant. This is deliberate:
AWS credentials (and, on some platforms, `AWS_REGION` itself — Lambda sets it
automatically) are ambient on a large fraction of machines, so any mechanism
that let their presence choose Bedrock would silently route an application
with a stray AWS profile to it. An application that wants Bedrock says so in
code:

```java
Model model = BedrockModelProvider.fromEnv().model("us.anthropic.claude-haiku-4-5-20251001-v1:0");
```

`us.anthropic.claude-haiku-4-5-20251001-v1:0` is the `us` cross-region
inference profile id for Claude Haiku 4.5 — a documented starting point, not
a default, since there is no bootstrap to hold one.

Capabilities in v1: text and tool calls, including parallel tool calls in
one assistant turn (Converse already streams several `toolUse` content
blocks per turn, each on its own `contentBlockIndex`). Thinking output is
not yet mapped — `ThinkingBlock`/`RedactedThinkingBlock` are dropped on
replay, the same discipline Gemini's own unadvertised capabilities document.

!!! note "Live-validated"
    The Bedrock mapping passed the live suite — a real conversation and a
    real tool round trip through the ConverseStream bridge against Amazon
    Bedrock — on 2026-08-16, on the default model id
    (`us.anthropic.claude-haiku-4-5-20251001-v1:0`, the `us` cross-region
    inference profile for Claude Haiku 4.5). Offline mapping tests
    (request/response translation, the async-to-blocking bridge, stop-reason
    and usage tables) run entirely against hand-built SDK fixtures and a
    hand-rolled async-client fake — no mocking library, no network. Rerun the
    live suite anytime — `AWS_ACCESS_KEY_ID` is the gate the suite itself
    checks, so it, not `AWS_REGION` alone, is what opts the tests in:

    ```sh
    AWS_ACCESS_KEY_ID=... AWS_SECRET_ACCESS_KEY=... AWS_REGION=us-east-1 \
      ./mvnw test -Dnessy.excludedGroups= -pl nessy-model-bedrock
    ```

## The OpenAI-compatible universe

`OpenAiModelProvider` + a base URL + a key is, itself, an integration: every
service below speaks the same OpenAI chat-completions wire protocol, so no
provider-specific module exists or is needed for any of them. Nessy
validates against OpenAI proper — a compatible endpoint is the vendor's
compatibility promise, not ours.

**Grok** (xAI ships no official Java SDK; its API is deliberately
OpenAI-compatible):

```java
ModelProvider provider =
    OpenAiModelProvider.create(c -> c.apiKey(key).baseUrl("https://api.x.ai/v1"));
```

`XAI_API_KEY` is a first-class discovery citizen — with `nessy-model-openai`
on the classpath, set it alone and `ModelDiscovery.fromEnv()` wires Grok with
no other code.

**OpenRouter** (validated live 2026-08-16 against `openai/gpt-4o-mini`: a
streamed text turn and an approval-gated tool round trip; note OpenRouter
model ids are vendor-prefixed slugs, so set `NESSY_MODEL`, and cached-token
counts may read zero since usage passthrough varies by upstream model):

```java
ModelProvider provider =
    OpenAiModelProvider.create(c -> c.apiKey(key).baseUrl("https://openrouter.ai/api/v1"));
```

**Groq** (validated live 2026-08-16 — the chip company with the LPU
inference silicon, no relation to xAI's Grok — serving open-weight models
at extreme speed; `openai/gpt-oss-120b`: a streamed text turn and an
approval-gated tool round trip, with time-to-first-token in the tens of
milliseconds. Keys are `gsk_...` from console.groq.com. One field-tested
quirk: a freshly minted key can intermittently 401 for a few minutes while
it propagates across their edge — the failed request won't even appear in
their logs; just retry):

```java
ModelProvider provider =
    OpenAiModelProvider.create(c -> c.apiKey(key).baseUrl("https://api.groq.com/openai/v1"));
```

**NVIDIA NIM** (validated live 2026-08-16 against the free open-weight
`nvidia/nemotron-3.5-lightning-30b-a3b` on NVIDIA's free developer tier: a
streamed text turn and an approval-gated tool round trip — a no-cost model
driving the whole loop; keys are `nvapi-...` from build.nvidia.com, model
ids are NVIDIA's catalog ids):

```java
ModelProvider provider =
    OpenAiModelProvider.create(c -> c.apiKey(key).baseUrl("https://integrate.api.nvidia.com/v1"));
```

**Ollama** (local, no key required — any non-empty string works; validated
live 2026-08-16 against `qwen3.6`: a streamed text turn and an
approval-gated tool round trip. Honest performance note: on the same
Apple-Silicon machine and model family, LM Studio's MLX engine was notably
faster than Ollama's GGUF serving — both work, one waits):

```java
ModelProvider provider =
    OpenAiModelProvider.create(c -> c.apiKey("ollama").baseUrl("http://localhost:11434/v1"));
```

**LM Studio** (local; validated 2026-08-15 against two models —
`google/gemma-4-e4b` and `qwen/qwen3.6-35b-a3b` — both a streamed text turn
and a tool-call round trip, on LM Studio's OpenAI-compatible endpoint):

```java
ModelProvider provider =
    OpenAiModelProvider.create(c -> c.apiKey("lm-studio").baseUrl("http://127.0.0.1:1234/v1"));
```

Note the `/v1` suffix on the base URL — the OpenAI SDK does not append it
itself.

`OPENAI_BASE_URL`, set alongside `OPENAI_API_KEY`, makes any of these a
zero-code env citizen too: `ModelDiscovery.fromEnv()` layers it onto the
OpenAI gateway exactly as shown above, the same way it wires Grok.

### Anthropic-compatible endpoints

LM Studio also speaks Anthropic's Messages dialect, and `AnthropicModelProvider`
was validated against it the same date, same two models, same coverage
(streamed text and a tool-call round trip):

```java
ModelProvider provider =
    AnthropicModelProvider.create(c -> c.apiKey("lm-studio").baseUrl("http://127.0.0.1:1234"));
```

!!! warning "The base URL is not symmetric with the OpenAI path"
    The Anthropic Java SDK's default base URL is the bare origin
    (`https://api.anthropic.com`, no `/v1`) — it appends `/v1/messages`
    itself. Passing `http://127.0.0.1:1234/v1` here, by analogy with the
    OpenAI example above, produces a `.../v1/v1/messages` double path that
    fails. Use the bare origin for `AnthropicModelProvider.baseUrl(...)`,
    and keep the `/v1` suffix for `OpenAiModelProvider.baseUrl(...)`.

## Running `ApprovalPlayground`

`ApprovalPlayground` builds its model choice from `ModelDiscovery.select()`,
and `nessy-agent`'s test classpath carries every keyed provider, so any of
the env setups above just works — set the key and run the class's `main`
from an IDE (it carries no `@Test` methods, so surefire never picks it up):

```console
$ GEMINI_API_KEY=... # then run ApprovalPlayground.main from the IDE
```

xAI has no small/cheap alias, so `select()`'s built-in Grok default may not
be the model you want — name one explicitly with `NESSY_MODEL`, the same
override described above:

```console
$ XAI_API_KEY=... NESSY_MODEL=<your-grok-model> # then run ApprovalPlayground.main
```

The same `NESSY_MODEL` override reaches any OpenAI-compatible local runtime
wired through `OPENAI_BASE_URL` — LM Studio, for instance, once a model is
loaded there:

```console
$ OPENAI_API_KEY=lm-studio OPENAI_BASE_URL=http://127.0.0.1:1234/v1 \
    NESSY_MODEL=<loaded-model> # then run ApprovalPlayground.main
```

Whichever provider answers, the wait itself looks the same: type an
observation, and the moment the model asks to restart something,
`ApprovalPlayground`'s own approver defers and prints `[parked] restart
<target>` before returning control to the prompt. Nothing blocks — the
turn is genuinely suspended on a durable computation, not a spinner — so
the same terminal is still free to type `approve` or `deny <reason>`,
which resolves the ticket at the front of the queue and lets the turn's
reply print once the tool runs.

## Where next

- [Getting Started](getting-started.md) — the smallest harness, model swap
  included.
- [The harness guide](harness.md) — the door a `Model` handle feeds.
- [Observability](observability.md) — narrating what a model handle's calls
  actually do, turn by turn.
- [Durable Computation](../concepts/durable-computation.md) — what a
  `Harness` built from a model handle actually gives you.

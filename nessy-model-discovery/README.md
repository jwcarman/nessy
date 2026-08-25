# Nessy Model Discovery

The provider follows the classpath. This module depends on no provider
module: each of `nessy-model-anthropic`, `nessy-model-openai`, and
`nessy-model-gemini` registers a `ModelProviderBootstrap` through
`java.util.ServiceLoader`, and `ModelDiscovery` loads whatever registrations
it finds, asks each to bootstrap from the environment, and hands back the one
that applies. An application chooses which SDKs ride its classpath by
choosing which provider jars it adds, then configures the one it chose with
its key.

Two entry points, both reading the real process environment:

```java
Model model = ModelDiscovery.fromEnv();
```

```java
ModelDiscovery.Selection selection = ModelDiscovery.select();
Model model = selection.model();
String vendor = selection.providerName();   // "anthropic", "openai", "xai", "gemini"
```

## Three outcomes

| Registered providers that find their key | Result |
|---|---|
| none | `IllegalStateException` listing each registered provider and the variables it reads, e.g. `anthropic [ANTHROPIC_API_KEY]; openai [OPENAI_API_KEY, OPENAI_BASE_URL]`. Only providers on the classpath are named. |
| one | chosen, silently — `NESSY_PROVIDER` is ignored, since one candidate is no tie |
| two or more | `NESSY_PROVIDER` naming one of them (case-insensitive) chooses it silently; anything else fails naming every candidate |

No provider module on the classpath at all is its own message, naming the
three that register one.

## The variables

| Variable | Read by |
|---|---|
| `ANTHROPIC_API_KEY` | `anthropic` |
| `OPENAI_API_KEY`, `OPENAI_BASE_URL` (optional) | `openai` |
| `XAI_API_KEY` | `xai` — Grok on OpenAI's wire protocol, in `nessy-model-openai` |
| `GEMINI_API_KEY`, then `GOOGLE_API_KEY` | `gemini` |
| `NESSY_PROVIDER` | breaks a tie between two or more of the above |
| `NESSY_MODEL` | names the model, winning over the chosen provider's default |

Each provider is built the way its own module builds one from an explicit
key — `Provider.create(c -> c.apiKey(key))` — never that provider's own
`fromEnv()`, so the key discovery saw is the key that gets built and no other
SDK-level variable is read underneath it.

## Bedrock

`nessy-model-bedrock` registers nothing and is never discovered. AWS
credentials are ambient on too many machines to mean "talk to Bedrock";
construct it directly: `BedrockModelProvider.fromEnv().model("...")`.

## Testing

Offline, entirely: the public doors read the real environment, but every
test drives the package-private `select(Map<String, String>, Iterable<ModelProviderBootstrap>)`
seam with hand-written fakes and no provider module on the classpath. A
handful of tests go through the real `ServiceLoader` against a registration
in this module's own test resources, proving the wiring.

## Writing your own

See "Writing your own provider" in
[`docs/guides/providers.md`](../docs/guides/providers.md).

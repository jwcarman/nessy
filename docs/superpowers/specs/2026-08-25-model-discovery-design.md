# Model provider discovery — the provider follows the classpath

**Date:** 2026-08-25
**Status:** draft for review
**Amends:** `2026-08-15-nessy-console-design.md` §4a (the non-optional
dependency premise and the no-reflection clause); `2026-08-16-bedrock-provider-design.md`
§4 (explicit-only selection, superseded by non-registration);
`2026-08-15-provider-expansion-design.md` §3 (the precedence table).

`nessy-model-env` exists so that an application switches model vendors by
switching an environment variable. It achieves that by depending on every
provider module non-optionally, which means a hello-world agent that talks to
Anthropic compiles against the AWS SDK, Google's genai stack, protobuf, grpc,
guava, and OpenAI's client. This spec inverts the mechanism: **classpath
presence is the discovery signal; the environment is configuration.** A
provider module that ships on the classpath registers itself; the discovery
module depends on none of them.

## 1. Why now

Measured on `nessy-examples/hello`'s compile classpath at `ee038469`, after
the codec bump removed Spring:

| Origin | Jars |
|---|---|
| AWS SDK, via `nessy-model-bedrock` | 26 |
| Google genai, guava, protobuf, grpc, opencensus, via `nessy-model-gemini` | 19 |
| Anthropic SDK plus Kotlin stdlib | 10 |
| OpenAI SDK | 3 |
| Nessy | 9 |
| everything else (Jackson, victools, micrometer, JUnit, logback, continuum, codec) | ~32 |
| **total** | **99** |

Sixty-two of those trace to one line in `nessy-model-env/pom.xml`. With
`hello` depending on `nessy-model-anthropic` directly, the tree is 37. The
`agent`/`api`/`spi` split — the first suspect — contributes nothing to the
count: collapsing all three would remove zero third-party jars.

The design premise being amended is stated in the console design §4a:

> A micro-module depending on BOTH provider modules non-optionally (its whole
> point: both on the classpath so either key just works). … No reflection, no
> Spring, ~twenty lines plus javadoc.

That premise was right for two providers and a demo. It does not survive four
providers, two of which carry twenty-plus transitive jars each, in the module
every quick-start snippet tells a new user to add.

## 2. Scope

- **One new SPI type**, `ModelProviderBootstrap`, in
  `org.jwcarman.nessy.spi.model`, discovered through `java.util.ServiceLoader`.
- **`nessy-model-env` becomes `nessy-model-discovery`**, depending on
  `nessy-spi` alone.
- **Three provider modules register**: `nessy-model-anthropic`,
  `nessy-model-openai` (twice — OpenAI and xAI), `nessy-model-gemini`.
- **`nessy-model-bedrock` registers nothing.** Its env path retires.
- **Docs, examples, and tests follow the rename**, and every document that
  promises "switching provider is just an env var" is corrected to the new
  premise: switching provider is swapping a dependency, then setting its key.

Not in scope: Spring auto-configuration; any change to `ModelProvider` or
`Model` beyond one javadoc sentence; dialect-style detection of *which*
provider a key belongs to (a key's shape is not a contract).

## 3. `ModelProviderBootstrap`

```java
package org.jwcarman.nessy.spi.model;

public interface ModelProviderBootstrap {

  /** The vocabulary token {@code NESSY_PROVIDER} accepts for this provider, e.g. {@code "anthropic"}. */
  String name();

  /** Every environment variable {@link #bootstrap} reads, for the no-credentials message. */
  Set<String> environmentVariables();

  /** The model id used when {@code NESSY_MODEL} is unset or blank. */
  String defaultModelId();

  /**
   * A gateway built from {@code env}, or empty when this provider's credentials are not present.
   */
  Optional<ModelProvider> bootstrap(Map<String, String> env);
}
```

Four members and no more. What is deliberately absent:

- **No ordering member.** Two candidates is an error (§4), so nothing needs
  a deterministic "first". `ServiceLoader` iteration order is
  classpath-dependent; this design never depends on it.
- **No "explicit-only" or "opt-out" member.** Implementing the interface *is*
  the declaration. There is no way to register for discovery while asking not
  to be discovered, so there is no flag to set wrong.

**The rule the javadoc must carry, in these terms:** a provider implements
this interface only when **the presence of its credentials in the environment
signals intent to use it**. `ANTHROPIC_API_KEY` in an environment means
someone meant to talk to Anthropic. Ambient AWS credentials mean someone once
deployed something to AWS. The test is not "can this provider be built from
the environment" — `BedrockModelProvider.fromEnv()` can — it is "does the
credential's presence mean the user chose this provider." A provider whose
credentials are ambient does not implement this interface, and its users
construct it explicitly.

**Contracts:**

- `name()` is lowercase, non-blank, and unique across every registered
  bootstrap on a classpath. Two registrations sharing a name is a
  configuration error and fails at discovery (§4).
- `environmentVariables()` names every variable `bootstrap` reads, including
  optional ones such as `OPENAI_BASE_URL`, so the failure message can list
  exactly what was checked.
- `bootstrap(env)` returns empty when — and only when — its credentials are
  absent. It must not throw for absent credentials. It may throw for
  *malformed* configuration (a present key plus an unparseable base URL), and
  such an exception propagates: a present key is intent, and intent that
  cannot be honoured is an error, not a silent skip.
- `bootstrap(env)` reads **only** `env`. It never consults
  `System.getenv()` directly, so the choice the discovery module makes from
  the map it was handed is the choice that gets built — the existing
  offline-testing seam, preserved.
- Implementations are stateless and cheap to construct; `ServiceLoader`
  instantiates them on every discovery call.

**Registration** is the standard file,
`META-INF/services/org.jwcarman.nessy.spi.model.ModelProviderBootstrap`, in
each provider module's main resources. No `module-info.java` — the project is
anti-JPMS and `ServiceLoader` works from the classpath file alone.

### On "no reflection"

§4a said "no reflection, no Spring." `ServiceLoader` instantiates registered
classes through their public no-arg constructor — reflection in the JDK's
narrowest sense. It is not the thing the no-reflection rule exists to forbid:
it does not inspect user types, walk fields, or derive behaviour from
annotations, and this repository already removed a reflection-based feature
once (the 2026-08-16 ruling) for exactly those reasons. The rule's spirit —
Nessy's behaviour is stated in code you can read, not discovered from
structure — holds. §4a's clause is amended to say so rather than quietly
contradicted.

## 4. Selection

The discovery module's one public class replaces `EnvModelProviders`. Its
name is a sign-off item (§11); this spec calls it `ModelDiscovery`. Same two
doors as today — `fromEnv()` returning a bound `Model`, and `select()`
returning a `Selection(Model model, String providerName)` — plus the same
package-private `Map<String, String>` seams for offline tests.

`select(env)`:

1. **Load** every `ModelProviderBootstrap` via `ServiceLoader`. Zero
   registrations is an `IllegalStateException`:
   *"no model provider modules are on the classpath: add one of
   nessy-model-anthropic, nessy-model-openai, or nessy-model-gemini (or
   construct a provider directly)"*. A duplicate `name()` is an
   `IllegalStateException` naming the duplicated token and both implementing
   classes.
2. **Bootstrap** each against `env`, collecting the non-empty results as
   candidates, each paired with its bootstrap.
3. **Zero candidates** is an `IllegalStateException` listing, per registered
   provider, its name and `environmentVariables()`:
   *"no model provider credentials found — registered providers and the
   variables they read: anthropic [ANTHROPIC_API_KEY]; openai
   [OPENAI_API_KEY, OPENAI_BASE_URL]; xai [XAI_API_KEY]"*. Only providers
   actually on the classpath are named; a user with one jar is told about
   one variable.
4. **One candidate** wins, silently.
5. **Two or more candidates**: `NESSY_PROVIDER`, lowercased, is compared to
   each candidate's `name()`. A match wins silently. No match — unset,
   blank, or naming something that did not bootstrap — is an
   `IllegalStateException`:
   *"multiple model providers can bootstrap from this environment (anthropic,
   openai) — set NESSY_PROVIDER to one of them to choose"*, with
   *"(NESSY_PROVIDER=gemini names none of them)"* appended when a value was
   set. **This replaces today's WARN-and-default.** Under classpath-first,
   two providers that both bootstrap means the application deliberately
   shipped two provider jars and set two keys; ambiguity is a configuration
   error, and a log line nobody reads is the wrong place to resolve it.
6. **Model id**: `NESSY_MODEL`, when set and non-blank, wins outright;
   otherwise the winner's `defaultModelId()`. Applied here, once, so the
   precedence rule has one owner rather than one copy per provider.
7. Return `new Selection(provider.model(modelId), bootstrap.name())`.

`NESSY_PROVIDER` when exactly one candidate exists is **ignored**, whatever
it says. Naming a provider that is not the one that bootstrapped is a
plausible sign of misconfiguration, but failing there would make a
single-provider deployment break because a stale variable survived from a
previous one. One candidate is unambiguous; the variable exists to break
ties.

The `grok` alias for `xai` retires. It was a convenience on a five-token
vocabulary; the vocabulary is now whatever registered, and an alias table
would be a second source of truth for names the bootstraps already own.

## 5. What each provider registers

| Module | `name()` | `environmentVariables()` | `defaultModelId()` | Bootstraps when |
|---|---|---|---|---|
| `nessy-model-anthropic` | `anthropic` | `ANTHROPIC_API_KEY` | `claude-haiku-4-5-20251001` | key present |
| `nessy-model-openai` | `openai` | `OPENAI_API_KEY`, `OPENAI_BASE_URL` | `gpt-4o-mini` | `OPENAI_API_KEY` present; base URL layered on when present |
| `nessy-model-openai` | `xai` | `XAI_API_KEY` | `grok-4.6` | key present; base URL fixed at `https://api.x.ai/v1` |
| `nessy-model-gemini` | `gemini` | `GEMINI_API_KEY`, `GOOGLE_API_KEY` | `gemini-3.6-flash` | either present, `GEMINI_API_KEY` first |
| `nessy-model-bedrock` | — | — | — | never; see §6 |

Default model ids, key-to-config mirroring (`Provider.create(c -> c.apiKey(key))`
rather than each SDK's own `fromEnv()`), and the Gemini two-key rule all move
verbatim from `EnvModelProviders` into the owning module. Each bootstrap is
a small `public final class` beside its provider, e.g.
`AnthropicModelProviderBootstrap`, so the module that owns the SDK owns the
knowledge of how to build it from a key.

The OpenAI module registering two bootstraps is the honest shape: xAI is
OpenAI's wire protocol at a different URL, has no module of its own, and
never will. Two service lines in one file.

## 6. Bedrock leaves the environment path

`nessy-model-bedrock` ships no services file. `NESSY_PROVIDER=bedrock` stops
meaning anything — with two candidates it is "names none of them"; with one
or zero it is ignored or moot. An application that wants Bedrock writes:

```java
Model model = BedrockModelProvider.fromEnv().model("us.anthropic.claude-haiku-4-5-20251001-v1:0");
```

The bedrock design §4's explicit-only rule was a guard: ambient AWS
credentials are common enough that letting them win, or even participate in
a tie, would silently route a laptop with a stray profile to Bedrock. The
guard survives; its mechanism changes. It was a branch checked before every
other selection step. It is now the absence of a file. There is no code path
by which Bedrock enters the candidate list, so there is nothing to keep
checking first.

`BEDROCK_DEFAULT_MODEL` moves to `nessy-model-bedrock`'s README as the
documented starting point, not to any code: with no bootstrap there is no
`defaultModelId()` to hold it, and `BedrockModelProvider.model(String)` has
always required an explicit id.

## 7. The rename, and what depends on what

`nessy-model-env` is deleted; `nessy-model-discovery` is created at
`org.jwcarman.nessy.model.discovery`. Its `pom.xml` depends on `nessy-spi`
alone — the SLF4J dependency goes with the WARN it existed for; every outcome
in §4 is either a silent success or an exception.

Dependency changes elsewhere:

| Module | Before | After |
|---|---|---|
| `nessy-agent` (test scope) | `nessy-model-env` | `nessy-model-discovery` plus `nessy-model-anthropic`, `nessy-model-openai`, `nessy-model-gemini` — `ApprovalPlayground` and `CliLiveSmokeTest` run against whichever key the developer has |
| `nessy-examples/hello` | `nessy-agent`, `nessy-model-env` | `nessy-agent`, `nessy-model-discovery`, `nessy-model-anthropic` — the minimal shape a new user copies (§11 sign-off) |
| `nessy-examples/approvals` | `nessy-agent`, `nessy-model-env` | `nessy-agent`, `nessy-model-discovery`, all three keyed provider modules — this one demonstrates the env switch |
| `nessy-bom` | manages `nessy-model-env` | manages `nessy-model-discovery` |
| `nessy-api`'s `NoPublicBuildersTest` | lists `nessy-model-env` | lists `nessy-model-discovery` |

Thirty-eight files reference the old name. Twenty are live and change: the
five poms above, `pom.xml`'s module list, the three Java callers, the four
docs guides (`getting-started`, `harness`, `providers`, `index`), both READMEs,
`nessy-model-bedrock/README.md`, `CHANGELOG.md`, `ModelProvider.java`'s
javadoc, and the module's own sources and tests. Eighteen are historical
plans and specs under `docs/superpowers/`, which record what was decided when
and are not edited — except the three this spec amends, which get an
amendment note pointing here.

## 8. Documentation

James's instruction: clean up the docs. Every page that states the old
premise is corrected, not annotated:

- **`docs/guides/providers.md`** — the `nessy-model-env` section is rewritten
  as `nessy-model-discovery`: the two-step story (add the provider jar, set
  its key), the fail-fast ambiguity rule, the exact failure messages, and a
  "writing your own provider" subsection showing a `ModelProviderBootstrap`
  implementation and its services file. The Bedrock section's "explicit
  selection only" paragraph becomes "not discovered — construct it".
- **`README.md`** — the quick-start dependency snippet shows
  `nessy-model-discovery` plus `nessy-model-anthropic`, and the
  `NESSY_PROVIDER=bedrock` comment goes. The sentence "reads whichever key is
  set and picks the model for you" becomes "picks the provider you shipped,
  configured by its key".
- **`docs/guides/getting-started.md`, `docs/index.md`, `docs/guides/harness.md`**
  — name and premise updates at the cited lines.
- **`nessy-model-discovery/README.md`** — replaces `nessy-model-env`'s. The
  precedence table is gone; in its place, the three outcomes (none, one,
  many) and what each says.
- **`nessy-model-bedrock/README.md`** — the env-selection paragraph is
  replaced by the explicit construction snippet in §6 and the reason Bedrock
  is not discovered.
- **`CHANGELOG.md`** — an Unreleased entry under Breaking changes: the
  rename, the removal of `NESSY_PROVIDER=bedrock` and the `grok` alias, and
  fail-fast replacing warn-and-default.

## 9. Testing

**`nessy-model-discovery`** tests discovery with **no real provider on its
classpath at all**. A test-scope `ModelProviderBootstrap` implementation, or
two, registered through a services file under `src/test/resources`, returning
a `ScriptedModelProvider` from `nessy-testing`. That proves the mechanism
independently of any SDK, and it is the only way to test the zero-registration
message. Cases:

- no registrations → the "no model provider modules" message
- one registered, key absent → the no-credentials message names that
  provider and its variables, and no other
- one registered, key present → chosen; `NESSY_PROVIDER` naming something
  else is ignored
- two registered, both present, `NESSY_PROVIDER` unset → fails naming both
- two registered, both present, `NESSY_PROVIDER` names one (any case) →
  chosen silently
- two registered, both present, `NESSY_PROVIDER` names neither → fails
  naming both and the value
- duplicate `name()` across two registrations → fails naming the token
- a bootstrap that throws on a present-but-malformed key → the exception
  propagates
- `NESSY_MODEL` set / blank / unset against `defaultModelId()`
- `Selection` null-rejection, carried over

The existing `EnvModelProvidersTest` cases that pinned the precedence order
and the WARN-and-default behaviour are deleted with the behaviour, not
ported. Those that pinned per-provider construction move to the owning
module.

**Each provider module** tests its own bootstrap: `name()`,
`environmentVariables()`, `defaultModelId()`, empty on absent key, a
provider on present key, and — for OpenAI — the base URL layered on when
present and the xAI bootstrap's fixed URL. Plus one test per module that
`ServiceLoader.load(ModelProviderBootstrap.class)` on that module's own
classpath yields its registration(s): the services file is a resource, and
a typo in it fails at runtime with no compiler to notice.

**`nessy-api`'s `NoPublicBuildersTest`** is the one test that names modules;
it picks up the new name. Nothing else in the tree names `nessy-model-env`
from a test.

**`hello`** must still build and run in scripted mode with only
`nessy-model-anthropic` on its classpath — the demonstration that the
minimal shape is a working shape.

## 10. Rejected alternatives

**`<optional>true</optional>` on the four provider dependencies.** Would
appear to work, because every provider construction already sits behind a
`Supplier`. It fails as `NoClassDefFoundError` at selection time rather than
degrading: `presentCandidates` builds a candidate for every key it finds, so
`OPENAI_API_KEY` set on a classpath without the OpenAI jar crashes instead of
falling through. Optional dependencies make the classpath a landmine;
discovery makes it the source of truth.

**A capability flag on the interface** ("explicit-only", "participates in
ties"). A boolean a future contributor sets wrong on a provider that then
silently joins discovery. Replaced by a type: what should not be discovered
does not implement the interface.

**Bedrock registers but self-declines unless named.** Keeps
`NESSY_PROVIDER=bedrock` working, at the cost of the flag above in disguise
— "I only activate when named" is the capability member wearing a different
name — and of the AWS SDK returning to any classpath that wants the env
switch. Rejected with James on 2026-08-25.

**Keep warn-and-default for ambiguity.** Requires an explicit ordering
member on the interface, because `ServiceLoader` order is not stable across
classpath layouts, and turns a deliberate two-jar deployment's
misconfiguration into a log line. Rejected with James on 2026-08-25.

**Keep the name `nessy-model-env`.** Still accurate — it resolves from the
environment — but "env" names the configuration source, and the module's job
is now discovery. Rejected with James on 2026-08-25; `nessy-model-discovery`
chosen.

## 11. Sign-offs (all granted 2026-08-25)

1. **`ModelProviderBootstrap`** — name, the four members, and the
   intent-signal rule.
2. **`ModelDiscovery`** as the public class replacing `EnvModelProviders`.
3. **`hello` depends on discovery plus Anthropic only** — the minimal
   template. `hello`'s compile tree is the measurement that started this,
   and a template that ships 99 jars teaches the old premise. `approvals`
   keeps all three keyed providers to demonstrate the switch.
4. **`nessy-model-discovery`** as the module name.

## 12. Deliberately not done

- No Spring auto-configuration. A Spring Boot starter would register
  bootstraps as beans and let `@ConditionalOnClass` do this job; that is a
  different module with a different discovery mechanism, and it is not this
  spec.
- No "which key is this" detection. A provider claims its variables by
  name; nothing inspects a key's format.
- No change to `Model`, `ModelProvider.model(String)`, or any provider's
  `create(...)` / `fromEnv()` API. Direct construction is untouched and
  remains the path for anything discovery does not cover.
- No migration shim for `NESSY_PROVIDER=bedrock` or `grok`. Zero users;
  breaking changes are allowed and the CHANGELOG records them.

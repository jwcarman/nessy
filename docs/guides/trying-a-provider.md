# Trying a Provider

`nessy-examples/chat-cli` is the fastest way to find out whether a provider
path actually works — real key, real network, real model, one conversation.
Point it at a provider, run one exchange, and you've exercised streaming, a
gated tool call, and a notebook write in about two minutes. This guide is the
live complement to [Testing](testing.md), which covers the offline story.

## The idea

`Chat`'s `main` builds its provider and model from
[`EnvModelProviders.select()`](providers.md#picking-a-model-too-select) —
whichever environment variables are set decide both, and the console banner
prints exactly what was picked. If the banner names the provider and model
you expected, the wiring is already half-validated before you type a word.

The rest of the validation is one conversation, described below.

## The gauntlet

Start chat-cli with a provider's env set (examples in the next section), then
type:

```
can you help me know the current time? Please remember that I prefer US pacific time
```

Walk through what happens:

1. **The model calls the `clock` tool**, and chat-cli's `ConsoleApprover`
   stops and prints a bold `approve: read the current time` prompt followed
   by `y/n>`. Type `y`.
2. **The tool completes** and the model gets the result back — a second
   round trip.
3. **The model calls `remember`** to save the timezone preference — no
   approval needed, this tool is granted unconditionally.
4. **The final answer** converts the clock's system time into US Pacific and
   states it in prose.

Each step proves something different:

| Step | What it proves |
|---|---|
| Streaming text appears at all | the provider's stream mapping works |
| The `clock` call shows up and pauses for `y/n>` | tool_calls wire support, and the approval gate |
| The answer arrives after you type `y` | multi-round tool sequencing (call → result → next turn) |
| `remember` fires without a prompt | an ungated tool call, and a notebook write |

If a provider gets through all four, its path is live-validated for the
things that matter most: it can stream, it can call tools, it can sequence
multiple tool rounds in one turn, and it can call a tool with no gate at all.

## Per-provider commands

Every command below is the same shape — set the provider's env, then:

```console
$ ./mvnw -q -pl nessy-examples/chat-cli -am compile exec:java
```

**Anthropic:**

```console
$ ANTHROPIC_API_KEY=... ./mvnw -q -pl nessy-examples/chat-cli -am compile exec:java
```

**OpenAI:**

```console
$ OPENAI_API_KEY=... ./mvnw -q -pl nessy-examples/chat-cli -am compile exec:java
```

**Gemini** (`GEMINI_API_KEY`, or `GOOGLE_API_KEY` if that's what you have):

```console
$ GEMINI_API_KEY=... ./mvnw -q -pl nessy-examples/chat-cli -am compile exec:java
```

**xAI / Grok** (no small/cheap default exists for Grok, so `select()` falls
back to `grok-4.6`, which is fine for this gauntlet):

```console
$ XAI_API_KEY=... ./mvnw -q -pl nessy-examples/chat-cli -am compile exec:java
```

**Bedrock** (`NESSY_PROVIDER=bedrock` is the only door — see
[Providers](providers.md#bedrock) for why key presence never chooses it):

```console
$ export AWS_ACCESS_KEY_ID=...
$ export AWS_SECRET_ACCESS_KEY=...
$ export AWS_SESSION_TOKEN=...   # only for temporary/STS/sandbox creds
$ export AWS_REGION=us-east-1
$ NESSY_PROVIDER=bedrock ./mvnw -q -pl nessy-examples/chat-cli -am compile exec:java
```

AWS retired the old Bedrock model-access console page — models auto-enable
on first invoke now. The one wrinkle: an Anthropic model's first-ever
invocation on a fresh account can trigger a one-time use-case form. If your
run fails with something access-shaped on the very first call, open the
Bedrock console's model playground for the model you're targeting — loading
it there is what actually triggers the form to appear.

**OpenRouter** (model ids are vendor-prefixed slugs, so `NESSY_MODEL` is
required — the validated path is `openai/gpt-4o-mini`; `:free`-tagged
variants exist for many models; note cached-token counts may read `0`
regardless of whether the upstream model actually cached anything, since
usage passthrough varies by vendor):

```console
$ OPENAI_API_KEY=sk-or-... OPENAI_BASE_URL=https://openrouter.ai/api/v1 \
    NESSY_MODEL=openai/gpt-4o-mini \
    ./mvnw -q -pl nessy-examples/chat-cli -am compile exec:java
```

**NVIDIA NIM** (free developer tier; keys are `nvapi-...` from
build.nvidia.com, model ids are NVIDIA's catalog ids — the validated path is
the free open-weight `nvidia/nemotron-3.5-lightning-30b-a3b`, which drove
the full gauntlet):

```console
$ OPENAI_API_KEY=nvapi-... OPENAI_BASE_URL=https://integrate.api.nvidia.com/v1 \
    NESSY_MODEL=nvidia/nemotron-3.5-lightning-30b-a3b \
    ./mvnw -q -pl nessy-examples/chat-cli -am compile exec:java
```

**LM Studio** (local; load a model in LM Studio first):

```console
$ OPENAI_API_KEY=lm-studio OPENAI_BASE_URL=http://127.0.0.1:1234/v1 \
    NESSY_MODEL=<loaded-model> \
    ./mvnw -q -pl nessy-examples/chat-cli -am compile exec:java
```

!!! warning "The base URL is not symmetric between the two doors"
    LM Studio also speaks Anthropic's Messages dialect. Reach it through
    `AnthropicModelProvider` with `ANTHROPIC_BASE_URL` and the bare origin,
    no `/v1`:

    ```console
    $ ANTHROPIC_API_KEY=lm-studio ANTHROPIC_BASE_URL=http://127.0.0.1:1234 \
        NESSY_MODEL=<loaded-model> \
        ./mvnw -q -pl nessy-examples/chat-cli -am compile exec:java
    ```

    The OpenAI-compatible door wants the `/v1` suffix; the Anthropic SDK
    appends `/v1/messages` itself, so giving it `/v1` produces a doubled
    path and fails. See [Providers](providers.md#anthropic-compatible-endpoints)
    for the full explanation.

**Ollama** — documented shape, **not yet validated** through this gauntlet:

```console
$ OPENAI_API_KEY=ollama OPENAI_BASE_URL=http://localhost:11434/v1 \
    NESSY_MODEL=<pulled-model> \
    ./mvnw -q -pl nessy-examples/chat-cli -am compile exec:java
```

Any non-empty string works as the key — Ollama doesn't check it. Run the
gauntlet against it yourself before trusting this path in anger.

**Multi-key environments:** if more than one of the keys above is set at
once, `NESSY_PROVIDER` breaks the tie (`anthropic`/`openai`/`gemini`/`xai`,
alias `grok`). An unrecognized value, or one naming a key that isn't
actually set, falls back to the first key present in `fromEnv()`'s own
order and logs a `WARN` line saying so — check the banner if you're not
sure which provider you got.

## Reading the failures

The gauntlet fails informatively. What each shape usually means:

| Symptom | Likely cause |
|---|---|
| `401`/`403` on the very first call | wrong or expired key |
| xAI `403` with a "purchase more credits" message | the account has no balance, not a bad key |
| Bedrock `UnrecognizedClientException` | expired STS/sandbox credentials — re-export them |
| Bedrock `AccessDeniedException` naming an IAM action | the credentials' IAM policy doesn't allow `bedrock:InvokeModel`/`InvokeModelWithResponseStream` |
| Bedrock `AccessDeniedException` naming the model | this account hasn't used that model yet — see the console-playground note above |
| Bedrock `ThrottlingException` | rate-limited; retry, or fall back to a different region/model |
| OpenRouter hanging or a rate-limit response on a `:free` model | free-tier queueing — expected under load, not a wiring bug |

None of these are Nessy bugs to chase — they're the provider or the account
telling you something. The point of the gauntlet is surfacing exactly this
kind of thing in two minutes instead of after you've built something on top.

## The deeper cut: each provider's live suite

The gauntlet is a smoke test, not a substitute for the automated live suite
each provider module carries. Every native provider module has one, gated
behind `-Dnessy.excludedGroups=` (clearing the `live` exclusion) and the
provider's key:

```console
$ ANTHROPIC_API_KEY=... ./mvnw test -Dnessy.excludedGroups= -pl nessy-model-anthropic
$ OPENAI_API_KEY=...    ./mvnw test -Dnessy.excludedGroups= -pl nessy-model-openai
$ GEMINI_API_KEY=...    ./mvnw test -Dnessy.excludedGroups= -pl nessy-model-gemini
$ AWS_ACCESS_KEY_ID=... AWS_SECRET_ACCESS_KEY=... AWS_REGION=us-east-1 \
    ./mvnw test -Dnessy.excludedGroups= -pl nessy-model-bedrock
```

These cover more than a chat-cli conversation can: a real conversation and a
real tool round trip driven from JUnit, asserted against directly rather
than eyeballed at a terminal — for Gemini, that includes the `thoughtSignature`
capture/replay path; for Bedrock, the ConverseStream async-to-blocking bridge
under real network conditions. See [Testing](testing.md) for the offline
suite these live tests sit alongside, and [Providers](providers.md) for what
each mapping actually covers and where its gaps are.

## Where next

- [Providers](providers.md) — the wiring reference and the dated
  validation notes this guide's commands are drawn from.
- [Testing](testing.md) — the offline, deterministic complement to this
  guide's live gauntlet.
- [Getting Started](getting-started.md) — the smallest agent, built from
  scratch rather than run from an example.

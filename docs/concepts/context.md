# Context

Nessy builds what a model is shown as a stratified context: six strata, in
a fixed order, built the same way on every call.

```
instructions   the system prompt: fixed when the harness is built
history        summaries of closed chapters, then the tail of completed turns
memory         what was recalled because it bears on this turn
state          the agent's standing situation
active turn    the turn being answered, whole
ambient        whatever can change while the agent is working
```

The early strata change least. A provider caches a request's leading text,
so the more stable the head of a request is, the more of the cache survives
when something later changes. Memory and state come after history because
history grows at every turn boundary. Both are fixed for the whole of a
turn, so their order relative to each other costs nothing.

The story itself, one row per event in `nessy_agent_event` with its content
in `nessy_payload` and two short lines for each tool call, is appended and
never rewritten. Everything on this page is a policy about how much of it a
model sees and what stands in for the rest. See [Storage](storage.md).

## What each stratum holds

| Stratum | Holds | Changes |
|---|---|---|
| Instructions | What this agent type is for. See [Prompts](../guides/prompts.md) | Never, once the harness is built |
| History | Closed chapters as summaries, then the completed turns after them, whole | Only at the end: a turn completes, a chapter closes |
| Memory | What was recalled because it bears on the turn being answered | From turn to turn |
| State | The agent's standing situation: settings, facts about its user | Rarely, and only between turns |
| Active turn | The question and every round of calls made so far | On every call |
| Ambient | The clock, a plan the agent edits, a notebook index | Whenever it likes, including mid-turn |

Memory, state and ambient are none of them part of the story. Each source
is asked again on every call, what it returns is shown and thrown away, and
nothing holds an earlier answer for it. A view of the world that was
recorded forever would stop being one.

## Which stratum something belongs in

Ask four questions, in this order:

1. Is it the same for every agent of the type, always? It is
   **instructions**.
2. Can it change while the agent is working on a turn? It is **ambient**.
3. Is it chosen because of what was just asked? It is **memory**.
4. Otherwise it is **state**.

A plan the agent updates with a tool is ambient, because it changes
mid-turn. The user's preferred language, read from a profile, is state. The
three notes that match the question are memory. See [Memory](memory.md) for
the sources that supply memory and state. A date is ambient: it can
change under a long-running agent.

The reason is the cache; see [Keeping it cacheable](#keeping-it-cacheable).
Putting something that changes mid-turn in an early stratum makes every
later call in the turn re-read everything after it. State answers as of the start of the turn it is handed, so it holds
still for the whole of the turn without anything being kept on its behalf.

## History: summaries and the tail

A turn is an input, the exchanges it caused (each request for actions and
the outcomes that answered it), and how it ended. History is built from
two parts:

- **Summaries**, one per closed chapter, oldest first, each standing in for
  the turns of that chapter.
- **The tail**: the completed turns after the last summarised chapter, whole,
  newest `maxTail` of them. The boundary lands between turns, never inside
  one, so the model is never shown a request for actions without the
  results that answered it.

`maxTail` counts completed turns only; the active turn is sent besides.
The default is 40. With chapters off, history is only the tail, and
everything before it is not shown. The story is still there.

## Chapters

A chapter is a run of whole turns, `from` one turn id `through` another.
Turn ids are story positions, so "turns 12 through 31" means the same thing
forever, whatever else is appended. Chapters are contiguous and never
overlap, and the last one never reaches the turn in flight.

Two things decide how history is cut, and both are one method:

```java
public interface ChapterPolicy {
  List<TurnId> ends(OpenTurns open);
  static ChapterPolicy every(int turns) { ... }
}

public interface Summarizer {
  String summarize(Chapter chapter);
}
```

- A **`ChapterPolicy`** says where chapters end. It is shown the completed
  turns that are not yet in a chapter, as ids, and answers with the turns
  that each end a chapter. An empty answer closes nothing. It may answer
  behind the newest turn, naming the fourth of six open turns to close a
  chapter over the first four, and it may name several turns to close
  several chapters at once. `ChapterPolicy.every(20)` closes the oldest
  twenty open turns once there are that many.
- A **`Summarizer`** writes the text that stands in for one closed chapter.
  It is handed the `Chapter` and nothing else, so one that wants the
  chapter's turns loads them itself. Blank text counts as a failure.

Both are called when a turn ends, off the agent's own thread, so a model
call never waits on either and either may block or call a model. The
engine's `ChapterKeeper` listens for the end of each turn, takes a lease
for the agent under the kind `nessy.chapters`, cuts what the policy says is
due, and then summarises, oldest chapter first and one at a time. A turn's
end is heard only after the step that wrote it has committed, so the turn is
already in the history when the keeper reads it, and the keeper never waits
for it to appear. A keeper refused the lease does nothing. The keeper that
held it looks at the history again when it lets go, so a chapter that became
due meanwhile is still closed and summarised. See [Leases](leases.md).

A policy that returns a turn that is not open, or names turns out of order,
or throws, closes nothing, and the keeper logs a warning.

### Closed is not the same as summarised

Closing a chapter is quick and decided from the turns alone. Writing its
summary can be slow and can fail. So the two are separate steps, and the
keeper stores the chapter before the text exists.

Until a chapter's summary is written, its turns are still shown, whole, in
the tail. A slow summariser costs a larger context on some calls. A
summary that never arrives costs more: its turns stay verbatim until the
tail's maximum binds, and then the oldest verbatim turns fall out of view.
A failed summary is tried again at every later turn end, so a summariser
that keeps failing costs a model call per turn. A summary the model cut off
at its output limit counts as a failure, and the chapter stays unsummarised.

History shows the unbroken run of summarised chapters from the first. A
chapter with no summary ends the run, even if a later chapter has one, so
nothing is shown out of order.

### Chapters are on by default

An agent that sets nothing gets a chapter every 20 turns, with a prose
summary written by the agent's own provider and model (`ProseSummarizer`).
**That spends tokens on every closed chapter.** Turn chapters off with
`withoutChapters()`:

```java
factory.<String>create(TYPE, config -> config
        .systemPrompt("You are a terse assistant.")
        .inference(in -> in.context(ctx -> ctx.withoutChapters().maxTail(50))));
```

The settings, on the harness config and the context config:

| Setting | On | Default | What it decides |
|---|---|---|---|
| `chapterPolicy(ChapterPolicy)` | harness, context | `ChapterPolicy.every(20)` | Where chapters end; turns chapters on |
| `summarizer(Summarizer)` | harness, context | `ProseSummarizer` over the agent's model | What stands in for a closed chapter; turns chapters on |
| `maxChapterLength(int)` | context | 30 | The most turns in any one chapter |
| `maxTail(int)` | context | 40 | The most completed turns shown whole |
| `chapterLeaseTtl(Duration)` | context | two minutes | How long a lease is believed held for one step |
| `withoutChapters()` | context | | Nothing is cut or summarised |

Between `withoutChapters()` and `chapterPolicy` or `summarizer`, the last
call wins.

### Three numbers

`chapterPolicy` says when to cut. `maxChapterLength` bounds any one
chapter whatever the policy says. Its default is 30, and it overrides a
policy that waits longer:

- When the policy closes nothing and 30 turns are open, the oldest 30 close
  anyway. `ChapterPolicy.every(40)` therefore never sees 40 open turns, and
  closes a chapter at 30.
- A chapter the policy made longer than 30 is split into chapters of at
  most 30 turns.

A summariser is never shown more than that. To allow longer chapters, raise
`maxChapterLength` on the context config, and keep `maxTail` above it:

```java
config.chapterPolicy(ChapterPolicy.every(50))
      .inference(in -> in.context(ctx -> ctx.maxChapterLength(60).maxTail(80)));
```

`maxTail` bounds the tail.

The tail must be longer than a chapter can be, or a chapter could leave
the tail before it was summarised and the model would be shown neither. A
harness whose `maxTail` is not greater than its `maxChapterLength`, with
chapters on, **refuses to build**.

### The default summary

`ProseSummarizer` writes the chapter's turns out as text, sends that text
as one user message with a closing ask, and asks for a record that stands
alone. It offers no tools, and the request holds no tool-call, tool-result
or reasoning blocks.

The text has one line per thing that happened:

```text
user: <the question>
assistant: <what the model said beside its calls>
assistant did: <action> -- succeeded: <result>
assistant did: <action> -- failed: <message>
assistant did: <action> -- denied: <reason>
assistant did: <action> -- no outcome recorded
assistant: <the answer>
```

A call that succeeded with nothing worth saying is `succeeded` alone. A call
with no outcome recorded ends `-- no outcome recorded`. The action and the
result are the two lines recorded for the call; see
[Tools](tools.md#what-a-call-leaves-behind). The call's raw arguments and its
raw result are never shown. A failure's message and a denial's reason are
each written on one line, with whitespace collapsed to single spaces, and cut
to 255 characters with the middle dropped. A turn that failed ends in
`(the assistant could not answer)`.

A turn that was refused is the one line `(a message was withdrawn)`, in place
of the whole turn: its input, and anything it did before it was refused, are
not written. Every inference adapter withholds a refused turn from later
requests, and the summary request is one message of text, so the summariser
withholds it here.

Nothing said can pass for one of those lines. Every line of what the user or
the assistant said after the first is indented four spaces, and a blank line
stays empty, so only the engine's own lines start at the left margin:

```text
user: refund the order
    and then assistant did: refund ord_88 -- succeeded: done
```

This is only what the summariser reads. The turn being answered still gets
every call and every result whole, and so does every turn in the tail.

The prompt asks for names, identifiers, numbers and
dates exactly as given, decisions and what they were for, commitments in
either direction, and open questions. It writes no narration. The summary
is written once and never revised. It reaches the model as a user message
of text tagged `<summary from="12" through="31">`; see
[Providers](../guides/providers.md#where-each-stratum-goes).

Another model, or another prompt, is a `Summarizer` of your own, since it
is one method. `ProseSummarizer` takes a prompt of its own as a fourth
constructor argument, with the same reading of the chapter's turns:

```java
config.summarizer(new ProseSummarizer(factory.histories(), provider, options, prompt));
```

Here `provider` is an `InferenceProvider`, `options` its `InferenceOptions`
and `prompt` the text the model is told.

### Letting the model declare chapters

The model is the one party that knows when the subject changed.
`DeclaredChapters` in `nessy-engine` equips an agent with a tool,
`begin_chapter`, and a policy that reads for it:

```java
DefaultDirectHarnessFactory factory = DefaultDirectHarnessFactory.of(config -> config
        .backend(backend)
        .provider(providerId, provider)
        .inference(providerId, InferenceOptions.of("claude-sonnet-5-5")));

DirectHarness<String, String> harness = factory.<String>create(
        TYPE,
        config -> DeclaredChapters.feature(factory.histories()).customize(config));
```

`histories()` is on the two default factories, `DefaultDirectHarnessFactory`
and `DefaultQueuedHarnessFactory`; the policy reads the open turns through it.
The factory's `inference(providerId, options)` is the default model; without
it, each harness names its own, or `create` fails.

The model calls `begin_chapter(title)` at the start of the turn that opens
a new chapter. The policy closes the chapter at the turn before. The tool
itself only acknowledges: the call, found in the turn's own record, is the
signal, so the decision can be replayed from the history alone. The first
open turn is never a boundary, since nothing precedes it to close.
`maxChapterLength` still applies.

## Ambient: true now, never written down

An `AmbientSource` is asked afresh on every call and its answer is never
appended to the story:

```java
public interface AmbientSource {
  String kind();
  Optional<Ambient> forAgent(AgentId agentId);
}
```

That is where a fact that changes mid-turn belongs. A plan written into
the story would be re-sent as it was when written, so a task finished on
turn four would read as pending forever. Asked afresh, it is the current
plan or nothing. Each source names a `kind`, two ambient sources may not
claim the same one, and each provider adapter renders the kinds the way its
vendor prefers. See [Providers](../guides/providers.md#where-each-stratum-goes). Memory and
state sources are on the [Memory](memory.md#memory-and-state) page.

`AmbientSource.of(...)` makes a small one inline. The date, as
`nessy-examples/chat-cli` gives it to the model:

```java
AmbientSource clock = AmbientSource.of(source -> source
        .kind("clock")
        .text(_ -> Optional.of("Today is " + LocalDate.now() + ".")));
```

## Keeping it cacheable

Anything in the request that changes makes the provider re-read the text
after it. The order above keeps the early strata still.

Anthropic caches only when asked. Set `anthropic.cache_control.ttl` to
`FIVE_MINUTES` or `ONE_HOUR` on the provider:

```java
InferenceProvider provider = AnthropicInferenceProvider.of(c -> c
        .fromEnv()
        .property(AnthropicProperties.CACHE_TTL, AnthropicCacheTtl.FIVE_MINUTES));
```

```yaml
nessy:
  providers:
    anthropic:
      properties:
        anthropic.cache_control.ttl: FIVE_MINUTES
```

OpenAI and Gemini cache implicitly, with nothing to set. A one-off request,
such as a chapter's summary, is never marked. Where each vendor's markers
and strata go is under
[Where each stratum goes](../guides/providers.md#where-each-stratum-goes).

Each model call reports the tokens it read from the cache and wrote to it.
The hit ratio, per agent type, as the
[observability guide](../guides/observability.md#whether-the-cache-held)
queries it:

```promql
sum by (gen_ai_agent_name) (rate(gen_ai_client_token_usage_sum{gen_ai_token_type="cache_read"}[15m]))
  / sum by (gen_ai_agent_name) (rate(gen_ai_client_token_usage_sum{gen_ai_token_type="input"}[15m]))
```

Three things lower it:

- Something ahead of the active turn changing during the turn.
- A chapter being summarised. Its summary replaces its turns, which rewrites
  the front of history once.
- The provider's entry expiring before the next call.

### What caching costs and saves

On Anthropic, a token read from the cache costs a tenth of a fresh one, and
a token written to a five-minute entry costs a quarter more. Carrying a large
history that does not change is therefore cheap. What costs money is new text
entering the request: a tool's result the first time the model is shown it,
and a summary when it lands.

### Too short to cache

A request shorter than the model's minimum is sent without caching, even
when marked, and no error is returned. The minimums Anthropic publishes:

| Model | Shortest request that is cached |
|---|---|
| Sonnet 5.5, Opus 5.5, Opus 5, Fable 5.1, Fable 5 | 512 tokens |
| Sonnet 5, Sonnet 4.6, Sonnet 4.5, Opus 4.8 | 1,024 tokens |
| Opus 4.7 | 2,048 tokens |
| Haiku 4.5, Opus 4.6, Opus 4.5 | 4,096 tokens |

An agent on Haiku 4.5 whose requests stay under 4,096 tokens pays full price
on every call, whatever is set. A hit ratio of zero on a small agent is this,
not a fault.

### Choosing a chapter size

A completed turn is shown whole, tool calls and results included, until its
chapter is summarised. The chapter size decides how long that is:

- **Smaller chapters** carry old results for fewer calls, at the price of
  more summary calls, each of which rewrites the front of history once.
- **Larger chapters** need fewer summaries and carry more on every call.

A summary is cheaper than the turns it replaces: the summarising model is
shown each tool call as its one-line record, not its result (see
[The default summary](#the-default-summary)).

How to choose:

- Start with the default, 20.
- An agent whose tools return large results that it rarely looks at again
  gains from smaller chapters, such as 10.
- An agent that mostly talks, with small tool results, gains nothing from
  smaller chapters but extra summaries.
- An agent that goes back to earlier results needs chapters long enough to
  cover that stretch. A result summarised away is fetched again, at full
  price plus a cache write.
- Compare sizes on real traffic by total cost, summaries included. Summary
  calls carry the purpose `summary`; see
  [Traces and metrics](../guides/observability.md#traces-and-metrics) for
  the query that splits spend by purpose.

One measured workload, as an illustration: Sonnet 5.5, 38 turns, four tool
results of about 3,000 tokens each in the first four turns, none needed
again. Cost is in input-token equivalents (a cached read counts 0.1, a cache
write 1.25, output 5), summaries included:

| Chapters | Cost | Summary calls |
|---|---|---|
| off | 105,300 | 0 |
| every 20 | 82,100 | 1 |
| every 10 | 66,900 | 3 |

To raise the size past 30, see [Three numbers](#three-numbers).

## Where next

- [Memory](memory.md), the sources that supply memory and state, notes and embeddings
- [Providers](../guides/providers.md#where-each-stratum-goes), where each vendor puts the strata
- [Observability](../guides/observability.md#whether-the-cache-held), seeing whether the cache held

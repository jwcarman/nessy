# Memory

What a model is shown on each call is built in six strata, in this order,
and the engine builds it the same way every time:

```
instructions   the system prompt: fixed when the harness is built
history        summaries of closed chapters, then the tail of completed turns
memory         what was recalled because it bears on this turn
state          the agent's standing situation
active turn    the turn being answered, whole
ambient        whatever can change while the agent is working
```

The order is the order of how often each changes, least first. A provider
caches a request's leading text, so the more stable a stratum is, the
earlier it goes and the more of the cache survives when something later
changes.

The story itself, one row per event in `nessy_agent_event` with its content
in `nessy_payload`, is appended and never rewritten. Everything on this page
is a policy about how much of it a model sees and what stands in for the
rest. See [Storage](storage.md).

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
three notes that match the question are memory. A date is ambient: it can
change under a long-running agent.

The reason is the cache. Putting something that changes mid-turn in an
early stratum makes every later call in the turn re-read everything after
it. State answers as of the start of the turn it is handed, so it holds
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
due, and then summarises, oldest chapter first and one at a time. See
[Leases](leases.md).

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
A failed summary is tried again at a later turn end.

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
chapter whatever the policy says: when the policy closes nothing and that
many turns are open, the oldest that many close anyway, and a chapter the
policy made longer is split. A summariser is never shown more than that.
`maxTail` bounds the tail.

The tail must be longer than a chapter can be, or a chapter could leave
the tail before it was summarised and the model would be shown neither. A
harness whose `maxTail` is not greater than its `maxChapterLength`, with
chapters on, **refuses to build**.

### The default summary

`ProseSummarizer` shows the model the chapter's turns and asks for a record
that stands alone. Its prompt asks for names, identifiers, numbers and
dates exactly as given, decisions and what they were for, commitments in
either direction, and open questions. It writes no narration. The summary
is written once and never revised.

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
        .provider(providerId, provider));

DirectHarness<String, String> harness = factory.<String>create(
        TYPE,
        config -> DeclaredChapters.feature(factory.histories()).customize(config));
```

`histories()` is on the two default factories, `DefaultDirectHarnessFactory`
and `DefaultQueuedHarnessFactory`; the policy reads the open turns through it.

The model calls `begin_chapter(title)` at the start of the turn that opens
a new chapter. The policy closes the chapter at the turn before. The tool
itself only acknowledges: the call, found in the turn's own record, is the
signal, so the decision can be replayed from the history alone. The first
open turn is never a boundary, since nothing precedes it to close.
`maxChapterLength` still applies.

## Memory and state

`MemorySource` and `StateSource` have the same shape. Each names a `kind`
and is handed the agent and the turn being answered:

```java
public interface StateSource {
  String kind();
  Optional<State> forAgent(AgentId agentId, Turn current);
}
```

`MemorySource` is the same with `Optional<Memory>`. A source is asked on
the dispatcher's thread, off the agent's row lock, once per call to the
model, so it may read a table or call a service. It is handed the current
turn so that memory can choose what bears on the question, and so that
state can answer as of the start of that turn.

```java
StateSource persona = new StateSource() {
  @Override
  public String kind() { return "persona"; }

  @Override
  public Optional<State> forAgent(AgentId agentId, Turn current) {
    return personas.forAgent(agentId).map(text -> State.text("persona", text));
  }
};

config.state(persona);
```

Here `personas` is a lookup of your own. `config.memory(source)` and
`config.state(source)` are on both the harness config and the context
config, beside `ambient(...)`. `MemorySource.constant(memory)` and
`StateSource.constant(state)` are for text that is the same for every agent
and turn.

A source with nothing to say returns empty. A `Memory` or `State` with no
content is refused, because a label with nothing under it reads to a model
as a claim, where absence is not.

Two sources of the same stratum may not offer the same `kind`: the harness
refuses to build. The same kind in two different strata is allowed.

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
vendor prefers. See [Providers](../guides/providers.md#where-each-stratum-goes).

`AmbientSource.of(...)` makes a small one inline. The date, as
`nessy-examples/chat-cli` gives it to the model:

```java
AmbientSource clock = AmbientSource.of(source -> source
        .kind("clock")
        .text(_ -> Optional.of("Today is " + LocalDate.now() + ".")));
```

## Notes and plans

`nessy-memory-notebook` gives an agent notes it keeps and recalls by
heading. `Notebook` is the store, `JdbcNotebook` the one that ships, scoped
to an agent type and keyed by agent id, with `write`, `revise`, `forget`,
`find` and `headings`. The index is ambient: `headings()` is `SELECT
note_id, hook`, so a body cannot reach the model by accident. The agent
recalls a note when it wants one, through four tools:

```java
Notebook notebook = new JdbcNotebook(dataSource, TYPE);

config.inference(in -> in.context(ctx -> ctx.ambient(NotebookTools.index(notebook))))
      .tool(NotebookTools.remember(notebook))
      .tool(NotebookTools.revise(notebook))
      .tool(NotebookTools.recall(notebook))
      .tool(NotebookTools.forget(notebook));
```

`nessy-planning` gives it a plan it holds across turns; see
[Planning](planning.md). The plan is ambient and the model resends the whole
list on every update, so a replayed write stores the identical list.

The contrast between the two is deliberate. A note is too large to resend,
so the notebook pays for addressability with minted ids; a plan is not, so
it gets idempotence for free.

## Embeddings

`Embedder` (in `nessy-api`) and `EmbeddingProvider` (in
`nessy-embedding-spi`) are the seam for choosing what to recall by meaning,
kept apart from inference on purpose: not every inference vendor embeds,
and the embedding model belongs to the store that holds the vectors, not to
the agent that talks, because every vector in a table must come from one
model.

No store that ships in Nessy uses an `Embedder` yet. The seam, the four
providers and the Boot wiring are in place for a `MemorySource` or a store
of your own.

```java
public interface Embedder {
  default String vendor() { ... }
  String model();
  Optional<Dimension> dimension();
  List<Embedding> embedDocuments(List<String> texts);
  default Embedding embedDocument(String text) { ... }
  default Embedding embedQuery(String query) { ... }
}
```

`dimension()` is the width asked for, or the one learned from the first reply
when none was; it is empty until then. A `Dimension` is at least 1: a width of
zero or less is refused where it is set.

Four embedding providers ship. Each holds a connection and nothing about a
model; the model and its width are the store's, named when its embedder is
made, because a store's index is sized by them:

| Module | Reaches | `DEFAULT_MODEL`, to cite |
|---|---|---|
| `nessy-embedding-openai` | OpenAI, and with a base URL every OpenAI-compatible endpoint, which is how a local `nomic-embed-text` on Ollama or LM Studio is reached | `text-embedding-3-small` |
| `nessy-embedding-gemini` | the Gemini Developer API, through java-genai | `gemini-embedding-001` |
| `nessy-embedding-bedrock` | Amazon Titan and Cohere models on Bedrock, through `InvokeModel` | `amazon.titan-embed-text-v2:0` |
| `nessy-embedding-voyage` | Voyage AI, over plain HTTP: the partner Anthropic points to, having no embeddings of its own | `voyage-3.5` |

Each batches where its vendor allows, puts the reply back in the order asked,
refuses a short reply rather than padding it, and has a live test tagged
`live` that runs when the vendor's key is in the environment.

```java
EmbedderFactory embedders = DefaultEmbedderFactory.of(f -> f
        .provider(ProviderId.of("openai"), OpenAiEmbeddingProvider.fromEnv())
        .provider(ProviderId.of("local"), OpenAiEmbeddingProvider.of(c -> c
                .apiKey("lm-studio").baseUrl("http://localhost:1234/v1").vendor("lmstudio")))
        .embedding(ProviderId.of("openai"), EmbeddingOptions.of("text-embedding-3-small")));

Embedder forDocs = embedders.create(c -> {});
Embedder forNotes = embedders.create(c -> c.provider("local").model("text-embedding-nomic-embed-text-v1.5"));
```

Each provider is a vendor connection, registered once under a name.
`EmbedderFactory` mints as many `Embedder`s over them as there are stores,
each naming its provider, model and width or taking the factory's default.
An embedder whose replies are not the width it asked for fails, naming both.
In a Boot application the starter contributes the `EmbedderFactory` bean,
with embedders registered from `nessy.embedders.<id>` and the default from
`nessy.embedder` and `nessy.embedding-model`; see
[Providers](../guides/providers.md#embedders).

An `Embedding` carries its model's name and compares by content; its
`similarity` is the cosine between two vectors and refuses a pair from
different models.

## Writing your own

A `MemorySource`, a `StateSource`, an `AmbientSource`, a `ChapterPolicy`
and a `Summarizer` are each one method, and all may do I/O. The three
sources are asked on the dispatcher's virtual thread, off the agent's row
lock; the policy and the summariser are asked off the agent's thread
altogether. A vector store behind a memory source, a retrieval step over
the story, a policy that asks a model whether the subject changed: the
engine cannot tell, and does not ask.

Two things to keep. A source must answer from what is true now, because the
engine will not ask twice for one call and nothing is kept for it. And a
state source that wants to hold still for a turn does so by answering as of
the turn it is handed, not by remembering.

## Where next

- [Planning](planning.md), the plan an agent works through
- [Storage](storage.md), the tables behind all of this
- [Leases](leases.md), how the chapter keeper runs once
- [The Harness](../guides/harness.md), the context settings in place

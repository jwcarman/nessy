# Memory

Memory is what an agent is shown because it bears on the turn being
answered: notes it kept, facts about its user, anything a source of your own
can look up. It is asked for again on every call and is never part of the
story. See [Context](context.md) for where it sits in a request.

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
the dispatcher's thread, outside the lock that serialises the agent, once
per call to the model, so it may read a table or call a service. It is handed the current
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

## Notes and plans

`nessy-memory-notebook` gives an agent notes it keeps and recalls by
heading. `Notebook` is the store, `JdbcNotebook` the one that ships, scoped
to an agent type and keyed by agent id, with `write`, `revise`, `forget`,
`find` and `headings`. The index is ambient: `headings()` is `SELECT
note_id, hook`, so a body cannot reach the model by accident. The hook and the body are
stored through the application's `CodecFactory`, the one the backend is built
from, in a column each. The agent
recalls a note when it wants one, through four tools:

```java
Notebook notebook = new JdbcNotebook(dataSource, TYPE, codecs);

config.inference(in -> in.context(ctx -> ctx.ambient(NotebookTools.index(notebook))))
      .tool(NotebookTools.remember(notebook))
      .tool(NotebookTools.revise(notebook))
      .tool(NotebookTools.recall(notebook))
      .tool(NotebookTools.forget(notebook));
```

With the Spring Boot starter the notebook installs itself on every agent, so
none of that is written; an application that declares its own `Notebook` bean
keeps the starter out.

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

A `MemorySource` and a `StateSource` are each one method, and both may do
I/O. They are asked on the dispatcher's thread, outside the lock that
serialises the agent. A vector store behind a memory source, or a retrieval
step over the story: the engine cannot tell, and does not ask. The
[ambient source](context.md#ambient-true-now), the chapter
policy and the summariser are written the same way; see
[Context](context.md).

Two things to keep. A source must answer from what is true now, because the
engine will not ask twice for one call and nothing is kept for it. And a
state source that wants to hold still for a turn does so by answering as of
the turn it is handed, not by remembering.

## Where next

- [Context](context.md), where memory sits in a request, and chapters
- [Planning](planning.md), the plan an agent works through
- [The Harness](../guides/harness.md), the context settings in place
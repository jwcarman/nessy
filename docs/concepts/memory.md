# Memory

What a model is shown on each call is built from three things, in this
order, and the engine builds it the same way every time:

```
summaries    whatever every Summarizer returns, oldest first
tail         the most recent turns of the story, whole
ambient      whatever every AmbientSource has to say right now
```

The story itself, one row per message in `nessy_agent_history`, is appended
and never rewritten. Everything on this page is a policy about how much of
it a model sees and what stands in for the rest.

```java
config.inference(in -> in.context(ctx -> ctx
        .summaries(summaries)                  // a Summarizer
        .maxTail(20)                           // turns shown whole
        .ambient(NotebookTools.index(notebook))
        .ambient(PlanTools.plan(plans))));
```

## The tail

A turn is an observation, the exchanges it caused (each request for actions
and the outcomes that answered it), and how it ended. The tail is the last
`maxTail` turns, whole: the boundary lands between turns, never inside one,
so the model is never shown a request for actions without the results that
answered it.

The default is 20 turns. Without a summarizer, that is all a long-lived
agent ever sees, and the rest is simply not shown. The story is still there.

## Summaries stand in for what is not shown

A `Summarizer` says what came before the tail:

```java
public interface Summarizer {
  List<Summary> forAgent(AgentId agentId);
  default Optional<TurnId> summarizedThrough(AgentId agentId) { ... }
}
```

A `Summary` covers a run of turns, `from` one turn id `through` another,
and carries the text that stands in for them. The assembler shows every
summary first, then starts the tail after the furthest turn any summary
reaches, so nothing is shown twice and nothing is dropped between a summary
and the turns after it. Several summarizers may be added and their results
are concatenated in order. A summary that overlaps another, or that reaches
the turn being answered, is refused at assembly rather than sent.

Turn ids are story positions, so a summary "through turn 38" means the same
thing forever, whatever else is appended.

## The head summarizer

`nessy-memory-summarizing` keeps one rolling summary per agent and replaces
it as the story grows. Every time a turn ends, it counts the turns after
what the summary already covers; past `maxTail` it folds the oldest of them
into the previous summary, leaving `minTail` turns shown whole.

```java
JdbcSummaries summaries = new JdbcSummaries(dataSource, TYPE);

HeadSummarizer summarizer = HeadSummarizer.create(c -> c
        .agentType(TYPE)
        .summaries(summaries)
        .histories(factory.histories())
        .leases(leases)
        .inference(provider, InferenceOptions.of("claude-haiku-4-5"))
        .tail(20, 8));

Harness<String> harness = factory.create(config -> config
        .agentType(TYPE)
        .listener(summarizer.listener())
        .inference(in -> in.context(ctx -> ctx.summaries(summaries).maxTail(20))));
```

Three things about how it runs:

- **Event-driven, off the fold.** It is an `AgentEventListener` on the
  turn-ended event, wrapped `async()`, so the model call it makes never
  holds up the agent. The next call the agent makes sees the new summary.
- **Under a lease.** Two processes hearing the same turn end do not both
  fold; `Leases.tryRun` lets one through per agent and the other finds
  nothing left to do. See [Leases](../guides/spring-boot.md#leases).
- **It replaces, never appends.** The fold request is the previous summary
  plus the turns being cut, with the instruction to write the summary of
  everything so far. One row per agent, in `nessy_summary`, replaced only
  if the new one reaches further.

Because it is its own next input, every generation is lossy over the last.
An agent does not forget suddenly; it fades. The default prompt asks for
names, identifiers, decisions, commitments and open questions rather than a
retelling, for exactly that reason. A summary that arrives late costs a
larger context on one call, not a wrong one.

Episodic summaries, one per episode the model itself declares, are the next
shape on the [roadmap](https://github.com/jwcarman/nessy/blob/main/ROADMAP.md);
they will be another `Summarizer` beside this one.

## Ambient: true now, never written down

An `AmbientSource` is asked afresh on every call and its answer is never
appended to the story:

```java
public interface AmbientSource {
  Optional<Ambient> forAgent(AgentId agentId);
}
```

That is where a fact that changes belongs. A plan written into the story
would be re-sent as it was when written, so a task finished on turn four
would read as pending forever. Asked afresh, it is the current plan or
nothing. Each source names a `kind`, two sources may not claim the same
one, and each provider adapter renders the kinds the way its vendor
prefers. A source with nothing to say returns empty and contributes nothing.

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

Relevance is the end state for every store on this page: the episodes, notes
and lessons that bear on what the agent is doing now, chosen by meaning, and
the rest reachable on demand. `nessy-embedding-api` is the seam for that,
kept apart from inference on purpose: not every inference vendor embeds, and
the embedding model belongs to the store that holds the vectors, not to the
agent that talks, because every vector in a table must come from one model.

```java
public interface Embedder {
  String model();
  int dimension();
  Embedding embed(String text);
  List<Embedding> embed(List<String> texts);
}
```

`nessy-embedding-openai` is the first embedder, over OpenAI's endpoint and,
with a base URL, every OpenAI-compatible one, which is how a local model such
as `nomic-embed-text` on Ollama or LM Studio is reached. It is one model at
one dimension, decided where it is built, because a store's index is sized
by it.

```java
Embedder embedder = OpenAiEmbedder.create(c -> c
        .fromEnv()
        .model("text-embedding-3-small")
        .dimension(512));
```

An `Embedding` carries its model's name and compares by content; its
`similarity` is the cosine between two vectors and refuses a pair from
different models. The stores that rank by relevance, and the `pgvector`
columns behind them, are the roadmap's embeddings-ranked recall item; until
then the notebook and the episodes rank by recency and let the model recall
by title.

## Writing your own

A `Summarizer` and an `AmbientSource` are each one method, and both may do
I/O: they are asked on the dispatcher's virtual thread, off the agent's row
lock. A vector store behind an ambient source, a sliding window of summaries,
a retrieval step over the story: the engine cannot tell, and does not ask.

Two things to keep. A summary must run forwards and must not overlap
another. An ambient source must answer from what is true now, because the
engine will not ask twice for one call.

## Where next

- [Planning](planning.md), the plan an agent works through
- [Storage](storage.md), the tables behind all of this
- [The Harness](../guides/harness.md), the context settings in place

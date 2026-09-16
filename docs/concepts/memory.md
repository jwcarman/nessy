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
  default List<Summary> forAgent(AgentId agentId, Turn current) { ... }
  default Optional<TurnId> summarizedThrough(AgentId agentId) { ... }
}
```

The engine asks the two-argument form, handing over the turn being answered,
so a source that ranks by relevance has something to rank against; a source
that does not rank inherits the default and never sees it.

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

## Episodes

`nessy-memory-episodic` cuts the story where the model says it changes
subject, summarises each piece once it closes, and on every call shows the
pieces that bear on what is being asked now. Where the head summariser fades
the whole past into one paragraph, episodes keep each stretch of work as its
own summary that can come back whole when it is relevant again.

The model draws the boundaries. `begin_episode(title, reason)` records that a
distinct piece of work has begun at this turn: the open episode closes at the
turn before, a new one opens, and the tool returns. Nothing else happens
inside the call. The summary is written later by the `EpisodeSummarizer`, a
listener like the head summariser's: it hears a turn end, sees a closed
episode with no summary, takes the `episode` lease for the agent and asks the
model for the summary of that episode's turns alone. Until it has one, the
episode's turns are still shown verbatim, so a slow summary costs a larger
context on a few calls and never a hole. An episode index is ambient, kind
`episodes`, listing every episode by number and title and which one is under
way; `recall_episode(n)` reads any summary the store did not choose to show.

`JdbcEpisodes` is the `Summarizer`. Its candidates are the summarised
episodes from the start of the story up to the first that is open or not yet
summarised; the tail begins after the last of them. Of the candidates it
shows at most `shown` (five by default): always the most recent, because the
story just left it, and the rest chosen by relevance when the store has an
`Embedder`, by recency when it has not. Relevance is the cosine between the
summary's embedding, written beside it when the summary was, and the
embedding of the observation being answered: one embedding call per model
call, against one agent's rows, which are tens rather than thousands. Change
the embedder and the rows the old model wrote rank last until re-embedded;
the model's name is stored beside every vector for exactly that reason.

```java
JdbcEpisodes episodes = JdbcEpisodes.create(c -> c
        .dataSource(dataSource)
        .agentType(new AgentType("support"))
        .embedder(OpenAiEmbedder.create(e -> e.fromEnv().dimension(512)))
        .shown(5));

EpisodeSummarizer summarizer = EpisodeSummarizer.create(c -> c
        .agentType(new AgentType("support"))
        .episodes(episodes)
        .histories(factory.histories())
        .leases(new JdbcLeases(dataSource))
        .inference(provider, InferenceOptions.of("claude-sonnet-5")));

factory.create(String.class, h -> h
        .agentType(new AgentType("support"))
        .tool(EpisodeTools.begin(episodes), t -> {})
        .tool(EpisodeTools.recall(episodes), t -> {})
        .inference(in -> in.context(ctx -> ctx
                .summaries(episodes)
                .ambient(EpisodeTools.index(episodes))))
        .listener(summarizer.listener()));
```

Run episodes or the head summariser on an agent type, not both: the head
would fold turns an episode already stands in for. Episodes suit an agent
whose work comes in distinguishable pieces and whose distant pieces come back;
the head summariser suits one long thread that only ever moves forward.

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

Four embedders ship, each one model at one dimension decided where it is
built, because a store's index is sized by it:

| Module | Reaches | Default model |
|---|---|---|
| `nessy-embedding-openai` | OpenAI, and with a base URL every OpenAI-compatible endpoint, which is how a local `nomic-embed-text` on Ollama or LM Studio is reached | `text-embedding-3-small` |
| `nessy-embedding-gemini` | the Gemini Developer API, through java-genai | `gemini-embedding-001` |
| `nessy-embedding-bedrock` | Amazon Titan and Cohere models on Bedrock, through `InvokeModel` | `amazon.titan-embed-text-v2:0` |
| `nessy-embedding-voyage` | Voyage AI, over plain HTTP: the partner Anthropic points to, having no embeddings of its own | `voyage-3.5` |

Each batches where its vendor allows, puts the reply back in the order asked,
refuses a short reply rather than padding it, and has a live test tagged
`live` that runs when the vendor's key is in the environment.

```java
Embedder embedder = OpenAiEmbedder.create(c -> c
        .fromEnv()
        .model("text-embedding-3-small")
        .dimension(512));
```

An `Embedding` carries its model's name and compares by content; its
`similarity` is the cosine between two vectors and refuses a pair from
different models. Episodes rank by it today, as described above; the notebook
and the lessons store are the rest of the roadmap's embeddings-ranked recall
item. Every store degrades to recency when it has no embedder, and the model
can always recall by title.

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

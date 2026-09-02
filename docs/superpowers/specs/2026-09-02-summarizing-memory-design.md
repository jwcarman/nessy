# Summarizing memory

**Status:** BUILT 2026-09-02 as `nessy-memory-summarizing`, with two corrections James caught
during implementation — see §3.1 and §4.1. Read those before the rest; the original reasoning in
both was wrong. Adds the first memory that COMPRESSES
rather than drops.

## 1. What is wrong

Every way Nessy bounds a context today throws information away:

| | What it does |
|---|---|
| `TranscriptMemory.recent(ds, type, maxCharacters)` | drops the oldest messages until the rest fit |
| `Context.keepRecent(n)` | drops all but the last `n` |
| `Context.elideToolResults(n)` | replaces old tool output with `[elided]` |

Each is honest about being lossy, and each is the right answer sometimes. None
preserves what happened. An agent that has been running for a month either carries
a month of verbatim transcript into every model call, or forgets that the customer's
name is Dale.

**Summarizing is the missing third option**: spend one model call to turn a hundred
old messages into a paragraph, and carry the paragraph.

## 2. A summary is not history

`HistoryMessage` is sealed:

```java
public sealed interface HistoryMessage extends ContextMessage
    permits UserMessage, ExchangeMessage, AnswerMessage {}
```

A summary is none of those. **Nobody said it** — it is derived, the way the notebook
index and the plan are derived, and both of those are already `AmbientMessage`:

```java
new AmbientMessage("summary", List.of(new TextBlock(text)))
```

So a summary structurally cannot be stored through `Memory.remember`, which takes a
`HistoryMessage`. That is not an obstacle to work around; it is the type system
saying the summary belongs somewhere else. **Hence its own table** — not merely
because it needs storage, but because it is a different kind of thing.

It also means the summary rides the existing `AmbientMessage` contract: an adapter
renders background the way its vendor prefers — XML tags for one, a heading for
another — and this module never writes markup into a prompt.

## 3. The shape

```java
Memory memory = SummarizingMemory.create(config -> config
    .over(TranscriptMemory.eternal(dataSource, type))
    .model(model)
    .summarizeAfter(40_000)   // characters of uncovered history
    .keepVerbatim(20));       // messages never summarized
```

A **decorator**, not a replacement: it wraps any `Memory` and adds one table.

```
recall(agentId)  =  the summary, if any        (AmbientMessage, from nessy_summary)
                 +  the messages it does not cover
```

### 3.1 Coverage is a SEQUENCE — the count was wrong

This section originally argued for a count, so that the module could decorate any
`Memory`. **That does not work, and James caught it.** Two ways:

- `TranscriptMemory.recent(…)` returns a *window* — the newest messages that fit a
  character budget. A count indexes into a window whose start position is unknown and
  MOVES, so the summary would silently cover the wrong messages.
- Even over `eternal`, applying a count means loading the whole transcript and
  discarding the covered prefix. That is exactly the cost summarizing exists to
  avoid, paid on every recall.

So coverage is `covers_through`, a transcript sequence, and this depends on
`TranscriptMemory` rather than any `Memory` — because it needs to read from a
POSITION, which `Memory.recall` cannot express:

```java
transcript.recallAfter(agentId, coversThrough)   // SELECT … WHERE seq > ?
```

The covered messages are never read at all. The generality the count was chosen for
was not real generality; it was a version that quietly did the wrong thing over half
the memories in this repo.

### 3.2 The table

```sql
CREATE TABLE IF NOT EXISTS nessy_summary (
  agent_type TEXT   NOT NULL,
  agent_id   TEXT   NOT NULL,
  covers     BIGINT NOT NULL,   -- how many of the oldest messages this replaces
  uncovered  BIGINT NOT NULL,   -- how many messages exist, so a sweep can find work
  summary    TEXT,              -- null until the first sweep gets to it
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
  PRIMARY KEY (agent_type, agent_id)
);
```

`uncovered` is what makes a background sweep possible at all: `Memory` cannot be
asked how many messages an agent has, so the count is maintained where it is already
being written. `remember` pays one small UPDATE; the sweep reads
`uncovered - covers > threshold` and needs to know nothing about how any memory
stores.

```
```

One row per agent: a summary REPLACES the previous one rather than accumulating,
because what matters is the single paragraph carried into the next call. Keyed on
the type AND the id, like every other table here, because an id is unique only
within its type.

`SummarizingMemory.forget` deletes this row as well as delegating — a summary of a
forgotten agent is exactly the thing forgetting is for.

### 3.3 One row means the summary is recursive, and that has a cost

One row per agent is the right shape — what matters is the single paragraph carried
into the next call, and unbounded summary rows would defeat the point. But it forces
something worth stating plainly rather than discovering:

```
sweep 1:  summarize(messages 1-100)          -> S1, covers = 100
sweep 2:  summarize(S1 + messages 101-200)   -> S2, covers = 200
```

**From the second sweep onward, every summary is a summary of a summary.** Each
generation is lossy over the last, so detail decays geometrically: a fact mentioned
once at message 3 survives S1 with some probability, S2 with that probability
squared, and is gone long before the agent is. The agent does not forget suddenly —
it fades.

Two alternatives, both rejected:

- **A chain of summary rows**, concatenated at recall, so nothing is ever
  re-summarized. It removes the drift and reintroduces the growth this module exists
  to stop.
- **Re-summarizing from the original messages every time.** No drift, because there
  is only ever one generation — but the input grows without bound, so eventually
  each sweep feeds the entire transcript to a model to produce one paragraph. That
  is affordable for a long time and not forever.

The second is worth remembering: it is strictly better while the transcript is small
enough to re-read, and a later version could summarize from source until some
ceiling and only then start compounding. That is a real improvement with a real
threshold to pick, and picking it without measurement would be guessing.

**What this means for the prompt.** Because the summary is its own next input, the
summarizing prompt must ask for something stable under repetition — names, decisions,
commitments, open questions — rather than prose that re-narrates. A summarizer told
"summarize the conversation" produces something that degrades into vagueness after
five generations. This is the part of the design most likely to be got wrong by
writing the obvious prompt.

## 4. Where the model call goes

**A `Memory` has never needed a model.** `recall`, `remember` and `forget` take an
`AgentId` and nothing else. Summarizing needs one, and where it goes is the real
decision in this spec.

**Rejected — a `ContextTransformer` stage.** Stages cannot persist, so it would
summarize on every recall: a model call per turn, forever, for a paragraph that
barely changed. The pipeline is for assembling context, not for producing it.

**Rejected for now — a summarizer AGENT**, with its own `AgentType`, prompt and
harness. It is the more elegant shape, it composes with `forget` (a disposable
summarizer per run), and it is where this should go IF summarizing ever wants tools
of its own — reading a notebook, say, to summarize with the agent's own notes in
view. It is rejected today for cost, not for taste: an agent brings a turn, a
backlog row, durable state and a transcript, to make one model call with no tools.
That is a large machine for a small job, and the seam below does not preclude
building it later.

**Chosen — the memory holds a `Model`.** One dependency, one call, no new
machinery:

```java
ModelResult summary = model.stream(new ModelRequest(
    Context.of(messagesToCompress), SUMMARIZE_PROMPT, maxTokens, List.of(), Set.of()));
```

The model is given NO tools, deliberately. A summarizer that could call something
is a summarizer that can act, and nothing it reads should be able to make it do
anything — the same rule the judging agent lives under, for the same reason.

### 4.1 A write hands the work to a thread — there is no sweeper

The first draft here was a periodic sweep, and a cached `latest_seq` column so the
sweep could find work without scanning. **James rejected both, and was right about
each.**

`latest_seq` duplicated something already indexed: `nessy_transcript` is keyed
`(agent_type, agent_id, seq)`, so `max(seq)` per agent is an index lookup. Caching it
cost an UPDATE on the write path for **every remembered message** — paying on the hot
path to save on the cold one. It also forced a nullable `summary` column, because a
row had to exist from the first message just to hold the counter.

And the sweeper itself was more machinery than the job needs:

```
remember()  ->  transcript.remember(…), then MAYBE hand off to an Executor
```

Nothing scans. A write notices the agent has outrun its summary and submits the work;
the model call happens on somebody else's thread. Two cheap guards keep that from
being expensive: the check only happens every `keepVerbatim` writes, so most writes
cost one in-memory increment and no query at all, and an in-flight set means a burst
past the threshold fires once rather than once per message.

Doing it inline at the tail of `remember` was the first draft, and it is worse in
three ways. It puts a vendor call on the end of a turn, so the turn that happens to
cross the threshold is the slow one for no reason the person can see. It makes a
model outage a turn-completion problem. And it gives the work nowhere to be retried
from — if the process dies mid-summary, nothing remembers to try again.

**A late summary is not a wrong answer.** This is what makes background natural
here rather than merely faster: until the sweep catches up, `recall` returns the
previous summary plus more verbatim history than the threshold intends. That is a
context slightly larger than planned, which costs tokens — not a context that is
wrong. Compare a background *approval*, where being late means an unanswered
question. Compression is the rare thing that degrades in exactly the direction you
want.

The sweep needs no new idiom. `ReminderSweep` is the model: a clock as a parameter,
a bounded batch, and its own test that fires it without waiting for real time.

### 4.2 Concurrency is a cost problem, not a correctness one

Two threads — or two processes — can both decide an agent is behind. Both call a
model. Both write. The write is what makes that safe:

```sql
UPDATE nessy_summary SET covers_through = ?, summary = ?
 WHERE agent_type = ? AND agent_id = ? AND covers_through < ?   -- monotonic
```

Whichever covers less loses. A summary never goes backwards, never re-reads history it
had compressed, and the winner always covers exactly what it claims — no gap, no
double-count. **What a race costs is a duplicate model call, not a wrong answer.**

That is why there is no lock. A distributed lock would buy the occasional duplicate
call across processes and bring stale locks, timeouts, and a new way for summarizing
to fail — to protect a few cents, when the data is already protected. The in-flight
set handles the common single-process case for nothing.

### 4.3 When the model refuses

A summarizer that fails must not lose history. If the call errors, times out, or
comes back empty, **nothing is written and the old summary stands** — the agent
carries more verbatim history than intended for a while, which is a cost, not a
loss. The failure is logged at `WARN`, because unlike a broken gate this degrades
rather than breaks.

The one thing it must never do is record a summary it is not confident in and drop
the messages behind it. Deleting is not this module's job: **the underlying memory
keeps everything.** The summary is an optimisation applied at recall, so a bad
summary is repairable by deleting one row, and no history is ever destroyed to make
one.

## 5. Testing

- **The contract test still passes**, unchanged. A summarizing memory is a `Memory`,
  and `MemoryContractTest` is what says so — including the three `forget` cases.
- **Below the threshold, the sweep finds nothing and no model is called.** A model
  that throws if invoked is the assertion.
- **`remember` never calls a model**, whatever the threshold — the same throwing
  model, asserted from the write path, so nobody can quietly put a vendor call back
  on a turn's thread.
- **Crossing the threshold produces one `AmbientMessage` and keeps the tail
  verbatim** — assert the recalled context is summary-plus-recent, in order.
- **A second crossing replaces the row rather than adding one.**
- **A model that fails leaves the previous recall intact**, and a model that returns
  empty does too. These are the tests that stop a bad day becoming amnesia.
- **`forget` takes the summary with it.**
- **A second sweep summarizes the PREVIOUS summary plus the new messages**, not the
  whole transcript — asserted on what the model was handed, because this is the
  behaviour that bounds the cost and the one a well-meaning refactor would undo.
- **Concurrency**: two sweeps over the same row produce one summary and one model
  call — the `FOR UPDATE` claim, tested the way the backlog's take is.
- **A late sweep is not a wrong answer**: with work pending, recall returns the
  previous summary plus the extra verbatim history, in order. This is the test that
  says background is safe rather than merely fast.
- **No tools are offered to the summarizer** — assert the `ModelRequest` carries an
  empty tool list, so a future refactor cannot quietly hand it capability.

## 6. Out of scope

- **Summarizing on demand** — "compress this agent now, I am about to ask it
  something expensive". Plausible, unrequested, and it would need a receipt, which
  the background design deliberately does not have.
- **Choosing what to keep verbatim by importance** rather than recency. Interesting,
  unmeasured, and a different feature.
- **A summarizer agent** (§4), until summarizing wants tools.
- **Summarizing from source until a ceiling** (§3.3), which would delay compounding
  drift. Strictly better while a transcript is small enough to re-read; the ceiling
  is the part that needs measuring first.

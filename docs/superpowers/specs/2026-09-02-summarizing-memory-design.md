# Summarizing memory

**Status:** designed 2026-09-02, not built. Adds the first memory that COMPRESSES
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

### 3.1 Coverage is a count, not a sequence number

The summary row records **how many messages it covers**, not a transcript sequence
number.

That is deliberate. A seq belongs to `TranscriptMemory`'s columns, and reaching into
them would weld this to one implementation. A count is expressible against the only
thing every `Memory` offers — the list `recall` returns — so this decorates a
notebook-backed memory, a pipeline, or something an application wrote, without
knowing anything about how they store.

The cost: a memory that reorders or removes history behind this module's back would
misalign the count. That is a contract this module states rather than defends, and
every memory in the repo appends.

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

### 4.1 It runs in the background

**Not on the turn's thread at all.** `remember` notices that history has grown and
records that fact; a sweeper does the work later, the way `ReminderSweep` already
handles deadlines nobody is waiting on.

```
remember()  ->  one small UPDATE: uncovered = uncovered + 1
SummarySweep ->  finds rows where uncovered - covers > threshold, summarizes, writes back
```

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

### 4.2 Two sweeps, one summary

Two sweepers can run — a second process, or an overlapping tick — and both can find
the same row over threshold. Unguarded that is two vendor calls producing two
summaries, one silently overwriting the other.

The fix is the discipline `BacklogStore.take` already uses: claim the row `FOR
UPDATE` before summarizing, so the second sweep finds it taken and moves on. Cheaper
than the alternative, and the alternative is paying a vendor twice for the same
paragraph.

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
- **Concurrency**: two sweeps over the same row produce one summary and one model
  call — the `FOR UPDATE` claim, tested the way the backlog's take is.
- **A late sweep is not a wrong answer**: with work pending, recall returns the
  previous summary plus the extra verbatim history, in order. This is the test that
  says background is safe rather than merely fast.
- **No tools are offered to the summarizer** — assert the `ModelRequest` carries an
  empty tool list, so a future refactor cannot quietly hand it capability.

## 6. Out of scope

- **Summarizing the summary.** Eventually a summary of summaries drifts; recursive
  compaction is its own problem with its own failure mode, and nothing needs it yet.
- **Summarizing on demand** — "compress this agent now, I am about to ask it
  something expensive". Plausible, unrequested, and it would need a receipt, which
  the background design deliberately does not have.
- **Choosing what to keep verbatim by importance** rather than recency. Interesting,
  unmeasured, and a different feature.
- **A summarizer agent** (§4), until summarizing wants tools.

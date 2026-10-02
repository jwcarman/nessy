# Stratified context: chapters, typed sources, and placement owned by the provider

**Status: PARTLY APPROVED, BUILT ON A BRANCH.** Written on 2026-10-01 from rulings James made in
conversation that day, for an overnight build on the branch `stratified-context`. Nothing here is
on `main`. Written on Opus because the session could not switch models; the model policy puts
specifications on Fable, so this is a draft for James to revise, not a finished record.

§2 says which names James approved by name and which are provisional. A provisional name is built
so the design can be tried; it has not had his yes and may be renamed.

Date: 2026-10-01. Replaces the summarising arrangement described in
`2026-09-02-summarizing-memory-design.md` and removes the episodic module. Evidence for the
placement rules is in the probe write-up, "Nessy Context Spec: What the Probes Show", and in
`~/IdeaProjects/nessy-context-probes/`.

---

## 1. What this changes, in one page

An agent's context is six **strata**, always in this order:

```text
INSTRUCTIONS   the system prompt
HISTORY        summaries of closed chapters, then the tail: completed turns after the last summary
MEMORY         what was recalled for this turn
STATE          the agent's standing situation
ACTIVE TURN    the turn being answered
AMBIENT        what is true right now, asked afresh on every call
```

The core says what each stratum holds and who may contribute to it. A provider adapter says where
each one lands on its vendor's wire.

Three things are new:

1. **History is cut into chapters.** A `ChapterPolicy` decides where a chapter ends. A
   `Summarizer` writes the text that stands in for a closed chapter. Both run when a turn ends, off
   the agent's own thread, under a lease. A closed chapter never changes and its summary is written
   once.
2. **Memory and State are strata with typed sources**, beside the existing `AmbientSource`.
3. **The canonical context names the strata**, so an adapter can place Memory and State between
   the completed turns and the active one, and Ambient at the tail.

What goes: the read-side `Summarizer` (`forAgent`, `summarizedThrough`), `summaries(...)` on the
configuration, `Block.SummaryContent`, and the modules `nessy-memory-summarizing` and
`nessy-memory-episodic`.

## 2. Names

Approved by James by name on 2026-10-01:

| Name | What it is |
|---|---|
| stratified context, stratum, strata | the model and its parts |
| `Chapter(agentType, agentId, from, through)` | a closed run of an agent's turns, both ends included |
| `Summary(Chapter chapter, String text)` | the text shown in place of one chapter; summaries are always text |
| `Summarizer` | `String summarize(Chapter chapter)`; the writer |
| `ChapterPolicy` | `List<TurnId> ends(OpenTurns open)`, with the factory `every(int)` |
| `OpenTurns(agentType, agentId, turns)` | the completed turns not yet in a chapter, as ids |
| `chapterPolicy(...)`, `summarizer(...)` | on `HarnessConfig`, replacing `summaries(...)` |
| `maxTail` | stays; default 40 |

Approved as behaviour, with no name ruled on: a maximum chapter length that forces a cut; chapters
on by default (every 20 turns, prose, the agent's own model) with a way to turn them off; a failed
summary leaves its turns verbatim and is retried at a later turn end; a model declaring its own
chapters with a tool.

Provisional, awaiting James's yes:

| Name | What it is |
|---|---|
| `Memory`, `MemorySource`, `Block.MemoryContent` | the Memory stratum's record, source and block position |
| `State`, `StateSource`, `Block.StateContent` | the same for State |
| `memory(...)`, `state(...)` | on `HarnessConfig` and `ContextConfig` |
| `maxChapterLength(int)` | on `ContextConfig`; default 30 |
| `chapterLeaseTtl(Duration)` | on `ContextConfig`; how long the lease for one model call is believed held; default two minutes |
| `ChapterKeeper` | the engine class that cuts and summarises when a turn ends |
| `withoutChapters()` | on `ContextConfig`; the off switch |
| `Chapters` | the store, in the backend SPI |
| `chapters()`, `leases()` | on `DirectBackend` and `QueuedBackend` |
| `ProseSummarizer` | the default `Summarizer`, in the engine |
| `DeclaredChapters`, the tool `begin_chapter` | a model-declared chapter policy |
| `InferenceContext(summaries, tail, memory, state, activeTurn, ambient)` | the canonical context |
| `nessy.context.changed` | the stability measurement's key |

## 3. History in chapters

### 3a. The rules that do not vary

- A chapter is a contiguous run of completed turns. Chapters do not overlap and leave no gaps: the
  first begins at the agent's first turn, and each later one begins at the turn after the one
  before it ended.
- A chapter never reaches the turn in flight.
- A closed chapter is immutable. Its summary is written once and never replaced.
- The raw turns of a closed chapter are never deleted.

Everything pluggable works inside those rules, which is what lets the choice of policy or writer
be made late: any of them leaves the head of the context stable.

### 3b. `ChapterPolicy`: where a chapter ends

```java
public record OpenTurns(AgentType agentType, AgentId agentId, List<TurnId> turns) {}

@FunctionalInterface
public interface ChapterPolicy {
  List<TurnId> ends(OpenTurns open);
  static ChapterPolicy every(int turns) { ... }
}
```

Asked when a turn ends. `open.turns()` is the agent's completed turns that are not yet in a
chapter, oldest first, as ids only. The answer is the turns that each end a chapter, in order;
empty means nothing closes yet. A policy may answer behind the newest turn, leaving later turns
open, and may return several ends. A policy that judges by content loads the turns itself.

The framework derives the chapters: the first runs from `open.turns().get(0)` through the first
end, the next from the id after that through the second end, and so on. An answer naming a turn
that is not in `open`, or out of order, closes nothing and is logged.

`every(n)` closes the oldest `n` open turns once there are `n` of them.

### 3c. The maximum chapter length

A setting on the context, default 30. Two uses:

- When the policy closes nothing and at least that many turns are open, the framework closes a
  chapter over the oldest that many.
- A chapter the policy asks for that would be longer is split into chapters of at most that many
  turns.

It sits between the default chapter size (20) and `maxTail` (40).

### 3d. `Summarizer`: what stands in for a chapter

```java
@FunctionalInterface
public interface Summarizer {
  String summarize(Chapter chapter);
}
```

Handed the chapter and nothing else. One that reads the turns, or the earlier summaries, loads
them itself. It returns text; the framework stores `new Summary(chapter, text)`. Blank text is
treated as a failure.

The default is `ProseSummarizer`: one model call over the chapter's turns, using the agent type's
own provider and options.

### 3e. When and where they run

When a turn ends, on a thread of its own, never on the agent's thread or the narration thread. So
both interfaces may block and may call a model.

1. Under the lease for this agent: read where the closed chapters end, read the open turns, ask the
   policy, apply §3c, and append the chapters.
2. For each closed chapter without a summary, oldest first, under the same lease kind taken again:
   ask the summariser and store the text.

The lease is taken once per model call, never across several, because a lease has a fixed time
limit and cannot be renewed. A refused lease skips the work; the next turn end tries again.

A lease can be overtaken while its holder is still running, so the store is what guarantees
correctness (§3f) and the lease only prevents wasted model calls.

A summariser that throws, or returns blank text, leaves the chapter closed and unsummarised. Its
turns stay verbatim and a later turn end tries again.

### 3f. The store

`Chapters`, in the backend SPI, implemented in memory and on JDBC, and reached through the backend
so that it always agrees with the events it describes.

```java
public interface Chapters {
  boolean append(AgentType type, AgentId agent, Optional<TurnId> after, List<Chapter> chapters);
  boolean summarize(Summary summary);
  Optional<TurnId> closedThrough(AgentType type, AgentId agent);
  List<Chapter> unsummarized(AgentType type, AgentId agent);
  List<Summary> summaries(AgentType type, AgentId agent);
}
```

- `append` stores the chapters only if the agent's closed chapters still end at `after` (empty for
  an agent with none). It stores all of them or none. This is what makes two holders cutting the
  same open turns differently safe: the first wins and the second stores nothing.
- `summarize` stores the text only if that chapter has none. The first wins.
- `summaries` returns the unbroken run of summarised chapters from the agent's first chapter,
  oldest first. A chapter whose summary is missing ends the run, even if a later one is written.

### 3g. What the model is shown

Summaries are the unbroken run from the start. The **tail** is the completed turns after the last
of those summaries, capped at `maxTail` newest. The turn in flight is not part of the tail; it is
the Active turn.

So a chapter that is closed but not yet summarised is still shown as turns, and the context
changes once, when the summary lands.

`maxTail` is the backstop for the cases the maximum chapter length cannot reach: a summary that
never arrives, and chapters switched off. When it is what limits the context, the window slides
by one turn every turn, which is the pattern the probes measured as the most expensive. With
chapters on it should not bind.

### 3h. Configuration

```java
harness.chapterPolicy(ChapterPolicy.every(20))
       .summarizer(mySummarizer);

harness.inference(in -> in.context(ctx -> ctx
    .maxChapterLength(30)
    .maxTail(40)
    .withoutChapters()));
```

Defaults: `ChapterPolicy.every(20)`, `ProseSummarizer` on the agent's own provider and model,
maximum chapter length 30, `maxTail` 40. `withoutChapters()` turns the whole pipeline off: nothing
is cut or summarised and history is the last `maxTail` completed turns.

### 3i. A model declaring its own chapters

`DeclaredChapters` installs a tool, `begin_chapter`, and a policy. The model calls the tool during
a turn to say a new chapter begins there. When that turn ends, the policy returns the turn before
it as a chapter end, and the turn that made the call stays open as the first of the next chapter.

It keeps no store. The call is already recorded in the turn, so the policy finds it by reading the
open turns. The first open turn cannot end a chapter before itself and is ignored. The maximum
chapter length covers a model that never calls the tool.

## 4. Memory and State

Each is a stratum with a typed source, in the same shape as `AmbientSource`: a source interface
with a fixed `kind()`, a record carrying that kind and content, and a block position.

```java
public interface MemorySource {
  String kind();
  Optional<Memory> forAgent(AgentId agentId, Turn current);
}

public interface StateSource {
  String kind();
  Optional<State> forAgent(AgentId agentId, Turn current);
}
```

- **Memory** is what was recalled because it bears on this turn, so its source is handed the turn
  being answered.
- **State** is the agent's standing situation. Its source is handed the turn being answered too, so
  it can answer as of the start of that turn and hold still for the whole of it.
- **Ambient** is unchanged: what is true right now.

There is no framework snapshot. A source returns what is current each time it is asked, which is
once per call to the model, and nothing holds an earlier answer on its behalf. A source that wants
its content stable across a turn keeps it stable itself. The framework measures (§6); it does not
enforce.

Two sources in one stratum may not share a kind; that is refused when the harness is built, as it
is for ambient today.

The existing notebook and planning modules stay `AmbientSource`s. Which stratum each belongs in is
a question for after the placement measurements (§8).

## 5. The canonical context and where each stratum lands

```java
public record InferenceContext(
    List<Summary> summaries,
    List<Turn> tail,
    List<Memory> memory,
    List<State> state,
    Turn activeTurn,
    List<Ambient> ambient) {}
```

The system prompt stays on `InferenceRequest`, where it is today.

Placement is each adapter's own. The first version uses one rule on every provider, because the
measurements support it on three of four and it is the simplest thing to reason about:

| Stratum | Where it lands |
|---|---|
| Instructions | the vendor's system field, alone |
| Summaries, tail | messages, as today |
| Memory, State | user-side text at the head of the active turn's first message, tagged `<memory kind="...">` and `<state kind="...">` |
| Active turn | messages, as today |
| Ambient | user-side text at the very end of the request, after the last content of the active turn |

Ambient leaves the system prompt on every provider. Today a change in any ambient source changes
the system field, which sits ahead of every message, so it invalidates the cache for the whole
conversation.

On Anthropic the cache markers stay where they are (the end of this request and the end of the
last one) and the ambient text follows the marked block, so the cached prefix never contains it.

What the measurements said, and where this first version departs from them:

- Memory and State before the active turn beat re-sending them at the tail on Anthropic, OpenAI
  and a local model. On Gemini the tail was cheaper on short turns and the same on long tool
  loops. Gemini gets the common rule in this version.
- A stale plan frozen ahead of the active turn made two models redo finished work. Nothing here
  freezes anything, so that failure needs a source that returns stale content to occur.
- On Anthropic with thinking on, a stateless renderer that moves ambient to the tail changes the
  text ahead of every thinking block on every call, so the vendor may drop the turn's thinking each
  time. This is unmeasured and is the first thing to check (§8).

## 6. Measuring stability

On every call the engine works out a fingerprint of each stratum of the context it assembled and
compares it with the previous call's for the same agent. It reports the earliest stratum that
changed, as the low-cardinality key `nessy.context.changed` on the context-assembly observation:
`first-call`, `none`, `history`, `memory`, `state`, `active-turn` or `ambient`.

The earliest change is what decides how much of a provider's cached prefix survives. In a healthy
agent the answer is `active-turn` or `ambient` inside a turn and `history` at the start of one.
`memory` or `state` in the middle of a turn means a source is not holding still.

The previous fingerprints are held in the process, bounded, and are not stored.

## 6a. Instructions are fixed

Added on James's instruction late on 2026-10-01: "we should not allow folks to create changing
system prompts".

An agent type's instructions are one text, built when its harness is built: the system prompt,
then each section a module added with `instructions(String)`, in order. Nothing is asked again per
call, so the text cannot differ between calls or between agents of the type. `SystemPromptSource`
is removed, and the prompt templating renders once.

What varied by agent moves to a `StateSource`; what varies by the moment moves to an
`AmbientSource`. Because every agent of a type then sends the same instructions and tools, a
provider's cache for that prefix is shared across all of them.

`instructions(String)` and the removal of `SystemPromptSource` are provisional.

The engine class `Instructions` joins the prompt and the sections for both doors.

## 6b. Noticing a broken cache

Inside a turn each request is the previous one with more at the end, so the tokens a provider
reads from its cache should only grow from call to call. The engine remembers the last cache-read
count per agent and turn and reports a call that reads fewer, as a warning and as a short
observation of its own named `nessy.cache.read.fell`. Read with §6: a fall while only `active-turn` or `ambient` changed
is the provider's cache expiring; a fall while an earlier stratum changed is ours.

No narration event carries an inference's usage, so the watch (`CacheWatch`) is told directly by
the engine's inference handler, which knows the agent, the turn and the counts.

It is dependable on Anthropic. OpenAI and Gemini cache implicitly and were measured returning no
cached tokens on a noticeable share of calls for no visible reason, so there only the rate means
anything.

## 7. What is removed

- `nessy-memory-episodic` (`begin_episode`, `recall_episode`, the embedding-ranked index). The
  model-declared boundary returns as §3i. `Embedder` stays and has no consumer.
- `nessy-memory-summarizing` (`HeadSummarizer`, `JdbcSummaries`). The prompt and the model call
  survive as `ProseSummarizer`.
- The tables `nessy_summary` and `nessy_episode` are no longer created. A database that has them
  keeps them; nothing reads them.

## 8. Open questions for James

1. Ambient placement on Anthropic when the agent thinks: the tail (built), or the start of the
   active turn. `anthropic_ambient_placement_probe.py` measures the three placements.
2. Whether the plan and the notebook index belong in State now that State returns what is current.
3. Whether Gemini should put Memory and State at the tail.
4. The provisional names in §2.
5. Whether a chapter carries an ordinal, and whether there is a tool to reopen one. Neither is
   built; the measurements on chat history showed little gain from fetching.
6. What becomes of old summaries once there are hundreds. Nothing folds them together.
7. Whether tool-result stubbing follows the same cuts.

## 2a. The whole public surface this work changes

§2 names the concepts. This is the complete list of what is added, changed or removed in public
types, for James to rule on by name. Everything here is provisional unless §2 says it was approved.

**`nessy-api`**
- Added: `Chapter` (with `covers`), `OpenTurns`, `ChapterPolicy` (`ends`, `every`), `Memory` (with
  `text`), `MemorySource` (`kind`, `forAgent(AgentId, Turn)`, `constant`), `State` (with `text`),
  `StateSource` (`kind`, `forAgent(AgentId, Turn)`, `constant`), `Block.MemoryContent`,
  `Block.StateContent`.
- Changed: `Summary` is `(Chapter chapter, String text)`; `Summarizer` is `String summarize(Chapter)`.
- On `HarnessConfig`: `chapterPolicy`, `summarizer`, `memory`, `state`, `instructions`.
- On `ContextConfig`: `chapterPolicy`, `summarizer`, `memory`, `state`, `maxChapterLength`,
  `chapterLeaseTtl`, `withoutChapters`; `maxTail` defaults to 40 and counts completed turns only.
- Removed: `SystemPromptSource`; `systemPrompt(SystemPromptSource)` on both door configs;
  `summaries(...)` on `HarnessConfig` and `ContextConfig`; `Summarizer.none()`, `forAgent`,
  `summarizedThrough`; `Summary.text(...)`; `Block.SummaryContent`.

**`nessy-inference-spi`**
- `InferenceContext` is `(summaries, tail, memory, state, activeTurn, ambient)`, with a derived
  `turns()` (the tail then the active turn) and `of(List<Turn>)`, which takes the last turn as the
  active one and refuses an empty list.

**`nessy-backend-spi`**
- Added: `Chapters` (`append`, `summarize`, `closedThrough`, `unsummarized`, `summaries`);
  `chapters()` and `leases()` on `DirectBackend` and `QueuedBackend`. Table `nessy_chapter`.

**`nessy-engine`** (public classes, not API)
- `ChapterKeeper` (`LEASE_KIND` = `nessy.chapters`, `DEFAULT_LEASE_TTL`, `listener()`,
  `keep(AgentId)`), `ProseSummarizer` (with `PROMPT`), `DeclaredChapters` (`TOOL_NAME`, `Beginning`,
  `tool()`, `feature(TurnHistories)`), `ChapterSettings`, `Instructions`, `CacheWatch`,
  `ContextFingerprint`, `Transcripts`.
- `TurnHistory` gains `turnsBetween` and `completedAfter`, which any outside implementation must
  now provide. `DefaultDirectHarnessFactory` gains `histories()`.
- `ObservedSummarizer`, `ObservedChapterPolicy`, `ObservedMemorySource`, `ObservedStateSource`.
- Both doors now refuse two sources of one kind within a stratum; the direct door did not check
  ambient before. The direct door now refuses a `maxTail` that is not positive.

**`nessy-prompt`**
- `TemplatedSystemPrompt` no longer implements a source: it offers `render(...)`, returning a
  `SystemPrompt`. `PromptVariableSource` is removed; `PromptVariables` gains `supplied` and
  `firstOf`. `EnvironmentVariables.of` returns `PromptVariables`.

**`nessy-console`**
- `ReplConfig.systemPrompt(SystemPromptSource)` is removed.

**`nessy-spring-boot`**
- The starter's prompt bean is a `SystemPrompt`, rendered once at startup.

**Removed modules**
- `nessy-memory-summarizing`, `nessy-memory-episodic`.

**Observation and log names**
- `nessy.context.changed` (a key on the context-assembly observation), `nessy.cache.read.fell`,
  `nessy.summary`, `nessy.chapter.policy`, `nessy.context memory <kind>`,
  `nessy.context state <kind>`; the log prefix `NESSY CACHE:`.

## 9. What the build found (2026-10-02)

Decisions the build made in James's absence. Each is recorded in the build's ledger with its
reason and is his to overturn.

1. **A turn's end is announced before it is committed.** On a JDBC backend both doors narrate
   `TurnEnded` inside the transaction that writes the turn, so a listener on another thread may
   read before the turn is visible. This was true before this work, for every listener. The chapter
   keeper is told which turn ended and waits up to two seconds for it to be visible before cutting.
   The root fix is to narrate after the commit.
2. **A policy that throws, or answers invalidly, closes nothing, and the maximum chapter length
   still applies.** A null answer is an invalid answer.
3. **`maxTail` must exceed the maximum chapter length** when chapters are on, or the harness
   refuses to build. An application that sets a tail of 30 or less today is affected.
4. **`chapterPolicy(...)` or `summarizer(...)` after `withoutChapters()` turns chapters back on.**
   The last call wins.
5. **Two public engine helpers the plan did not name:** `ChapterSettings` and `Instructions`, each
   shared by the two doors' configurations. Neither is API.
6. **A state source is handed the turn being answered**, as a memory source is, so that it can
   answer as of the start of that turn.
7. **The append to the chapter store is a compare-and-set on the previous chapter's end**, enforced
   by a unique constraint (`after_turn`), after the first design was measured letting three of eight
   racing writers through.
8. **The starter's system-prompt bean has no consumer**, and had none before. Whether the starter
   should apply it to harnesses or drop it is open.

### Withdrawn: Anthropic's markers when memory or state is present

Memory and state sit at the head of the active turn's first message, and a finished turn is
rendered without them. So the first call of the next turn differs from what was cached from that
message on, and every turn is written to the cache twice over its life, once with them and once
bare. If the previous turn was longer than the vendor's 20-block lookback, the two markers used
today read nothing and the whole conversation is rewritten.

A rule to fix this was built and withdrawn. It placed three message markers beside the system
prompt's and the tools' own, which is five where the vendor allows four. The reviewer's proposal,
to measure live before building:

1. Count the tools' marker: stop marking the tools when caching is on (the system prompt's marker
   already covers them), or allow two message markers when tools are offered.
2. On the first call of a turn, mark the last two ends of history and the end of the request.
3. On later calls, mark the last end of history, the previous user-side message and the end of the
   request.
4. Apply this whether or not the request carries memory or state, because what matters is whether
   the previous turn was rendered with them, which a stateless renderer cannot know.

No shipped module supplies memory or state yet, so nothing is worse than before without it.

### From the final whole-branch review

Left as they are for James to decide; none is a correctness defect.

- **The keeper waits its full two seconds after a turn ended by the turn policy.** Such a turn is
  never shown as completed, so the wait cannot be satisfied.
- **The default lease (two minutes) is shorter than the default model-call timeout (five).** A
  summary that takes longer than the lease is started a second time at the next turn end; the
  store keeps the first to arrive. The default summariser's model call has no deadline of the
  engine's own.
- **A summary that keeps failing is retried at every turn end, with no backoff.**
- **A chapter policy that asks for chapters longer than the maximum is overridden by the forced
  cut**, silently.
- **The adapters differ in three places.** When a request ends on tool results, Anthropic and
  Bedrock add the ambient text to that user message while Gemini and both OpenAI wires add a user
  message of its own. When the active turn renders to nothing, Anthropic sends no memory or state
  and the other four send them as a message of their own; the assembler never produces that case.
- **No placement has met a real wire** except Anthropic's (probed) and a local OpenAI-compatible
  server through the lab, with no tools and no ambient.
- **A cache-fall warning is expected in two ordinary cases:** on the call after a summary lands in
  the middle of a turn, and on an Anthropic answer-only call, which drops the tools.

# Stratified Context Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task.

**Goal:** Cut an agent's history into chapters with a pluggable policy and summariser, add Memory and State as strata with typed sources, and let each provider adapter place the strata.

**Architecture:** The API gains `Chapter`, `OpenTurns`, `ChapterPolicy` and a write-side `Summarizer`; the backend SPI gains a `Chapters` store reached through the backend beside `Leases`; the engine runs policy and summariser when a turn ends, off the agent's thread and under a lease, and assembles a canonical `InferenceContext` whose fields are the strata. Each provider adapter renders that context: instructions alone in the system field, Memory and State at the head of the active turn, Ambient at the end of the request.

**Tech Stack:** Java 25, Maven (`./mvnw`), JUnit 5 with AssertJ, Spring JDBC `JdbcClient`, PostgreSQL through Testcontainers, Micrometer Observation. No mocking library anywhere.

**Spec:** `docs/superpowers/specs/2026-10-01-stratified-context-design.md`

## Global Constraints

- Work only in the worktree `/Users/jcarman/IdeaProjects/nessy/.worktrees/stratified-context`, on the branch `stratified-context`. Use absolute paths under it. Never touch the main checkout, never merge, never push.
- Never suppress a warning: no `@SuppressWarnings`, no equivalent. Fix the cause.
- No star imports, regular or static. No fully-qualified type names in code: import and use the simple name.
- Tests: snake_case method names with `@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)`, grouped in `@Nested` classes by behaviour. Match the style of the tests already in the module being changed where it differs.
- An exception-assertion lambda (`assertThatThrownBy` and the like) contains exactly one invocation that can throw; arrange everything else outside it.
- Assert a collection is not empty before any all-match or none-match assertion on it.
- No mocking library. Use hand-written fakes and scripted providers, as the existing tests do.
- Every source file carries the Apache licence header. Run `./mvnw spotless:apply license:format` before every commit.
- Builds: iterate with `./mvnw -q -pl :<artifactId> -am test` (artifactId with the colon, always `-am`, no `clean`). The final gate, once per task before its last commit, is `./mvnw -q clean verify`; a task that touches JDBC, locks, leases or a backend gates on `./mvnw -q clean verify -Dnessy.excludedGroups=live` instead, so the Testcontainers tests run. Judge a build by its exit code, never by grepping its output.
- Never run two Maven processes at once in the worktree. Start every build command with `pgrep -fl nessy-example;` so a running example application is seen; if one is running, stop and report it.
- Live tests (tagged `live`) spend tokens and need keys this environment does not have. Write them where a task asks for one, tagged `live`, and do not run them.
- Javadoc and comments describe what the code does now. No history, no roads not taken, no references to this plan or to tasks.
- Match the surrounding code: its comment density, its naming, its idiom. The project writes long explanatory javadoc on public types; new public types get the same.
- Commit messages follow the repository's style: a lower-case type prefix and a sentence, for example `feat: a chapter policy says where an agent's history is cut`. End every commit message with a `Co-Authored-By:` line naming the model you are running as, then the line `Claude-Session: https://claude.ai/code/session_01AFNcEjRLnMqMPkEMiki84C`.
- Names marked provisional in the spec (§2) are built exactly as the task gives them. Do not rename them and do not invent other public names; if a task seems to need a public type or method it does not name, stop and report it.

## Review Focus

- An agent with fewer open turns than the chapter size: nothing is cut, nothing is summarised, no model call is made.
- A summariser that throws or returns blank text: the chapter stays closed, its turns are still shown verbatim, and a later turn end tries again.
- Two holders cutting the same open turns differently: the store keeps the first and stores nothing from the second, with no gap and no overlap.
- A context with no Memory, no State and no Ambient: every adapter renders exactly what it rendered before for the same turns, apart from the system field no longer carrying ambient.
- An ambient source whose content changes on every call: on Anthropic the cached prefix must not contain it, so the cache marker sits on the block before it.

---

### Task 1: The chapter vocabulary in the API

**Files:**
- Create: `nessy-api/src/main/java/org/jwcarman/nessy/api/turn/Chapter.java`
- Create: `nessy-api/src/main/java/org/jwcarman/nessy/api/OpenTurns.java`
- Create: `nessy-api/src/main/java/org/jwcarman/nessy/api/ChapterPolicy.java`
- Test: `nessy-api/src/test/java/org/jwcarman/nessy/api/ChapterPolicyTest.java`
- Test: `nessy-api/src/test/java/org/jwcarman/nessy/api/turn/ChapterTest.java`

**Interfaces:**
- Consumes: `AgentType`, `AgentId`, `TurnId` (all in `org.jwcarman.nessy.api`).
- Produces: `Chapter`, `OpenTurns`, `ChapterPolicy` exactly as below. Later tasks use these signatures.

Purely additive. Nothing existing changes.

- [ ] **Step 1: Write `Chapter`**

```java
package org.jwcarman.nessy.api.turn;

import java.util.Objects;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.TurnId;

/**
 * A closed run of one agent's turns: every turn from {@code from} through {@code through}, both
 * included.
 *
 * <p>The unit an agent's history is cut into. Chapters are contiguous, never overlap, and never
 * reach the turn in flight. Once closed a chapter does not change: its bounds are fixed, and what
 * stands in for it in the context is written once.
 *
 * <p><b>The range is whole turns.</b> A turn id is the seq of the input that opened it, so the
 * bounds are ordered but not consecutive. A chapter can never split a turn.
 *
 * @param agentType the type of the agent whose turns these are
 * @param agentId the agent
 * @param from the first turn of the chapter
 * @param through the last turn of the chapter, inclusive
 */
public record Chapter(AgentType agentType, AgentId agentId, TurnId from, TurnId through) {

  public Chapter {
    Objects.requireNonNull(agentType, "agentType must not be null");
    Objects.requireNonNull(agentId, "agentId must not be null");
    Objects.requireNonNull(from, "from must not be null");
    Objects.requireNonNull(through, "through must not be null");
    if (through.value() < from.value()) {
      throw new IllegalArgumentException(
          "a chapter must run forwards: from %s through %s".formatted(from, through));
    }
  }

  /** Whether {@code turn} is one of this chapter's turns. */
  public boolean covers(TurnId turn) {
    return turn.value() >= from.value() && turn.value() <= through.value();
  }
}
```

- [ ] **Step 2: Write `OpenTurns`**

```java
package org.jwcarman.nessy.api;

import java.util.List;
import java.util.Objects;

/**
 * An agent's completed turns that are not yet in a chapter, oldest first.
 *
 * <p>What a {@link ChapterPolicy} is asked about. Ids and nothing else: a policy that counts needs
 * no more, and one that judges by what was said reads the turns itself. The turn in flight is never
 * among them.
 *
 * @param agentType the type of the agent
 * @param agentId the agent
 * @param turns the open turns, oldest first
 */
public record OpenTurns(AgentType agentType, AgentId agentId, List<TurnId> turns) {

  public OpenTurns {
    Objects.requireNonNull(agentType, "agentType must not be null");
    Objects.requireNonNull(agentId, "agentId must not be null");
    Objects.requireNonNull(turns, "turns must not be null");
    turns = List.copyOf(turns);
  }
}
```

- [ ] **Step 3: Write `ChapterPolicy`**

```java
package org.jwcarman.nessy.api;

import java.util.List;

/**
 * Says where an agent's history is cut into chapters.
 *
 * <p>Asked when a turn ends, off the agent's own thread, so it may block and may call a model. It
 * is shown the turns that are still open and answers with the ones that each end a chapter, in
 * order. An empty answer closes nothing.
 *
 * <p><b>It may answer behind the newest turn.</b> Naming the fourth of six open turns closes a
 * chapter over the first four and leaves two open. It may also name several turns, closing several
 * chapters at once, which is what an agent with a long run of open turns needs.
 *
 * <p>The chapters themselves are made by the engine from the answer: the first runs from the
 * oldest open turn through the first end, the next from the turn after that through the second,
 * and so on. An answer naming a turn that is not open, or out of order, closes nothing.
 */
@FunctionalInterface
public interface ChapterPolicy {

  /**
   * @param open the completed turns not yet in a chapter, oldest first
   * @return the turns that each end a chapter, in order; empty when nothing closes yet
   */
  List<TurnId> ends(OpenTurns open);

  /**
   * A chapter every {@code turns} turns: closes the oldest {@code turns} open turns once there are
   * that many.
   */
  static ChapterPolicy every(int turns) {
    if (turns < 1) {
      throw new IllegalArgumentException("a chapter holds at least one turn: " + turns);
    }
    return open ->
        open.turns().size() < turns ? List.of() : List.of(open.turns().get(turns - 1));
  }
}
```

- [ ] **Step 4: Write the tests**

`ChapterTest`, nested by behaviour:
- `runs_forwards`: `from` after `through` throws `IllegalArgumentException` with a message containing `must run forwards`; `from` equal to `through` is accepted.
- `rejects_nulls`: each of the four components null throws `NullPointerException` naming the component (four tests, one throwing call each, arrangement outside the lambda).
- `covers`: true for `from`, for `through` and for an id between; false for the id below `from` and the id above `through`.

`ChapterPolicyTest`, nested class `Every`:
- `closes_nothing_while_fewer_turns_are_open`: `every(3)` with two open turns returns an empty list.
- `closes_the_oldest_turns_once_enough_are_open`: `every(3)` with open ids 5, 9, 12 returns `[12]`.
- `leaves_newer_turns_open`: `every(3)` with open ids 5, 9, 12, 20, 31 returns `[12]`.
- `refuses_a_chapter_of_no_turns`: `every(0)` throws `IllegalArgumentException`.

Also a test in `ChapterPolicyTest` for `OpenTurns`: the list is copied (mutating the list passed in does not change `turns()`), and a null in any component throws.

Use `new AgentType("chat")` and `new AgentId(UUID.randomUUID())` as other tests in `nessy-api/src/test` do.

- [ ] **Step 5: Run and commit**

Run: `pgrep -fl nessy-example; ./mvnw -q -pl :nessy-api -am test` then `./mvnw spotless:apply license:format` then `pgrep -fl nessy-example; ./mvnw -q clean verify`.
Commit: `feat: a chapter, the open turns, and a policy that says where history is cut`

---

### Task 2: Clear the ground: summaries become chapter text, and the old summarisers go

**Files (the complete list is in the inventory below; read each before changing it):**
- Modify: `nessy-api/src/main/java/org/jwcarman/nessy/api/turn/Summary.java`
- Modify: `nessy-api/src/main/java/org/jwcarman/nessy/api/Summarizer.java` (replaced by the new interface)
- Modify: `nessy-api/src/main/java/org/jwcarman/nessy/api/block/Block.java` (remove `SummaryContent`)
- Modify: `nessy-api/src/main/java/org/jwcarman/nessy/api/ContextConfig.java`, `HarnessConfig.java` (remove `summaries(...)`)
- Modify: `nessy-engine` — `DefaultDirectHarnessConfig`, `DefaultQueuedHarnessConfig`, `DefaultDirectHarnessFactory`, `DefaultQueuedHarnessFactory`, `ContextAssembler`; delete `ObservedSummarizer`
- Create: `nessy-engine/src/main/java/org/jwcarman/nessy/engine/chapter/Transcripts.java` and `SummaryObservation.java` (moved from `nessy-memory/summarizing`, package changed)
- Modify: the four adapters' summary rendering — `AnthropicRequests`, `BedrockRequests`, `GeminiRequests`, `OpenAiRendering`
- Delete: the modules `nessy-memory/summarizing` and `nessy-memory/episodic` whole, and their entries in `nessy-memory/pom.xml`, `nessy-bom/pom.xml`, `nessy-coverage/pom.xml`
- Modify: `nessy-examples/chat-web` — `pom.xml`, `ChatConfiguration.java`, `application.yml`, its `README.md`; delete `EpisodesWiringTest.java`
- Modify: every test the inventory names

**Interfaces:**
- Consumes: `Chapter` from Task 1.
- Produces:

```java
// org.jwcarman.nessy.api.turn
public record Summary(Chapter chapter, String text) {
  // null checks on both; text.isBlank() throws IllegalArgumentException("a summary must say something")
  public boolean covers(TurnId turn) { return chapter.covers(turn); }
}

// org.jwcarman.nessy.api
@FunctionalInterface
public interface Summarizer {
  String summarize(Chapter chapter);
}
```

**What to do.**

1. `Summary` becomes `(Chapter chapter, String text)` as above. Remove `Summary.text(...)`. Its javadoc: the text shown to a model in place of one chapter's turns; written once and never replaced; several of them cover a long story in successive chapters.
2. `Summarizer` becomes the write-side interface above. Its javadoc: writes what stands in for one closed chapter; handed the chapter and nothing else, so one that reads the turns or earlier summaries loads them itself; called when a turn ends, off the agent's thread, so it may block and may call a model; returns text, and blank text is treated as having failed.
3. Remove `Block.SummaryContent` and drop it from `Block.Text`'s `implements` list.
4. Remove `summaries(Summarizer)` from `HarnessConfig` and `ContextConfig` and from both engine configs. Update `ContextConfig`'s class javadoc so it no longer describes summaries as coming from sources (Task 6 rewrites it fully; for now say summaries are the engine's and the tail follows them).
5. `ContextAssembler` no longer takes or reads summarisers. It assembles an `InferenceContext` with an empty summary list, the last `maxTail` turns, and ambient, as it does today when no summariser is configured. Remove the overlap check and `through(...)`. Rewrite its javadoc to match.
6. `maxTail` defaults to 40 on both doors (`DefaultDirectHarnessConfig` has 50 today, `DefaultQueuedHarnessConfig` has 20). The direct config's setter gets the same positivity check the queued one has. Update `ContextConfig.maxTail`'s javadoc to say 40.
7. Move `Transcripts` and `SummaryObservation` into `nessy-engine` under `org.jwcarman.nessy.engine.chapter`, unchanged apart from the package. Nothing uses them yet after this task; Task 4 does.
8. Adapters: wherever a summary is rendered, use `summary.chapter().from()`, `summary.chapter().through()` and `summary.text()`. The rendered text must be byte-for-byte what it was for the same range and words.
9. Delete both memory modules and every reference: the three poms, chat-web's dependency, beans, listener, tools, ambient index, `summaries(...)` call, its `MAX_TAIL` constant if nothing else uses it, the episode paragraphs in its `application.yml` system prompt and `README.md`, and `EpisodesWiringTest`. chat-web keeps its notebook and plan. Remove the `agentLeases` bean if nothing else uses it.
10. Tests: delete tests of removed code. Rewrite tests that built a `Summary` to build `new Summary(new Chapter(type, agent, from, through), text)`. `ContextAssemblerTest` loses its summariser cases and keeps tail and ambient cases. `TurnIdAndSummaryTest` tests the new `Summary` (blank text refused, nulls refused, `covers` delegates). `ReplConfigTest`'s fake `HarnessConfig` drops `summaries`.

Do not touch `docs/` in this task except nothing; documentation is Task 15.

**Inventory.** The full list of references, with line numbers at the commit this plan was written against, is in `/Users/jcarman/.claude/projects/-Users-jcarman-IdeaProjects-nessy/7519615c-8715-49a8-a095-388e7b1a243b/tool-results/toolu_01XS38pH6dk2NHVKumfaczny.txt`, sections 1 to 6. Read sections 1 to 6 before starting. Treat it as a map, and confirm each site with a search of your own: `git grep -n "Summarizer\|SummaryContent\|summaries(\|Summary\.text\|memory.episodic\|memory.summarizing\|nessy-memory-summarizing\|nessy-memory-episodic"` must return nothing outside `docs/` and `CHANGELOG.md`/`README.md`/`ROADMAP.md` when you are done, apart from the new `Summarizer` interface and its users.

- [ ] **Step 1:** Change `Summary`, `Summarizer` and `Block`, and write the `Summary` tests first (they fail to compile until the record changes).
- [ ] **Step 2:** Remove `summaries(...)` and the summariser plumbing from the engine; simplify `ContextAssembler` and its test.
- [ ] **Step 3:** Move `Transcripts` and `SummaryObservation`; delete the two memory modules and their pom entries.
- [ ] **Step 4:** Update the adapters and their tests.
- [ ] **Step 5:** Rewire chat-web.
- [ ] **Step 6:** `pgrep -fl nessy-example; ./mvnw -q clean verify -Dnessy.excludedGroups=live`, then `./mvnw spotless:apply license:format`, re-run the gate if anything was reformatted, commit.

Commit: `refactor: a summary is a chapter's text, and the head and episode summarisers are gone`

---

### Task 3: The chapter store, in the backend

**Files:**
- Create: `nessy-backend/spi/src/main/java/org/jwcarman/nessy/backend/chapter/Chapters.java`
- Create: `nessy-backend/inmemory/src/main/java/org/jwcarman/nessy/backend/inmemory/InMemoryChapters.java`
- Create: `nessy-backend/jdbc/src/main/java/org/jwcarman/nessy/backend/jdbc/JdbcChapters.java`
- Modify: `nessy-backend/jdbc/src/main/resources/nessy-schema.sql` (add `nessy_chapter`)
- Modify: `nessy-backend/spi/src/main/java/org/jwcarman/nessy/backend/DirectBackend.java`, `QueuedBackend.java` (add `chapters()` and `leases()`)
- Modify: `InMemoryDirectBackend`, `InMemoryQueuedBackend`, `JdbcDirectBackend`, `JdbcQueuedBackend`, and every other implementation of the two backend interfaces (search for them; `nessy-engine/src/test/.../FixedDirectBackend.java` is one)
- Test: `nessy-backend/inmemory/src/test/java/org/jwcarman/nessy/backend/inmemory/InMemoryChaptersTest.java`
- Test: `nessy-backend/jdbc/src/test/java/org/jwcarman/nessy/backend/jdbc/JdbcChaptersTest.java` (tagged `container`, like `JdbcLeasesTest`)

**Interfaces:**
- Consumes: `Chapter`, `Summary` (`org.jwcarman.nessy.api.turn`), `Leases` (`org.jwcarman.nessy.backend.lease`).
- Produces:

```java
package org.jwcarman.nessy.backend.chapter;

public interface Chapters {

  /**
   * Appends chapters to the end of an agent's closed chapters, all of them or none.
   *
   * @param after where the caller believes the agent's closed chapters end; empty for none
   * @return true if they were stored; false if the closed chapters no longer end at {@code after}
   */
  boolean append(AgentType type, AgentId agent, Optional<TurnId> after, List<Chapter> chapters);

  /** Stores the text for a chapter that has none. False if it already has one or does not exist. */
  boolean summarize(Summary summary);

  /** The last turn of the agent's last closed chapter. */
  Optional<TurnId> closedThrough(AgentType type, AgentId agent);

  /** Closed chapters with no summary yet, oldest first. */
  List<Chapter> unsummarized(AgentType type, AgentId agent);

  /**
   * The unbroken run of summarised chapters from the agent's first chapter, oldest first. A chapter
   * with no summary ends the run, even if a later one has been written.
   */
  List<Summary> summaries(AgentType type, AgentId agent);
}
```

and on both `DirectBackend` and `QueuedBackend`: `Chapters chapters();` and `Leases leases();`.

**Behaviour.**
- `append` with an empty list returns true and stores nothing. It throws `IllegalArgumentException` if a chapter is not for the given type and agent, if the chapters are not in ascending order without overlap (`chapters[i].from > chapters[i-1].through`), or if `after` is present and the first chapter's `from` is not greater than it.
- `append` is atomic and conditional: it stores only if `closedThrough` is still equal to `after`. Two callers appending different chapters after the same point: exactly one returns true.
- JDBC: one transaction is not available to this class, so make each row's insert conditional on the previous end, in order, in one statement per row, and treat any row that stores nothing as the whole call failing only if it is the first row; design the SQL so that later rows cannot store when the first did not. The simplest correct form is a single multi-row `INSERT ... SELECT` guarded by a subquery comparing the current `MAX(through_turn)` with `after`, with a primary key on `(agent_type, agent_id, from_turn)` and `ON CONFLICT DO NOTHING`, followed by a check that the number of rows stored equals the number asked for; if it does not, delete the rows this call stored and return false. Whatever form you choose, the concurrency test below must pass against PostgreSQL.
- Table:

```sql
-- One row per closed chapter of an agent's history. A chapter is a run of whole turns, from_turn
-- through through_turn inclusive; chapters are contiguous and never overlap. summary is null until
-- the text that stands in for the chapter has been written, and is written once.
CREATE TABLE IF NOT EXISTS nessy_chapter
(
    agent_type    VARCHAR(64) NOT NULL,
    agent_id      UUID        NOT NULL,
    from_turn     BIGINT      NOT NULL,
    through_turn  BIGINT      NOT NULL,
    summary       TEXT,
    closed_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    summarized_at TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (agent_type, agent_id, from_turn)
);
```

  Follow the column types and comment style of the tables already in that file.
- `InMemoryChapters` is thread-safe and holds everything in the process.
- `JdbcDirectBackend` and `JdbcQueuedBackend` build `new JdbcChapters(jdbc)` and `new JdbcLeases(jdbc)`; the in-memory backends build `new InMemoryChapters()` and `new InMemoryLeases()`. Update the two backend interfaces' javadoc: the stores are chosen together so that they agree, and chapters and leases are now among them.
- If a backend has a path that deletes everything an agent has (search the SPI and both implementations for one), the agent's chapters go with it.

**Tests.** Write one abstract contract, `ChaptersContract`, in `nessy-backend/spi/src/test/java/org/jwcarman/nessy/backend/chapter/` if the spi module publishes a test-jar the other two can use; otherwise write the same cases in both test classes. Cases, nested by behaviour:
- `Appending`: stores chapters for an agent with none (`after` empty); stores after the current end; returns false and stores nothing when `after` is stale; returns false when an agent with chapters is appended to with `after` empty; an empty list returns true and stores nothing; rejects a chapter for another agent; rejects overlapping or unordered chapters; rejects a first chapter that does not come after `after`.
- `Summarising`: stores text for a chapter with none; returns false the second time and keeps the first text; returns false for a chapter that was never closed.
- `Reading`: `closedThrough` is empty for a new agent and the last `through` otherwise; `unsummarized` returns closed chapters without text, oldest first; `summaries` returns the unbroken run from the first chapter and stops at the first chapter with no text even when a later one has text; one agent's chapters are invisible to another agent and to the same id under another type.
- `Racing` (JDBC only): eight threads each append a different single chapter after the same `after`; exactly one returns true and exactly one row exists.

- [ ] **Step 1:** Write the SPI interface and the in-memory implementation with its tests; `pgrep -fl nessy-example; ./mvnw -q -pl :nessy-backend-inmemory -am test`.
- [ ] **Step 2:** Write the table, `JdbcChapters` and its tests; `pgrep -fl nessy-example; ./mvnw -q -pl :nessy-backend-jdbc -am test -Dnessy.excludedGroups=live`.
- [ ] **Step 3:** Add `chapters()` and `leases()` to both backend interfaces and every implementation; add a line to `JdbcDirectBackendTest` and `JdbcQueuedBackendTest` asserting both are present.
- [ ] **Step 4:** `./mvnw spotless:apply license:format`; `pgrep -fl nessy-example; ./mvnw -q clean verify -Dnessy.excludedGroups=live`; commit.

Commit: `feat: closed chapters and their summaries are kept by the backend`

---

### Task 4: Reading a chapter's turns, and the default summariser

**Files:**
- Modify: `nessy-engine/src/main/java/org/jwcarman/nessy/engine/store/TurnHistory.java`
- Modify: `nessy-engine/src/main/java/org/jwcarman/nessy/engine/history/EventStreamHistory.java`
- Modify: `nessy-engine/src/main/java/org/jwcarman/nessy/engine/observability/ObservedTurnHistories.java`, and every other implementation of `TurnHistory` (test doubles included)
- Create: `nessy-engine/src/main/java/org/jwcarman/nessy/engine/chapter/ProseSummarizer.java`
- Test: `nessy-engine/src/test/java/org/jwcarman/nessy/engine/chapter/ProseSummarizerTest.java`
- Test: add cases to the existing test of `EventStreamHistory` if there is one, else create `nessy-engine/src/test/java/org/jwcarman/nessy/engine/history/EventStreamHistoryTest.java`

**Interfaces:**
- Consumes: `Chapter`, `Summarizer`, `Transcripts` (moved in Task 2), `InferenceProvider`, `InferenceOptions`, `InferenceRequest`, `InferenceContext`, `TurnHistories`.
- Produces, on `TurnHistory`:

```java
  /** Every turn from {@code from} through {@code through}, both included, whole, oldest first. */
  List<Turn> turnsBetween(TurnId from, TurnId through);

  /**
   * The ids of the completed turns after {@code through}, oldest first; every completed turn when
   * {@code through} is empty. A turn still under way is never among them.
   */
  List<TurnId> completedAfter(Optional<TurnId> through);
```

  and

```java
package org.jwcarman.nessy.engine.chapter;

public final class ProseSummarizer implements Summarizer {
  public static final String PROMPT = ...;
  public ProseSummarizer(TurnHistories histories, InferenceProvider provider, InferenceOptions options);
  @Override public String summarize(Chapter chapter);
}
```

**`ProseSummarizer` behaviour.**
- Reads the chapter's turns with `histories.forAgent(chapter.agentType(), chapter.agentId()).turnsBetween(chapter.from(), chapter.through())`.
- Asks the provider once: system prompt `PROMPT`, a context holding those turns followed by one open turn that asks for the summary (`Transcripts.ask(lastTurn, "Write the record of everything above now.")`), no tools, the options it was built with. Build the `InferenceContext` with the constructor that exists after Task 2 (no summaries, those turns, no ambient).
- Returns the answer's text (`Transcripts.text` over the answer blocks).
- Throws `IllegalStateException` with a message naming the chapter's bounds when the chapter has no turns, and when the result is anything other than an answer. Returns whatever text the model wrote, blank included; the caller treats blank as a failure.
- The constructor calls `provider.validate(options)` so a bad option fails when the summariser is built.
- `PROMPT`, adapted from the head summariser's and written for a chapter that stands alone:

```text
You are writing the record of one part of a longer conversation. It will be shown in place of that
part, and it is the only trace of it that is kept in view, so it must stand alone.

Keep what a reader would need in order to continue:
- who said or did what, with names, places, numbers, identifiers and dates exactly as given
- decisions made, and what they were made for
- commitments and obligations, in either direction, and how things turned out
- questions raised that are still open

Work out actual dates when someone says "yesterday" or "last week" and the date is known. Do not
narrate, and do not describe the conversation as a conversation. Keep exact values: a name, a
number or an identifier is worth more than a sentence about it.
```

**Tests.**
- `turnsBetween`: returns the turns in range inclusive at both ends, in order; a range holding no turn returns an empty list; turn ids that are not consecutive are handled (a history with ids 1, 4, 9 and a range of 4 through 9 returns two turns).
- `completedAfter`: empty `through` returns every completed turn's id; a `through` returns only later ones; the turn under way (no result) is left out.
- `ProseSummarizerTest`, with a scripted `InferenceProvider` lambda that records the request, and a fake `TurnHistories` like `ContextAssemblerTest.RecordingHistories`: sends exactly the chapter's turns plus the asking turn; uses `PROMPT` as the system prompt and offers no tools; returns the model's text; throws on a chapter with no turns; throws when the provider returns a failure; validates the options when built (a provider whose `validate` throws makes the constructor throw).

- [ ] **Step 1:** Add the two reads with tests. `pgrep -fl nessy-example; ./mvnw -q -pl :nessy-engine -am test`.
- [ ] **Step 2:** Write `ProseSummarizer` with tests.
- [ ] **Step 3:** `./mvnw spotless:apply license:format`; `pgrep -fl nessy-example; ./mvnw -q clean verify`; commit.

Commit: `feat: a prose summariser writes the record of one chapter`

---

### Task 5: The chapter keeper: cut, then summarise, under a lease

**Files:**
- Create: `nessy-engine/src/main/java/org/jwcarman/nessy/engine/chapter/ChapterKeeper.java`
- Test: `nessy-engine/src/test/java/org/jwcarman/nessy/engine/chapter/ChapterKeeperTest.java`

**Interfaces:**
- Consumes: `ChapterPolicy`, `OpenTurns`, `Summarizer`, `Chapter`, `Summary`, `Chapters` (Task 3), `Leases`, `LeaseKind`, `TurnHistories` with `completedAfter` (Task 4), `NarrationListener`.
- Produces:

```java
package org.jwcarman.nessy.engine.chapter;

public final class ChapterKeeper {

  public static final LeaseKind LEASE_KIND = new LeaseKind("nessy.chapters");
  public static final Duration DEFAULT_LEASE_TTL = Duration.ofMinutes(2);

  public ChapterKeeper(
      AgentType agentType,
      ChapterPolicy policy,
      Summarizer summarizer,
      Chapters chapters,
      Leases leases,
      TurnHistories histories,
      int maxChapterLength,
      Duration leaseTtl);

  /** The listener to attach to a harness: hears this type's turns end, on a thread of its own. */
  public NarrationListener listener();

  /** Cuts what is due, then writes what is unwritten. Public so a caller may ask outright. */
  public void keep(AgentId agentId);
}
```

**Behaviour of `keep`.**
1. **Cut**, inside `leases.tryWithLease(LEASE_KIND, agentType, agentId, leaseTtl, ...)`:
   - `after = chapters.closedThrough(agentType, agentId)`.
   - `open = histories.forAgent(agentType, agentId).completedAfter(after)`. If empty, nothing to cut.
   - `ends = policy.ends(new OpenTurns(agentType, agentId, open))`.
   - The answer is valid when every id is in `open` and they are strictly ascending. An invalid answer is logged at WARN, naming the agent type, the agent and the offending answer, and treated as empty.
   - If `ends` is empty and `open.size() >= maxChapterLength`, `ends` becomes the one id at index `maxChapterLength - 1`.
   - Turn `ends` into chapters over `open`: the first from `open.get(0)` through the first end, each next one from the id following the previous end.
   - Split any chapter holding more than `maxChapterLength` of the open turns into consecutive chapters of at most that many.
   - `chapters.append(agentType, agentId, after, thoseChapters)`. A false return is logged at DEBUG and is not an error.
   - A policy that throws is logged at WARN and cuts nothing; the summarising step still runs.
2. **Summarise**: read `chapters.unsummarized(agentType, agentId)` outside any lease. For each, oldest first, take the lease again (`tryWithLease` with the same kind), and inside it call `summarizer.summarize(chapter)`; if the text is blank, log at WARN and stop; otherwise `chapters.summarize(new Summary(chapter, text))`. A summariser that throws is logged at WARN and stops the loop: later chapters are not attempted, so summaries are always written oldest first. A refused lease stops the loop.
3. Never call `tryWithLease` from inside work already holding the lease.

`listener()` returns `NarrationListener.of(c -> c.agentType(agentType).onTurnEnded((_, agentId, _) -> keep(agentId))).async()`.

The constructor rejects a `maxChapterLength` below one and a lease time that is zero or negative.

Log lines follow the project's style, prefixed with the agent type in square brackets as the head summariser's were, for example `[{}] closed {} chapter(s) of agent {} through turn {}` at INFO when chapters are stored and `[{}] wrote the summary of turns {}..{} of agent {}` at INFO when one is written.

**Tests** (`ChapterKeeperTest`), using `InMemoryChapters`, `InMemoryLeases`, a fake `TurnHistories` whose `completedAfter` serves a list of ids, and lambdas for the policy and summariser. Add `nessy-backend-inmemory` as a test dependency of `nessy-engine` if it is not one already. Nested by behaviour:
- `Cutting`: nothing is cut while the policy returns nothing and fewer than the maximum are open; the policy's answer becomes chapters with the right bounds (non-consecutive ids, two ends in one answer, an answer behind the newest turn); the second call cuts from where the first ended; an answer naming a turn that is not open cuts nothing; an unordered answer cuts nothing; a policy that throws cuts nothing; the maximum forces a chapter over the oldest turns when the policy returns nothing; a chapter longer than the maximum is split.
- `Summarising`: every unsummarised chapter is written, oldest first; a blank summary is not stored and stops the loop; a summariser that throws stops the loop and leaves the chapter unsummarised; a later `keep` writes what an earlier one could not; a chapter is never summarised twice.
- `Leasing`: when the lease is held by someone else, `keep` neither asks the policy nor the summariser (use a `Leases` fake that always returns `Attempt.Ignored`); the policy and the summariser are each called inside the lease and never nested (a `Leases` fake that fails the test if `tryWithLease` is entered while already inside).
- `Listening`: the listener calls `keep` for a turn that ended on this agent type and ignores another type's.

- [ ] **Step 1:** Write the tests for cutting, then the cutting code.
- [ ] **Step 2:** Write the tests for summarising and leasing, then that code.
- [ ] **Step 3:** `./mvnw spotless:apply license:format`; `pgrep -fl nessy-example; ./mvnw -q clean verify`; commit.

Commit: `feat: a chapter keeper cuts and summarises an agent's history when a turn ends`

---

### Task 6: Chapters on both doors, on by default

**Files:**
- Modify: `nessy-api/src/main/java/org/jwcarman/nessy/api/ContextConfig.java`, `HarnessConfig.java`
- Modify: `nessy-engine` — `DefaultDirectHarnessConfig`, `DefaultQueuedHarnessConfig`, `DefaultDirectHarnessFactory`, `DefaultQueuedHarnessFactory`, `ContextAssembler`
- Create: `nessy-engine/src/main/java/org/jwcarman/nessy/engine/observability/ObservedSummarizer.java`, `ObservedChapterPolicy.java`
- Modify: every other implementation of `HarnessConfig`/`ContextConfig` (search; `nessy-console`'s `ReplConfigTest` fake and `nessy-api`'s `ValueValidationTest` are two)
- Test: `nessy-engine/src/test/java/org/jwcarman/nessy/engine/harness/direct/DirectHarnessChaptersTest.java`
- Test: `nessy-engine/src/test/java/org/jwcarman/nessy/engine/harness/queued/QueuedHarnessChaptersTest.java` (tagged `container`; uses `EngineFixture`)
- Test: update `ContextAssemblerTest`

**Interfaces:**
- Consumes: `ChapterKeeper` (Task 5), `ProseSummarizer` (Task 4), `Chapters` and `Leases` from the backend (Task 3).
- Produces, on `HarnessConfig<SELF>`:

```java
  /** Where this agent's history is cut into chapters. Defaults to {@link ChapterPolicy#every(int)} at twenty. */
  SELF chapterPolicy(ChapterPolicy policy);

  /** What writes the text that stands in for a closed chapter. Defaults to a prose summary written by this agent's own model. */
  SELF summarizer(Summarizer summarizer);
```

  on `ContextConfig`:

```java
  ContextConfig chapterPolicy(ChapterPolicy policy);
  ContextConfig summarizer(Summarizer summarizer);
  /** At most this many turns in one chapter. Defaults to 30. */
  ContextConfig maxChapterLength(int turns);
  /** How long the lease taken for one model call is believed held. Defaults to two minutes. */
  ContextConfig chapterLeaseTtl(Duration ttl);
  /** No chapters: nothing is cut or summarised, and history is the last maxTail completed turns. */
  ContextConfig withoutChapters();
```

  and on `DefaultDirectHarnessFactory`: `public TurnHistories histories()`, the same as the queued factory's.

**Behaviour.**
- Both factories, when building a harness whose context has chapters on, construct a `ChapterKeeper` (policy and summariser wrapped by `ObservedChapterPolicy.wrap` and `ObservedSummarizer.wrap`; chapters and leases from the backend; the histories the assembler uses; the configured maximum and lease time) and add its `listener()` to that harness's own listeners. With `withoutChapters()` no keeper is built and no listener is added.
- The default summariser is `new ProseSummarizer(histories, provider, options)` using the provider and options the factory resolved for this agent type. It is built only when chapters are on and no summariser was configured.
- `ContextAssembler` is given the backend's `Chapters` (or told chapters are off). With chapters on it reads `chapters.summaries(type, agent)`, and the tail is the turns after the last summary's `through`, capped at `maxTail`; with none, the last `maxTail` turns. The turn being answered is always the newest turn and is always included (it is counted within the cap for now; Task 7 separates it). If summaries exist and nothing follows them, throw `IllegalStateException("a summary reaches the turn being answered: nothing is left after turn " + through)`, as the assembler did before Task 2.
- `ObservedSummarizer` and `ObservedChapterPolicy` follow the pattern of the other `Observed*` classes in that package: `wrap(delegate, observations)` returns the delegate unchanged when the registry is a no-op or the delegate is already wrapped, otherwise an observing wrapper. Span names `nessy.summary` and `nessy.chapter.policy`, with the agent type as a low-cardinality key. `SummaryObservation` (moved in Task 2) may be used or deleted; do not leave it unused.
- Setters validate: `maxChapterLength` at least one; `chapterLeaseTtl` positive; nulls refused. Building a harness with `maxTail` not greater than `maxChapterLength` is refused with a message naming both numbers, because a chapter could then be hidden before it is summarised.
- Rewrite `ContextConfig`'s class javadoc to describe what is now true: summaries of closed chapters, then the tail, then ambient; who decides the cut and who writes the summary; the three numbers and how they relate.

**Tests.**
- `DirectHarnessChaptersTest`, on the direct door with `InMemoryDirectBackend` and a scripted provider that answers every chat request and recognises a summary request by `ProseSummarizer.PROMPT` (the removed `HeadSummarizerTest` did the same; see git history for the pattern). The keeper's listener is asynchronous, so after driving turns, wait for the store with a bounded poll (Awaitility if the module already uses it, otherwise a small loop with a deadline; no bare sleeps):
  - `a_chapter_closes_and_its_summary_is_shown`: with `chapterPolicy(ChapterPolicy.every(3))`, after three turns and a wait, the fourth turn's request carries one summary covering turns one to three and a tail that starts at turn four.
  - `turns_stay_verbatim_until_the_summary_is_written`: a summariser that throws; after three turns the fourth request has no summaries and still carries all four turns.
  - `a_failed_summary_is_retried_at_a_later_turn_end`: a summariser that throws once then succeeds; the summary appears after a later turn.
  - `nothing_is_cut_without_chapters`: `withoutChapters()`; after many turns the store is empty and the provider was never asked for a summary.
  - `the_default_summariser_uses_the_agents_own_model`: no summariser configured; the summary request carries the agent's model name.
  - `max_tail_must_exceed_the_maximum_chapter_length`: building with `maxTail(10)` and `maxChapterLength(10)` throws.
- `QueuedHarnessChaptersTest`: the first case above on the queued door against PostgreSQL.
- `ContextAssemblerTest`: summaries from a fake `Chapters`, the tail beginning after the last summary, the cap, the refusal when a summary reaches the turn being answered, and chapters off.

- [ ] **Step 1:** API methods and both configs, with validation tests.
- [ ] **Step 2:** `ContextAssembler` reads the store; its tests.
- [ ] **Step 3:** The observed wrappers.
- [ ] **Step 4:** Both factories build the keeper; `histories()` on the direct factory; the two harness tests.
- [ ] **Step 5:** `./mvnw spotless:apply license:format`; `pgrep -fl nessy-example; ./mvnw -q clean verify -Dnessy.excludedGroups=live`; commit.

Commit: `feat: an agent's history is cut into chapters and summarised, on both doors`

---

### Task 7: Memory and State, and a context that names its strata

**Files:**
- Create in `nessy-api/src/main/java/org/jwcarman/nessy/api/`: `Memory.java`, `MemorySource.java`, `State.java`, `StateSource.java`
- Modify: `nessy-api/.../block/Block.java` (add `MemoryContent`, `StateContent`; `Text` implements both)
- Modify: `nessy-api/.../ContextConfig.java`, `HarnessConfig.java` (add `memory(...)`, `state(...)`)
- Modify: `nessy-inference/spi/src/main/java/org/jwcarman/nessy/inference/InferenceContext.java`
- Modify: `nessy-engine` — `ContextAssembler`, both configs, both factories, `ProseSummarizer`, `ObservedInferenceProvider`
- Create: `nessy-engine/.../observability/ObservedMemorySource.java`, `ObservedStateSource.java`
- Modify: the five request builders so they compile against the new context: `AnthropicRequests`, `BedrockRequests`, `GeminiRequests`, `OpenAiChatRequests`, `OpenAiResponsesRequests`
- Modify: scripted providers and tests that read `context().turns()` (inventory section 7)
- Test: `nessy-api/src/test/java/org/jwcarman/nessy/api/MemoryAndStateTest.java`, `nessy-inference/spi/src/test/.../InferenceTypesTest.java`, `ContextAssemblerTest`

**Interfaces:**
- Produces:

```java
// org.jwcarman.nessy.api
public record Memory(String kind, List<Block.MemoryContent> content) { public static Memory text(String kind, String text); }
public interface MemorySource {
  String kind();
  Optional<Memory> forAgent(AgentId agentId, Turn current);
}
public record State(String kind, List<Block.StateContent> content) { public static State text(String kind, String text); }
public interface StateSource {
  String kind();
  Optional<State> forAgent(AgentId agentId, Turn current);
}

// on HarnessConfig<SELF>:  SELF memory(MemorySource source);  SELF state(StateSource source);
// on ContextConfig:        ContextConfig memory(MemorySource source);  ContextConfig state(StateSource source);

// org.jwcarman.nessy.inference
public record InferenceContext(
    List<Summary> summaries,
    List<Turn> tail,
    List<Memory> memory,
    List<State> state,
    Turn activeTurn,
    List<Ambient> ambient) {

  /** The tail and then the active turn: every turn in the context, oldest first. */
  public List<Turn> turns();

  /** Only turns: the last is the active turn, the rest are the tail. */
  public static InferenceContext of(List<Turn> turns);
}
```

**Behaviour.**
- `Memory` and `State` mirror `Ambient` exactly: the same kind pattern and its safety reasoning, non-empty content, a defensive copy, a `text` factory. Their javadoc says what each stratum is. Memory: what was recalled because it bears on this turn. State: the agent's standing situation. Both say that a source returns what is current each time it is asked and that nothing holds an earlier answer for it.
- `MemorySource` and `StateSource` mirror `AmbientSource`'s contract and javadoc (asked on the dispatcher's thread, off the agent's row lock, once per call to the model; empty is the right answer for nothing to say; two sources may not share a kind). Both are also handed the turn being answered: a memory source to choose what bears on it, and a state source so that it can answer as of the start of that turn (the notes written before it, say) and so hold still for the whole of it without anything being kept on its behalf. No `of(Customizer)` builder and no config class for these two; a `constant(...)` static on each is enough.
- `InferenceContext`: all lists copied and null-checked; `activeTurn` required. Keep `of(List<Turn>)` (throws `IllegalArgumentException` on an empty list). Keep the existing convenience constructors' call sites compiling by offering the same argument lists with the last turn taken as the active one, or change the call sites; choose whichever leaves the tests clearest, and do not leave a constructor nobody calls. Keep `hasSummaries()` and `hasAmbient()` only if something calls them. Rewrite the record's javadoc to describe the six strata, their order, and that placement on the wire is the adapter's.
- `ContextAssembler`: reads `maxTail + 1` newest turns after the summaries; the newest is the active turn and the rest are the tail, so `maxTail` now counts completed turns only. Asks each memory source and each state source with the active turn, then each ambient source, in the order they were bound.
- Both configs refuse two sources of the same kind within a stratum when the source is added, with the message the ambient check uses today, naming the stratum. The same kind in two different strata is allowed.
- `ObservedMemorySource` and `ObservedStateSource` follow `ObservedAmbientSource`.
- Adapters in this task change only enough to compile and keep behaviour: render `summaries`, then `tail` and `activeTurn` as the turns were rendered before, and ambient where it is today. They do not render memory or state yet; Tasks 8 to 11 do. Do not add placeholder rendering.
- `ProseSummarizer` builds its context with the chapter's turns as the tail and the asking turn as the active turn.

**Tests.**
- `MemoryAndStateTest`: the kind pattern, empty content, the copy, the `text` factory and `constant` source, for both.
- `InferenceTypesTest`: `of` splits the last turn off as active; `of` on an empty list throws; `turns()` is tail then active.
- `ContextAssemblerTest`: the active turn is the newest turn; the tail excludes it and is capped at `maxTail`; a memory source and a state source are each handed the active turn; sources are asked in bound order; a duplicate kind within a stratum is refused when added (test on each config); the same kind in two strata is accepted.
- Every adapter's existing request tests still pass unchanged in what they assert.

- [ ] **Step 1:** API types and tests.
- [ ] **Step 2:** `InferenceContext` and its tests; make the adapters and the engine compile.
- [ ] **Step 3:** Assembler, configs, factories, observed wrappers; tests.
- [ ] **Step 4:** `./mvnw spotless:apply license:format`; `pgrep -fl nessy-example; ./mvnw -q clean verify -Dnessy.excludedGroups=live`; commit.

Commit: `feat: memory and state are strata of the context, each with its own source`

---

### Task 8: Anthropic places the strata

**Files:**
- Modify: `nessy-inference/anthropic/src/main/java/org/jwcarman/nessy/inference/anthropic/AnthropicRequests.java`
- Test: `nessy-inference/anthropic/src/test/java/org/jwcarman/nessy/inference/anthropic/AnthropicRequestsTest.java`
- Test: `nessy-inference/anthropic/src/test/java/org/jwcarman/nessy/inference/anthropic/AnthropicLiveTest.java` (one new test, tagged `live`, not run)

**Interfaces:**
- Consumes: `InferenceContext(summaries, tail, memory, state, activeTurn, ambient)` from Task 7.
- Produces: no new public surface.

**Placement.**
- The `system` field holds the system prompt block only, with its cache marker as today. Ambient no longer appears there.
- Messages, in order: summaries (as today), the tail's turns (as today), then the active turn.
- Memory and State are text blocks at the head of the active turn's first user message, before the input's own blocks: every memory first, then every state, in context order. Each is one text block, `<memory kind="KIND">\nTEXT\n</memory>` and `<state kind="KIND">\nTEXT\n</state>`, with the text stripped. A memory or state whose text is blank after stripping is left out. With neither, the message is exactly what it is today.
- Ambient is text blocks at the very end of the request: appended, in context order, to the last message when that message is user-role, as `<KIND>\nTEXT\n</KIND>` (the tag form used today). If the last message is not user-role, a new user message holds them. Blank ambient is left out, as today.
- Cache markers: chosen as they are today, over the messages before any ambient block is appended, so a marker never sits on an ambient block and the block it sits on is followed by ambient. The rule in `breakpoints` and `endingOnMarker` does not otherwise change.
- A refused active turn cannot occur (the active turn has no result), but a turn in the tail that was refused is still omitted whole, as today.
- Rewrite the javadoc on `AnthropicRequests`, `systemBlocks` (or whatever replaces it) and the constant `BLOCK_BINDING` so they describe the new placement: say that background follows the marked block so the cached prefix never contains it, and that a change in background no longer changes the system field.

**Tests** (add a nested class `PlacingTheStrata` to `AnthropicRequestsTest`; keep the existing tests passing, changing only assertions about ambient in the system field):
- `the_system_field_holds_only_the_instructions`
- `memory_and_state_lead_the_active_turns_first_message` (order: memory blocks, state blocks, input blocks; exact text of each tag)
- `memory_and_state_are_not_attached_to_a_turn_in_the_tail`
- `ambient_ends_the_request_after_the_active_turns_input`
- `ambient_ends_the_request_after_the_last_tool_results` (active turn with one exchange)
- `the_cache_marker_sits_on_the_block_before_ambient` (with a cache time set: the last marked block is the last non-ambient block of the last message, and no ambient block carries a marker)
- `a_context_with_no_memory_state_or_ambient_renders_as_before` (compare with the request built for the same turns before, block for block)
- `blank_memory_state_and_ambient_are_left_out`
- Live, tagged `live`: `ambient_that_changes_on_every_call_does_not_spoil_the_cache`: a tool loop of three calls with a five-minute cache and an ambient whose text differs on each call; assert the second and third calls report cache-read input tokens greater than zero. Model it on the existing `a_cached_tool_loop_reads_back_what_the_last_call_wrote`.

- [ ] **Step 1:** Write the tests; watch them fail.
- [ ] **Step 2:** Implement the placement.
- [ ] **Step 3:** `./mvnw spotless:apply license:format`; `pgrep -fl nessy-example; ./mvnw -q clean verify`; commit.

Commit: `feat: Anthropic places memory and state before the active turn and background at the end`

---

### Task 9: OpenAI places the strata, on both wires

**Files:**
- Modify: `nessy-inference/openai/src/main/java/org/jwcarman/nessy/inference/openai/OpenAiRendering.java`, `OpenAiChatRequests.java`, `OpenAiResponsesRequests.java`
- Test: `OpenAiChatRequestsTest.java`, `OpenAiResponsesRequestsTest.java`

**Placement, both wires.**
- The system message (chat) and `instructions` (responses) hold the system prompt only. `OpenAiRendering.system` no longer appends ambient; rename or remove it as fits, and rewrite its javadoc.
- Memory and State lead the active turn's user message: the user message's text is the memory tags, then the state tags, then the input's text, separated by blank lines. Tags as in Task 8: `<memory kind="KIND">\nTEXT\n</memory>`, `<state kind="KIND">\nTEXT\n</state>`. With neither, the message is exactly what it is today.
- Ambient is one user-role message (chat) or user-role input item (responses) at the very end of the request, after the active turn's last content, holding every ambient as `<KIND>\nTEXT\n</KIND>` separated by blank lines. With no ambient there is no such message. When the request already ends on the active turn's user message (no exchanges yet), append the ambient text to that message after a blank line instead of adding a second user message.
- Blank memory, state and ambient are left out.

Put the shared tag rendering in `OpenAiRendering` so both wires use one implementation.

**Tests** (a nested class `PlacingTheStrata` in each of the two request tests): the same seven model-free cases as Task 8, adapted to this wire's shapes (no cache-marker case). Existing tests keep passing, changing only assertions about ambient in the system text.

- [ ] **Step 1:** Tests for chat; implement. **Step 2:** Tests for responses; implement.
- [ ] **Step 3:** `./mvnw spotless:apply license:format`; `pgrep -fl nessy-example; ./mvnw -q clean verify`; commit.

Commit: `feat: OpenAI places memory and state before the active turn and background at the end`

---

### Task 10: Gemini places the strata

**Files:**
- Modify: `nessy-inference/gemini/src/main/java/org/jwcarman/nessy/inference/gemini/GeminiRequests.java`
- Test: `GeminiRequestsTest.java`

**Placement.**
- `systemInstruction` holds the system prompt part only.
- Memory and State are text parts at the head of the active turn's first user content, before the input's parts: `<memory kind="KIND">\nTEXT\n</memory>`, then `<state kind="KIND">\nTEXT\n</state>`.
- Ambient is text parts at the very end of the request: appended to the last content when it is user-role and consists of text parts, otherwise a new user content. Function-response parts and text parts are not mixed in one content unless the existing code already does so; if the last content holds function responses, add a new user content for ambient.
- Blank ones are left out. With no memory, state or ambient the request is exactly what it is today apart from the system instruction no longer carrying ambient.

**Tests:** nested class `PlacingTheStrata`, the same model-free cases as Task 9. Existing thought-signature behaviour must not change: add `thought_signatures_in_the_active_turn_are_still_replayed_unchanged`.

- [ ] Tests, implementation, `./mvnw spotless:apply license:format`, `pgrep -fl nessy-example; ./mvnw -q clean verify`, commit.

Commit: `feat: Gemini places memory and state before the active turn and background at the end`

---

### Task 11: Bedrock places the strata

**Files:**
- Modify: `nessy-inference/bedrock/src/main/java/org/jwcarman/nessy/inference/bedrock/BedrockRequests.java`
- Test: `BedrockRequestsTest.java`

**Placement.**
- The Converse `system` list holds the system prompt only.
- Memory and State are text content blocks at the head of the active turn's first user message; ambient is text content blocks at the end of the last user message, or a new user message when the last message is not user-role. Tags as in Task 8. The existing `alternating` merge of adjacent same-role messages still applies and must leave the order: memory, state, input.
- Blank ones are left out.

**Tests:** nested class `PlacingTheStrata`, the same model-free cases as Task 9.

- [ ] Tests, implementation, `./mvnw spotless:apply license:format`, `pgrep -fl nessy-example; ./mvnw -q clean verify`, commit.

Commit: `feat: Bedrock places memory and state before the active turn and background at the end`

---

### Task 12: Measuring which stratum changed

**Files:**
- Create: `nessy-engine/src/main/java/org/jwcarman/nessy/engine/inference/ContextFingerprint.java`
- Modify: `nessy-engine/src/main/java/org/jwcarman/nessy/engine/observability/ObservedInferenceContextAssembler.java`
- Test: `nessy-engine/src/test/java/org/jwcarman/nessy/engine/inference/ContextFingerprintTest.java`, and the existing test of the observed assembler (`ObservedContextTest`)

**Interfaces:**
- Produces (engine-internal, public for the test):

```java
package org.jwcarman.nessy.engine.inference;

public record ContextFingerprint(int history, int memory, int state, int activeTurn, int ambient) {
  public static ContextFingerprint of(InferenceContext context);
  /** The earliest stratum that differs from {@code previous}: "none", "history", "memory", "state", "active-turn" or "ambient". */
  public String firstChangeSince(ContextFingerprint previous);
}
```

**Behaviour.**
- `of` hashes each stratum's content with the records' own `hashCode` (summaries and tail together are `history`).
- `ObservedInferenceContextAssembler` keeps the last fingerprint per `(agentType, agentId)` in a bounded, thread-safe, access-ordered map of at most 10,000 agents, and adds the low-cardinality key `nessy.context.changed` to the observation it already makes: `first-call` when it has no previous fingerprint for the agent, otherwise `firstChangeSince`. It also logs the same value at DEBUG with the agent type and agent. With a no-op registry the wrapper is not installed, as today, and nothing is measured.
- Update the class javadoc to say what the key means and why the earliest change is the one that matters.

**Tests.**
- `ContextFingerprintTest`: identical contexts give `none`; a new turn in the tail gives `history`; a new summary gives `history`; changed memory with unchanged history gives `memory`; changed state gives `state`; a new exchange in the active turn gives `active-turn`; changed ambient alone gives `ambient`; when two strata changed the earlier one is reported.
- `ObservedContextTest`: the first call for an agent reports `first-call`; a second identical call reports `none`; two agents do not affect each other. Use the test observation registry the existing tests use.

- [ ] Tests, implementation, `./mvnw spotless:apply license:format`, `pgrep -fl nessy-example; ./mvnw -q clean verify`, commit.

Commit: `feat: the context reports the earliest stratum that changed since the last call`

---

### Task 13: A model that declares its own chapters

**Files:**
- Create: `nessy-engine/src/main/java/org/jwcarman/nessy/engine/chapter/DeclaredChapters.java`
- Test: `nessy-engine/src/test/java/org/jwcarman/nessy/engine/chapter/DeclaredChaptersTest.java`
- Test: add one case to `DirectHarnessChaptersTest`

**Interfaces:**
- Produces:

```java
package org.jwcarman.nessy.engine.chapter;

public final class DeclaredChapters implements ChapterPolicy {

  public static final ToolName TOOL_NAME = new ToolName("begin_chapter");

  /** What the model says when it begins a chapter. */
  public record Beginning(String title) {}

  public DeclaredChapters(TurnHistories histories);

  /** The tool the model calls to say a new chapter begins with this turn. */
  public static Tool<Beginning> tool();

  /** Installs the tool and the policy on an agent: one call equips it. */
  public static Customizer<HarnessConfig<?>> feature(TurnHistories histories);

  @Override public List<TurnId> ends(OpenTurns open);
}
```

**Behaviour.**
- `ends`: reads the open turns (`turnsBetween(first, last)`), and for every open turn other than the first that contains a call to `begin_chapter` in any of its exchanges, returns the open turn immediately before it. Results are in order and without duplicates. No open turn calling the tool gives an empty list.
- The tool does nothing but acknowledge: its result is the text `A new chapter begins with this turn.` Its description tells the model when to call it: when the conversation moves to a new subject or a piece of business is finished and another begins; call it at the start of the turn that opens the new chapter; `title` is a few words naming what the new chapter is about. Follow `NotebookTools` or `PlanTools` for how a `Tool` is written in this codebase, including its input schema (a record with no properties needs an explicit empty properties object; `Beginning` has one property, so the default schema is fine).
- `feature` returns a customizer that calls `config.tool(tool())` and `config.chapterPolicy(new DeclaredChapters(histories))`.

**Tests.**
- `DeclaredChaptersTest`: a call in the third of five open turns ends a chapter at the second; a call in the first open turn ends nothing; calls in the third and fifth end chapters at the second and fourth; no calls ends nothing; a call to a different tool is ignored; the tool acknowledges with the stated text; the feature installs one tool named `begin_chapter` and a policy (use a small fake `HarnessConfig` like the one in `ReplConfigTest`, or the real direct config if that is simpler).
- `DirectHarnessChaptersTest.a_model_that_begins_a_chapter_closes_the_one_before`: with `DeclaredChapters.feature(factory.histories())`, a scripted provider that calls `begin_chapter` in turn three; after turn three ends and a wait, one chapter covers turns one and two.

- [ ] Tests, implementation, `./mvnw spotless:apply license:format`, `pgrep -fl nessy-example; ./mvnw -q clean verify`, commit.

Commit: `feat: a model can begin a chapter with a tool, closing the one before`

---

### Task 14: Comparing policies in the harness

**Files:**
- Create: `nessy-engine/src/test/java/org/jwcarman/nessy/engine/chapter/ContextReplay.java` (test support)
- Create: `nessy-engine/src/test/java/org/jwcarman/nessy/engine/chapter/ChapterPolicyComparisonTest.java`

**What it is.** A way to run one scripted conversation through the real direct harness under different chapter settings and see, call by call, what the model was sent and how much of it was unchanged from the call before. It needs no key and no database.

**`ContextReplay`.**
- Built with a list of scripted turns. Each scripted turn is the user's input and the model's replies for that turn in order: zero or more tool-call replies, then an answer. A small builder: `ContextReplay.conversation().turn("input", answer("text")).turn("input", calls("lookup", "{\"key\":\"K1\"}"), answer("text")) ...`.
- `run(Customizer<DirectHarnessConfig<String>> settings)` builds a `DefaultDirectHarnessFactory` over `InMemoryDirectBackend`, a scripted `InferenceProvider` that plays the replies and records every `InferenceRequest`, one tool named `lookup` that returns a fixed long text, and a summariser stub that returns `"summary of turns FROM..THROUGH"` for a chapter (so no summary request reaches the scripted provider). It drives every turn, waiting after each for the chapter keeper to finish. To know when that is, it wraps the policy it is given so that it counts each time the policy is asked and remembers the last answer; after a turn it waits, with a deadline, until the count has risen, the store's `closedThrough` has reached the last end the policy returned (if it returned one), and no chapter is unsummarised. With chapters off it does not wait.
- It returns a `Report`: for each call to the model, the turn number, the call number within the turn, the number of summaries, the tail length, the active turn's exchange count, the size in characters of each stratum, and `stablePrefix`: the number of leading characters the call's canonical text shares with the previous call's. The canonical text is the strata rendered in order as plain text: system prompt, each summary's text, each tail turn via `Transcripts.render`, memory, state, the active turn via `Transcripts.render`, ambient.
- `Report.totals()`: calls, total characters sent, total characters in the stable prefix, and the ratio. `Report.table()`: a fixed-width text table of the per-call rows.

**`ChapterPolicyComparisonTest`.**
- One 60-turn scripted conversation in which every third turn makes one tool call.
- Runs it under: chapters off; `every(20)`; `every(10)`; `every(5)`.
- Prints each run's totals as one line, and asserts:
  - with chapters off, every call after the fortieth turn has a tail of forty and no summaries;
  - with `every(20)`, the calls of turn forty-five have two summaries and a tail of four;
  - the share of text in the stable prefix is higher with `every(20)` than with chapters off, across the calls after turn forty (the window slides every turn when off);
  - no run's context ever shows a turn both in a summary and verbatim.
- A second test with an ambient source whose text changes on every call asserts the stable prefix is unaffected by it (ambient is last in the canonical text).

Print with the test's own output, not a logger, so `./mvnw -q -pl :nessy-engine -am test -Dtest=ChapterPolicyComparisonTest` shows the table.

- [ ] Write the support class and tests, `./mvnw spotless:apply license:format`, `pgrep -fl nessy-example; ./mvnw -q clean verify`, commit.

Commit: `test: one scripted conversation replayed under different chapter policies`

---

### Task 15: The examples, the guides and the changelog say what is now true

**Files:**
- Modify: `docs/concepts/memory.md`, `docs/concepts/storage.md`, `docs/concepts/leases.md`, `docs/concepts/turns.md`, `docs/guides/harness.md`, `docs/guides/providers.md`, `docs/guides/narration.md`, `docs/guides/observability.md`, `docs/guides/spring-boot.md`, `docs/index.md`, `README.md`, `ROADMAP.md`, `CHANGELOG.md`, `nessy-examples/chat-web/README.md`
- Check: `nessy-examples/chat-web`, `chat-cli`, `watchman` build and their tests pass with the defaults

**What to do.**
- Every place the inventory (sections 5, 6, and the "Docs that are stale" notes) names: rewrite to describe chapters, the policy, the summariser, the store, the three numbers, Memory, State and Ambient sources, and where each provider puts each stratum. Remove the head summariser and episodes. Documentation states current behaviour only: no history, no "previously", no roads not taken.
- `docs/concepts/memory.md` is rewritten around the six strata.
- `CHANGELOG.md`: entries under the unreleased heading, in the file's existing style, for what was added, changed and removed. The removals are breaking and say so.
- Check that `Leases` in the Spring Boot guide is still described correctly now that the backend carries one.

- [ ] Write the docs; `pgrep -fl nessy-example; ./mvnw -q clean verify` (for the examples); commit.

Commit: `docs: chapters, the strata of the context, and where each provider puts them`

---

### Task 16: A system prompt that cannot change

**Files:**
- Delete: `nessy-api/src/main/java/org/jwcarman/nessy/api/SystemPromptSource.java`
- Modify: `nessy-api/.../DirectHarnessConfig.java`, `QueuedHarnessConfig.java` (remove `systemPrompt(SystemPromptSource)`), `HarnessConfig.java` (add `instructions(String)`)
- Modify: `nessy-engine` — both harness configs, both factories, `DefaultInferenceService`
- Modify: `nessy-console` — `ReplConfig` and its README
- Modify: `nessy-prompt/api` — `TemplatedSystemPrompt`, `PromptVariableSource`, `PromptVariables`; `nessy-prompt/spring` — `EnvironmentVariables`
- Modify: `nessy-spring-boot/autoconfigure` — `PromptAutoConfiguration` and whatever consumes its bean
- Modify: `nessy-examples/chat-cli/.../Chat.java` and any other example that passes prompt variables
- Tests: the tests of each of the above

**Interfaces:**
- Produces, on `HarnessConfig<SELF>`:

```java
  /**
   * Adds a section to what this agent type is told about itself. Fixed when the harness is built:
   * the system prompt, then each section in the order it was added, separated by blank lines.
   */
  SELF instructions(String text);
```

- `systemPrompt(String)` stays on both door configs. `systemPrompt(SystemPromptSource)` and the type `SystemPromptSource` are removed.
- `DefaultInferenceService` takes a `SystemPrompt` in place of a source.
- `TemplatedSystemPrompt` renders once: `static SystemPrompt render(PromptTemplate template, PromptVariables variables)` and `static SystemPrompt render(PromptTemplateFactory engine, String source, PromptVariables variables)`.
- `PromptVariableSource` is removed; its three factories (`of(Map)`, `supplied(String, Supplier)`, `firstOf(List)`) move to `PromptVariables` if they are not already there, without the agent parameter.

**Behaviour.**
- The instructions an agent type sends are one text, built once when the harness is built, and identical on every call and for every agent of the type. Nothing is asked again per call.
- Blank `instructions` text is refused, as a blank system prompt is.
- The Spring Boot starter publishes a `SystemPrompt` bean rendered once from `nessy.system-prompt` with every `PromptVariables` bean, in order, then the `Environment`; whatever applied the `SystemPromptSource` bean to a harness applies the `SystemPrompt` bean.
- An example that put the date into the system prompt through a supplied variable offers it as an `AmbientSource` of kind `clock` instead.
- Javadoc on `SystemPrompt`, `systemPrompt(String)` and `instructions(String)` says the text is fixed for the life of the harness and why: it is the head of every request, so a change in it invalidates everything a provider has cached for every agent of the type; what varies by agent belongs in a `StateSource`, and what varies by the moment in an `AmbientSource`.

**Tests.**
- Engine, on the direct door with a scripted provider: `the_system_prompt_is_the_prompt_then_each_instruction_in_order`; `the_system_prompt_is_the_same_on_every_call_and_for_every_agent`; `blank_instructions_are_refused`; a feature customizer's `instructions(...)` reaches the prompt.
- Prompt module: `TemplatedSystemPromptTest` rewritten for one rendering; a supplied variable is read once.
- Starter: `PromptAutoConfigurationTest` rewritten for a `SystemPrompt` bean.

- [ ] Tests, implementation, `./mvnw spotless:apply license:format`, `pgrep -fl nessy-example; ./mvnw -q clean verify -Dnessy.excludedGroups=live`, commit.

Commit: `feat: an agent type's instructions are fixed when its harness is built`

---

### Task 17: Noticing when cached tokens fall inside a turn

**Files:**
- Create: `nessy-engine/src/main/java/org/jwcarman/nessy/engine/observability/CacheWatch.java`
- Modify: both harness factories (install it on every harness)
- Test: `nessy-engine/src/test/java/org/jwcarman/nessy/engine/observability/CacheWatchTest.java`

**Interfaces:**
- Produces:

```java
package org.jwcarman.nessy.engine.observability;

/** Hears every inference's token counts and reports a call that read fewer cached tokens than the call before it in the same turn. */
public final class CacheWatch implements NarrationListener {
  public CacheWatch(ObservationRegistry observations);
  @Override public void on(AgentType agentType, AgentId agentId, Narration event);
}
```

**Behaviour.**
- Find the narration events that carry an inference's `Usage` and its turn (read `Narration.java` and `Usage.java`; the cache-read count is nullable). For each agent, remember the turn and the cache-read count of the last inference that reported one.
- When an inference in the SAME turn reports a cache-read count lower than the remembered one, log at WARN: `NESSY CACHE: [{type}] agent {id} turn {turn} read {now} cached tokens after reading {before} on the call before; something ahead of the active turn changed, or the provider's cache expired`, and record an `Observation.Event` named `nessy.cache.read.fell` on the current observation if there is one, with the agent type as a low-cardinality key.
- A new turn resets the remembered count without reporting. A count that is absent is ignored: it neither reports nor replaces the remembered one.
- The memory is bounded (at most 10,000 agents, least recently used out first) and thread-safe.
- Both factories add one `CacheWatch` to every harness's listeners. It is not asynchronous: it does no I/O.

**Tests.** A fall inside a turn warns once and names both counts; a rise does not; the first call of a new turn that reads less than the previous turn's last call does not; an absent count is ignored; two agents do not affect each other; the map does not grow past its bound. Capture the log with the approach the module's other log-asserting tests use; if there is none, assert on the observation event with the test observation registry instead and leave the log unasserted.

- [ ] Tests, implementation, `./mvnw spotless:apply license:format`, `pgrep -fl nessy-example; ./mvnw -q clean verify`, commit.

Commit: `feat: a turn whose cached tokens fall from one call to the next is reported`

---

### Task 18: A lab for trying chapter policies and summarisers on a real conversation

**Files:**
- Modify: `nessy-engine/.../chapter/ProseSummarizer.java` (a constructor taking the prompt) and its test
- Create: module `nessy-examples/chapter-lab` (artifactId `nessy-example-chapter-lab`): `pom.xml`, `README.md`, `src/main/java/org/jwcarman/nessy/examples/chapterlab/ChapterLab.java`, `LocomoConversation.java`, `LabPolicies.java`, `LabPrompts.java`, `Grader.java`
- Modify: `nessy-examples/pom.xml` (add the module), and the root pom's publishing exclusions if the other examples are listed there
- Test: `nessy-examples/chapter-lab/src/test/java/org/jwcarman/nessy/examples/chapterlab/ChapterLabTest.java`

**What it is.** A command-line program that replays a long recorded conversation through the real engine, lets a real model write the chapter summaries under a chosen policy and summariser, asks questions with known answers, and prints how many were answered correctly and how large the context was. Not a Spring application; follow `nessy-examples/chat-cli` for how a provider is built from the environment without Spring and for the module's pom.

**Interfaces:**
- `ProseSummarizer` gains `public ProseSummarizer(TurnHistories histories, InferenceProvider provider, InferenceOptions options, String prompt)`; the three-argument constructor delegates with `PROMPT`.

**Usage.**

```
java -jar nessy-example-chapter-lab.jar \
  --data /path/to/locomo10.json --conversation 0 --questions 40 \
  --provider anthropic --model claude-sonnet-5-5 \
  --policy every:20 --summarizer prose
```

- `--policy`: `every:N`; `session` (a chapter per recorded session); `hindsight` (a model reads the open turns and names the natural breaks); `none` (no chapters: the whole conversation is sent).
- `--summarizer`: `prose` (the default prompt) or `index` (a short entry: the dates covered, then who and what the chapter is about as short phrases; names things, does not explain them).
- `--summary-model NAME` to write summaries with a different model from the one answering; `--max-chapter-length N` (default 200 so the policy alone decides) and `--max-tail N` (default 400).
- The key is read from the environment by the provider, as chat-cli does it, and is never printed.

**Behaviour.**
1. `LocomoConversation` reads one conversation from the LoCoMo file: each recorded session's messages are paired into turns (the first speaker's message is the input, the reply is the answer; an unpaired last message is a turn with an empty-looking reply `(no reply)`); each input is prefixed with the session's date in brackets; it also keeps the questions with categories 1 to 4 that have evidence, and their answers. Selecting `--questions N` takes a fixed pseudo-random sample with seed `7 + conversation`.
2. Seeding: a direct harness over `InMemoryDirectBackend` whose provider is a scripted one that replies with the recorded answer for each input, configured with the chosen policy, the chosen summariser built on the REAL provider, the maximum chapter length and `maxTail`. Every turn is driven in order; after the last, wait (with a deadline of ten minutes) until the store has no unsummarised chapter and the policy has been asked about the final turn.
3. Asking: for each question, build an `InferenceContext` directly from the store (`chapters().summaries(...)`), the completed turns after the last summary, and an active turn whose input is `Question: ...` under the system prompt `Answer the question from the record of a conversation given below. Reply with the answer only, in a few words. If the record does not say, reply: unknown.`, and call the real provider. Nothing is appended to the agent, so every question sees the same context.
4. Grading: `Grader` asks the real provider yes or no with this rule: same fact is yes whatever the wording, order or extra detail, unless the extra contradicts; a date expressed differently but meaning the same day or period is yes; when the correct answer is a list the given answer must contain every item; more specific and consistent is yes, vaguer is no; `unknown` or a refusal is no.
5. Output: one line of settings, then chapters, summary words in total, context words per question, correct of asked, a breakdown by category, and the input and output tokens the providers reported. Each question, the answer, the correct answer and the verdict are appended as JSON lines to `chapter-lab-<provider>-<model>-<policy>-<summarizer>.jsonl` in the working directory.

**Tests** (no key, no network): `LocomoConversation` pairs messages into turns and keeps session dates, against a small fixture JSON written for the test in `src/test/resources`; `LabPolicies.session` ends a chapter at each session's last turn; argument parsing rejects an unknown policy and an unknown summariser with a message listing the choices; an end-to-end run with a scripted provider standing in for the real one (it answers summary requests, questions and grading with fixed text) reports the expected counts for a three-session fixture.

- [ ] Tests, implementation, README with the commands above and what each option does, `./mvnw spotless:apply license:format`, `pgrep -fl nessy-example; ./mvnw -q clean verify`, commit.

Commit: `feat: a lab that tries chapter policies and summarisers on a recorded conversation`

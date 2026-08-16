# Reflection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps
> use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The agent gets durably smarter — an automatic critic reviews settled
conversations and writes lessons into the subject's Notebook, protected by a new
authorship invariant so an agent can't erase its own performance reviews.

**Architecture:** Two fronts. Front one: `Notebook.Entry` gains `source` (author
identity); `NotebookTools` enforces mutation-by-author (create/update/delete only
own-source; foreign name collision → `ToolResult.error`); the index annotates foreign
entries; JDBC column ×5 + TCK authorship cases. Front two: `spi.reflection` —
`Reflection.critic(ReflectionCustomizer)` returns a `Consumer<ConversationSettled>`;
`ReflectionConfig` is an INTERFACE per the DSL amendment (package-private impl); FAILED
settlements always reflect, COMPLETE only when enabled; deterministic lesson names make
at-least-once redelivery an LWW overwrite; critic failures log-and-drop, never throwing
into the settling drive (deliberately opposite the completions listener — wakes are
load-bearing, lessons are best-effort).

**Tech Stack:** Java 21, JUnit 5 + AssertJ, ScriptedModelProvider as the critic in
tests, Jackson for lesson parsing.

**Spec:** `docs/superpowers/specs/2026-08-16-reflection-design.md` (+ the config-interface
amendment in `2026-08-16-dsl-coherence-design.md` §1).

## Global Constraints

- `./mvnw -q clean verify` green offline, always; FOREGROUND builds.
- Before every commit: `./mvnw license:format -Plicense && ./mvnw spotless:apply`.
- Standing rule (lesson 8): tasks touching public API/javadoc run
  `./mvnw clean install -DskipTests` then reactor-wide
  `./mvnw javadoc:javadoc -Dmaven.javadoc.failOnError=true` before commit.
- No mocking; prose test names; no suppressions; no star imports; S5778/S5976/S107.
- The reducer, the loop, and `ConversationSettled` emission are UNTOUCHED — reflection
  is a listener, not a loop change.
- New config surfaces follow the interface ruling: `ReflectionConfig` is an interface
  with only configuration verbs; the impl is package-private.

---

### Task 1: Notebook authorship — the source field and its guard

**Files:**
- Modify: `nessy-core/.../spi/notebook/Notebook.java` (Entry record gains `source`),
  `InMemoryNotebook.java`, `NotebookTools.java` (factories take the author identity;
  mutation guard; index annotation)
- Modify: `nessy-jdbc/.../JdbcNotebook.java` + the five `notebook-schema.sql`s (add
  `source` column — additive, nothing released)
- Modify: `nessy-tck/.../NotebookContract.java` (+ authorship cases, ALL @Test public)
- Tests: notebook suites in core/jdbc/tck; call sites (chat-cli, newsroom wire
  NotebookTools — they now pass the agent's name as identity)

**Interfaces (Produces):**
- `Notebook.Entry(String name, String hook, String body, String source)` — source
  requireNonNull; javadoc: the author's identity (an agent name, or "reflection").
- `NotebookTools.remember/recall/forget(...)` factories gain the author identity
  parameter (String; the wiring passes the agent's name). `recall` is read-any;
  `remember`/`forget` mutate only entries whose source equals the tool's identity —
  create with own source; update (LWW) only own; foreign collision or foreign forget →
  `ToolResult.error` naming the conflict and the owning source.
- Index rendering annotates foreign-sourced entries (e.g. `(from reflection)`) so the
  model distinguishes its notes from imposed lessons.

**Steps:**
- [ ] Failing tests first: own create/update/delete pass; foreign remember-collision →
  error naming conflict + owner; foreign forget → error; recall reads any source; index
  annotation shape pinned; LWW-within-own-source preserved.
- [ ] JDBC: column in all five schemas (follow the vendor files' conventions; comment
  semicolons only at line-end); round-trips with source; race-recovery upsert unchanged.
- [ ] TCK: authorship cases public; certification + vendor nests pick them up. Docker is
  likely up — run at least Postgres locally; report which vendors ran.
- [ ] Call sites: wiring passes agent names; examples compile and their tests stay green.
- [ ] Full verify; license + spotless; standing javadoc rule; commit.

### Task 2: The critic — spi.reflection

**Files:**
- Create: `nessy-core/.../spi/reflection/{Reflection,ReflectionConfig,ReflectionCustomizer}.java`
  (+ package-private impl + package-info)
- Tests: `ReflectionTest` (or CriticTest) in core; scripted critic provider.

**Interfaces (Produces):**
```java
@FunctionalInterface public interface ReflectionCustomizer { void customize(ReflectionConfig reflection); }
public interface ReflectionConfig {  // interface per the DSL amendment
  ReflectionConfig transcript(Transcript transcript);       // required
  ReflectionConfig notebook(Notebook notebook);             // required
  ReflectionConfig subject(Function<ConversationId, SubjectId> subject); // required; null return = skip that conversation
  ReflectionConfig provider(ModelProvider provider);        // required
  ReflectionConfig model(String model);                     // required — no silent default spend
  ReflectionConfig prompt(String prompt);                   // optional; default critic prompt ships
  ReflectionConfig reflectOnSuccess(boolean reflectOnSuccess); // default false
}
public static Consumer<ConversationSettled> critic(ReflectionCustomizer customizer); // on Reflection
```

**Steps:**
- [ ] Behavior, failing tests first: FAILED settlement → side model call over the
  transcript (one-shot, no tools) → 0..n lessons written with `source="reflection"`,
  deterministic names `lesson:<conversation-id>[-n]`, hook = index one-liner; COMPLETE
  skipped unless `reflectOnSuccess(true)`; subject resolver returning null → skip
  entirely (no model call); duplicate settlement → same names, LWW overwrite, no
  duplicates; critic model/parse failure → WARN (naming the conversation) + drop,
  the settling drive UNDISTURBED (test: a throwing scripted critic doesn't propagate).
- [ ] Lesson format: the default prompt requests a JSON array
  `[{"hook": ..., "body": ...}]`; parse leniently via Jackson (accept a fenced code
  block around it); unparseable → WARN + drop. Pin both parse paths.
- [ ] Factory validation names each missing required field.
- [ ] Full verify; license + spotless; standing javadoc rule; commit.

### Task 3: End to end, the demo wire, the docs

**Files:**
- Test: the e2e proof beside the scripted loop tests — settle-fail → lesson written →
  a NEXT conversation's index carries the annotated lesson → `recall` returns the body.
- Modify: `nessy-examples/chat-cli/.../DemoAgent.java` (wire the critic:
  `harnessConfig.listen(ConversationSettled.class, Reflection.critic(c -> ...))`, same
  provider, selection model, reflectOnSuccess false) + its README line.
- Docs (docs-writer half, controller may split the dispatch): new
  `docs/concepts/reflection.md` (the two halves; the authorship invariant; the
  best-effort-vs-load-bearing listener asymmetry; what v1 omits per spec §4), mkdocs
  nav, docs index map, README capabilities row, notebook page cross-link + authorship
  note, CHANGELOG entries (first-release form: notebook authorship + reflection),
  ROADMAP: reflection moves from *(designed)* to shipped-silence (drop the entry).

**Steps:**
- [ ] The e2e test (scripted agent + scripted critic, offline).
- [ ] chat-cli wiring; smoke-compatible (REPL behavior unchanged when nothing fails).
- [ ] Docs per truth discipline; strict mkdocs build.
- [ ] Full verify; license + spotless; commit.

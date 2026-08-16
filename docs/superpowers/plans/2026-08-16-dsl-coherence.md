# DSL Coherence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps
> use checkbox (`- [ ]`) syntax for tracking.

**Goal:** One construction idiom across the public surface — factory + named customizer +
config with no `build()` — with blessed one-call statics for the sunny-day cases; no
public `build()` survives anywhere.

**Architecture:** Rename-and-reshape, not redesign: `HarnessBuilder`→`HarnessConfig`
(+`HarnessCustomizer`, `Nessy.harness(customizer)`), `AgentBuilder`→`AgentConfig<T>`
(+`AgentCustomizer<T>`, `harness.agent(...)` both doors), provider builders → `fromEnv()`
statics + `create(XProviderCustomizer)`, satellites (`ConsoleRepl`, `TurnObserverBuilder`,
`PipelineMemory`, `ScriptedModelProvider`) converted with the same pattern. The
`SubagentCustomizer`/`SubagentConfig` pair already ships the idiom and is untouched.
Internal builders (SDK wrappers, `SubagentAssembly`) are exempt by nature.

**Tech Stack:** Java 21, JUnit 5 + AssertJ; behavior-preserving throughout (every
factory validates required fields naming the field).

**Spec:** `docs/superpowers/specs/2026-08-16-dsl-coherence-design.md`

## Global Constraints

- `./mvnw -q clean verify` green offline, always; FOREGROUND builds.
- Before every commit: `./mvnw license:format -Plicense && ./mvnw spotless:apply`.
- No mocking; prose test names; no suppressions; no star imports; S5778/S5976/S107.
- Behavior identical: every converted surface's existing test suite migrates with
  assertions unweakened; required-field validation happens at the factory, exception
  names the field.
- End-state grep gate (final task adds the architecture test): no `public` method named
  `build()` and no `public static ... builder()` in any `src/main` of the public modules
  (internal/package-private builders exempt; document each exemption in the test).
- Known inventory (from main @ the sweep merge): `Nessy`/`HarnessBuilder`/`Harness`,
  `AgentBuilder` (+`SubagentAssembly` internal), `PipelineMemory.Builder` (public
  `build()` at :142), `TurnObserverBuilder`, `ConsoleRepl` builder,
  `AnthropicModelProvider`/`OpenAiModelProvider`/`GeminiModelProvider`/
  `BedrockModelProvider` builders, `ScriptedModelProvider.Builder` (nessy-testing).

---

### Task 1: The trunk — harness and agent configs

**Files:**
- Rename/reshape: `HarnessBuilder.java`→`HarnessConfig.java`; `AgentBuilder.java`→
  `AgentConfig.java`; create `HarnessCustomizer.java`, `AgentCustomizer.java`
- Modify: `Nessy.java` (`harness(HarnessCustomizer)` replaces `harness(provider)`),
  `Harness.java` (`agent(AgentCustomizer<String>)` + `agent(Class<T>, AgentCustomizer<T>)`
  replace `agent()`), `SubagentAssembly.java` (owner type rename only)
- Tests: migrate `HarnessBuilderTest`→`HarnessConfigTest`, `AgentBuilderTest`→
  `AgentConfigTest`, and every core test constructing harnesses/agents.

**Interfaces (Produces):**
```java
@FunctionalInterface public interface HarnessCustomizer { void customize(HarnessConfig harness); }
@FunctionalInterface public interface AgentCustomizer<T> { void customize(AgentConfig<T> agent); }
public static Harness harness(HarnessCustomizer customizer);            // on Nessy; provider REQUIRED inside, factory validates naming it
public Agent<String> agent(AgentCustomizer<String> customizer);         // on Harness
public <T> Agent<T> agent(Class<T> inputType, AgentCustomizer<T> customizer); // typed; renderer-type agreement validated at construction
```
`HarnessConfig`/`AgentConfig<T>` keep every existing setter verbatim (including
`AgentConfig.subagent(...)` both doors); NO public `build()` — the factories construct.

**Steps:**
- [ ] Reshape with the compiler as the guide; `AgentConfig<T>` genericization follows the
  existing `renderer(InputRenderer<I>)` typing; the String door keeps `Agent<String>`
  inference clean (no casts at call sites).
- [ ] Required-field factory validation: harness→provider; agent→name, model; typed
  agent→renderer presence + `Class<T>` agreement. Each failure names the field; tests pin
  messages.
- [ ] Migrate every in-core call site and test; assertions unweakened; the v2 subagent
  suites (SubagentConfigTest, SubagentTest, newsroom is Task 3's) compile against
  `AgentConfig` with only construction-line changes.
- [ ] Full verify; license + spotless; commit.

### Task 2: The satellites — console, observer, memory, scripted

**Files:** `ConsoleRepl.java` (+ its builder), `TurnObserverBuilder.java`→shape per spec
(`TurnObserver.observe(TurnObserverCustomizer)` or closest natural factory — settle
against the class and say which in the report), `PipelineMemory.java` (:142 public
`build()` dies; `Memory.pipeline(transcript)` chain ends in a terminal that returns
`Memory` — inventory the current chain shape first), `ScriptedModelProvider.java`
(nessy-testing) + all their tests and in-repo call sites (chat-cli/scout/newsroom
construction lines migrate here ONLY if trivial; else Task 3).

**Steps:**
- [ ] Inventory each class's current public surface in the report BEFORE changing it;
  convert with the statics+customizer pattern, smallest natural shape per class.
- [ ] Migrate call sites + tests; full verify; license + spotless; commit.

### Task 3: The providers + wiring + examples

**Files:** the four provider modules (`XModelProvider.fromEnv()` static +
`create(XProviderCustomizer)`; builder classes become package-private or die),
`EnvModelProviders` (constructs via the new statics/factories), autoconfigure (all four
provider auto-configs), all eight examples' construction lines, `nessy-testing` docs of
ScriptedModelProvider if its README shows builders.

**Steps:**
- [ ] Convert per spec §2 (Bedrock keeps region/credentialsProvider/client knobs in its
  customizer; `fromEnv()` statics on all four).
- [ ] Migrate env + autoconfigure + examples; live suites (`@Tag("live")`) compile but
  stay excluded; full verify; license + spotless; commit.

### Task 4: The gate, the docs, the changelog

**Files:**
- Create: the no-public-builders architecture test (core test or a small module-spanning
  check — follow ZoneBoundariesTest's precedent; every exemption listed with a reason).
- Docs (docs-writer half): every code sample across the docs site + root README's
  five-minute example (TurnObserver line included) + module READMEs converted to the new
  idiom; CHANGELOG vocabulary updated in place (first-release form maintained — no
  "changed from builders" entries); Trying-a-Provider spot-check (env commands unaffected).

**Steps:**
- [ ] Architecture test red against a planted violation, green on the tree.
- [ ] Docs sweep with per-sample compile-accuracy check against shipped signatures.
- [ ] Full verify; license + spotless; commit.

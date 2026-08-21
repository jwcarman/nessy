# Examples Rebirth Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Bring back `nessy-examples` against the current API — three small consumer-perspective apps plus the restored CI smoke ("hello's five-minute promise runs for real").

**Architecture:** A non-published aggregator module `nessy-examples` in the root reactor with three submodules, each a standalone `main` consuming reactor siblings the way an adopter would: `hello` (CLI door), `approvals` (autonomous door + desk), `governed` (intent + risk + threshold). CI runs `hello` headlessly with a scripted provider and greps its output — the consumer smoke the build lost.

**Spec:** No spec change — this packages existing, ratified API. Design-authority rule: any new concept discovered mid-build STOPS and asks.

## Global Constraints

- Examples are consumer code: they use ONLY public surface (`Nessy.cli()/autonomous()`, `Tool.of`, `ToolGrant.grant`, the kits). If an example cannot be written without internals, that is an API finding to report, not a workaround to bury.
- Non-published: examples' poms set `maven.deploy.skip`; excluded from Sonar per the existing `sonar.exclusions=nessy-examples/**` (already in the root pom — it survived).
- `hello` must run with NO API key when given `--scripted` (a `ScriptedModelProvider`-style in-example fake — nessy-testing dependency is allowed for examples) and with a real provider via nessy-model-env otherwise. CI uses `--scripted`.
- Restore the CI step in `.github/workflows/maven.yml`: run hello scripted, `grep -q` its final line (choose a stable sentinel, e.g. "The answer is 4. (COMPLETE)" recreated honestly: hello asks the model 2+2 via a calculator tool; scripted provider replies deterministically).
- House law throughout: no @SuppressWarnings, no star imports; full `./mvnw -q clean verify` green (examples compile in the reactor; hello's smoke runs in CI, not in verify).
- README + docs/index: examples get one "learn by example" pointer line (coordinate with the freshly-reborn docs voice).

## Tasks

### Task 1: The aggregator + hello
Create `nessy-examples/pom.xml` (aggregator, non-published) + `nessy-examples/hello`: a `main` that wires `Nessy.cli()` with one `Tool.of(Calculate.class, …)` calculator tool, converses one turn ("what is 2+2? use the tool"), prints the reply and `(COMPLETE)`. `--scripted` flag swaps in a deterministic provider (tool_use add(2,2) → text "The answer is 4."). Root pom `<modules>` gains nessy-examples. Verify reactor green + `./mvnw -q -pl nessy-examples/hello -am compile exec:java -Dexec.args=--scripted` prints the sentinel.

### Task 2: approvals + governed
`nessy-examples/approvals`: autonomous door, one DURABLE `restart` tool behind `requireApproval()`, console loop mirroring ApprovalPlayground but as consumer code (post / approve / deny / quit), scripted-or-env provider like hello. `nessy-examples/governed`: the full gate — typed `OpsIntent` vocabulary, risk assessor enricher, `allOf(requireDeclared, threshold)`, one run-through printed as narration (scripted). Both compile in verify; neither runs in CI.

### Task 3: CI + paper trail
Restore the maven.yml hello step (scripted, sentinel grep). README "Try it" section + docs pointer. CHANGELOG entry.

## Model policy
| Task | Implementer | Review |
|---|---|---|
| 1 | Sonnet | Sonnet |
| 2 | Sonnet | Sonnet |
| 3 | Sonnet | Haiku scoped |
| Final | — | Opus |

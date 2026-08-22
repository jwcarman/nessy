# Spring Rebirth Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Bring back `nessy-autoconfigure` and `nessy-spring-boot-starter` against the current API, plus a Spring Boot example app with a REST door.

**Architecture:** Autoconfiguration composes through the sanctioned door — `Nessy.autonomous()` — never `Harness.of`. Beans map to the four tiers: process-tier collaborators (`DurableComputationBackend`, provider, factories) are `@ConditionalOnMissingBean` seams; the `AutonomousHost` is the exposed bean (AutoCloseable → Spring lifecycle closes it); `@ConfigurationProperties("nessy")` carries type name, model settings, staleness, backlog capacity. Tool grants are collected from the application context (`List<ToolGrant>` beans; bare `Tool` beans get the allow-sugar via `ToolRegistry.of` semantics). The starter aggregates dependencies only. **No HTTP surface in framework modules** — the REST controller is example code.

**Spec:** agent-as-scope §7 (hosts), §10.11 (tiers); no spec change — this packages existing API through existing doors. Design-authority rule: any new concept mid-build stops and asks; the old modules' poms in git history may be borrowed for BUILD PLUMBING ONLY (Spring Boot version, plugin config) — zero old-world code.

## Global Constraints

- Both new framework modules are PUBLISHED artifacts: BOM entries, license headers, javadoc that survives the release profile (`mvn -P release -DskipTests -Dgpg.skip=true verify` must pass — run it once before the final commit of each framework task).
- `nessy-autoconfigure` joins `NoPublicBuildersTest`'s scanned-module roster (it ships public classes); the starter ships no code (deps + optional placeholder) and stays unscanned like before.
- Autoconfigure tests use Spring Boot's `ApplicationContextRunner` — no mocking libraries (house law); scripted providers/fakes are real classes.
- The Spring example is non-published under `nessy-examples/spring`, uses the STARTER as its dependency (proving the aggregate), runs a full scripted approval arc as a `@SpringBootTest` in normal `verify` (no key, no network), and its REST controller is plain consumer code over `AutonomousHost.post`/`approvals()`.
- Build economics per CLAUDE.md; house law throughout (no @SuppressWarnings/star imports; camelCase prose tests; S5778; S5841).

## Tasks

### Task 1: `nessy-autoconfigure`
Module with: `NessyProperties` (`nessy.type`, `nessy.model.{id,system-prompt,max-tokens}`, `nessy.staleness`, `nessy.backlog-capacity`); `NessyAutoConfiguration` providing `@ConditionalOnMissingBean` defaults for `DurableComputationBackend` (in-memory), `ModelProvider` (via `EnvModelProviders.select()` — document that a real deployment overrides or sets env), `Approver` notifier seam (`Consumer<ApprovalRequest>` bean, default no-op logged), `TurnObserver` (logging), `StalenessPolicy` (from properties); an `AutonomousHost` bean built via `Nessy.autonomous()` from all of the above plus every `ToolGrant` bean (ordered) and every bare `Tool<?>` bean (allow-sugared) in the context; `@AutoConfiguration` registration file (`META-INF/spring/...imports`). Old pom in git history is the plumbing reference (`git log --all --oneline -- 'nessy-autoconfigure*'`). Tests: `ApplicationContextRunner` — host bean exists with defaults; user beans win (custom backend/provider/policy observed in the built host where observable); grants collected in order; properties bind; context closes the host. Add module to root `<modules>` + BOM; extend `NoPublicBuildersTest` roster. Release-profile check. Commit: "feat: spring finds the harness — autoconfiguration returns against the real doors".

### Task 2: `nessy-spring-boot-starter`
Dependency aggregate (autoconfigure + nessy-agent), javadoc-placeholder module shape as the old starter had (borrow plumbing from history). BOM entry, root modules. Release-profile check. Commit: "feat: the starter returns — one dependency to the autonomous door".

### Task 3: `nessy-examples/spring`
Spring Boot app on the STARTER: a couple of `@Bean ToolGrant`s (one requireApproval'd restart tool with a named contributor), `application.yaml` with `nessy.*` properties, a small `@RestController` (consumer code): `POST /agents/{id}/messages` → `host.post`; `GET /approvals/pending` (from the notifier bean collecting requests); `POST /approvals/{slot}/approve` + `/deny` → desks. A `scripted` profile supplies a deterministic `ModelProvider` bean. `@SpringBootTest(webEnvironment=RANDOM_PORT)` + `TestRestTemplate` drives the arc: post → pending approval appears → approve → poll until the turn completes (bounded, never unbounded waits — the F1 lesson) → assert the reply. Runs in plain verify. Commit: "feat: the spring example — the autonomous door behind a REST facade".

### Task 4: Paper trail
README Try-it gains the spring example; docs/guides gets `spring-boot.md` (short, truthful: starter coordinates, properties table, override seams, the example as the worked reference) + nav entry; CHANGELOG. mkdocs strict. Commit: "docs: spring is a dependency away again".

## Model policy
| Task | Implementer | Review |
|---|---|---|
| 1 | Sonnet | **Opus** (the bean composition is the new public surface) |
| 2 | Sonnet | Haiku scoped |
| 3 | Sonnet | Sonnet |
| 4 | docs-writer (Sonnet) | Haiku scoped |
| Final | — | **Opus** |

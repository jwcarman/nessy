# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.1.0] - 2026-09-27

Nessy is an agent harness framework for Java. This is the first release.

### Added

- **Two doors.** `DirectHarness<I, O>.ask(agentId, input)` runs a turn on the
  calling thread and returns an `Outcome<O>` — `Answered`, `Refused`,
  `Failed`, or `Busy` if another turn is already running for that agent.
  `QueuedHarness<I>.tell(agentId, input)` always accepts and returns nothing;
  the turn runs later. Both doors exclude each other per agent with a lock
  (`Locks.TURN`), so only one turn ever runs for a given agent at a time.
- **A decide/accept fold.** `AgentState` is a sealed type — `Idle`,
  `Inferring`, `AwaitingActions`, `Terminal` — that decides a command and
  folds the resulting events. State is rebuilt by replaying events, so
  recovery after a crash is the same code path as normal operation.
- **Storage backends.** `DirectBackend` and `QueuedBackend` are implemented
  by `nessy-backend-jdbc` (PostgreSQL, via `Schemas.initialize`) and
  `nessy-backend-inmemory`. On the queued door, effects are rows performed
  by a dispatcher; on the direct door, the calling thread performs them.
- **Turn policy and cost.** `TurnPolicy.calls(20, 25)` is the default: the
  model is asked to answer at twenty calls and the turn fails at
  twenty-five, communicated to providers as `ToolChoice.Answer`. Every
  `Outcome.Answered`/`Refused`/`Failed` carries a `TurnStats` tally built
  from `Usage` (one model call) and `Tokens`, which is either `Counted` or
  `Uncounted` depending on what the vendor reports.
- **Retries.** Every retry default is `RetryPolicy.Never`. A model call
  reaches a configured retry policy only when its adapter classifies the
  failure as `Failure.Transient`.
- **Structured output.** `OutputReader<O>` reads a model's answer into a
  typed result; `JsonSchema` and `JsonSchemaGenerator` derive the schema
  sent to the provider.
- **Model providers.** Inference adapters for Anthropic, OpenAI (and any
  OpenAI-compatible endpoint), Gemini, and Bedrock. Embedding adapters for
  OpenAI, Gemini, Voyage, and Bedrock.
- **Tools and approvals.** `Tool<I>` binds a typed input and executes
  against a `ToolCallRequest<I>`. `Awaited` lets a call answer immediately
  or defer, and every call goes through an `Approver`. `nessy-approval-risk`
  scores a call with NIST SP 800-30's likelihood/impact matrix and routes
  it to auto-approve, auto-deny, or a human desk. `nessy-approval-policy`
  delegates the decision to a `PolicyEngine`, with `nessy-approval-policy-opa`
  speaking Rego to Open Policy Agent. `nessy-approval-intent` is a claim
  channel for a model to declare what it is about to do before it does it.
- **Memory.** `nessy-memory-episodic` cuts a story into named episodes and
  summarizes each as it closes. `nessy-memory-notebook` gives an agent notes
  it recalls by heading. `nessy-memory-summarizing` writes a background
  summary of the head of a long conversation.
- **Planning.** `nessy-planning` gives an agent a plan it writes, holds
  across turns, and works through, resent wholesale on every turn.
- **MCP tools.** `nessy-tool-mcp` imports a remote MCP server's tools as
  ordinary `Tool`s, over the official MCP Java SDK.
- **Observability.** `Narration` and `NarrationListener` report what an
  agent is doing as it happens; `nessy-narration-odyssey` journals events
  to an Odyssey stream so a page can subscribe, resume, and replay them as
  Server-Sent Events. Async listeners run outside a turn's trace by design.
- **Spring Boot.** `nessy-spring-boot-starter` is a single dependency that
  assembles a harness from `nessy.*` properties and beans. Each door,
  backend, provider, and embedder has its own `@ConditionalOnMissingBean`
  auto-configuration in `nessy-spring-boot-autoconfigure`, so an
  application can override any bean without the starter's opinions.
- **Console.** `nessy-console` builds a working terminal agent from a main
  method in one call.

### Requirements

- Java 25.
- Spring Boot 4.1 (optional — only needed for `nessy-spring-boot-starter`).

[0.1.0]: https://github.com/jwcarman/nessy/releases/tag/0.1.0

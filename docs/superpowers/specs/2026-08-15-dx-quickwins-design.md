# The DX Quick Wins — the audit's small change, paid at once

**Date:** 2026-08-15
**Status:** APPROVED — 2026-08-15 (owner: "wave", from the third DX audit's
quick-wins list; the audit report is the evidence record, this spec is the
rulings)
**Deliberately NOT here (generation-sized, already mapped to the roadmap):**
CallbackRouter + starter auto-collection; the `duet` two-agent example; the
memory-split fix / harness-seeded `defaultMemory`; tokens on
`RunOutcome.Parked` (API half); the SSE wire-contract table (MCP wave);
outstanding-parks enumeration (o11y wave); the park-resolution HTTP
canonicalization.

## 1. Install (the audit's #1)

Root README gains an **Install** section directly after the five-minute
example: the `nessy-bom` `<dependencyManagement>` import, the artifacts an
application actually depends on (read the reactor for exact ids — core,
the starter, a provider, testing for the scripted flow), and one honest
sentence: not yet on Maven Central; until then `./mvnw install` and depend
on `0.1.0-SNAPSHOT`.

## 2. Guards and exceptions grow up

- **Parks WARN gated on `storeSet`** — same rule as the memory guard: an
  all-in-memory harness is a coherent choice; a durable store with
  in-memory parks is the mismatch worth shouting about. hello's first
  line of output stops being a warning.
- **The name path speaks `AgentConfigurationException`, both branches** —
  missing name at `build()` AND blank name at the setter both raise it,
  both carrying the covenant sentence. The README's "same exception every
  other agent-configuration failure raises" promise becomes true.
  Breaking (pre-1.0): callers catching `IllegalStateException`/
  `IllegalArgumentException` for these retune.
- **`approver`/`termination`/`systemPrompt`/`maxTokens` get
  `requireNonNull` + javadoc** — `approver(null)` silently degrading to
  allow-all dies; the approver javadoc points at `Approver.parkAll()` for
  the durable-HITL posture.
- **`WrongAgentException` earns its self-diagnosing claim**: message gains
  the token and the fix ("an agent's name is a durable wire contract;
  redeploy under '<stamp>' to drain its parks").
- **`UnknownParkTokenException` stops saying "settled"** (message +
  javadoc — registry entries survive resolution; settled tokens drain
  quietly, they do not throw); message prints `token.value()`, not the
  record toString.

## 3. The API path teaches parking

`Tool#execute` javadoc gains the three-line recipe (mint via
`ParkToken.generate()` → return `Awaited.parked(token)` → getting the
token to the outside world is the tool's job, via `ToolContext#progress`,
its own transport, or `Agent#snapshot`), with `@see Agent#resume`.
`Awaited`'s four members (`Ready`, `Parked`, `ready()`, `parked()`) get
javadoc. `TurnEvent`'s false "callers already hold tokens via
`RunOutcome`" sentence is rewritten to name the two real sources
(`Agent#snapshot`, `Agent#peek`).

## 4. Two tiny API additions

- **`Memory.windowed(Memory delegate, int n)`** — static factory in
  `spi.memory`: recall = delegate's recall clipped by
  `Context.keepRecent(n)`; remember delegates. night-watchman's
  `WindowedMemory` class deletes in favor of one line; its README's "about
  ten lines" overclaim corrects to the factory story.
- **`TurnObserver.logging(Logger, Supplier<String>)`** — the
  deferred-prefix overload (prefix resolved per event at narration time);
  `FulfillmentReplies`' ~35 hand-rolled lines collapse onto it (its
  hand-rolled tail existed only because the order id isn't known until
  the drive returns).

## 5. Example and paper-cut sweep

chat-web `NessyConfig` → `ChatWebConfig` (sibling convention);
`IncidentLog` inlined (parameter-inverting one-call wrapper dies); `Hello`
gains the one comment explaining why a `testing`-package provider sits in
a `main()` (it is what makes no-key/no-network true); `hello/README.md`
(what it shows, run command, expected output, no-key/no-Docker);
`app.js` `catch (err)` → `catch`; stale local `patient-researcher/`
directory removed; README hero snippet's agent name aligned with
`Hello.java` (the snippet says `hello` — the runnable-module claim is
exact again); roster "mirroring" sentence replaced with the real
relationship (facts vs narration, two vocabularies, one sentence on which
to reach for); port map gains 4317; `contextWindow` builder javadoc
admits nothing consumes it yet; `HarnessBuilder.parks` javadoc stops
calling itself "the callback door's own registry"; `Agent#deny`'s double
null-check drops the outer one; `Agent#resume`'s 20-line javadoc
restructures so the what-to-pass summary leads and the at-least-once
theory follows under its own paragraph; `ScriptedModelProvider` becomes
thread-safe (synchronized turn/request bookkeeping + one javadoc line) —
examples drive on virtual threads and a concurrent park test currently
gets intermittent script exhaustion.

## 6. Testing

Exception-type changes pinned in `AgentBuilderTest` (both name branches →
`AgentConfigurationException`, covenant in both messages); parks-WARN
guard tested on the mismatch quadrants like its siblings;
`Memory.windowed` unit-tested (clip + delegation); the `Supplier`
overload tested beside the existing logging tests; both exception-message
changes asserted; night-watchman offline suite green after the deletion;
order-desk container suite green after the FulfillmentReplies collapse;
chat-web container suite green after the rename (smoke assertions
untouched — the standing invariant); full offline + container sweeps at
the end.

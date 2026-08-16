# Subagents v2 — defined, not handed over

**Date:** 2026-08-16
**Status:** RATIFIED in conversation (owner rulings, all four construction questions).
**Supersedes:** the construction surface of `2026-08-15-subagents-design.md`. The v1
runtime — deterministic child ids, the park chain, `ConversationSettled`, `SubagentLinks`
storage, snapshot idempotency, sync completions with throw-on-race-window — is
unchanged and remains that spec's authority. This spec replaces only how subagents are
BUILT and how apps reach them. No public release exists; the v1 assembly API is removed,
not deprecated.

## 0. The rulings

1. **Construction: inside the parent's builder.** `AgentBuilder` gains
   `.subagent(SubagentCustomizer<String>)` and
   `.subagent(Class<T>, SubagentCustomizer<T>)` — `SubagentCustomizer<T>` is a named
   functional interface (`void customize(SubagentConfig<T>)`), per the DSL-idiom
   rulings of 2026-08-16. The delegation graph is a build-time lexical
   tree: a subagent may declare its own `.subagent(...)`, and a cycle is unrepresentable
   because a child is defined inside its parent and cannot reference it. The v1
   depth-cap/cycle concern (final review N-6) dissolves by construction.
2. **Trimming: prompt/model/tools/memory only — and it is a CONFIG, not a builder
   (owner ruling).** `SubagentConfig<T>` has fluent setters and NO `build()` method: the
   app describes the child; only the parent's builder constructs it. Nothing
   half-buildable escapes the lambda. It exposes `name`
   (required), `description` (required — it IS the delegation tool's description),
   `model`, system prompt, tool grants, context transformers (plan/notebook style),
   termination policy, and `policy(UsagePolicy)` for the delegation tool itself
   (default allow; gating a parking child is fully supported once §4's re-park fix
   lands — same generation), plus `renderer(InputRenderer<T>)` for typed subagents
   (§0.5). Everything else is inherited from the harness and not overridable:
   provider, stores, approver, observations, listeners.
3. **Child doors: through the parent handle.** `Agent` gains
   `subagent(String name)` returning a `Subagent` handle — a narrow doors view:
   `approve(token)`, `deny(token, reason)`, `resume(token, resolution)`, `name()`,
   `snapshot(childConversationId)`, and `subagent(String)` for tree traversal. NO
   `converse()`/`tell()`: a subagent's conversations exist only through delegation.
   Unknown name → IllegalArgumentException naming parent and requested child.
4. **Timing: now, before anything else.** The v1 surface never calcifies in examples.
5. **Typed delegation inputs (owner ruling, 2026-08-16): both doors ship.**
   - The **degenerate String door** — `.subagent(sub -> ...)` — keeps v1's wire shape:
     the delegation tool's input is `Delegation(String task)` (a bare String makes a
     degenerate tool schema; the wrapper gives the model a proper one-field object),
     and the child is `Agent<String>` told the task text. No renderer involved.
   - The **typed door** — `.subagent(ResearchRequest.class, sub -> ...)` — makes `T`
     the delegation tool's wire shape directly: `Tool.inputType()` already drives the
     victools schema, the invoker already parses tool-call JSON to `T`, and the child
     is `Agent<T>` opened through its `InputRenderer<T>`. The subagent's input type IS
     its tool schema — structured arguments instead of prose-packed strings.
   - `renderer(...)` is REQUIRED on the typed door (parent build fails loudly naming
     the missing renderer); no silent render-as-JSON default — explicit renderers make
     deliberate child prompts.
   - Typed OUTPUT (structured results to the parent instead of final text) is out of
     scope and banked — it changes ToolResult mapping and deserves its own design.

## 1. What the app writes (the whole story)

```java
Agent<String> writer = harness.agent()
    .name("writer")
    .model(MODEL)
    .prompt(WRITER_PROMPT)
    .subagent(sub -> sub
        .name("researcher")
        .description("Delegate research questions to a focused researcher.")
        .model(MODEL)
        .prompt(RESEARCHER_PROMPT)
        .tools(ToolGrant.grant(new SearchNotesTool(), UsagePolicy.allow()),
               ToolGrant.grant(new AskQuestionTool(pending), UsagePolicy.requireApproval())))
    .build();
```

No `AgentTools`. No `CallbackRouter`. No manual `SubagentLinks`. No listener
registration. The four-part v1 wiring ritual is gone: building the parent builds the
child, grants the delegation tool (tool name = child name, as v1 ruled), wires the
links store from the harness's store family, and registers the completion wiring
internally. Delegation continuity conventions (shared Notebook subject) remain app
choices expressed through the child's transformers — the framework shares
infrastructure, not application semantics; the newsroom shows the convention.

## 2. What internalizes

- **`CallbackRouter` leaves the public API.** The harness keeps an internal registry of
  every agent and subagent built from it (duplicate names rejected at build, same
  vocabulary as v1). Routing at wake time still resolves the name from the Parks stamp —
  the v1 Option C ruling stands — it just resolves against the internal registry.
- **`AgentTools` leaves the public API.** The delegation tool and the completions
  consumer become internal machinery the builder assembles. Their v1 semantics carry
  over verbatim: child id `<parent-conversation-id>/<tool-call-id>`, snapshot
  short-circuit idempotency, complete→ok(finalText) / fail→error(reason) /
  park→link+parent-park, sync completions, throw on the not-yet-registered-park window,
  silent no-op on absent link, forget after successful resume, activity progress pings.
  The existing test suite migrates with them.
- **`SubagentLinks` stays public SPI** (storage contract, in-memory + JDBC + TCK,
  unchanged) — the harness's store selection supplies it the way it supplies Parks;
  `JdbcPersistence.subagentLinks()` already exists.

## 3. Sharing, stated precisely

A subagent shares by construction: the harness's provider, conversation store family
(including the links store), Parks, approver, observations, and harness-seeded
listeners. A subagent owns: its name, prompt, model, tool grants, transformers,
termination policy. This is the "must share some stuff to make the coordination
simpler" ruling made mechanical — the shared half is exactly the coordination
infrastructure, the owned half is exactly the agent's identity and competence.

## 4. The re-park fix (owner ruling: fix it in this generation)

v1's ugliest restriction — an approval-gated delegation whose child parks wedges both
conversations — dies here. The loop currently refuses to park a call that has already
been through a park cycle ("does not support re-parking an already-parked call").
The behavioral contract changes to:

- **Parking is two waits, not one: permission, then work.** A call may park for
  approval, be resumed, execute, and park again for its own wait — each wait minting
  its own token. (Amended after Task 1 review, 2026-08-16: a THIRD park is
  structurally unreachable — approval gating runs only from execute, never from
  resume, and only `Decided(Allow)` re-invokes a tool — so the contract is precisely
  "at most one approval wait and one execution wait, at most one outstanding." A
  resolved park is history, not a lock.)
- **The drained resolution is consumed by the execution it triggered**, whatever that
  execution's outcome — ok, error, or a fresh park. A fresh park after an approval is
  a legal fold, not a protocol violation.
- **Replay discipline:** a re-driven execution that parks with the SAME token as the
  call's outstanding park is an idempotent no-op (the subagent tool returns the stored
  link token on replay, so this is the common replay shape); a park with a NEW token
  while one is outstanding remains the loud invariant violation it always was.
- Reducer semantics and transcript invariants are the highest-risk zone in this
  codebase: the plan pins exact fold transitions after reading the current loop, the
  task carries an Opus review, and the newsroom's gated-delegation-plus-parking-child
  scenario becomes a mandatory offline end-to-end test (park for approval → approve →
  child parks → parent re-parks → child approved → parent wakes → completes).
- `SubagentConfig.policy(requireApproval())` on a parking child is thereby fully
  supported; its javadoc documents the two-park lifecycle instead of a wedge warning.

## 4b. What still does not change (with true owners)

- Sequential fan-out (loop, separate generation), child-delta streaming (deferred; the
  config now owns both sides, so the future feature has a home), cross-harness (A2A
  generation), typed delegation inputs.

## 5. Testing

House rules. The v1 behavioral suite (park-and-wake, idempotency, duplicate settlement,
race-window throw, FAILED arm, wrong-name refusal) migrates to drive the v2 surface —
same assertions, new construction. New coverage: the §4 re-park lifecycle
(approval park → resume → execution park → wake, plus its replay idempotency and the
new-token-while-outstanding violation), lexical nesting (A→B→C wake chain
end-to-end offline), duplicate-name rejection across the whole tree, the Subagent
handle's doors (approve/deny/resume against a real parked child) and its refusal to
converse (no such method — compile-time, so the test is the handle's API shape itself),
traversal, unknown-name errors. The newsroom rewrites to the v2 surface and its smoke
test must not shrink.

## 6. Docs

`docs/concepts/subagents.md` rewritten around §1's single code block; examples index and
newsroom README updated; the capabilities table row stands. Truth discipline as always.

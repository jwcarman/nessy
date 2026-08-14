# The Harness Forgets — callbacks go through the agent

**Date:** 2026-08-14
**Status:** APPROVED — 2026-08-14 (owner rulings in session: "callbacks
always go through the agent"; "move it and add agent name required")
**Amends:** the design of record
(`2026-08-09-nessy-agent-harness-design-v2.md`): the harness-as-callback-
front-door decision is superseded. This spec is the amendment the house
rules require in place of quiet divergence.

---

## 1. The ruling

Owner, 2026-08-14, verbatim: *"the callbacks should not be coming to the
harness. They should always go through the agent"* — and, on the
mechanism that forced the question: *"the harness should NOT have the
last built agent's loop. It should not be stateful."*

Both are one defect. `Harness` today carries three mutable fields —
`AtomicReference<ConversationLoop> loop`,
`AtomicReference<ListenerRegistry> agentRegistry`,
`AtomicInteger loopRegistrations` — written **backwards** by
`AgentBuilder.build()`, so the substrate mutates as a side effect of
declaring identity, with last-writer-wins semantics and a guard rail
(`resume is single-agent this generation`) bolted on to refuse the
second writer. The single-agent restriction on `resume`/`progress` was
never a design decision; it was the symptom of the harness holding state
it had no business holding.

## 2. The move

Every token door leaves `Harness` and lands on `Agent<I>`, signatures
otherwise unchanged:

| Leaves `Harness` | Lands on `Agent<I>` |
|---|---|
| `resume(ParkToken, ToolResolution)` | same |
| `resume(ParkToken, ToolResolution, TurnObserver)` | same |
| `approve(ParkToken)` / `approve(ParkToken, TurnObserver)` | same |
| `deny(ParkToken, String)` / `deny(ParkToken, String, TurnObserver)` | same |
| `progress(ParkToken, String)` | same |
| `peek(ParkToken)` | same |

`peek` involves no loop and could have stayed, but the ruling is
"always through the agent" — one front door, no exceptions, and the API
reads better for it: the class you hold to talk to an agent is the class
the world's answers come back through.

`Agent` already owns everything the doors need: its loop, its registry,
its grants, its approver, its memory. No capture, no registration, no
reference juggling — the methods move onto fields that already exist.

Every door **verifies ownership** before acting — see §3.

## 3. Agent identity — the required name

**`AgentBuilder.name(String)` is REQUIRED** (owner ruling). Non-blank;
`build()` throws `IllegalStateException` without it, with a message that
explains the covenant (the name is how parked work finds its way home
across restarts). Uniform — every agent, including `hello`'s: one honest
line. A durability-scoped variant (generated instance identity for
in-memory parks, declared name required only when parks are durable) was
considered and rejected: two identity modes is cleverness where one rule
reads like prose.

**The stamp lives in the park record, not the token string.** `ParkToken`
stays an opaque correlation id — external systems hold it, embed it in
URLs and AMQP headers; encoding identity into it would invite parsing
and leak structure. `Parks.Park` gains an `agentName` component; the
registration written at park time carries the parking loop's agent name;
`nessy_parks` gains an `agent_name` column (NOT NULL — pre-1.0 schema
recreate, no migration).

**Every door verifies:** `resume`, `approve`, `deny`, `progress`, and
`peek` all check the park's `agentName` against their own and throw a
new `WrongAgentException` naming BOTH sides
(`park was minted by agent 'order-desk'; this agent is 'orderdesk'`) —
self-diagnosing, so a rename breaks the first callback in CI with the
fix spelled out rather than misrouting silently.

**The covenant, stated where names are declared** (javadoc on
`AgentBuilder#name`): the name is a durable wire contract exactly like a
queue name or a callback URL — renaming an agent with durable parks in
flight orphans them (their `WrongAgentException` names the old name;
recovery is redeploying under it). Collisions are undetectable by a
stateless harness: two agents declaring the same name share one identity
and can serve each other's parks — an app contract violation, not a
framework-detectable state.

**Dividends** (not this wave's deliverables, but the reasons the name
earns its line): the low-cardinality tag the banked agentic-o11y
generation needs; a natural default logging prefix; and the routability
that makes an app-composed, name-keyed callback router possible (§9).

## 4. The harness goes immutable

- `loop`, `agentRegistry`, and `loopRegistrations` are **deleted**.
- `AgentBuilder.build()` stops writing back into the harness entirely.
  Building an agent no longer mutates anything but the returned `Agent`.
- What remains on `Harness`: the substrate (provider, store, parks,
  observation, harness-level `ListenerRegistry`, mapper, defaults) and
  the two `agent(...)` builder factories. Every field final, no
  post-construction writes. The class javadoc gains the sentence the
  type now deserves: a harness is inert wiring; it never changes after
  `build()`.
- The "two progress lanes reach the same audience" property survives
  intact and stops needing a hack: `progress` now emits on the agent's
  own registry, which is *already* the harness registry extended with
  the agent's declared registrations (the §17 seeding built at
  `AgentBuilder.build()`). The composition happens where it always did;
  only the AtomicReference that smuggled it back to the harness dies.

## 5. Failure modes — two deleted, one transformed

- **The single-agent `IllegalStateException`s are deleted**, both of
  them. Two agents, ten agents: every one's doors work, because every
  one drives with its own identity.
- **The zero-agent case becomes unrepresentable.** Today
  `harness.resume` can be called on a harness that never built an agent
  (durable parks from a prior process) and throws at runtime. You
  cannot call `agent.resume` without an agent in hand. A whole failure
  mode leaves the language.
- **Cross-agent delivery is REFUSED, loudly.** The old design's
  can't-misroute property is restored by the stamp (§3) without the old
  design's statefulness or single-agent ceiling:
  `agentB.resume(tokenOwnedByA, …)` throws `WrongAgentException` before
  anything is appended or driven. Fail-safe, not fail-open — the
  trade the draft spec was willing to make is one the name deleted.

## 6. Ripples

- **chat-web `ApprovalController`**: injects the `Agent` bean it already
  shares a config with; `harness.peek/approve/deny` become
  `agent.peek/approve/deny`.
- **dispatcher `CallbackController`**: `harness.resume/progress` →
  `agent.resume/progress` (the `Agent<Signal>` bean already exists in
  `DispatcherConfig`).
- **order-desk `FulfillmentReplies`**: same swap on the
  `Agent<OrderEvent>` bean.
- **Every agent build gains `.name(...)`** — all six examples (names:
  `hello`, `chat-cli`, `chat-web`, `night-watchman`, `order-desk`,
  `dispatcher` — or the module's established prose name) and every test
  that builds an agent (mechanical churn, batched).
- **`nessy-store-jdbc`**: `parks-schema.sql` gains `agent_name` NOT
  NULL; `JdbcParks` reads/writes it. Pre-1.0: schema recreate, no
  migration script.
- **`Parks` SPI**: `Park` record gains `agentName`; `register` (or its
  equivalent — read the seam) carries it. In-memory impl follows.
- **READMEs**: the root README's durable section and both callback
  examples' READMEs rewrite the verbs onto the agent, and the
  single-agent caveat (added to the root README by the DX-polish fix
  wave) is **deleted** — the limitation no longer exists. The naming
  covenant gets one sentence beside the queue-name/correlation-id
  covenants it matches.
- **nessy-autoconfigure / nessy-testing**: no references to the moved
  methods (verified by sweep, 2026-08-14); nessy-testing's scripted
  flows that build agents gain names like every other build site.
- The DX re-eval's tax #5 closes outright; its tax #10 (README never
  mentions `peek`/`approve`/`deny`) gets fixed in the rewritten section
  rather than patched where it was.

## 7. Breaking (pre-1.0), stated loud

1. `Harness.resume`/`approve`/`deny`/`progress`/`peek` — **removed**,
   not deprecated (pre-1.0, and a deprecation shim would keep the
   stateful fields alive, defeating the point). Callers hold the agent.
2. **`AgentBuilder.name(String)` is required** — every existing
   `build()` call site without it now throws.
3. `Parks.Park` gains `agentName`; the `nessy_parks` schema gains
   `agent_name NOT NULL` (recreate, no migration).
4. `Harness` is no longer a place to receive callbacks; its javadoc and
   the README say what it is instead: substrate, immutable, front door
   for *building* agents only.

## 8. Testing

- The harness token-door tests move to `Agent`, re-targeted, semantics
  identical (post-save discipline, quiet-drain replay protection,
  `UnknownParkTokenException`, the progress tee).
- **The previously-refused scenario becomes a green-path test**: two
  agents on one harness, each parks a call, each resumes its own token,
  each drive runs under its own grants and narrates on its own
  observer — the test the old design could not even express.
- **Cross-agent delivery asserts REFUSAL**: `agentB.resume(A's token)`
  throws `WrongAgentException` naming both names, and A's conversation
  is untouched afterward (nothing appended, nothing driven — verify
  before append). Same for `progress` and `peek`.
- Name requirement: `build()` without `name(...)` throws with the
  covenant message; blank name rejected at the setter (S5778-style
  single-invocation assertions).
- Stamp roundtrip: a park written by a named agent carries the name
  through the JDBC registry and back (container test in store-jdbc's
  existing suite style).
- An immutability test pins the new contract structurally: building a
  second agent changes nothing about the first's behavior (drive both,
  assert isolation), and `Harness` has no non-final fields (reflection
  sweep, mirroring the style of the existing annotation-pin tests).

## 9. Deliberately not in this wave

The name-keyed **callback router** (`CallbackRouter.of(agents…)` /
starter auto-collection of named `Agent` beans) — sanctioned shape for
generic token-only endpoints, ships when a multi-agent example needs
it; conversation-level ownership stamping (the park stamp covers the
callback doors; whether conversations themselves record an owner waits
for the multi-agent example); any change to `ToolContext.progress` (the
in-tool lane is untouched); the callback-desk *extraction* idea from
the store-rework brainstorm — this spec answers that open question
differently: the desk was never a missing object, it was a misplaced
set of methods.

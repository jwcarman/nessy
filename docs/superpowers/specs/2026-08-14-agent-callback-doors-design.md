# The Harness Forgets — callbacks go through the agent

**Date:** 2026-08-14
**Status:** DRAFT — awaiting owner review
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

## 3. The harness goes immutable

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

## 4. Failure modes — two deleted, one honest

- **The single-agent `IllegalStateException`s are deleted**, both of
  them. Two agents, ten agents: every one's doors work, because every
  one drives with its own identity.
- **The zero-agent case becomes unrepresentable.** Today
  `harness.resume` can be called on a harness that never built an agent
  (durable parks from a prior process) and throws at runtime. You
  cannot call `agent.resume` without an agent in hand. A whole failure
  mode leaves the language.
- **Cross-agent delivery is trusted, not verified — stated loud.**
  Nothing durable records which agent owns a conversation, so
  `agentB.resume(tokenOwnedByA, …)` would drive A's conversation with
  B's grants, tools, and approver. This generation trusts the app's
  routing — the app minted the correlation ids and owns the endpoints,
  so it holds exactly the knowledge it already uses to `tell` the right
  agent. The ownership stamp (conversation carries an agent name;
  `resume` verifies) is **banked as future hardening** — demoted from
  prerequisite-for-multi-agent to optional guard, which is the demotion
  this whole spec exists to perform. Javadoc on `Agent#resume` says
  this in one sentence.

## 5. Ripples

- **chat-web `ApprovalController`**: injects the `Agent` bean it already
  shares a config with; `harness.peek/approve/deny` become
  `agent.peek/approve/deny`.
- **dispatcher `CallbackController`**: `harness.resume/progress` →
  `agent.resume/progress` (the `Agent<Signal>` bean already exists in
  `DispatcherConfig`).
- **order-desk `FulfillmentReplies`**: same swap on the
  `Agent<OrderEvent>` bean.
- **READMEs**: the root README's durable section and both callback
  examples' READMEs rewrite the verbs onto the agent, and the
  single-agent caveat (added to the root README by the DX-polish fix
  wave) is **deleted** — the limitation no longer exists.
- **nessy-autoconfigure / nessy-testing**: no references to the moved
  methods (verified by sweep, 2026-08-14); no changes.
- The DX re-eval's tax #5 closes outright; its tax #10 (README never
  mentions `peek`/`approve`/`deny`) gets fixed in the rewritten section
  rather than patched where it was.

## 6. Breaking (pre-1.0), stated loud

1. `Harness.resume`/`approve`/`deny`/`progress`/`peek` — **removed**,
   not deprecated (pre-1.0, and a deprecation shim would keep the
   stateful fields alive, defeating the point). Callers hold the agent.
2. `Harness` is no longer a place to receive callbacks; its javadoc and
   the README say what it is instead: substrate, immutable, front door
   for *building* agents only.

## 7. Testing

- The harness token-door tests move to `Agent`, re-targeted, semantics
  identical (post-save discipline, quiet-drain replay protection,
  `UnknownParkTokenException`, the progress tee).
- **The previously-refused scenario becomes a green-path test**: two
  agents on one harness, each parks a call, each resumes its own token,
  each drive runs under its own grants and narrates on its own
  observer — the test the old design could not even express.
- An immutability test pins the new contract structurally: building a
  second agent changes nothing about the first's behavior (drive both,
  assert isolation), and `Harness` has no non-final fields (reflection
  sweep, mirroring the style of the existing annotation-pin tests).
- Cross-agent delivery (`agentB.resume(tokenOwnedByA)`) gets a test
  documenting today's honest behavior — it drives, under B's identity —
  so the future ownership stamp has a red test to flip.

## 8. Deliberately not in this wave

Agent naming / the durable ownership stamp (banked above); any change
to `ToolContext.progress` (the in-tool lane is untouched); the
callback-desk *extraction* idea from the store-rework brainstorm — this
spec answers that open question differently: the desk was never a
missing object, it was a misplaced set of methods.

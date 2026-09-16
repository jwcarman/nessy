# Roadmap

**The aim:** Nessy should be the framework an enterprise trusts to run agentic
workflows in production — opinionated enough that the safe path is the default
path, open enough that every policy a governed organization must impose has a
seam to live in, and honest enough that enforcement belongs to the harness, not
to convention. Nessy has opinions, not mandates: the shipped implementations embody
the opinions, the interfaces are the contract, and anyone can implement their
own without the framework getting in the way. Durability,
auditability, and human authority over agents are the substrate, not features.
Every roadmap item below is judged against that aim; so is every gap.

Where Nessy is headed, by theme. No dates — items ship when they're ready, in
roughly the order listed within each theme. Everything here is subject to
change until it lands; the [changelog](CHANGELOG.md) records what actually
shipped, and the design specs under `docs/superpowers/specs/` record why.

## Memory

Memory Management, in the canonical vocabulary: what an agent carries between
turns and how it is compressed. `nessy-memory` holds the notebook and the
summarisers; the plan lives under [Planning](#planning).

- **Episodic summaries** *(ready to build)* — one summary per episode instead
  of one rolling head. A tool the model calls (`begin_episode`, with a title
  and a reason) summarises everything since the last boundary and stores it;
  `JdbcEpisodes` is a `Summarizer`, so the assembler shows the episodes and
  then the tail. No lease needed: a tool call runs inside the fold. Open
  decisions: whether anything other than the model closes an episode (an idle
  gap, the head summariser's threshold), and whether episodes and the head
  summariser may run on the same agent (they compose, but the head would fold
  turns an episode already covers; one or the other per harness to start).
- **Sliding-window summaries** — a sibling of the head summariser that keeps a
  window of several summaries and folds the oldest, for agents whose story is
  long but whose distant past still matters.
- **Reflection** *(designed)* — an automatic critic reviews settled
  conversations (failures always, successes opt-in) and writes durable lessons
  into the agent's Notebook; the Notebook learns authorship (`source`) so an
  agent can't erase its own performance reviews.
- **Embeddings-ranked recall** — semantic retrieval over notes, episodes and
  lessons. The `Embedder` seam shipped 2026-09-16 as `nessy-embedding-api`
  with an OpenAI-compatible embedder beside it; what remains is `pgvector`
  columns on the stores, a current-turn parameter on `Summarizer` and
  `AmbientSource` so a store knows what to rank against, an in-process
  ONNX embedder for air-gapped and test use (OpenAI-compatible, Gemini, Bedrock
  and Voyage shipped 2026-09-16), and a `Reranker` seam later. Recall degrades to recency and titles when no embedder is present.
- **Lesson retention** — pruning, capping, or expiry policies for notebook
  entries, before reflection's index grows without bound.
- **Blackboard** — a shared, structured working memory several agents (or
  several background processes of one agent) read and write, keyed like the
  notebook but with a schema per board; the coordination surface the items
  under [Background cognition](#background-cognition--coordination) write to.
- **Knowledge-graph memory (Graph RAG)** — the long-horizon landing: entities
  and relationships distilled from conversations, queryable as context.

## Planning

The Planning pattern, as `nessy-planning`. Today it is a task list the model
writes, holds across turns and ticks off, carried in every context. The rest of
the family, in the order it pays off; the first three make the module a parent
with `plan`, `replan` and `critic` as siblings:

- **Plan-and-Execute with replanning** — after a step's result the model
  revises the remaining steps rather than only ticking one off: a `replan`
  tool plus a policy on when it fires (every tool result, or a failed step).
- **Plan critique (Reflection on the plan)** — before execution, a second
  inference call, with a critic prompt and often a different model, reviews
  the plan for missing steps, wrong order or unsafe actions and hands back
  revisions; a collaborator on the plan tool's write.
- **Hierarchical task decomposition** — a step can itself be a plan (goal,
  subgoals, leaf actions): a parent pointer in the store and an "expand step"
  tool. A subtree is exactly what a delegated sub-agent gets handed.
- **Goal setting and progress monitoring** — a standing goal with success
  criteria on the plan, checked each turn against the plan's state, with the
  agent told when it drifts.
- **Budget-aware planning** — each step carries an estimated cost (tokens,
  time, money) and replanning fires when the budget is exceeded; cheap once
  replanning exists.
- **ReWOO (plan with placeholders)** — the model writes the whole plan up
  front with variables for results it does not yet have, the engine executes
  the tool calls without further model turns, and one final call composes the
  answer. Needs a small expression language and an executor.
- **Plan search (Tree of Thoughts)** — several candidate plans, scored by a
  critic, the best kept and expanded. Speculative fan-out seen through
  planning; wants leases and the fan-out machinery first.

## Background cognition & coordination

Work an agent does when nobody is talking to it, and the primitives that keep
two copies of that work from colliding.

- **Leases** *(shipped 2026-09-15, `nessy-lease`)* — `tryRun(kind, key, ttl,
  work)` over a `nessy_lease` row; the head summariser runs under one.
- **Alarms** — durable timers that wake an agent (an observation at a time)
  so follow-ups, deadlines and "check back in an hour" survive a restart.
- **Blackboard** — see [Memory](#memory).
- **Saga / compensation** — a multi-step effect that fails halfway runs its
  compensations in reverse, recorded as effects like everything else.
- **Speculative fan-out** — several candidate continuations run in parallel
  and one is kept; the loop-level feature both plan search and parallel
  delegation need.

## Delegation

Not a tool. A delegation built as a tool that defers, a side table from child
to parent token, and a listener under a lease closing the loop would work and
would be torn out the moment fan-out or lineage was wanted. Delegation goes in
the fold, where every other obligation lives:

- **A `Delegate` effect** beside `Infer`, `CallTool` and `Approve`: the fold
  emits "open agent type X with this observation on behalf of my call C",
  through the same outbox row, terms and recovery as any effect.
- **Lineage on the child**: parent type, id and the call it answers, two
  nullable columns on the state row. A depth cap is a count up the chain, a
  cycle is a lookup, and an operator can ask whose work this is.
- **The child's ending is the parent's outcome**: a child that folds to
  answered, failed or refused and has a parent delivers a tool outcome to the
  parent through the same path replies use. No token in the middle.
- **Fan-out falls out**: one advance may carry several `Delegate` effects and
  the parent waits on several outstanding calls, which `AwaitingActions`
  already models; speculative fan-out is keeping the first and terminating
  the rest.
- **Child progress in the parent's stream**: a listener republishing under
  the parent's id, free once lineage exists.
- **Typed delegation output** — a child that must end with a structured
  report needs a schema-constrained answer from the provider, designed once
  for delegation and for evals.
- **Remote delegation (A2A)** — the cross-harness mirror, later.

Built after episodes and replanning, because hierarchical planning is what
makes delegation earn its keep.

## Providers

- **Reasoning controls on the OpenAI adapter** — `reasoning_effort` (and the
  thinking-off switch local runtimes honour) as provider settings, as the
  Anthropic adapter already has `thinking` and `promptCaching`.
- **Azure OpenAI** *(spike first)* — likely a base-URL-and-auth story over the
  OpenAI module; the spike decides. Any OpenAI-shaped endpoint is already the
  OpenAI provider with a base URL and a provider name.
- **Vertex AI** — Gemini's enterprise door, as a `GeminiProviderConfig` option.
- **Usage completeness** — cache-write token accounting.

## Triggers

- **Webhook / A2A server door** — both inbox doors as HTTP; an agent other
  agents can call.
- **Queue-driven example, AMQP** — RabbitMQ joins the trigger family.
- **Telling idempotency keys** — a redelivered telling from an at-least-once
  transport is re-told rather than deduplicated; an optional idempotency key
  on `tell` closes the gap.

## Safety & governance

- **`nessy-crypto`** — encryption at rest as a `Codec<byte[]>` on the storage
  seam that shipped with the codec-across-the-board work: AES-GCM with a fresh
  nonce per write, a versioned key-id envelope, and a `KeySource` seam
  (`current()` to encrypt, `byId()` to decrypt) so rotation never strands old
  rows. The seam exists; the codec does not yet. Extending the storage codec
  to the notebook, plan and watchman tables comes with it.
- **Guardrail policy engine** — interception seams at all four boundaries
  (pre-model, post-model, pre-tool, post-tool) with policy-as-data. The
  PRE-TOOL boundary already takes policy-as-data through `ApprovalRequest`
  and the risk gate; the gap is the other three boundaries.
- **Principals & identity propagation** — who a conversation acts *for*, as a
  first-class concept: per-principal grants and quotas, on-behalf-of identity
  reaching tools.
- **Park lifecycle governance** — timeouts, expiry, and escalation policies
  for waits; alarms are the mechanism.
- **Audit surface** — approvals, denials, and overrides as a first-class
  queryable record; retention policies and redaction hooks at the storage
  seams.
- **Budgets beyond the conversation** — org- and principal-level spend quotas.
- **Eval gates** — behavioral regression suites over scripted and recorded
  trajectories.

## Observability

- **Agentic metrics & trajectory tracking** *(brainstormed)* — a metrics
  roster plus per-conversation trajectory records, fed by the event stream the
  Odyssey narration module now publishes.

## Platform & developer experience

- **`nessy-testing`** — bring the test-support module back onto the current
  engine (scripted providers, recorded trajectories).
- **Tool-input validation, two layers** — JSON Schema validation of raw
  tool-call arguments before binding plus Jakarta validation on the bound
  input object, failures compacted into `ToolResult.error` for model
  self-correction.
- **Docs catch-up** — `docs/concepts/memory.md` still describes the retired
  `Memory` / `ContextTransformer` SPI; rewrite around `Summarizer`, `maxTail`
  and ambient sources.
- **GraalVM native-image support** — runtime hints so agents compile to native
  executables.
- **First release** — `0.1.0` to Maven Central once the surface above
  stabilizes.

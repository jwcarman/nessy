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
turns and how it is compressed. `nessy-memory` holds the notebook; history is
cut into summarised chapters by the engine; the plan lives under
[Planning](#planning).

- **Sliding-window summaries** — history shows one summary for every closed
  chapter, for as long as the agent lives; a window that folds the oldest
  summaries together, for agents whose story is long but whose distant past
  still matters.
- **Reflection** *(designed)* — an automatic critic reviews settled
  conversations (failures always, successes opt-in) and writes durable lessons
  into the agent's Notebook; the Notebook learns authorship (`source`) so an
  agent can't erase its own performance reviews.
- **Embeddings-ranked recall** — semantic retrieval over notes and lessons.
  The `Embedder` seam shipped 2026-09-16 (`nessy-api`, with
  `nessy-embedding-spi` for providers), with OpenAI-compatible, Gemini,
  Bedrock and Voyage embedders beside it, and a `MemorySource` receives the
  turn being answered so that it can rank by it. No store uses an `Embedder`
  yet. What remains: a memory source over the notebook that ranks by meaning,
  a `pgvector` index once an agent's notes are more than a scan, an in-process
  ONNX embedder for air-gapped and test use, and a `Reranker` seam later.
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
  work)` over a `nessy_lease` row; the chapter keeper runs under one.
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

Built after replanning, because hierarchical planning is what
makes delegation earn its keep.

## Providers

The first three ship together as `0.3.0`, in this order.

- **OpenAI Responses adapter** *(designed,
  `2026-09-29-openai-responses-design.md`)* — a second adapter in
  `nessy-inference-openai` speaking OpenAI's Responses API, stateless
  (`store: false`, the whole context every call, encrypted reasoning items
  carried as `Block.Provider` and replayed within the turn), with function
  tools, `ToolChoice.Answer`, structured output and usage at parity with the
  Chat Completions adapter. Unblocks GPT-6 with tools, which Chat Completions
  refuses, and Perplexity, which now answers only on `/v1/responses`. The
  wires become `openai-chat` and `openai-responses`; the `openai` preset
  moves to the new wire once the live tests pass through it.
- **Vendor properties** *(built for `0.3.0`)* -- a
  free-form, adapter-owned property bag instead of a typed effort concept:
  `InferenceConfig.property(name, value)` and
  `nessy.providers.<id>.properties.*`. Each adapter owns one prefix
  (`openai.`, `anthropic.`, `gemini.`, `bedrock.`) and reads the names it
  knows, such as `openai.reasoning.effort`, `openai.reasoning.summary` and
  `openai.tools.strict`. See
  `docs/superpowers/specs/2026-09-30-vendor-properties-design.md`.
- **Named embedders** *(built for `0.3.0`)* — the inference design mirrored
  for embeddings under its own namespace, `nessy.embedders.<id>`: presets for
  `openai`, `gemini` and `voyage` lit by a key when the adapter jar is
  present, custom embedders for local servers and gateways, application
  `EmbeddingProvider` beans joining under their bean names, a
  `nessy.embedder` + `nessy.embedding-model` default, and a startup report. A
  store chooses its embedder, never an agent type. Still ahead: vendor
  properties the embedding adapters support (none does yet), and re-embedding
  the rows a previous embedder wrote. See
  `docs/superpowers/specs/2026-09-30-named-embedders-design.md`.
- **First-class hosted tools** *(a later design)* — OpenAI's web search, file
  search, code interpreter and remote MCP run where Nessy cannot approve or
  gate them, so the Responses adapter offers function tools only. Enabling a
  hosted tool per agent type and recording each call and result in the event
  log, so the audit trail stays whole, is its own design; a passthrough was
  rejected as a hole in that trail.
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
- **GraalVM native-image support** — runtime hints so agents compile to native
  executables.
- **First release** — `0.1.0` to Maven Central once the surface above
  stabilizes.

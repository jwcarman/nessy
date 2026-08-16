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

## Memory & intelligence

- **Reflection** *(designed)* — an automatic critic reviews settled
  conversations (failures always, successes opt-in) and writes durable lessons
  into the agent's Notebook; the Notebook learns authorship (`source`) so an
  agent can't erase its own performance reviews. The agent gets smarter across
  restarts.
- **Embeddings-ranked recall** — semantic retrieval over Notebook entries and
  transcripts; today's recall is model-gated via the index, which scales
  further than expected but not forever.
- **Lesson retention** — reflection's index grows without bound today
  (every failed conversation adds lessons forever); pruning, capping, or
  expiry policies for notebook entries.
- **Knowledge-graph memory** — the long-horizon landing: entities and
  relationships distilled from conversations, queryable as context.

## Delegation

- **Subagents v2** *(in flight)* — subagents are defined inside their parent
  (`SubagentConfig`, no builder escape), coordination wiring fully internal,
  typed delegation inputs (a subagent's input record is its tool schema), and
  repeatable parking so approval-gated delegation composes with a child that
  parks.
- **Parallel fan-out** — a turn's delegations run concurrently instead of in
  order; a loop-level feature with its own design weight.
- **Child progress streaming** — forwarding a child's deltas into the parent's
  observer, now that construction owns both sides of the relationship.
- **Typed delegation output** — structured results back to the parent instead
  of final text.
- **Remote delegation (A2A)** — the cross-harness mirror of local subagents.

## Providers

- **Azure OpenAI** *(spike first)* — likely a base-URL-and-auth story over the
  OpenAI module rather than a new one; the spike decides.
- **Vertex AI** — Gemini's enterprise door, as a `nessy-model-gemini`
  enhancement.
- **Thinking/reasoning capability** — advertising `THINKING` on Gemini and
  Bedrock (Claude reasoning blocks); the signature grammar it needs already
  shipped.
- **Usage completeness** — cache-write token accounting (needs a `Usage` slot;
  paired with observability).

## Triggers

- **Webhook / A2A server door** — both inbox doors as HTTP; an agent other
  agents can call.
- **Queue-driven example, AMQP** — RabbitMQ joins the trigger family
  (chosen over NATS/Kafka for the example).
- **Telling idempotency keys** — a redelivered telling from an at-least-once
  transport is currently re-told rather than deduplicated (resolutions already
  drain quietly); an optional idempotency key on `tell` closes the gap the
  twelve-factor page's factor 11 discloses.

## Safety & governance

The lens above exposes this theme as the roadmap's thinnest — the substrate
(durable execution, full transcripts, per-tool grants, human approval gates)
shipped early, but *policy* is still per-tool and binary. These close the gap:

- **Guardrail policy engine** — interception seams at all four boundaries
  (pre-model, post-model, pre-tool, post-tool) with policy-as-data: argument
  constraints ("this tool, but never above this amount"), content screens,
  deny-with-reason compacted into context so the model corrects course;
  provider-native hooks (Bedrock Guardrails) ride the same seam.
- **Principals & identity propagation** — who a conversation acts *for*, as a
  first-class concept: per-principal grants and quotas, on-behalf-of identity
  reaching tools, tenancy built on the `SubjectId` foundation.
- **Park lifecycle governance** — timeouts, expiry, and escalation policies
  for waits; nothing sleeps forever without a policy saying so.
- **Audit surface** — approvals, denials, and overrides as a first-class
  queryable record (the raw material already exists in parks and the inbox);
  retention policies and redaction hooks at the storage seams.
- **Budgets beyond the conversation** — org- and principal-level spend
  quotas layered above the per-conversation `TerminationPolicy`.
- **Eval gates** — behavioral regression suites over scripted and recorded
  trajectories, so an agent change ships with evidence; feeds on the
  observability theme's trajectory records.

## Observability

- **Agentic metrics & trajectory tracking** *(brainstormed)* — a holistic
  metrics roster plus per-conversation trajectory records; the Notebook's
  authorship field and reflection lessons feed it.

## Platform & developer experience

- **DSL coherence** *(ruled)* — one construction idiom everywhere:
  customizers over configs (`Nessy.harness(h -> ...)`,
  `harness.agent(a -> ...)`), no `build()` in the public surface.
- **Console maturity** — a parked-outcome hook on `ConsoleRepl` so apps stop
  hand-rolling approval loops.
- **Loop maturity** — re-parking shipped with subagents v2; parallel tool
  execution is the next loop milestone.
- **Tool-input validation, two layers** — JSON Schema validation of raw
  tool-call arguments before binding (uniform across providers) plus Jakarta
  validation on the bound input object, with victools' jakarta module feeding
  the same annotations into the schema the model sees; failures compact into
  `ToolResult.error` for model self-correction. (The Mocapi pattern, applied
  to tools.)
- **GraalVM native-image support** — runtime hints (reflection, resources,
  serialization) so agents compile to native executables; instant-start,
  low-footprint agents are a natural fit for queue- and webhook-triggered
  deployment.
- **Jackson 3 migration** — retires the victools 4 bridge.
- **First release** — `0.1.0` to Maven Central once the surface above
  stabilizes; the changelog is already written first-release style.

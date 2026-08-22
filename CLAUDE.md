# Nessy project rules

## Model policy — the right model for the job, every time

Activities in this repository pin model families. Subagent dispatches MUST
specify the model explicitly (an omitted model silently inherits the session's,
usually the most expensive). Agent definitions in `.claude/agents/` carry the
defaults; the table is the law:

| Activity | Model | How |
|---|---|---|
| Writing or revising specifications | Fable | main session (`/model`) |
| Writing implementation plans | Opus, or the session model if Opus-tier or above | main session |
| Executing plan tasks | Sonnet | `implementer` agent |
| Verbatim transcription tasks (the brief contains the complete code) | Haiku | `implementer` + model override |
| Per-task review | Sonnet | `task-reviewer` agent |
| High-risk review (reducer semantics, engine concurrency, transcript invariants) | Opus | `task-reviewer` + model override |
| Scoped re-review of a small fix diff | Haiku | `task-reviewer` + model override |
| Final whole-branch review | Opus | `final-reviewer` agent |
| Fix-loop rounds 4–5 | one tier above the stuck implementer | model override |

These are defaults with a judgment clause, not absolutes: downshift when the
work is mechanical, escalate when it is stuck or risky, and say which model was
used and why in the ledger. Never pay Opus prices for transcription; never send
Haiku to review concurrency.

## Design authority — new concepts need a yes

A NEW CONCEPT — a public type, abstraction, or vocabulary word we have not
explicitly discussed — requires James's sign-off BEFORE it lands. Surface the
problem and the proposed shape in conversation and wait for the yes. Burying a
new concept in a plan or a dispatch brief does not count as discussion: call
it out, by name, as a question. Mechanical internals (private helpers, test
fixtures, captured fields) need no asking; anything that appears on the API
surface or in the design vocabulary does.
(Origin: `ToolGrant.Judgment`, invented un-discussed inside the action-wave
plan to dodge an erasure problem — ruled a shitty design and executed in the
context-pipeline reform.)

## Build

- Full verification: `./mvnw -q clean verify` — must pass with no API key and
  no model-provider network access, always.
- **Build economics**: `clean verify` on the whole reactor is the FINAL GATE,
  run ONCE per task before its last commit — never per step. While iterating,
  use warm scoped builds: `./mvnw -q -pl <module> -am test` (no `clean`).
  Never run two Maven processes concurrently in one worktree (they collide on
  `target/`). Parallel reactor builds (`-T 1C`) are permitted once verified
  green in a worktree.
- Before every commit: `./mvnw license:format -Plicense && ./mvnw spotless:apply`.
- Live (token-spending) tests: excluded by default; run with
  `./mvnw test -Dnessy.excludedGroups=`.

## Test conventions

- Exception-assertion lambdas (`assertThatThrownBy`, etc.) contain exactly ONE
  invocation that can throw; arrange all setup — construction, lookups — outside
  the lambda (Sonar S5778).
- Assert emptiness before any all/none-match-style assertion predicate on the
  same collection, so the predicate can't pass vacuously (S5841-family).

## Design of record

`docs/superpowers/specs/2026-08-18-agent-as-scope-design.md`, its companion
`docs/superpowers/specs/2026-08-20-durable-computation.md`, the vocabulary
amendment `docs/superpowers/specs/2026-08-20-action-and-tool-vocabulary.md`
(which amends `2026-08-16-authorization-design.md`), the substrate spec
`docs/superpowers/specs/2026-08-21-scoped-store-design.md` (which supersedes
the durable spec's storage mechanics), and the parcel spec
`docs/superpowers/specs/2026-08-22-durable-parcels-design.md` (which reshapes
the durable spec's execution semantics) are the design of record. Its decisions (zones, naming conventions, the grant
principle, sealed-grammar etiquette, the no-mocking-library promise, prose test
style) bind all work here; propose spec amendments rather than quietly
diverging.
